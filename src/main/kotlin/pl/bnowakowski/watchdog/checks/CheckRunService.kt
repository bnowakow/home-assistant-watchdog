// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.fixes.AutoFixService
import pl.bnowakowski.watchdog.history.ParameterHistoryWriter
import pl.bnowakowski.watchdog.notifications.NotificationService
import pl.bnowakowski.watchdog.rules.EffectiveRuleResolver
import tools.jackson.databind.ObjectMapper

@Service
class CheckRunService(
	private val queries: CheckRunQueries,
	private val effectiveRuleResolver: EffectiveRuleResolver,
	private val evaluator: CheckEvaluator,
	private val parameterHistoryWriter: ParameterHistoryWriter,
	private val autoFixService: AutoFixService,
	private val notificationService: NotificationService,
	private val transactionOperations: TransactionOperations,
	private val properties: CheckProperties,
	private val objectMapper: ObjectMapper,
	private val progressTracker: CheckRunProgressTracker,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val logger = LoggerFactory.getLogger(javaClass)
	private val runLock = ReentrantLock()

	fun runManualCheck(): CheckRunReport =
		runCheck(CheckRunTriggerType.MANUAL)

	fun isRunning(): Boolean =
		runLock.isLocked

	fun runCheck(triggerType: CheckRunTriggerType): CheckRunReport {
		if (!runLock.tryLock()) {
			throw CheckRunAlreadyRunningException()
		}
		return try {
			runCheckWithTimeout(triggerType)
		} finally {
			runLock.unlock()
		}
	}

	private fun runCheckWithTimeout(triggerType: CheckRunTriggerType): CheckRunReport {
		val startedAt = clock.instant()
		val checkRunId = transactionOperations.execute<Long> {
			queries.createRun(triggerType, startedAt)
		} ?: error("Could not create check run")
		val timedOut = AtomicBoolean(false)
		val timeout = properties.runTimeout
		val executor = Executors.newSingleThreadExecutor()
		val future = executor.submit<CheckRunReport> {
			runCatching {
				transactionOperations.execute<CheckRunReport> {
					executeRun(checkRunId, triggerType, startedAt, timedOut)
				} ?: error("Could not execute check run")
			}.getOrElse { error ->
				if (!timedOut.get()) {
					val finishedAt = clock.instant()
					transactionOperations.executeWithoutResult {
						queries.completeRun(checkRunId, CheckRunStatus.FAILED, finishedAt, failureSummary(error))
					}
				}
				throw error
			}
		}
		return try {
			future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
		} catch (_: TimeoutException) {
			timedOut.set(true)
			future.cancel(true)
			val finishedAt = clock.instant()
			transactionOperations.executeWithoutResult {
				queries.completeRun(checkRunId, CheckRunStatus.TIMED_OUT, finishedAt, timeoutSummary(timeout))
			}
			throw CheckRunTimedOutException(timeout)
		} finally {
			progressTracker.finish(checkRunId)
			executor.shutdownNow()
		}
	}

	private fun executeRun(
		checkRunId: Long,
		triggerType: CheckRunTriggerType,
		startedAt: Instant,
		timedOut: AtomicBoolean,
	): CheckRunReport {
		val devices = queries.loadEnabledDevices()
		progressTracker.start(checkRunId, triggerType, devices.size, startedAt)
		val deviceResults = devices.mapNotNull { device ->
			throwIfTimedOut(timedOut)
			progressTracker.evaluating(device.displayName)
			val result = if (device.skipChecks) {
				skippedDeviceResult(device)
			} else {
				effectiveRuleResolver.resolveForDevice(device.id ?: return@mapNotNull null)
					?.let(evaluator::evaluate)
			}
			progressTracker.evaluationCompleted(device.displayName)
			result
		}
		deviceResults.forEach {
			throwIfTimedOut(timedOut)
			val persistedRuleResults = persistDeviceResult(checkRunId, it)
			persistedRuleResults.forEach(notificationService::notifyRuleResult)
			persistedRuleResults.forEach(autoFixService::maybeFix)
			parameterHistoryWriter.recordCheck(checkRunId, it)
		}
		throwIfTimedOut(timedOut)
		parameterHistoryWriter.cleanupExpiredHistory()
		val finishedAt = clock.instant()
		val status = CheckRunStatus.COMPLETED
		if (!timedOut.get()) {
			queries.completeRun(checkRunId, status, finishedAt, summary(deviceResults))
		}
		return CheckRunReport(
			checkRunId = checkRunId,
			triggerType = triggerType,
			status = status,
			startedAt = startedAt,
			finishedAt = finishedAt,
			deviceResults = deviceResults,
		)
	}

	private fun persistDeviceResult(
		checkRunId: Long,
		result: EvaluatedDeviceCheck,
	): List<PersistedRuleCheckResult> {
		val deviceCheckResultId = queries.insertDeviceResult(
			checkRunId = checkRunId,
			deviceId = requireNotNull(result.device.id),
			status = result.status,
			snapshot = result.snapshot?.payload ?: objectMapper.createObjectNode(),
			checkedAt = result.checkedAt,
		)
		return result.ruleResults.map { ruleResult ->
			val ruleCheckResultId = queries.insertRuleResult(
				deviceCheckResultId = deviceCheckResultId,
				ruleId = requireNotNull(ruleResult.effectiveRule.rule.id),
				status = ruleResult.status,
				actualValue = ruleResult.actualValue,
				expectedValue = ruleResult.expectedValue,
				message = ruleResult.message,
			)
			PersistedRuleCheckResult(ruleResult, ruleCheckResultId)
		}
	}

	private fun summary(results: List<EvaluatedDeviceCheck>) =
		objectMapper.createObjectNode().apply {
			put("devices", results.size)
			put("healthy", results.count { it.status == DeviceCheckStatus.HEALTHY })
			put("degraded", results.count { it.status == DeviceCheckStatus.DEGRADED })
			put("offline", results.count { it.status == DeviceCheckStatus.OFFLINE })
			put("unknown", results.count { it.status == DeviceCheckStatus.UNKNOWN })
			put("skipped", results.count { it.status == DeviceCheckStatus.SKIPPED })
			put("rules_match", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.MATCH })
			put("rules_mismatch", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.MISMATCH })
			put("rules_error", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.ERROR })
			put("rules_skipped", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.SKIPPED })
		}

	private fun skippedDeviceResult(device: Device) =
		EvaluatedDeviceCheck(
			device = device,
			status = DeviceCheckStatus.SKIPPED,
			snapshot = null,
			checkedAt = clock.instant(),
			ruleResults = emptyList(),
			message = "Device checks are skipped",
		)

	private fun failureSummary(error: Throwable) =
		objectMapper.createObjectNode().apply {
			put("error", error.message ?: error.javaClass.name)
		}

	private fun timeoutSummary(timeout: Duration) =
		objectMapper.createObjectNode().apply {
			put("error", checkRunTimeoutMessage(timeout))
			put("timeout", timeout.toString())
		}

	private fun throwIfTimedOut(timedOut: AtomicBoolean) {
		if (timedOut.get() || Thread.currentThread().isInterrupted) {
			throw CancellationException("Check run timed out")
		}
	}
}

class CheckRunAlreadyRunningException : IllegalStateException("A check run is already running")

class CheckRunTimedOutException(timeout: Duration) : RuntimeException(checkRunTimeoutMessage(timeout))

private fun checkRunTimeoutMessage(timeout: Duration): String =
	"Check run exceeded the configured run timeout of ${formatDuration(timeout)} (${timeout}). " +
		"The run was stopped and marked as timed out."

private fun formatDuration(timeout: Duration): String {
	val millis = timeout.toMillis()
	if (millis < 1_000) {
		return "$millis millisecond${pluralSuffix(millis)}"
	}
	val seconds = timeout.seconds
	if (seconds < 60) {
		return "$seconds second${pluralSuffix(seconds)}"
	}
	val minutes = seconds / 60
	val remainingSeconds = seconds % 60
	return if (remainingSeconds == 0L) {
		"$minutes minute${pluralSuffix(minutes)}"
	} else {
		"$minutes minute${pluralSuffix(minutes)} $remainingSeconds second${pluralSuffix(remainingSeconds)}"
	}
}

private fun pluralSuffix(value: Long): String =
	if (value == 1L) "" else "s"

@Service
class CheckRunStartupRecovery(
	private val queries: CheckRunQueries,
	private val transactionOperations: TransactionOperations,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@EventListener(ApplicationReadyEvent::class)
	@Order(Ordered.HIGHEST_PRECEDENCE)
	fun markStaleRunningRuns() {
		val finishedAt = clock.instant()
		val staleCount = transactionOperations.execute<Int> {
			queries.markRunningRunsStale(finishedAt, staleRunSummary())
		} ?: 0
		if (staleCount > 0) {
			logger.warn("Marked {} check run(s) as stale after application startup", staleCount)
		}
	}

	private fun staleRunSummary() =
		objectMapper.createObjectNode().apply {
			put("error", "Application stopped before check run completed")
			put("recovered_on_startup", true)
		}
}

@Service
@ConditionalOnProperty(prefix = "watchdog.check", name = ["scheduled-enabled"], havingValue = "true", matchIfMissing = true)
class ScheduledCheckRunner(
	private val checkRunBackgroundRunner: CheckRunBackgroundRunner,
	private val properties: CheckProperties,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@EventListener(ApplicationReadyEvent::class)
	fun runStartupCheck() {
		if (properties.runOnStartup) {
			startScheduledCheck("Startup")
		}
	}

	@Scheduled(
		initialDelayString = "\${watchdog.check.interval-seconds}000",
		fixedDelayString = "\${watchdog.check.interval-seconds}000",
	)
	fun runScheduledCheck() {
		startScheduledCheck("Scheduled")
	}

	private fun startScheduledCheck(label: String) {
		when (checkRunBackgroundRunner.startCheck(CheckRunTriggerType.SCHEDULED)) {
			CheckRunStartResult.STARTED -> logger.debug("{} check queued", label)
			CheckRunStartResult.ALREADY_RUNNING -> logger.info("{} check skipped because another check is already running", label)
		}
	}
}
