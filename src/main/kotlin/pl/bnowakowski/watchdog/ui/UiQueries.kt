package pl.bnowakowski.watchdog.ui

import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.FixAttemptStatus
import pl.bnowakowski.watchdog.domain.NotificationEventStatus
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.Severity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class UiQueries(
	private val jdbc: NamedParameterJdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	fun latestDeviceStatuses(): List<DeviceStatusRow> =
		jdbc.query(
			"""
			SELECT d.id,
			       d.display_name,
			       d.criticality,
			       d.enabled,
			       d.last_seen_at,
			       latest.status,
			       latest.checked_at
			FROM device d
			LEFT JOIN LATERAL (
				SELECT dcr.status, dcr.checked_at
				FROM device_check_result dcr
				WHERE dcr.device_id = d.id
				ORDER BY dcr.checked_at DESC, dcr.id DESC
				LIMIT 1
			) latest ON true
			ORDER BY
				CASE WHEN d.criticality = 'CRITICAL' THEN 0 WHEN d.criticality = 'IMPORTANT' THEN 1 ELSE 2 END,
				d.display_name,
				d.id
			""".trimIndent(),
			emptyMap<String, Any?>(),
		) { rs, _ -> rs.toDeviceStatusRow() }

	fun latestCheckRun(): CheckRunRow? =
		jdbc.query(
			"""
			SELECT id, trigger_type, status, started_at, finished_at, summary
			FROM check_run
			ORDER BY started_at DESC, id DESC
			LIMIT 1
			""".trimIndent(),
			emptyMap<String, Any?>(),
		) { rs, _ -> rs.toCheckRunRow() }.firstOrNull()

	fun recentCheckRuns(limit: Int = 50): List<CheckRunRow> =
		jdbc.query(
			"""
			SELECT id, trigger_type, status, started_at, finished_at, summary
			FROM check_run
			ORDER BY started_at DESC, id DESC
			LIMIT :limit
			""".trimIndent(),
			mapOf("limit" to limit),
		) { rs, _ -> rs.toCheckRunRow() }

	fun checkRunDeviceResults(checkRunId: Long): List<CheckRunDeviceResultRow> =
		jdbc.query(
			"""
			SELECT dcr.id,
			       dcr.device_id,
			       d.display_name,
			       d.criticality,
			       dcr.status,
			       dcr.checked_at,
			       COUNT(rcr.id) AS rule_count,
			       COUNT(rcr.id) FILTER (WHERE rcr.status = 'MATCH') AS matched_rule_count,
			       COUNT(rcr.id) FILTER (WHERE rcr.status = 'MISMATCH') AS mismatched_rule_count,
			       COUNT(rcr.id) FILTER (WHERE rcr.status = 'ERROR') AS error_rule_count,
			       COUNT(rcr.id) FILTER (WHERE rcr.status = 'SKIPPED') AS skipped_rule_count
			FROM device_check_result dcr
			JOIN device d ON d.id = dcr.device_id
			LEFT JOIN rule_check_result rcr ON rcr.device_check_result_id = dcr.id
			WHERE dcr.check_run_id = :checkRunId
			GROUP BY dcr.id, dcr.device_id, d.display_name, d.criticality, dcr.status, dcr.checked_at
			ORDER BY
				CASE dcr.status
					WHEN 'OFFLINE' THEN 0
					WHEN 'DEGRADED' THEN 1
					WHEN 'UNKNOWN' THEN 2
					WHEN 'SKIPPED' THEN 3
					ELSE 4
				END,
				d.display_name,
				dcr.id
			""".trimIndent(),
			mapOf("checkRunId" to checkRunId),
		) { rs, _ -> rs.toCheckRunDeviceResultRow() }

	fun checkRunRuleResults(checkRunId: Long): List<CheckRunRuleResultRow> =
		jdbc.query(
			"""
			SELECT *
			FROM (
				SELECT rcr.id,
				       d.display_name,
				       d.criticality,
				       dg.name AS group_name,
				       rcr.rule_id,
				       dgr.rule_type,
				       dgr.property_path,
				       dgr.endpoint,
				       dgr.severity,
				       rcr.status,
				       rcr.actual_value,
				       rcr.expected_value,
				       rcr.message
				FROM rule_check_result rcr
				JOIN device_check_result dcr ON dcr.id = rcr.device_check_result_id
				JOIN device d ON d.id = dcr.device_id
				JOIN device_group_rule dgr ON dgr.id = rcr.rule_id
				JOIN device_group dg ON dg.id = dgr.group_id
				WHERE dcr.check_run_id = :checkRunId

				UNION ALL

				SELECT -dcr.id AS id,
				       d.display_name,
				       d.criticality,
				       '-' AS group_name,
				       NULL::BIGINT AS rule_id,
				       'DEVICE_SKIPPED' AS rule_type,
				       NULL::TEXT AS property_path,
				       NULL::TEXT AS endpoint,
				       'INFO' AS severity,
				       'SKIPPED' AS status,
				       NULL::JSONB AS actual_value,
				       NULL::JSONB AS expected_value,
				       'Device checks are skipped' AS message
				FROM device_check_result dcr
				JOIN device d ON d.id = dcr.device_id
				WHERE dcr.check_run_id = :checkRunId
				  AND dcr.status = 'SKIPPED'
			) results
			ORDER BY
				CASE status
					WHEN 'ERROR' THEN 0
					WHEN 'MISMATCH' THEN 1
					WHEN 'SKIPPED' THEN 2
					ELSE 3
				END,
				display_name,
				id
			""".trimIndent(),
			mapOf("checkRunId" to checkRunId),
		) { rs, _ -> rs.toCheckRunRuleResultRow() }

	fun groupMemberships(deviceId: Long): List<GroupMembershipRow> =
		jdbc.query(
			"""
			SELECT g.id, g.name, g.provider_type, g.model_key, g.priority, g.enabled
			FROM device_group_membership m
			JOIN device_group g ON g.id = m.group_id
			WHERE m.device_id = :deviceId
			ORDER BY g.priority DESC, g.name, g.id
			""".trimIndent(),
			mapOf("deviceId" to deviceId),
		) { rs, _ -> rs.toGroupMembershipRow() }

	fun groupMemberIds(groupId: Long): Set<Long> =
		jdbc.query(
			"""
			SELECT device_id
			FROM device_group_membership
			WHERE group_id = :groupId
			""".trimIndent(),
			mapOf("groupId" to groupId),
		) { rs, _ -> rs.getLong("device_id") }.toSet()

	fun removeMembership(deviceId: Long, groupId: Long): Int =
		jdbc.update(
			"""
			DELETE FROM device_group_membership
			WHERE device_id = :deviceId
				AND group_id = :groupId
			""".trimIndent(),
			mapOf("deviceId" to deviceId, "groupId" to groupId),
		)

	fun latestSnapshot(deviceId: Long): JsonNode? =
		jdbc.query(
			"""
			SELECT snapshot
			FROM device_check_result
			WHERE device_id = :deviceId
			ORDER BY checked_at DESC, id DESC
			LIMIT 1
			""".trimIndent(),
			mapOf("deviceId" to deviceId),
		) { rs, _ -> rs.getString("snapshot")?.let(objectMapper::readTree) }.firstOrNull()

	fun recentRuleResults(deviceId: Long, limit: Int = 25): List<RuleResultRow> =
		jdbc.query(
			"""
			SELECT rcr.id,
			       rcr.rule_id,
			       rcr.status,
			       rcr.message,
			       rcr.actual_value,
			       rcr.expected_value,
			       dcr.checked_at
			FROM rule_check_result rcr
			JOIN device_check_result dcr ON dcr.id = rcr.device_check_result_id
			WHERE dcr.device_id = :deviceId
			ORDER BY dcr.checked_at DESC, rcr.id DESC
			LIMIT :limit
			""".trimIndent(),
			mapOf("deviceId" to deviceId, "limit" to limit),
		) { rs, _ -> rs.toRuleResultRow() }

	fun recentFixAttempts(deviceId: Long, limit: Int = 25): List<FixAttemptRow> =
		jdbc.query(
			"""
			SELECT fa.id,
			       rcr.rule_id,
			       fa.status,
			       fa.requested_at,
			       fa.confirmed_at,
			       fa.requested_value,
			       fa.provider_response
			FROM fix_attempt fa
			JOIN rule_check_result rcr ON rcr.id = fa.rule_check_result_id
			JOIN device_check_result dcr ON dcr.id = rcr.device_check_result_id
			WHERE dcr.device_id = :deviceId
			ORDER BY fa.requested_at DESC, fa.id DESC
			LIMIT :limit
			""".trimIndent(),
			mapOf("deviceId" to deviceId, "limit" to limit),
		) { rs, _ -> rs.toFixAttemptRow() }

	fun recentNotifications(deviceId: Long? = null, limit: Int = 50): List<NotificationRow> {
		val filter = if (deviceId == null) "" else "WHERE ne.device_id = :deviceId"
		return jdbc.query(
			"""
			SELECT ne.id,
			       ne.device_id,
			       d.display_name,
			       ne.rule_id,
			       ne.status,
			       ne.severity,
			       ne.message,
			       ne.created_at
			FROM notification_event ne
			LEFT JOIN device d ON d.id = ne.device_id
			$filter
			ORDER BY ne.created_at DESC, ne.id DESC
			LIMIT :limit
			""".trimIndent(),
			mapOf("deviceId" to deviceId, "limit" to limit),
		) { rs, _ -> rs.toNotificationRow() }
	}

	fun parameterHistory(deviceId: Long, limit: Int = 100): List<ParameterHistoryRow> =
		jdbc.query(
			"""
			SELECT id, rule_id, property_path, endpoint, value_json, changed, source, observed_at
			FROM device_parameter_history
			WHERE device_id = :deviceId
			ORDER BY observed_at DESC, id DESC
			LIMIT :limit
			""".trimIndent(),
			mapOf("deviceId" to deviceId, "limit" to limit),
		) { rs, _ -> rs.toParameterHistoryRow() }

	private fun ResultSet.toDeviceStatusRow() =
		DeviceStatusRow(
			id = getLong("id"),
			displayName = getString("display_name"),
			criticality = getString("criticality"),
			enabled = getBoolean("enabled"),
			lastSeenAt = getTimestamp("last_seen_at")?.toInstant(),
			status = getString("status"),
			checkedAt = getTimestamp("checked_at")?.toInstant(),
		)

	private fun ResultSet.toCheckRunRow() =
		CheckRunRow(
			id = getLong("id"),
			triggerType = CheckRunTriggerType.valueOf(getString("trigger_type")),
			status = CheckRunStatus.valueOf(getString("status")),
			startedAt = getTimestamp("started_at").toInstant(),
			finishedAt = getTimestamp("finished_at")?.toInstant(),
			summary = getString("summary")?.let(objectMapper::readTree),
		)

	private fun ResultSet.toCheckRunDeviceResultRow() =
		CheckRunDeviceResultRow(
			id = getLong("id"),
			deviceId = getLong("device_id"),
			deviceName = getString("display_name"),
			criticality = getString("criticality"),
			status = DeviceCheckStatus.valueOf(getString("status")),
			checkedAt = getTimestamp("checked_at").toInstant(),
			ruleCount = getInt("rule_count"),
			matchedRuleCount = getInt("matched_rule_count"),
			mismatchedRuleCount = getInt("mismatched_rule_count"),
			errorRuleCount = getInt("error_rule_count"),
			skippedRuleCount = getInt("skipped_rule_count"),
		)

	private fun ResultSet.toCheckRunRuleResultRow() =
		CheckRunRuleResultRow(
			id = getLong("id"),
			deviceName = getString("display_name"),
			criticality = getString("criticality"),
			groupName = getString("group_name"),
			ruleId = getLong("rule_id").takeUnless { wasNull() },
			ruleType = getString("rule_type"),
			propertyPath = getString("property_path"),
			endpoint = getString("endpoint"),
			severity = getString("severity"),
			status = RuleCheckStatus.valueOf(getString("status")),
			actualValue = getString("actual_value")?.let(objectMapper::readTree),
			expectedValue = getString("expected_value")?.let(objectMapper::readTree),
			message = getString("message"),
		)

	private fun ResultSet.toGroupMembershipRow() =
		GroupMembershipRow(
			id = getLong("id"),
			name = getString("name"),
			providerType = getString("provider_type"),
			modelKey = getString("model_key"),
			priority = getInt("priority"),
			enabled = getBoolean("enabled"),
		)

	private fun ResultSet.toRuleResultRow() =
		RuleResultRow(
			id = getLong("id"),
			ruleId = getLong("rule_id"),
			status = RuleCheckStatus.valueOf(getString("status")),
			message = getString("message"),
			actualValue = getString("actual_value")?.let(objectMapper::readTree),
			expectedValue = getString("expected_value")?.let(objectMapper::readTree),
			checkedAt = getTimestamp("checked_at").toInstant(),
		)

	private fun ResultSet.toFixAttemptRow() =
		FixAttemptRow(
			id = getLong("id"),
			ruleId = getLong("rule_id"),
			status = FixAttemptStatus.valueOf(getString("status")),
			requestedAt = getTimestamp("requested_at").toInstant(),
			confirmedAt = getTimestamp("confirmed_at")?.toInstant(),
			requestedValue = getString("requested_value")?.let(objectMapper::readTree),
			providerResponse = getString("provider_response")?.let(objectMapper::readTree),
		)

	private fun ResultSet.toNotificationRow() =
		NotificationRow(
			id = getLong("id"),
			deviceId = getLong("device_id").takeUnless { wasNull() },
			deviceName = getString("display_name"),
			ruleId = getLong("rule_id").takeUnless { wasNull() },
			status = NotificationEventStatus.valueOf(getString("status")),
			severity = Severity.valueOf(getString("severity")),
			message = getString("message"),
			createdAt = getTimestamp("created_at").toInstant(),
		)

	private fun ResultSet.toParameterHistoryRow() =
		ParameterHistoryRow(
			id = getLong("id"),
			ruleId = getLong("rule_id").takeUnless { wasNull() },
			propertyPath = getString("property_path"),
			endpoint = getString("endpoint"),
			value = getString("value_json")?.let(objectMapper::readTree),
			changed = getBoolean("changed"),
			source = getString("source"),
			observedAt = getTimestamp("observed_at").toInstant(),
		)
}

data class DeviceStatusRow(
	val id: Long,
	val displayName: String,
	val criticality: String,
	val enabled: Boolean,
	val lastSeenAt: Instant?,
	val status: String?,
	val checkedAt: Instant?,
)

data class CheckRunRow(
	val id: Long,
	val triggerType: CheckRunTriggerType,
	val status: CheckRunStatus,
	val startedAt: Instant,
	val finishedAt: Instant?,
	val summary: JsonNode?,
)

data class CheckRunDeviceResultRow(
	val id: Long,
	val deviceId: Long,
	val deviceName: String,
	val criticality: String,
	val status: DeviceCheckStatus,
	val checkedAt: Instant,
	val ruleCount: Int,
	val matchedRuleCount: Int,
	val mismatchedRuleCount: Int,
	val errorRuleCount: Int,
	val skippedRuleCount: Int,
)

data class CheckRunRuleResultRow(
	val id: Long,
	val deviceName: String,
	val criticality: String,
	val groupName: String,
	val ruleId: Long?,
	val ruleType: String,
	val propertyPath: String?,
	val endpoint: String?,
	val severity: String,
	val status: RuleCheckStatus,
	val actualValue: JsonNode?,
	val expectedValue: JsonNode?,
	val message: String?,
)

data class GroupMembershipRow(
	val id: Long,
	val name: String,
	val providerType: String?,
	val modelKey: String?,
	val priority: Int,
	val enabled: Boolean,
)

data class RuleResultRow(
	val id: Long,
	val ruleId: Long,
	val status: RuleCheckStatus,
	val message: String?,
	val actualValue: JsonNode?,
	val expectedValue: JsonNode?,
	val checkedAt: Instant,
)

data class FixAttemptRow(
	val id: Long,
	val ruleId: Long,
	val status: FixAttemptStatus,
	val requestedAt: Instant,
	val confirmedAt: Instant?,
	val requestedValue: JsonNode?,
	val providerResponse: JsonNode?,
)

data class NotificationRow(
	val id: Long,
	val deviceId: Long?,
	val deviceName: String?,
	val ruleId: Long?,
	val status: NotificationEventStatus,
	val severity: Severity,
	val message: String,
	val createdAt: Instant,
)

data class ParameterHistoryRow(
	val id: Long,
	val ruleId: Long?,
	val propertyPath: String,
	val endpoint: String?,
	val value: JsonNode?,
	val changed: Boolean,
	val source: String,
	val observedAt: Instant,
)
