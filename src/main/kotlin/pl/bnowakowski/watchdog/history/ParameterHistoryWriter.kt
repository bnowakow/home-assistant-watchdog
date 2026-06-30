// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.history

import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.checks.EvaluatedDeviceCheck
import pl.bnowakowski.watchdog.domain.ParameterHistorySource
import tools.jackson.databind.JsonNode

@Service
class ParameterHistoryWriter(
	private val queries: ParameterHistoryQueries,
	private val properties: ParameterHistoryProperties,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun recordCheck(
		checkRunId: Long,
		deviceResult: EvaluatedDeviceCheck,
	) {
		val deviceId = deviceResult.device.id ?: return
		deviceResult.ruleResults
			.mapNotNull { ruleResult ->
				val propertyKey = ruleResult.effectiveRule.propertyKey ?: return@mapNotNull null
				val value = ruleResult.actualValue ?: return@mapNotNull null
				TrackedParameterValue(
					deviceId = deviceId,
					ruleId = ruleResult.effectiveRule.rule.id,
					providerType = propertyKey.providerType,
					propertyPath = propertyKey.propertyPath,
					endpoint = propertyKey.endpoint,
					value = value,
					observedAt = deviceResult.checkedAt,
				)
			}
			.distinctBy { Triple(it.providerType, it.propertyPath, it.endpoint) }
			.forEach { record(checkRunId, it) }
	}

	fun cleanupExpiredHistory(): Int =
		queries.deleteObservedBefore(clock.instant().minus(Duration.ofDays(properties.retentionDays)))

	private fun record(
		checkRunId: Long,
		value: TrackedParameterValue,
	) {
		val latest = queries.latestValue(
			deviceId = value.deviceId,
			providerType = value.providerType,
			propertyPath = value.propertyPath,
			endpoint = value.endpoint,
		)
		val changed = latest?.value != value.value
		if (!changed && !shouldRecordUnchanged(latest, value)) {
			return
		}

		queries.insert(
			ParameterHistoryEntry(
				deviceId = value.deviceId,
				ruleId = value.ruleId,
				providerType = value.providerType,
				propertyPath = value.propertyPath,
				endpoint = value.endpoint,
				valueJson = value.value,
				valueText = value.value.projectText(),
				valueNumber = value.value.takeIf { it.isNumber }?.doubleValue(),
				valueBoolean = value.value.takeIf { it.isBoolean }?.booleanValue(),
				previousValueJson = latest?.value,
				changed = changed,
				source = ParameterHistorySource.CHECK,
				observedAt = value.observedAt,
				checkRunId = checkRunId,
			),
		)
	}

	private fun shouldRecordUnchanged(
		latest: ParameterHistoryLatestValue?,
		value: TrackedParameterValue,
	): Boolean {
		if (latest == null) {
			return true
		}
		if (!properties.recordUnchanged) {
			return false
		}
		val minAge = Duration.ofSeconds(properties.unchangedSampleSeconds)
		return Duration.between(latest.observedAt, value.observedAt) >= minAge
	}

	private fun JsonNode.projectText(): String? =
		when {
			isTextual -> asText()
			isNumber || isBoolean -> asText()
			else -> null
		}
}

private data class TrackedParameterValue(
	val deviceId: Long,
	val ruleId: Long?,
	val providerType: pl.bnowakowski.watchdog.domain.ProviderType,
	val propertyPath: String,
	val endpoint: String?,
	val value: JsonNode,
	val observedAt: java.time.Instant,
)
