// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
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
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun runManualCheck(): CheckRunReport =
		runCheck(CheckRunTriggerType.MANUAL)

	@Transactional
	fun runCheck(triggerType: CheckRunTriggerType): CheckRunReport {
		val startedAt = clock.instant()
		val checkRunId = queries.createRun(triggerType, startedAt)
		return runCatching {
			val devices = queries.loadEnabledDevices()
			val deviceResults = devices.mapNotNull { device ->
				effectiveRuleResolver.resolveForDevice(device.id ?: return@mapNotNull null)
					?.let(evaluator::evaluate)
			}
			deviceResults.forEach {
					val persistedRuleResults = persistDeviceResult(checkRunId, it)
					persistedRuleResults.forEach(notificationService::notifyRuleResult)
					persistedRuleResults.forEach(autoFixService::maybeFix)
					parameterHistoryWriter.recordCheck(checkRunId, it)
			}
			parameterHistoryWriter.cleanupExpiredHistory()
			val finishedAt = clock.instant()
			val status = CheckRunStatus.COMPLETED
			queries.completeRun(checkRunId, status, finishedAt, summary(deviceResults))
			CheckRunReport(
				checkRunId = checkRunId,
				triggerType = triggerType,
				status = status,
				startedAt = startedAt,
				finishedAt = finishedAt,
				deviceResults = deviceResults,
			)
		}.getOrElse { error ->
			val finishedAt = clock.instant()
			queries.completeRun(checkRunId, CheckRunStatus.FAILED, finishedAt, failureSummary(error))
			throw error
		}
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
			put("rules_match", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.MATCH })
			put("rules_mismatch", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.MISMATCH })
			put("rules_error", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.ERROR })
			put("rules_skipped", results.flatMap { it.ruleResults }.count { it.status == RuleCheckStatus.SKIPPED })
		}

	private fun failureSummary(error: Throwable) =
		objectMapper.createObjectNode().apply {
			put("error", error.message ?: error.javaClass.name)
		}
}

@Service
@ConditionalOnProperty(prefix = "watchdog.check", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ScheduledCheckRunner(
	private val checkRunService: CheckRunService,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		initialDelayString = "\${watchdog.check.interval-seconds}000",
		fixedDelayString = "\${watchdog.check.interval-seconds}000",
	)
	fun runScheduledCheck() {
		runCatching {
			checkRunService.runCheck(CheckRunTriggerType.SCHEDULED)
		}.onFailure {
			logger.warn("Scheduled check failed: {}", it.message)
		}
	}
}
