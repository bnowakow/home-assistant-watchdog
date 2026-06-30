// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Criticality
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRulePropertyKey
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import pl.bnowakowski.watchdog.rules.EffectiveRulesView
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory

class CheckEvaluatorTest {
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T18:00:00Z"), ZoneOffset.UTC)
	private val provider = mock<DeviceProvider>()

	@Test
	fun `matches desired property rule`() {
		val device = device()
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.readSnapshot(device)).thenReturn(
			snapshot(device, mapOf("operation_mode_right" to objectMapper.readTree(""""decoupled""""))),
		)

		val result = evaluator().evaluate(
			EffectiveRulesView(
				device = device,
				rules = listOf(rule(propertyPath = "operation_mode_right", expectedJson = """"decoupled"""")),
			),
		)

		assertEquals(DeviceCheckStatus.HEALTHY, result.status)
		assertEquals(RuleCheckStatus.MATCH, result.ruleResults.single().status)
	}

	@Test
	fun `retries missing property and records error when still missing`() {
		val device = device()
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.readSnapshot(device)).thenReturn(snapshot(device, emptyMap()))

		val result = evaluator().evaluate(
			EffectiveRulesView(
				device = device,
				rules = listOf(rule(propertyPath = "operation_mode_right", expectedJson = """"decoupled"""")),
			),
		)

		assertEquals(DeviceCheckStatus.DEGRADED, result.status)
		assertEquals(RuleCheckStatus.ERROR, result.ruleResults.single().status)
	}

	@Test
	fun `marks critical stale device as degraded using critical threshold`() {
		val device = device(powerSource = PowerSource.BATTERY, criticality = Criticality.CRITICAL)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.readSnapshot(device)).thenReturn(
			DeviceSnapshot(
				providerType = ProviderType.ZIGBEE2MQTT,
				providerDeviceId = device.providerDeviceId,
				observedAt = clock.instant(),
				available = true,
				lastSeenAt = clock.instant().minus(Duration.ofSeconds(3_601)),
			),
		)

		val result = evaluator().evaluate(
			EffectiveRulesView(
				device = device,
				rules = listOf(rule(device = device, ruleType = RuleType.FRESHNESS, propertyPath = null)),
			),
		)

		assertEquals(DeviceCheckStatus.DEGRADED, result.status)
		assertEquals(RuleCheckStatus.MISMATCH, result.ruleResults.single().status)
	}

	@Test
	fun `treats home assistant unavailable state as offline`() {
		val device = device(providerType = ProviderType.HOME_ASSISTANT, providerDeviceId = "light.bedroom")
		whenever(provider.providerType).thenReturn(ProviderType.HOME_ASSISTANT)
		whenever(provider.readSnapshot(device)).thenReturn(
			DeviceSnapshot(
				providerType = ProviderType.HOME_ASSISTANT,
				providerDeviceId = device.providerDeviceId,
				observedAt = clock.instant(),
				available = true,
				payload = objectMapper.readTree("""{"state":"unavailable"}"""),
			),
		)

		val result = evaluator().evaluate(
			EffectiveRulesView(
				device = device,
				rules = listOf(
					rule(
						device = device,
						ruleType = RuleType.AVAILABILITY,
						propertyPath = null,
						providerType = ProviderType.HOME_ASSISTANT,
					),
				),
			),
		)

		assertEquals(DeviceCheckStatus.OFFLINE, result.status)
		assertEquals(RuleCheckStatus.MISMATCH, result.ruleResults.single().status)
	}

	private fun device(
		providerType: ProviderType = ProviderType.ZIGBEE2MQTT,
		providerDeviceId: String = "0xabc",
		powerSource: PowerSource = PowerSource.MAINS,
		criticality: Criticality = Criticality.NORMAL,
	): Device =
		Device(
			id = 1,
			providerType = providerType,
			providerDeviceId = providerDeviceId,
			ieeeAddress = providerDeviceId.takeIf { providerType == ProviderType.ZIGBEE2MQTT },
			friendlyName = "Bedroom Switch",
			displayName = "Bedroom Switch",
			modelKey = "TS0012",
			powerSource = powerSource,
			criticality = criticality,
		)

	private fun snapshot(
		device: Device,
		properties: Map<String, tools.jackson.databind.JsonNode>,
	): DeviceSnapshot =
		DeviceSnapshot(
			providerType = device.providerType,
			providerDeviceId = device.providerDeviceId,
			observedAt = clock.instant(),
			available = true,
			lastSeenAt = clock.instant(),
			payload = objectMapper.createObjectNode().also { payload ->
				properties.forEach { (key, value) -> payload.set(key, value) }
			},
			properties = properties.mapKeys {
				DevicePropertyRef(device.providerType, it.key)
			},
		)

	private fun rule(
		providerType: ProviderType = ProviderType.ZIGBEE2MQTT,
		device: Device = device(providerType = providerType),
		ruleType: RuleType = RuleType.DESIRED_PROPERTY,
		propertyPath: String?,
		expectedJson: String = "true",
	): EffectiveRule =
		EffectiveRule(
			device = device,
			group = DeviceGroup(id = 1, name = "Group", providerType = providerType, modelKey = "TS0012"),
			rule = DeviceGroupRule(
				id = 10,
				groupId = 1,
				providerType = providerType,
				ruleType = ruleType,
				propertyPath = propertyPath,
				comparisonOperator = ComparisonOperator.EQUALS,
				desiredValue = objectMapper.readTree(expectedJson),
			),
			effectiveProviderType = providerType,
			propertyKey = propertyPath?.let { EffectiveRulePropertyKey(providerType, it, null) },
			status = EffectiveRuleStatus.ACTIVE,
		)

	private fun evaluator(): CheckEvaluator =
		CheckEvaluator(
			providerRegistry = DeviceProviderRegistry(listOf(provider)),
			properties = CheckProperties(
				missingPropertyRetryCount = 1,
				missingPropertyRetryDelaySeconds = 0,
				staleMainsSeconds = 7_200,
				staleBatterySeconds = 86_400,
				staleCriticalSeconds = 3_600,
			),
			clock = clock,
			sleeper = CheckRetrySleeper { },
		)
}
