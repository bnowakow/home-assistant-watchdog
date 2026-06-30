// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.PropertyMetadata
import pl.bnowakowski.watchdog.provider.PropertyValueType

class EffectiveRuleResolverTest {
	private val queries: EffectiveRuleQueries = mock()
	private val provider = mock<DeviceProvider>()

	@Test
	fun `loads enabled group rules for enabled device`() {
		val device = device()
		val group = group(id = 10, name = "Bedroom switches")
		val rule = rule(groupId = 10, propertyPath = "operation_mode_right", endpoint = "right")
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(queries.loadEnabledDevice(1)).thenReturn(device)
		whenever(queries.loadEnabledGroupRules(1)).thenReturn(listOf(GroupRuleRow(group, rule)))
		whenever(provider.supportedProperties(device)).thenReturn(
			listOf(property("operation_mode_right", endpoint = "right")),
		)

		val view = resolver().resolveForDevice(1)

		requireNotNull(view)
		assertEquals(device, view.device)
		assertEquals(1, view.activeRules.size)
		assertEquals("Bedroom switches", view.activeRules.single().group.name)
	}

	@Test
	fun `skips rules for a different provider`() {
		val device = device()
		val rule = rule(
			groupId = 10,
			providerType = ProviderType.HOME_ASSISTANT,
			propertyPath = "state",
		)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.supportedProperties(device)).thenReturn(emptyList())

		val view = resolver().resolveForDevice(device, listOf(GroupRuleRow(group(id = 10), rule)))

		assertTrue(view.activeRules.isEmpty())
		assertEquals(EffectiveRuleStatus.SKIPPED_PROVIDER_MISMATCH, view.skippedRules.single().status)
	}

	@Test
	fun `skips rules whose property is not exposed by provider metadata`() {
		val device = device()
		val rule = rule(groupId = 10, propertyPath = "operation_mode_left", endpoint = "left")
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.supportedProperties(device)).thenReturn(
			listOf(property("operation_mode_right", endpoint = "right")),
		)

		val view = resolver().resolveForDevice(device, listOf(GroupRuleRow(group(id = 10), rule)))

		assertFalse(view.rules.single().active)
		assertEquals(EffectiveRuleStatus.SKIPPED_UNSUPPORTED_PROPERTY, view.rules.single().status)
	}

	@Test
	fun `marks conflicting rules as configuration errors`() {
		val device = device()
		val first = GroupRuleRow(
			group = group(id = 10, name = "Bedroom switches"),
			rule = rule(id = 100, groupId = 10, propertyPath = "state_right", endpoint = "right"),
		)
		val second = GroupRuleRow(
			group = group(id = 11, name = "All switches"),
			rule = rule(id = 101, groupId = 11, propertyPath = "state_right", endpoint = "right"),
		)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.supportedProperties(device)).thenReturn(
			listOf(property("state_right", endpoint = "right")),
		)

		val view = resolver().resolveForDevice(device, listOf(first, second))

		assertTrue(view.activeRules.isEmpty())
		assertEquals(2, view.skippedRules.size)
		view.skippedRules.forEach {
			assertEquals(EffectiveRuleStatus.CONFIGURATION_ERROR, it.status)
			assertEquals(listOf("All switches", "Bedroom switches"), it.conflictingGroups)
		}
	}

	private fun device(): Device =
		Device(
			id = 1,
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "0xabc",
			ieeeAddress = "0xabc",
			friendlyName = "Bedroom Switch",
			displayName = "Bedroom Switch",
			modelKey = "TS0012",
		)

	private fun group(
		id: Long,
		name: String = "Group $id",
	): DeviceGroup =
		DeviceGroup(
			id = id,
			name = name,
			providerType = ProviderType.ZIGBEE2MQTT,
			modelKey = "TS0012",
		)

	private fun rule(
		id: Long = 100,
		groupId: Long,
		providerType: ProviderType? = null,
		propertyPath: String,
		endpoint: String? = null,
	): DeviceGroupRule =
		DeviceGroupRule(
			id = id,
			groupId = groupId,
			providerType = providerType,
			ruleType = RuleType.DESIRED_PROPERTY,
			propertyPath = propertyPath,
			endpoint = endpoint,
			comparisonOperator = ComparisonOperator.EQUALS,
		)

	private fun property(
		propertyPath: String,
		endpoint: String? = null,
	): PropertyMetadata =
		PropertyMetadata(
			ref = DevicePropertyRef(
				providerType = ProviderType.ZIGBEE2MQTT,
				propertyPath = propertyPath,
				endpoint = endpoint,
			),
			displayName = propertyPath,
			valueType = PropertyValueType.STRING,
		)

	private fun resolver(): EffectiveRuleResolver =
		EffectiveRuleResolver(
			queries = queries,
			providerRegistry = DeviceProviderRegistry(listOf(provider)),
		)
}
