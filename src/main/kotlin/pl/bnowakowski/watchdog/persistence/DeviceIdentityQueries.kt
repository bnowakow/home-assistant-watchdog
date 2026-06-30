// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.persistence

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.ProviderType

@Repository
class DeviceIdentityQueries(
	private val jdbc: NamedParameterJdbcTemplate,
) {
	fun providerIdentityExists(
		providerType: ProviderType,
		providerDeviceId: String,
		excludingDeviceId: Long? = null,
	): Boolean =
		exists(
			"""
			SELECT 1
			FROM device
			WHERE provider_type = :providerType
				AND provider_device_id = :providerDeviceId
				AND (:excludingDeviceId IS NULL OR id <> :excludingDeviceId)
			LIMIT 1
			""".trimIndent(),
			mapOf(
				"providerType" to providerType.name,
				"providerDeviceId" to providerDeviceId,
				"excludingDeviceId" to excludingDeviceId,
			),
		)

	fun zigbeeIeeeAddressExists(
		ieeeAddress: String,
		excludingDeviceId: Long? = null,
	): Boolean =
		exists(
			"""
			SELECT 1
			FROM device
			WHERE provider_type = :providerType
				AND ieee_address = :ieeeAddress
				AND (:excludingDeviceId IS NULL OR id <> :excludingDeviceId)
			LIMIT 1
			""".trimIndent(),
			mapOf(
				"providerType" to ProviderType.ZIGBEE2MQTT.name,
				"ieeeAddress" to ieeeAddress,
				"excludingDeviceId" to excludingDeviceId,
			),
		)

	private fun exists(sql: String, parameters: Map<String, Any?>): Boolean =
		jdbc.query(sql, parameters) { rs, _ -> rs.getInt(1) }
			.firstOrNull() != null
}
