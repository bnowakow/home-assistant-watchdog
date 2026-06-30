// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.history

import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.ParameterHistorySource
import pl.bnowakowski.watchdog.domain.ProviderType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class ParameterHistoryQueries(
	private val jdbc: NamedParameterJdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	fun latestValue(
		deviceId: Long,
		providerType: ProviderType,
		propertyPath: String,
		endpoint: String?,
	): ParameterHistoryLatestValue? =
		jdbc.query(
			"""
			SELECT value_json, observed_at
			FROM device_parameter_history
			WHERE device_id = :deviceId
				AND provider_type = :providerType
				AND property_path = :propertyPath
				AND (
					(:endpoint IS NULL AND endpoint IS NULL)
					OR endpoint = :endpoint
				)
			ORDER BY observed_at DESC, id DESC
			LIMIT 1
			""".trimIndent(),
			mapOf(
				"deviceId" to deviceId,
				"providerType" to providerType.name,
				"propertyPath" to propertyPath,
				"endpoint" to endpoint,
			),
		) { rs, _ ->
			ParameterHistoryLatestValue(
				value = rs.getString("value_json")?.let(objectMapper::readTree),
				observedAt = rs.getTimestamp("observed_at").toInstant(),
			)
		}.firstOrNull()

	fun insert(
		entry: ParameterHistoryEntry,
	): Long {
		val keyHolder = GeneratedKeyHolder()
		jdbc.update(
			"""
			INSERT INTO device_parameter_history (
				device_id,
				rule_id,
				provider_type,
				property_path,
				endpoint,
				value_json,
				value_text,
				value_number,
				value_boolean,
				previous_value_json,
				changed,
				source,
				observed_at,
				check_run_id
			)
			VALUES (
				:deviceId,
				:ruleId,
				:providerType,
				:propertyPath,
				:endpoint,
				CAST(:valueJson AS jsonb),
				:valueText,
				:valueNumber,
				:valueBoolean,
				CAST(:previousValueJson AS jsonb),
				:changed,
				:source,
				:observedAt,
				:checkRunId
			)
			""".trimIndent(),
			org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
				mapOf(
					"deviceId" to entry.deviceId,
					"ruleId" to entry.ruleId,
					"providerType" to entry.providerType.name,
					"propertyPath" to entry.propertyPath,
					"endpoint" to entry.endpoint,
					"valueJson" to entry.valueJson?.let(objectMapper::writeValueAsString),
					"valueText" to entry.valueText,
					"valueNumber" to entry.valueNumber,
					"valueBoolean" to entry.valueBoolean,
					"previousValueJson" to entry.previousValueJson?.let(objectMapper::writeValueAsString),
					"changed" to entry.changed,
					"source" to entry.source.name,
					"observedAt" to entry.observedAt,
					"checkRunId" to entry.checkRunId,
				),
			),
			keyHolder,
			arrayOf("id"),
		)
		return requireNotNull(keyHolder.key).toLong()
	}

	fun deleteObservedBefore(cutoff: Instant): Int =
		jdbc.update(
			"""
			DELETE FROM device_parameter_history
			WHERE observed_at < :cutoff
			""".trimIndent(),
			mapOf("cutoff" to cutoff),
		)
}

data class ParameterHistoryLatestValue(
	val value: JsonNode?,
	val observedAt: Instant,
)

data class ParameterHistoryEntry(
	val deviceId: Long,
	val ruleId: Long?,
	val providerType: ProviderType,
	val propertyPath: String,
	val endpoint: String?,
	val valueJson: JsonNode?,
	val valueText: String?,
	val valueNumber: Double?,
	val valueBoolean: Boolean?,
	val previousValueJson: JsonNode?,
	val changed: Boolean,
	val source: ParameterHistorySource,
	val observedAt: Instant,
	val checkRunId: Long?,
)
