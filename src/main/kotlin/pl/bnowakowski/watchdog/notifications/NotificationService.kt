// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.checks.PersistedRuleCheckResult
import pl.bnowakowski.watchdog.domain.FixAttemptStatus
import pl.bnowakowski.watchdog.domain.NotificationEventStatus
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.domain.Severity
import tools.jackson.databind.ObjectMapper

@Service
class NotificationService(
	private val queries: NotificationQueries,
	private val encryptor: PushoverUserKeyEncryptor,
	private val pushoverClient: PushoverClient,
	private val notificationProperties: NotificationProperties,
	private val deliveryProperties: NotificationDeliveryProperties,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun notifyRuleResult(result: PersistedRuleCheckResult) {
		val decision = ruleDecision(result) ?: return
		deliver(decision)
	}

	fun notifyFixResult(
		result: PersistedRuleCheckResult,
		status: FixAttemptStatus,
		providerMessage: String?,
	) {
		val rule = result.evaluatedRuleCheck.effectiveRule.rule
		val problemType = when (status) {
			FixAttemptStatus.CONFIRMED -> NotificationProblemType.FIX_SUCCESS
			FixAttemptStatus.REQUESTED -> NotificationProblemType.FIX_SUCCESS
			FixAttemptStatus.FAILED,
			FixAttemptStatus.TIMED_OUT,
			FixAttemptStatus.SKIPPED,
			-> NotificationProblemType.FIX_FAILURE
		}
		val shouldNotify = when (problemType) {
			NotificationProblemType.FIX_SUCCESS -> rule.notifyOnFixSuccess
			NotificationProblemType.FIX_FAILURE -> rule.notifyOnFixFailure
			else -> false
		}
		if (!shouldNotify) {
			return
		}
		val effective = result.evaluatedRuleCheck.effectiveRule
		val device = effective.device
		deliver(
			NotificationDecision(
				deviceId = device.id,
				ruleId = rule.id,
				problemType = problemType,
				severity = rule.severity,
				message = "${device.displayName}: ${problemType.title()} for ${effective.group.name}${providerMessage?.let { " ($it)" } ?: ""}",
				shouldNotify = true,
			),
		)
	}

	private fun ruleDecision(result: PersistedRuleCheckResult): NotificationDecision? {
		val evaluated = result.evaluatedRuleCheck
		val effective = evaluated.effectiveRule
		val rule = effective.rule
		val device = effective.device
		val deviceId = device.id
		val ruleId = rule.id
		if (deviceId == null || ruleId == null) {
			return null
		}
		return when (evaluated.status) {
			RuleCheckStatus.MISMATCH,
			RuleCheckStatus.ERROR,
			-> {
				val problemType = when (rule.ruleType) {
					RuleType.BATTERY_THRESHOLD -> NotificationProblemType.LOW_BATTERY
					RuleType.AVAILABILITY,
					RuleType.FRESHNESS,
					-> NotificationProblemType.OFFLINE_STALE
					RuleType.DESIRED_PROPERTY -> NotificationProblemType.MISMATCH
				}
				val shouldNotify = when (problemType) {
					NotificationProblemType.LOW_BATTERY -> rule.notifyOnLowBattery || rule.notifyOnMismatch
					NotificationProblemType.OFFLINE_STALE -> rule.notifyOnOfflineStale || rule.notifyOnMismatch
					NotificationProblemType.MISMATCH -> rule.notifyOnMismatch
					else -> false
				}
				NotificationDecision(
					deviceId = deviceId,
					ruleId = ruleId,
					problemType = problemType,
					severity = rule.severity,
					message = "${device.displayName}: ${problemType.title()} in ${effective.group.name}${evaluated.message?.let { " ($it)" } ?: ""}",
					shouldNotify = shouldNotify,
				)
			}
			RuleCheckStatus.MATCH -> {
				if (!rule.notifyOnRecovery || !wasPreviouslyUnhealthy(deviceId, ruleId, result.ruleCheckResultId)) {
					return null
				}
				NotificationDecision(
					deviceId = deviceId,
					ruleId = ruleId,
					problemType = NotificationProblemType.RECOVERY,
					severity = Severity.INFO,
					message = "${device.displayName}: recovered for ${effective.group.name}",
					shouldNotify = true,
				)
			}
			RuleCheckStatus.SKIPPED -> null
		}
	}

	private fun wasPreviouslyUnhealthy(
		deviceId: Long,
		ruleId: Long,
		currentRuleCheckResultId: Long,
	): Boolean {
		val previous = queries.previousRuleStatus(deviceId, ruleId, currentRuleCheckResultId)
			?: return false
		return previous != RuleCheckStatus.MATCH.name
	}

	private fun deliver(decision: NotificationDecision) {
		if (!decision.shouldNotify || !notificationProperties.pushoverConfigured) {
			return
		}
		val channelId = queries.ensurePushoverChannel()
		val dedupeKey = listOf(
			"pushover",
			decision.deviceId ?: "none",
			decision.ruleId ?: "none",
			decision.problemType.name,
		).joinToString(":")
		val cooldown = Duration.ofSeconds(deliveryProperties.defaultCooldownSeconds.coerceAtLeast(0))
		val now = clock.instant()
		val latestSent = queries.latestSentAt(dedupeKey)
		if (latestSent != null && Duration.between(latestSent, now) < cooldown) {
			queries.insertEvent(
				channelId = channelId,
				deviceId = decision.deviceId,
				ruleId = decision.ruleId,
				dedupeKey = dedupeKey,
				status = NotificationEventStatus.SKIPPED_COOLDOWN,
				severity = decision.severity,
				message = decision.message,
				providerResponse = objectMapper.createObjectNode().put("message", "Notification cooldown is still active"),
				createdAt = now,
			)
			return
		}
		queries.findPushoverRecipients()
			.filter { decision.problemType != NotificationProblemType.RECOVERY || it.notifyRecoveryEnabled }
			.forEach { recipient ->
				runCatching {
					pushoverClient.send(
						PushoverMessage(
							userKey = encryptor.decrypt(recipient.pushoverUserKeyEncrypted),
							devices = recipient.pushoverDevices,
							title = "Home Assistant Watchdog",
							message = decision.message,
							priority = decision.severity.pushoverPriority(),
						),
					)
					queries.insertEvent(
						channelId = channelId,
						deviceId = decision.deviceId,
						ruleId = decision.ruleId,
						dedupeKey = dedupeKey,
						status = NotificationEventStatus.SENT,
						severity = decision.severity,
						message = decision.message,
						providerResponse = objectMapper.createObjectNode()
							.put("provider", "PUSHOVER")
							.put("recipient", recipient.email),
						createdAt = clock.instant(),
					)
				}.onFailure { error ->
					logger.warn("Could not deliver Pushover notification to userId={} email={}", recipient.appUserId, recipient.email, error)
					queries.insertEvent(
						channelId = channelId,
						deviceId = decision.deviceId,
						ruleId = decision.ruleId,
						dedupeKey = dedupeKey,
						status = NotificationEventStatus.FAILED,
						severity = decision.severity,
						message = decision.message,
						providerResponse = objectMapper.createObjectNode()
							.put("provider", "PUSHOVER")
							.put("recipient", recipient.email)
							.put("error", error.message ?: error.javaClass.name),
						createdAt = clock.instant(),
					)
				}
			}
	}

	private fun NotificationProblemType.title(): String =
		name.lowercase().replace('_', ' ')

	private fun Severity.pushoverPriority(): Int =
		when (this) {
			Severity.INFO -> -1
			Severity.WARNING -> 0
			Severity.CRITICAL -> 1
		}
}
