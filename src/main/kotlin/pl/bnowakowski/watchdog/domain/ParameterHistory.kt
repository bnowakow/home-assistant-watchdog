// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import tools.jackson.databind.JsonNode

@Table("device_parameter_history")
data class DeviceParameterHistory(
	@Id
	val id: Long? = null,
	@Column("device_id")
	val deviceId: Long,
	@Column("rule_id")
	val ruleId: Long? = null,
	@Column("provider_type")
	val providerType: ProviderType,
	@Column("property_path")
	val propertyPath: String,
	val endpoint: String? = null,
	@Column("value_json")
	val valueJson: JsonNode? = null,
	@Column("value_text")
	val valueText: String? = null,
	@Column("value_number")
	val valueNumber: Double? = null,
	@Column("value_boolean")
	val valueBoolean: Boolean? = null,
	@Column("previous_value_json")
	val previousValueJson: JsonNode? = null,
	val changed: Boolean,
	val source: ParameterHistorySource,
	@Column("observed_at")
	val observedAt: Instant = Instant.now(),
	@Column("check_run_id")
	val checkRunId: Long? = null,
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
)
