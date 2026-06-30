// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.rules

import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.ProviderType

data class EffectiveRulesView(
	val device: Device,
	val rules: List<EffectiveRule>,
) {
	val activeRules: List<EffectiveRule>
		get() = rules.filter { it.status == EffectiveRuleStatus.ACTIVE }

	val skippedRules: List<EffectiveRule>
		get() = rules.filter { it.status != EffectiveRuleStatus.ACTIVE }
}

data class EffectiveRule(
	val device: Device,
	val group: DeviceGroup,
	val rule: DeviceGroupRule,
	val effectiveProviderType: ProviderType,
	val propertyKey: EffectiveRulePropertyKey?,
	val status: EffectiveRuleStatus,
	val reason: String? = null,
	val conflictingGroups: List<String> = emptyList(),
) {
	val active: Boolean
		get() = status == EffectiveRuleStatus.ACTIVE
}

data class EffectiveRulePropertyKey(
	val providerType: ProviderType,
	val propertyPath: String,
	val endpoint: String?,
)

enum class EffectiveRuleStatus {
	ACTIVE,
	SKIPPED_PROVIDER_MISMATCH,
	SKIPPED_UNSUPPORTED_PROPERTY,
	CONFIGURATION_ERROR,
}
