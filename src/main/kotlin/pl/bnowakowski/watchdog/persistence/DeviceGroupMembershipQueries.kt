// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.persistence

import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.ProviderType

data class GroupModelLock(
	val providerType: ProviderType?,
	val modelKey: String?,
)

@Repository
class DeviceGroupMembershipQueries(
	private val jdbc: NamedParameterJdbcTemplate,
) {
	fun findModelLock(groupId: Long): GroupModelLock? =
		jdbc.query(
			"""
			SELECT provider_type, model_key
			FROM device_group
			WHERE id = :groupId
			""".trimIndent(),
			mapOf("groupId" to groupId),
		) { rs, _ ->
			GroupModelLock(
				providerType = rs.getString("provider_type")?.let(ProviderType::valueOf),
				modelKey = rs.getString("model_key"),
			)
		}.firstOrNull()

	fun lockModel(
		groupId: Long,
		providerType: ProviderType,
		modelKey: String,
		updatedAt: Instant,
	): Int =
		jdbc.update(
			"""
			UPDATE device_group
			SET provider_type = :providerType,
				model_key = :modelKey,
				updated_at = :updatedAt
			WHERE id = :groupId
				AND provider_type IS NULL
				AND model_key IS NULL
			""".trimIndent(),
			mapOf(
				"groupId" to groupId,
				"providerType" to providerType.name,
				"modelKey" to modelKey,
				"updatedAt" to Timestamp.from(updatedAt),
			),
		)

	fun addMembership(
		deviceId: Long,
		groupId: Long,
	): Int =
		jdbc.update(
			"""
			INSERT INTO device_group_membership (device_id, group_id)
			VALUES (:deviceId, :groupId)
			ON CONFLICT (device_id, group_id) DO NOTHING
			""".trimIndent(),
			mapOf(
				"deviceId" to deviceId,
				"groupId" to groupId,
			),
		)

	fun membersWithDifferentModel(
		groupId: Long,
		providerType: ProviderType,
		modelKey: String,
	): List<Long> =
		jdbc.query(
			"""
			SELECT d.id
			FROM device_group_membership m
			JOIN device d ON d.id = m.device_id
			WHERE m.group_id = :groupId
				AND (d.provider_type <> :providerType OR d.model_key <> :modelKey)
			ORDER BY d.id
			""".trimIndent(),
			mapOf(
				"groupId" to groupId,
				"providerType" to providerType.name,
				"modelKey" to modelKey,
			),
		) { rs, _ -> rs.getLong("id") }
}
