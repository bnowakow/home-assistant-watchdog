// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import tools.jackson.databind.JsonNode

@Table("device_group_rule")
data class DeviceGroupRule(
	@Id
	val id: Long? = null,
	@Column("group_id")
	val groupId: Long,
	@Column("provider_type")
	val providerType: ProviderType? = null,
	@Column("rule_type")
	val ruleType: RuleType,
	@Column("property_path")
	val propertyPath: String? = null,
	val endpoint: String? = null,
	@Column("comparison_operator")
	val comparisonOperator: ComparisonOperator,
	@Column("desired_value")
	val desiredValue: JsonNode? = null,
	@Column("check_mode")
	val checkMode: CheckMode = CheckMode.OBSERVE_ONLY,
	val severity: Severity = Severity.WARNING,
	@Column("notify_on_mismatch")
	val notifyOnMismatch: Boolean = false,
	@Column("notify_on_fix_success")
	val notifyOnFixSuccess: Boolean = false,
	@Column("notify_on_fix_failure")
	val notifyOnFixFailure: Boolean = true,
	@Column("notify_on_low_battery")
	val notifyOnLowBattery: Boolean = false,
	@Column("notify_on_offline_stale")
	val notifyOnOfflineStale: Boolean = false,
	@Column("notify_on_recovery")
	val notifyOnRecovery: Boolean = true,
	@Column("cooldown_seconds")
	val cooldownSeconds: Int = 86_400,
	@Column("retry_count")
	val retryCount: Int = 3,
	@Column("retry_delay_seconds")
	val retryDelaySeconds: Int = 60,
	@Column("missing_property_retry_count")
	val missingPropertyRetryCount: Int = 3,
	@Column("missing_property_retry_delay_seconds")
	val missingPropertyRetryDelaySeconds: Int = 10,
	val enabled: Boolean = true,
	@Column("created_at")
	val createdAt: Instant = Instant.now(),
	@Column("updated_at")
	val updatedAt: Instant = Instant.now(),
)
