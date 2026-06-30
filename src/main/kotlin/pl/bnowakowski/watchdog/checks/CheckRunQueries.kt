// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.Criticality
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.JsonDefaults
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class CheckRunQueries(
	private val jdbc: NamedParameterJdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	fun loadEnabledDevices(): List<Device> =
		jdbc.query(
			"""
			SELECT *
			FROM device
			WHERE enabled = true
			ORDER BY display_name, id
			""".trimIndent(),
			emptyMap<String, Any?>(),
		) { rs, _ -> rs.toDevice() }

	fun createRun(
		triggerType: CheckRunTriggerType,
		startedAt: Instant,
	): Long =
		insertReturningId(
			"""
			INSERT INTO check_run (trigger_type, status, started_at, summary)
			VALUES (:triggerType, :status, :startedAt, CAST(:summary AS jsonb))
			""".trimIndent(),
			mapOf(
				"triggerType" to triggerType.name,
				"status" to CheckRunStatus.RUNNING.name,
				"startedAt" to startedAt,
				"summary" to "{}",
			),
		)

	fun completeRun(
		checkRunId: Long,
		status: CheckRunStatus,
		finishedAt: Instant,
		summary: JsonNode,
	) {
		jdbc.update(
			"""
			UPDATE check_run
			SET status = :status,
				finished_at = :finishedAt,
				summary = CAST(:summary AS jsonb)
			WHERE id = :checkRunId
			""".trimIndent(),
			mapOf(
				"checkRunId" to checkRunId,
				"status" to status.name,
				"finishedAt" to finishedAt,
				"summary" to objectMapper.writeValueAsString(summary),
			),
		)
	}

	fun insertDeviceResult(
		checkRunId: Long,
		deviceId: Long,
		status: DeviceCheckStatus,
		snapshot: JsonNode,
		checkedAt: Instant,
	): Long =
		insertReturningId(
			"""
			INSERT INTO device_check_result (check_run_id, device_id, status, snapshot, checked_at)
			VALUES (:checkRunId, :deviceId, :status, CAST(:snapshot AS jsonb), :checkedAt)
			""".trimIndent(),
			mapOf(
				"checkRunId" to checkRunId,
				"deviceId" to deviceId,
				"status" to status.name,
				"snapshot" to objectMapper.writeValueAsString(snapshot),
				"checkedAt" to checkedAt,
			),
		)

	fun insertRuleResult(
		deviceCheckResultId: Long,
		ruleId: Long,
		status: RuleCheckStatus,
		actualValue: JsonNode?,
		expectedValue: JsonNode?,
		message: String?,
	): Long =
		insertReturningId(
			"""
			INSERT INTO rule_check_result (
				device_check_result_id,
				rule_id,
				status,
				actual_value,
				expected_value,
				message
			)
			VALUES (
				:deviceCheckResultId,
				:ruleId,
				:status,
				CAST(:actualValue AS jsonb),
				CAST(:expectedValue AS jsonb),
				:message
			)
			""".trimIndent(),
			mapOf(
				"deviceCheckResultId" to deviceCheckResultId,
				"ruleId" to ruleId,
				"status" to status.name,
				"actualValue" to actualValue?.let(objectMapper::writeValueAsString),
				"expectedValue" to expectedValue?.let(objectMapper::writeValueAsString),
				"message" to message,
			),
		)

	private fun insertReturningId(
		sql: String,
		parameters: Map<String, Any?>,
	): Long {
		val keyHolder = GeneratedKeyHolder()
		jdbc.update(sql, org.springframework.jdbc.core.namedparam.MapSqlParameterSource(parameters), keyHolder, arrayOf("id"))
		return requireNotNull(keyHolder.key).toLong()
	}

	private fun ResultSet.toDevice(): Device =
		Device(
			id = getLong("id"),
			providerType = ProviderType.valueOf(getString("provider_type")),
			providerDeviceId = getString("provider_device_id"),
			ieeeAddress = getString("ieee_address"),
			networkAddress = getString("network_address"),
			friendlyName = getString("friendly_name"),
			displayName = getString("display_name"),
			modelKey = getString("model_key"),
			modelName = getString("model_name"),
			powerSource = PowerSource.valueOf(getString("power_source")),
			criticality = Criticality.valueOf(getString("criticality")),
			enabled = getBoolean("enabled"),
			providerMetadata = jsonNode(getString("provider_metadata")) ?: JsonDefaults.emptyObject(),
			lastSeenAt = getTimestamp("last_seen_at")?.toInstant(),
			createdAt = getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
			updatedAt = getTimestamp("updated_at")?.toInstant() ?: Instant.EPOCH,
		)

	private fun jsonNode(value: String?): JsonNode? =
		value?.let(objectMapper::readTree)
}
