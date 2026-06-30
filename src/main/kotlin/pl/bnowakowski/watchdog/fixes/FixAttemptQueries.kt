// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.fixes

import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.FixAttemptStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class FixAttemptQueries(
	private val jdbc: NamedParameterJdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	fun insertAttempt(
		ruleCheckResultId: Long,
		status: FixAttemptStatus,
		requestedValue: JsonNode?,
		providerResponse: JsonNode?,
		requestedAt: Instant,
		confirmedAt: Instant?,
	): Long {
		val keyHolder = GeneratedKeyHolder()
		jdbc.update(
			"""
			INSERT INTO fix_attempt (
				rule_check_result_id,
				status,
				requested_value,
				provider_response,
				requested_at,
				confirmed_at
			)
			VALUES (
				:ruleCheckResultId,
				:status,
				CAST(:requestedValue AS jsonb),
				CAST(:providerResponse AS jsonb),
				:requestedAt,
				:confirmedAt
			)
			""".trimIndent(),
			org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
				mapOf(
					"ruleCheckResultId" to ruleCheckResultId,
					"status" to status.name,
					"requestedValue" to requestedValue?.let(objectMapper::writeValueAsString),
					"providerResponse" to providerResponse?.let(objectMapper::writeValueAsString),
					"requestedAt" to requestedAt,
					"confirmedAt" to confirmedAt,
				),
			),
			keyHolder,
			arrayOf("id"),
		)
		return requireNotNull(keyHolder.key).toLong()
	}

	fun latestAttemptAt(
		ruleId: Long,
		deviceId: Long,
	): Instant? =
		jdbc.query(
			"""
			SELECT fa.requested_at
			FROM fix_attempt fa
			JOIN rule_check_result rcr ON rcr.id = fa.rule_check_result_id
			JOIN device_check_result dcr ON dcr.id = rcr.device_check_result_id
			WHERE rcr.rule_id = :ruleId
				AND dcr.device_id = :deviceId
			ORDER BY fa.requested_at DESC, fa.id DESC
			LIMIT 1
			""".trimIndent(),
			mapOf(
				"ruleId" to ruleId,
				"deviceId" to deviceId,
			),
		) { rs, _ -> rs.getTimestamp("requested_at").toInstant() }
			.firstOrNull()
}
