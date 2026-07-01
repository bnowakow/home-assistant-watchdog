// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.sql.Types
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.NotificationEventStatus
import pl.bnowakowski.watchdog.domain.Severity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class NotificationQueries(
	private val jdbc: NamedParameterJdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	fun ensureAppUser(
		email: String,
		role: String = "ADMIN",
	): Long =
		requireNotNull(
			jdbc.queryForObject(
				"""
				INSERT INTO app_user(email, role, status)
				VALUES (:email, :role, 'ACTIVE')
				ON CONFLICT (email) DO UPDATE
				   SET status = 'ACTIVE',
				       updated_at = now()
				RETURNING id
				""".trimIndent(),
				mapOf("email" to email, "role" to role),
				Long::class.java,
			),
		)

	fun findPushoverPreference(appUserId: Long): NotificationPreference? =
		jdbc.query(
			"""
			SELECT app_user_id, pushover_user_key_encrypted, pushover_user_key_suffix,
			       pushover_device, notify_recovery_enabled, created_at, updated_at
			FROM notification_preference
			WHERE app_user_id = :appUserId
				AND provider = 'PUSHOVER'
			""".trimIndent(),
			mapOf("appUserId" to appUserId),
			PREFERENCE_ROW_MAPPER,
		).firstOrNull()

	fun upsertPushoverPreference(
		appUserId: Long,
		pushoverUserKeyEncrypted: String,
		pushoverUserKeySuffix: String,
		pushoverDevices: Collection<String>,
		notifyRecoveryEnabled: Boolean,
	): NotificationPreference =
		jdbc.query(
			"""
			INSERT INTO notification_preference(
				app_user_id,
				provider,
				pushover_user_key_encrypted,
				pushover_user_key_suffix,
				pushover_device,
				notify_recovery_enabled
			)
			VALUES (
				:appUserId,
				'PUSHOVER',
				:pushoverUserKeyEncrypted,
				:pushoverUserKeySuffix,
				:pushoverDevice,
				:notifyRecoveryEnabled
			)
			ON CONFLICT (app_user_id) DO UPDATE
			   SET pushover_user_key_encrypted = :pushoverUserKeyEncrypted,
			       pushover_user_key_suffix = :pushoverUserKeySuffix,
			       pushover_device = :pushoverDevice,
			       notify_recovery_enabled = :notifyRecoveryEnabled,
			       updated_at = now()
			RETURNING app_user_id, pushover_user_key_encrypted, pushover_user_key_suffix,
			          pushover_device, notify_recovery_enabled, created_at, updated_at
			""".trimIndent(),
			MapSqlParameterSource()
				.addValue("appUserId", appUserId)
				.addValue("pushoverUserKeyEncrypted", pushoverUserKeyEncrypted)
				.addValue("pushoverUserKeySuffix", pushoverUserKeySuffix)
				.addValue("pushoverDevice", PushoverDevices.format(pushoverDevices))
				.addValue("notifyRecoveryEnabled", notifyRecoveryEnabled),
			PREFERENCE_ROW_MAPPER,
		).single()

	fun findPushoverRecipients(): List<NotificationRecipient> =
		jdbc.query(
			"""
			SELECT np.app_user_id,
			       au.email,
			       np.pushover_user_key_encrypted,
			       np.pushover_device,
			       np.notify_recovery_enabled
			FROM notification_preference np
			JOIN app_user au ON au.id = np.app_user_id
			WHERE np.provider = 'PUSHOVER'
				AND np.pushover_user_key_encrypted IS NOT NULL
				AND au.status = 'ACTIVE'
			""".trimIndent(),
			emptyMap<String, Any>(),
			RECIPIENT_ROW_MAPPER,
		)

	fun ensurePushoverChannel(): Long {
		val existing = jdbc.query(
			"""
			SELECT id
			FROM notification_channel
			WHERE channel_type = 'PUSHOVER'
				AND name = 'Pushover'
			""".trimIndent(),
			emptyMap<String, Any>(),
		) { rs, _ -> rs.getLong("id") }.firstOrNull()
		if (existing != null) {
			return existing
		}
		return requireNotNull(
			jdbc.queryForObject(
				"""
				INSERT INTO notification_channel(channel_type, name, enabled)
				VALUES ('PUSHOVER', 'Pushover', true)
				ON CONFLICT (name) DO UPDATE
				   SET enabled = notification_channel.enabled,
				       updated_at = now()
				RETURNING id
				""".trimIndent(),
				MapSqlParameterSource(),
				Long::class.java,
			),
		)
	}

	fun latestSentAt(dedupeKey: String): Instant? =
		jdbc.query(
			"""
			SELECT created_at
			FROM notification_event
			WHERE dedupe_key = :dedupeKey
				AND status = 'SENT'
			ORDER BY created_at DESC, id DESC
			LIMIT 1
			""".trimIndent(),
			mapOf("dedupeKey" to dedupeKey),
		) { rs, _ -> rs.getTimestamp("created_at").toInstant() }
			.firstOrNull()

	fun previousRuleStatus(
		deviceId: Long,
		ruleId: Long,
		currentRuleCheckResultId: Long,
	): String? =
		jdbc.query(
			"""
			SELECT rcr.status
			FROM rule_check_result rcr
			JOIN device_check_result dcr ON dcr.id = rcr.device_check_result_id
			WHERE dcr.device_id = :deviceId
				AND rcr.rule_id = :ruleId
				AND rcr.id < :currentRuleCheckResultId
			ORDER BY rcr.id DESC
			LIMIT 1
			""".trimIndent(),
			mapOf(
				"deviceId" to deviceId,
				"ruleId" to ruleId,
				"currentRuleCheckResultId" to currentRuleCheckResultId,
			),
		) { rs, _ -> rs.getString("status") }
			.firstOrNull()

	fun insertEvent(
		channelId: Long,
		deviceId: Long?,
		ruleId: Long?,
		dedupeKey: String,
		status: NotificationEventStatus,
		severity: Severity,
		message: String,
		providerResponse: JsonNode?,
		createdAt: Instant,
	): Long {
		val keyHolder = GeneratedKeyHolder()
		jdbc.update(
			"""
			INSERT INTO notification_event (
				channel_id,
				device_id,
				rule_id,
				dedupe_key,
				status,
				severity,
				message,
				provider_response,
				created_at
			)
			VALUES (
				:channelId,
				:deviceId,
				:ruleId,
				:dedupeKey,
				:status,
				:severity,
				:message,
				CAST(:providerResponse AS jsonb),
				:createdAt
			)
			""".trimIndent(),
			MapSqlParameterSource()
				.addValue("channelId", channelId)
				.addValue("deviceId", deviceId)
				.addValue("ruleId", ruleId)
				.addValue("dedupeKey", dedupeKey)
				.addValue("status", status.name)
				.addValue("severity", severity.name)
				.addValue("message", message)
				.addValue("providerResponse", providerResponse?.let(objectMapper::writeValueAsString))
				.addValue("createdAt", createdAt.toUtcOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE),
			keyHolder,
			arrayOf("id"),
		)
		return requireNotNull(keyHolder.key).toLong()
	}

	private companion object {
		val PREFERENCE_ROW_MAPPER = RowMapper<NotificationPreference> { rs, _ ->
			NotificationPreference(
				appUserId = rs.getLong("app_user_id"),
				pushoverUserKeyEncrypted = rs.getString("pushover_user_key_encrypted"),
				pushoverUserKeySuffix = rs.getString("pushover_user_key_suffix"),
				pushoverDevices = PushoverDevices.parse(rs.getString("pushover_device")),
				notifyRecoveryEnabled = rs.getBoolean("notify_recovery_enabled"),
				createdAt = rs.getTimestamp("created_at")?.toInstant(),
				updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
			)
		}

		val RECIPIENT_ROW_MAPPER = RowMapper<NotificationRecipient> { rs, _ ->
			NotificationRecipient(
				appUserId = rs.getLong("app_user_id"),
				email = rs.getString("email"),
				pushoverUserKeyEncrypted = rs.getString("pushover_user_key_encrypted"),
				pushoverDevices = PushoverDevices.parse(rs.getString("pushover_device")),
				notifyRecoveryEnabled = rs.getBoolean("notify_recovery_enabled"),
			)
		}
	}

	private fun Instant.toUtcOffsetDateTime(): OffsetDateTime =
		OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
