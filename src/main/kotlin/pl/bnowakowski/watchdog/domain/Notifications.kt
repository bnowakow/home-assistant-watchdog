// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import tools.jackson.databind.JsonNode

@Table("notification_channel")
data class NotificationChannel(
	@Id
	val id: Long? = null,
	@Column("channel_type")
	val channelType: NotificationChannelType,
	val name: String,
	val enabled: Boolean = true,
	val configuration: JsonNode = JsonDefaults.emptyObject(),
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
	@Column("updated_at")
	val updatedAt: Instant = Instant.now(),
)

@Table("notification_event")
data class NotificationEvent(
	@Id
	val id: Long? = null,
	@Column("channel_id")
	val channelId: Long,
	@Column("device_id")
	val deviceId: Long? = null,
	@Column("rule_id")
	val ruleId: Long? = null,
	@Column("dedupe_key")
	val dedupeKey: String,
	val status: NotificationEventStatus,
	val severity: Severity,
	val message: String,
	@Column("provider_response")
	val providerResponse: JsonNode? = null,
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
)
