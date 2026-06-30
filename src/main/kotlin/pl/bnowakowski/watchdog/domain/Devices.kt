// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import tools.jackson.databind.JsonNode

@Table("device")
data class Device(
	@Id
	val id: Long? = null,
	@Column("provider_type")
	val providerType: ProviderType,
	@Column("provider_device_id")
	val providerDeviceId: String,
	@Column("ieee_address")
	val ieeeAddress: String? = null,
	@Column("network_address")
	val networkAddress: String? = null,
	@Column("friendly_name")
	val friendlyName: String,
	@Column("display_name")
	val displayName: String,
	@Column("model_key")
	val modelKey: String,
	@Column("model_name")
	val modelName: String? = null,
	@Column("power_source")
	val powerSource: PowerSource = PowerSource.UNKNOWN,
	val criticality: Criticality = Criticality.NORMAL,
	val enabled: Boolean = true,
	@Column("provider_metadata")
	val providerMetadata: JsonNode = JsonDefaults.emptyObject(),
	@Column("last_seen_at")
	val lastSeenAt: Instant? = null,
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
	@Column("updated_at")
	val updatedAt: Instant = Instant.now(),
)

@Table("device_group")
data class DeviceGroup(
	@Id
	val id: Long? = null,
	val name: String,
	val description: String? = null,
	@Column("provider_type")
	val providerType: ProviderType? = null,
	@Column("model_key")
	val modelKey: String? = null,
	val priority: Int = 0,
	val enabled: Boolean = true,
	@Column("notification_defaults")
	val notificationDefaults: JsonNode = JsonDefaults.emptyObject(),
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
	@Column("updated_at")
	val updatedAt: Instant = Instant.now(),
)

@Table("device_group_membership")
data class DeviceGroupMembership(
	@Column("device_id")
	val deviceId: Long,
	@Column("group_id")
	val groupId: Long,
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
)
