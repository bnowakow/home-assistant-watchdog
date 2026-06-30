// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.rules

import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.PropertyMetadata
import pl.bnowakowski.watchdog.provider.UnknownDeviceProviderException

@Service
class EffectiveRuleResolver(
	private val queries: EffectiveRuleQueries,
	private val providerRegistry: DeviceProviderRegistry,
) {
	fun resolveForDevice(deviceId: Long): EffectiveRulesView? {
		val device = queries.loadEnabledDevice(deviceId) ?: return null
		return resolveForDevice(device, queries.loadEnabledGroupRules(deviceId))
	}

	fun resolveForDevice(
		device: Device,
		groupRules: List<GroupRuleRow>,
	): EffectiveRulesView {
		val supportedProperties = supportedProperties(device)
		val resolved = groupRules.map { row ->
			row.toEffectiveRule(device, supportedProperties)
		}
		return EffectiveRulesView(
			device = device,
			rules = markConflicts(resolved),
		)
	}

	private fun supportedProperties(device: Device): List<PropertyMetadata> =
		try {
			providerRegistry.providerFor(device.providerType)
				.supportedProperties(device)
		} catch (_: UnknownDeviceProviderException) {
			emptyList()
		}

	private fun GroupRuleRow.toEffectiveRule(
		device: Device,
		supportedProperties: List<PropertyMetadata>,
	): EffectiveRule {
		val effectiveProviderType = rule.providerType ?: device.providerType
		val propertyKey = rule.propertyPath?.let {
			EffectiveRulePropertyKey(
				providerType = effectiveProviderType,
				propertyPath = it,
				endpoint = rule.endpoint,
			)
		}

		if (effectiveProviderType != device.providerType) {
			return skipped(
				device = device,
				group = group,
				rule = rule,
				effectiveProviderType = effectiveProviderType,
				propertyKey = propertyKey,
				status = EffectiveRuleStatus.SKIPPED_PROVIDER_MISMATCH,
				reason = "Rule provider $effectiveProviderType does not match device provider ${device.providerType}",
			)
		}

		if (propertyKey != null && !isSupported(propertyKey, supportedProperties)) {
			return skipped(
				device = device,
				group = group,
				rule = rule,
				effectiveProviderType = effectiveProviderType,
				propertyKey = propertyKey,
				status = EffectiveRuleStatus.SKIPPED_UNSUPPORTED_PROPERTY,
				reason = "Device provider metadata does not expose ${propertyKey.propertyPath}",
			)
		}

		return EffectiveRule(
			device = device,
			group = group,
			rule = rule,
			effectiveProviderType = effectiveProviderType,
			propertyKey = propertyKey,
			status = EffectiveRuleStatus.ACTIVE,
		)
	}

	private fun isSupported(
		propertyKey: EffectiveRulePropertyKey,
		supportedProperties: List<PropertyMetadata>,
	): Boolean =
		supportedProperties.any {
			it.ref.providerType == propertyKey.providerType &&
				it.ref.propertyPath == propertyKey.propertyPath &&
				it.ref.endpoint == propertyKey.endpoint
		}

	private fun markConflicts(rules: List<EffectiveRule>): List<EffectiveRule> {
		val conflicts = rules
			.filter { it.status == EffectiveRuleStatus.ACTIVE && it.propertyKey != null }
			.groupBy { it.propertyKey }
			.filterValues { it.size > 1 }

		if (conflicts.isEmpty()) {
			return rules
		}

		return rules.map { rule ->
			val conflictGroup = conflicts[rule.propertyKey]
			if (conflictGroup == null) {
				rule
			} else {
				val groupNames = conflictGroup.map { it.group.name }.distinct().sorted()
				rule.copy(
					status = EffectiveRuleStatus.CONFIGURATION_ERROR,
					reason = "Conflicting group rules define ${rule.propertyKey?.propertyPath}",
					conflictingGroups = groupNames,
				)
			}
		}
	}

	private fun skipped(
		device: Device,
		group: DeviceGroup,
		rule: DeviceGroupRule,
		effectiveProviderType: pl.bnowakowski.watchdog.domain.ProviderType,
		propertyKey: EffectiveRulePropertyKey?,
		status: EffectiveRuleStatus,
		reason: String,
	): EffectiveRule =
		EffectiveRule(
			device = device,
			group = group,
			rule = rule,
			effectiveProviderType = effectiveProviderType,
			propertyKey = propertyKey,
			status = status,
			reason = reason,
		)
}
