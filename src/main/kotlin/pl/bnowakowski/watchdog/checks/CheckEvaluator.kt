// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Criticality
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.provider.UnknownDeviceProviderException
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import pl.bnowakowski.watchdog.rules.EffectiveRulesView
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

@Service
class CheckEvaluator(
	private val providerRegistry: DeviceProviderRegistry,
	private val properties: CheckProperties,
	private val clock: Clock = Clock.systemUTC(),
	private val sleeper: CheckRetrySleeper = ThreadCheckRetrySleeper,
) {
	fun evaluate(view: EffectiveRulesView): EvaluatedDeviceCheck {
		val device = view.device
		val checkedAt = clock.instant()
		val provider = provider(device)
			?: return EvaluatedDeviceCheck(
				device = device,
				status = DeviceCheckStatus.UNKNOWN,
				snapshot = null,
				checkedAt = checkedAt,
				ruleResults = view.rules.map {
					EvaluatedRuleCheck(
						effectiveRule = it,
						status = RuleCheckStatus.ERROR,
						message = "No provider registered for ${device.providerType}",
					)
				},
				message = "No provider registered for ${device.providerType}",
			)

		val snapshot = readSnapshotWithMissingPropertyRetries(provider, device, view.activeRules)
		val adjustedSnapshot = snapshot.adjustHomeAssistantUnavailable(device)
		val ruleResults = view.rules.map { evaluateRule(it, adjustedSnapshot) }
		val deviceStatus = deviceStatus(adjustedSnapshot, ruleResults)

		return EvaluatedDeviceCheck(
			device = device,
			status = deviceStatus,
			snapshot = adjustedSnapshot,
			checkedAt = checkedAt,
			ruleResults = ruleResults,
		)
	}

	private fun provider(device: Device): DeviceProvider? =
		try {
			providerRegistry.providerFor(device.providerType)
		} catch (_: UnknownDeviceProviderException) {
			null
		}

	private fun readSnapshotWithMissingPropertyRetries(
		provider: DeviceProvider,
		device: Device,
		activeRules: List<EffectiveRule>,
	): DeviceSnapshot {
		var snapshot = provider.readSnapshot(device)
		repeat(properties.missingPropertyRetryCount) {
			if (!hasMissingCheckedProperty(snapshot, activeRules)) {
				return snapshot
			}
			sleeper.sleep(Duration.ofSeconds(properties.missingPropertyRetryDelaySeconds))
			snapshot = provider.readSnapshot(device)
		}
		return snapshot
	}

	private fun hasMissingCheckedProperty(
		snapshot: DeviceSnapshot,
		activeRules: List<EffectiveRule>,
	): Boolean =
		activeRules.any { rule ->
			when (rule.rule.ruleType) {
				RuleType.DESIRED_PROPERTY -> rule.propertyKey?.let { propertyValue(snapshot, it.propertyPath) == null } == true
				RuleType.BATTERY_THRESHOLD -> snapshot.batteryLevel == null && propertyValue(snapshot, rule.rule.propertyPath ?: "battery") == null
				RuleType.AVAILABILITY,
				RuleType.FRESHNESS,
				-> false
			}
		}

	private fun evaluateRule(
		effectiveRule: EffectiveRule,
		snapshot: DeviceSnapshot,
	): EvaluatedRuleCheck {
		if (effectiveRule.status != EffectiveRuleStatus.ACTIVE) {
			return EvaluatedRuleCheck(
				effectiveRule = effectiveRule,
				status = RuleCheckStatus.SKIPPED,
				message = effectiveRule.reason,
			)
		}

		return when (effectiveRule.rule.ruleType) {
			RuleType.AVAILABILITY -> evaluateAvailability(effectiveRule, snapshot)
			RuleType.FRESHNESS -> evaluateFreshness(effectiveRule, snapshot)
			RuleType.BATTERY_THRESHOLD -> evaluateBattery(effectiveRule, snapshot)
			RuleType.DESIRED_PROPERTY -> evaluateDesiredProperty(effectiveRule, snapshot)
		}
	}

	private fun evaluateAvailability(
		rule: EffectiveRule,
		snapshot: DeviceSnapshot,
	): EvaluatedRuleCheck =
		EvaluatedRuleCheck(
			effectiveRule = rule,
			status = if (snapshot.available) RuleCheckStatus.MATCH else RuleCheckStatus.MISMATCH,
			actualValue = JsonNodeFactory.instance.booleanNode(snapshot.available),
			expectedValue = JsonNodeFactory.instance.booleanNode(true),
			message = if (snapshot.available) null else "Device is unavailable",
		)

	private fun evaluateFreshness(
		rule: EffectiveRule,
		snapshot: DeviceSnapshot,
	): EvaluatedRuleCheck {
		val lastSeenAt = snapshot.lastSeenAt
			?: return EvaluatedRuleCheck(
				effectiveRule = rule,
				status = RuleCheckStatus.ERROR,
				message = "Snapshot does not contain last seen time",
			)
		val staleAfter = staleThreshold(rule.device)
		val age = Duration.between(lastSeenAt, snapshot.observedAt)
		val fresh = age <= staleAfter
		return EvaluatedRuleCheck(
			effectiveRule = rule,
			status = if (fresh) RuleCheckStatus.MATCH else RuleCheckStatus.MISMATCH,
			actualValue = JsonNodeFactory.instance.numberNode(age.seconds),
			expectedValue = JsonNodeFactory.instance.numberNode(staleAfter.seconds),
			message = if (fresh) null else "Device state is stale for ${age.seconds} seconds",
		)
	}

	private fun evaluateBattery(
		rule: EffectiveRule,
		snapshot: DeviceSnapshot,
	): EvaluatedRuleCheck {
		val actual = snapshot.batteryLevel
			?: propertyValue(snapshot, rule.rule.propertyPath ?: "battery")?.takeIf { it.isNumber }?.doubleValue()
			?: return EvaluatedRuleCheck(
				effectiveRule = rule,
				status = RuleCheckStatus.ERROR,
				expectedValue = rule.rule.desiredValue,
				message = "Snapshot does not contain battery level",
			)
		val expected = rule.rule.desiredValue?.takeIf { it.isNumber }?.doubleValue()
			?: return EvaluatedRuleCheck(
				effectiveRule = rule,
				status = RuleCheckStatus.ERROR,
				actualValue = JsonNodeFactory.instance.numberNode(actual),
				message = "Battery rule does not contain a numeric threshold",
			)
		val matches = compareNumbers(actual, expected, rule.rule.comparisonOperator)
		return EvaluatedRuleCheck(
			effectiveRule = rule,
			status = if (matches) RuleCheckStatus.MATCH else RuleCheckStatus.MISMATCH,
			actualValue = JsonNodeFactory.instance.numberNode(actual),
			expectedValue = rule.rule.desiredValue,
			message = if (matches) null else "Battery level $actual does not satisfy ${rule.rule.comparisonOperator} $expected",
		)
	}

	private fun evaluateDesiredProperty(
		rule: EffectiveRule,
		snapshot: DeviceSnapshot,
	): EvaluatedRuleCheck {
		val propertyPath = rule.propertyKey?.propertyPath
			?: return EvaluatedRuleCheck(
				effectiveRule = rule,
				status = RuleCheckStatus.ERROR,
				expectedValue = rule.rule.desiredValue,
				message = "Desired-property rule has no property path",
			)
		val actual = propertyValue(snapshot, propertyPath)
			?: return EvaluatedRuleCheck(
				effectiveRule = rule,
				status = RuleCheckStatus.ERROR,
				expectedValue = rule.rule.desiredValue,
				message = "Snapshot does not contain $propertyPath after retries",
			)
		val expected = rule.rule.desiredValue
			?: return EvaluatedRuleCheck(
				effectiveRule = rule,
				status = RuleCheckStatus.ERROR,
				actualValue = actual,
				message = "Desired-property rule has no expected value",
			)
		val matches = compareJson(actual, expected, rule.rule.comparisonOperator)
		return EvaluatedRuleCheck(
			effectiveRule = rule,
			status = if (matches) RuleCheckStatus.MATCH else RuleCheckStatus.MISMATCH,
			actualValue = actual,
			expectedValue = expected,
			message = if (matches) null else "Value at $propertyPath does not satisfy ${rule.rule.comparisonOperator}",
		)
	}

	private fun propertyValue(snapshot: DeviceSnapshot, propertyPath: String): JsonNode? {
		snapshot.properties.entries
			.firstOrNull { it.key.propertyPath == propertyPath }
			?.let { return it.value }

		return propertyPath.split('.').fold(snapshot.payload as JsonNode?) { node, part ->
			node?.get(part)
		}
	}

	private fun compareJson(
		actual: JsonNode,
		expected: JsonNode,
		operator: ComparisonOperator,
	): Boolean =
		when {
			actual.isNumber && expected.isNumber -> compareNumbers(actual.doubleValue(), expected.doubleValue(), operator)
			actual.isBoolean && expected.isBoolean -> compareComparable(actual.booleanValue(), expected.booleanValue(), operator)
			else -> compareComparable(actual, expected, operator)
		}

	private fun <T> compareComparable(
		actual: T,
		expected: T,
		operator: ComparisonOperator,
	): Boolean =
		when (operator) {
			ComparisonOperator.EQUALS -> actual == expected
			ComparisonOperator.NOT_EQUALS -> actual != expected
			else -> false
		}

	private fun compareNumbers(
		actual: Double,
		expected: Double,
		operator: ComparisonOperator,
	): Boolean =
		when (operator) {
			ComparisonOperator.EQUALS -> actual == expected
			ComparisonOperator.NOT_EQUALS -> actual != expected
			ComparisonOperator.GREATER_THAN -> actual > expected
			ComparisonOperator.GREATER_THAN_OR_EQUAL -> actual >= expected
			ComparisonOperator.LESS_THAN -> actual < expected
			ComparisonOperator.LESS_THAN_OR_EQUAL -> actual <= expected
		}

	private fun staleThreshold(device: Device): Duration =
		Duration.ofSeconds(
			when {
				device.criticality == Criticality.CRITICAL -> properties.staleCriticalSeconds
				device.powerSource == PowerSource.BATTERY -> properties.staleBatterySeconds
				else -> properties.staleMainsSeconds
			},
		)

	private fun deviceStatus(
		snapshot: DeviceSnapshot,
		ruleResults: List<EvaluatedRuleCheck>,
	): DeviceCheckStatus =
		when {
			!snapshot.available -> DeviceCheckStatus.OFFLINE
			ruleResults.any { it.status == RuleCheckStatus.ERROR || it.status == RuleCheckStatus.MISMATCH } -> DeviceCheckStatus.DEGRADED
			ruleResults.any { it.status == RuleCheckStatus.SKIPPED } -> DeviceCheckStatus.UNKNOWN
			else -> DeviceCheckStatus.HEALTHY
		}

	private fun DeviceSnapshot.adjustHomeAssistantUnavailable(device: Device): DeviceSnapshot {
		if (device.providerType != ProviderType.HOME_ASSISTANT) {
			return this
		}
		val state = payload["state"]?.asText()
		return if (state == "unavailable") copy(available = false) else this
	}
}

fun interface CheckRetrySleeper {
	fun sleep(duration: Duration)
}

object ThreadCheckRetrySleeper : CheckRetrySleeper {
	override fun sleep(duration: Duration) {
		if (!duration.isZero && !duration.isNegative) {
			Thread.sleep(duration.toMillis())
		}
	}
}
