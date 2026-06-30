// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.fixes

import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.checks.PersistedRuleCheckResult
import pl.bnowakowski.watchdog.domain.CheckMode
import pl.bnowakowski.watchdog.domain.FixAttemptStatus
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.ProviderFixStatus
import pl.bnowakowski.watchdog.provider.UnknownDeviceProviderException
import pl.bnowakowski.watchdog.notifications.NotificationService
import tools.jackson.databind.ObjectMapper

@Service
class AutoFixService(
	private val providerRegistry: DeviceProviderRegistry,
	private val queries: FixAttemptQueries,
	private val properties: FixProperties,
	private val notificationService: NotificationService,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
	private val sleeper: FixRetrySleeper = ThreadFixRetrySleeper,
) {
	fun maybeFix(result: PersistedRuleCheckResult) {
		val evaluated = result.evaluatedRuleCheck
		val effectiveRule = evaluated.effectiveRule
		val rule = effectiveRule.rule
		if (rule.checkMode != CheckMode.AUTO_FIX || evaluated.status != RuleCheckStatus.MISMATCH) {
			return
		}
		val device = effectiveRule.device
		val ruleId = rule.id ?: return
		val cooldownSeconds = rule.cooldownSeconds.toLong().takeIf { it >= 0 } ?: properties.defaultCooldownSeconds
		if (withinCooldown(ruleId, device.id ?: return, cooldownSeconds)) {
			recordSkipped(result, "Fix cooldown is still active")
			return
		}
		val propertyKey = effectiveRule.propertyKey
		val desiredValue = rule.desiredValue
		if (propertyKey == null || desiredValue == null) {
			recordSkipped(result, "Rule does not contain a fixable property/value")
			return
		}
		val provider = try {
			providerRegistry.providerFor(device.providerType)
		} catch (_: UnknownDeviceProviderException) {
			recordFailed(result, desiredValue, "No provider registered for ${device.providerType}")
			return
		}
		val propertyMetadata = provider.supportedProperties(device)
			.firstOrNull {
				it.ref.providerType == propertyKey.providerType &&
					it.ref.propertyPath == propertyKey.propertyPath &&
					it.ref.endpoint == propertyKey.endpoint
			}
		if (propertyMetadata?.writable != true) {
			recordSkipped(result, "Provider does not mark ${propertyKey.propertyPath} as writable", desiredValue)
			return
		}

		val propertyRef = DevicePropertyRef(
			providerType = propertyKey.providerType,
			propertyPath = propertyKey.propertyPath,
			endpoint = propertyKey.endpoint,
			capability = propertyMetadata.ref.capability,
		)
		val retryCount = rule.retryCount.takeIf { it >= 0 } ?: properties.defaultRetryCount
		val retryDelay = Duration.ofSeconds(rule.retryDelaySeconds.takeIf { it >= 0 }?.toLong() ?: properties.defaultRetryDelaySeconds)
		var lastStatus = FixAttemptStatus.FAILED
		var lastResponse = objectMapper.createObjectNode().put("message", "Fix was not attempted")
		repeat(retryCount + 1) { attempt ->
			val providerResult = provider.applyDesiredState(device, propertyRef, desiredValue)
			lastStatus = providerResult.status.toFixAttemptStatus()
			lastResponse = objectMapper.createObjectNode().apply {
				put("status", providerResult.status.name)
				providerResult.message?.let { put("message", it) }
				providerResult.providerResponse?.let { set("provider_response", it) }
			}
			if (lastStatus == FixAttemptStatus.REQUESTED || lastStatus == FixAttemptStatus.CONFIRMED) {
				queries.insertAttempt(
					ruleCheckResultId = result.ruleCheckResultId,
					status = lastStatus,
					requestedValue = desiredValue,
					providerResponse = lastResponse,
					requestedAt = clock.instant(),
					confirmedAt = providerResult.confirmedAt,
				)
				notificationService.notifyFixResult(result, lastStatus, providerResult.message)
				return
			}
			if (attempt < retryCount) {
				sleeper.sleep(retryDelay)
			}
		}

		queries.insertAttempt(
			ruleCheckResultId = result.ruleCheckResultId,
			status = lastStatus,
			requestedValue = desiredValue,
			providerResponse = lastResponse,
			requestedAt = clock.instant(),
			confirmedAt = null,
		)
		notificationService.notifyFixResult(result, lastStatus, lastResponse.get("message")?.asText())
	}

	private fun withinCooldown(ruleId: Long, deviceId: Long, cooldownSeconds: Long): Boolean {
		if (cooldownSeconds <= 0) {
			return false
		}
		val latest = queries.latestAttemptAt(ruleId, deviceId) ?: return false
		return Duration.between(latest, clock.instant()) < Duration.ofSeconds(cooldownSeconds)
	}

	private fun recordSkipped(
		result: PersistedRuleCheckResult,
		message: String,
		requestedValue: tools.jackson.databind.JsonNode? = null,
	) {
		queries.insertAttempt(
			ruleCheckResultId = result.ruleCheckResultId,
			status = FixAttemptStatus.SKIPPED,
			requestedValue = requestedValue,
			providerResponse = objectMapper.createObjectNode().put("message", message),
			requestedAt = clock.instant(),
			confirmedAt = null,
		)
	}

	private fun recordFailed(
		result: PersistedRuleCheckResult,
		requestedValue: tools.jackson.databind.JsonNode?,
		message: String,
	) {
		queries.insertAttempt(
			ruleCheckResultId = result.ruleCheckResultId,
			status = FixAttemptStatus.FAILED,
			requestedValue = requestedValue,
			providerResponse = objectMapper.createObjectNode().put("message", message),
			requestedAt = clock.instant(),
			confirmedAt = null,
		)
	}

	private fun ProviderFixStatus.toFixAttemptStatus(): FixAttemptStatus =
		when (this) {
			ProviderFixStatus.REQUESTED -> FixAttemptStatus.REQUESTED
			ProviderFixStatus.CONFIRMED -> FixAttemptStatus.CONFIRMED
			ProviderFixStatus.FAILED -> FixAttemptStatus.FAILED
			ProviderFixStatus.TIMED_OUT -> FixAttemptStatus.TIMED_OUT
			ProviderFixStatus.SKIPPED -> FixAttemptStatus.SKIPPED
		}
}

fun interface FixRetrySleeper {
	fun sleep(duration: Duration)
}

object ThreadFixRetrySleeper : FixRetrySleeper {
	override fun sleep(duration: Duration) {
		if (!duration.isZero && !duration.isNegative) {
			Thread.sleep(duration.toMillis())
		}
	}
}
