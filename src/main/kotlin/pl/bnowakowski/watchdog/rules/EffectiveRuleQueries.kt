// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.rules

import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import pl.bnowakowski.watchdog.domain.CheckMode
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Criticality
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.JsonDefaults
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.domain.Severity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Repository
class EffectiveRuleQueries(
	private val jdbc: NamedParameterJdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	fun loadEnabledDevice(deviceId: Long): Device? =
		jdbc.query(
			"""
			SELECT *
			FROM device
			WHERE id = :deviceId
				AND enabled = true
			""".trimIndent(),
			mapOf("deviceId" to deviceId),
		) { rs, _ -> rs.toDevice() }
			.firstOrNull()

	fun loadEnabledGroupRules(deviceId: Long): List<GroupRuleRow> =
		jdbc.query(
			"""
			SELECT
				g.id AS group_id,
				g.name AS group_name,
				g.description AS group_description,
				g.provider_type AS group_provider_type,
				g.model_key AS group_model_key,
				g.priority AS group_priority,
				g.enabled AS group_enabled,
				g.notification_defaults AS group_notification_defaults,
				g.created_at AS group_created_at,
				g.updated_at AS group_updated_at,
				r.id AS rule_id,
				r.group_id AS rule_group_id,
				r.provider_type AS rule_provider_type,
				r.rule_type AS rule_type,
				r.property_path AS rule_property_path,
				r.endpoint AS rule_endpoint,
				r.comparison_operator AS rule_comparison_operator,
				r.desired_value AS rule_desired_value,
				r.check_mode AS rule_check_mode,
				r.severity AS rule_severity,
				r.notify_on_mismatch AS rule_notify_on_mismatch,
				r.notify_on_fix_success AS rule_notify_on_fix_success,
				r.notify_on_fix_failure AS rule_notify_on_fix_failure,
				r.notify_on_low_battery AS rule_notify_on_low_battery,
				r.notify_on_offline_stale AS rule_notify_on_offline_stale,
				r.notify_on_recovery AS rule_notify_on_recovery,
				r.cooldown_seconds AS rule_cooldown_seconds,
				r.retry_count AS rule_retry_count,
				r.retry_delay_seconds AS rule_retry_delay_seconds,
				r.missing_property_retry_count AS rule_missing_property_retry_count,
				r.missing_property_retry_delay_seconds AS rule_missing_property_retry_delay_seconds,
				r.enabled AS rule_enabled,
				r.created_at AS rule_created_at,
				r.updated_at AS rule_updated_at
			FROM device_group_membership m
			JOIN device_group g ON g.id = m.group_id
			JOIN device_group_rule r ON r.group_id = g.id
			WHERE m.device_id = :deviceId
				AND g.enabled = true
				AND r.enabled = true
			ORDER BY g.priority DESC, g.id ASC, r.id ASC
			""".trimIndent(),
			mapOf("deviceId" to deviceId),
		) { rs, _ ->
			GroupRuleRow(
				group = rs.toDeviceGroup(),
				rule = rs.toDeviceGroupRule(),
			)
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
			skipChecks = getBoolean("skip_checks"),
			providerMetadata = jsonNode(getString("provider_metadata")) ?: JsonDefaults.emptyObject(),
			lastSeenAt = instant("last_seen_at"),
			createdAt = instant("created_at") ?: Instant.EPOCH,
			updatedAt = instant("updated_at") ?: Instant.EPOCH,
		)

	private fun ResultSet.toDeviceGroup(): DeviceGroup =
		DeviceGroup(
			id = getLong("group_id"),
			name = getString("group_name"),
			description = getString("group_description"),
			providerType = getString("group_provider_type")?.let(ProviderType::valueOf),
			modelKey = getString("group_model_key"),
			priority = getInt("group_priority"),
			enabled = getBoolean("group_enabled"),
			notificationDefaults = jsonNode(getString("group_notification_defaults")) ?: JsonDefaults.emptyObject(),
			createdAt = instant("group_created_at") ?: Instant.EPOCH,
			updatedAt = instant("group_updated_at") ?: Instant.EPOCH,
		)

	private fun ResultSet.toDeviceGroupRule(): DeviceGroupRule =
		DeviceGroupRule(
			id = getLong("rule_id"),
			groupId = getLong("rule_group_id"),
			providerType = getString("rule_provider_type")?.let(ProviderType::valueOf),
			ruleType = RuleType.valueOf(getString("rule_type")),
			propertyPath = getString("rule_property_path"),
			endpoint = getString("rule_endpoint"),
			comparisonOperator = ComparisonOperator.valueOf(getString("rule_comparison_operator")),
			desiredValue = jsonNode(getString("rule_desired_value")),
			checkMode = CheckMode.valueOf(getString("rule_check_mode")),
			severity = Severity.valueOf(getString("rule_severity")),
			notifyOnMismatch = getBoolean("rule_notify_on_mismatch"),
			notifyOnFixSuccess = getBoolean("rule_notify_on_fix_success"),
			notifyOnFixFailure = getBoolean("rule_notify_on_fix_failure"),
			notifyOnLowBattery = getBoolean("rule_notify_on_low_battery"),
			notifyOnOfflineStale = getBoolean("rule_notify_on_offline_stale"),
			notifyOnRecovery = getBoolean("rule_notify_on_recovery"),
			cooldownSeconds = getInt("rule_cooldown_seconds"),
			retryCount = getInt("rule_retry_count"),
			retryDelaySeconds = getInt("rule_retry_delay_seconds"),
			missingPropertyRetryCount = getInt("rule_missing_property_retry_count"),
			missingPropertyRetryDelaySeconds = getInt("rule_missing_property_retry_delay_seconds"),
			enabled = getBoolean("rule_enabled"),
			createdAt = instant("rule_created_at") ?: Instant.EPOCH,
			updatedAt = instant("rule_updated_at") ?: Instant.EPOCH,
		)

	private fun ResultSet.instant(column: String): Instant? =
		getTimestamp(column)?.toInstant()

	private fun jsonNode(value: String?): JsonNode? =
		value?.let(objectMapper::readTree)
}

data class GroupRuleRow(
	val group: DeviceGroup,
	val rule: DeviceGroupRule,
)
