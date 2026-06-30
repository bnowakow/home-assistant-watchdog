// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.history

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.checks.EvaluatedDeviceCheck
import pl.bnowakowski.watchdog.checks.EvaluatedRuleCheck
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.ParameterHistorySource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRulePropertyKey
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import tools.jackson.databind.ObjectMapper

class ParameterHistoryWriterTest {
	private val queries: ParameterHistoryQueries = mock()
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T20:00:00Z"), ZoneOffset.UTC)

	@Test
	fun `writes first observed checked parameter with projections`() {
		val writer = writer(recordUnchanged = false)
		val value = objectMapper.readTree("42")
		whenever(queries.latestValue(1, ProviderType.ZIGBEE2MQTT, "battery", null)).thenReturn(null)

		writer.recordCheck(99, deviceResult(value = value, propertyPath = "battery"))

		verify(queries).insert(
			argThat {
				deviceId == 1L &&
					ruleId == 10L &&
					providerType == ProviderType.ZIGBEE2MQTT &&
					propertyPath == "battery" &&
					valueNumber == 42.0 &&
					valueText == "42" &&
					previousValueJson == null &&
					changed &&
					source == ParameterHistorySource.CHECK &&
					checkRunId == 99L
			},
		)
	}

	@Test
	fun `skips unchanged value by default`() {
		val writer = writer(recordUnchanged = false)
		val value = objectMapper.readTree(""""ON"""")
		whenever(queries.latestValue(1, ProviderType.ZIGBEE2MQTT, "state_right", null))
			.thenReturn(ParameterHistoryLatestValue(value, Instant.parse("2026-06-30T19:59:00Z")))

		writer.recordCheck(99, deviceResult(value = value, propertyPath = "state_right"))

		verify(queries, never()).insert(any())
	}

	@Test
	fun `samples unchanged value when enabled and sample age has elapsed`() {
		val writer = writer(recordUnchanged = true, unchangedSampleSeconds = 60)
		val value = objectMapper.readTree("true")
		whenever(queries.latestValue(1, ProviderType.ZIGBEE2MQTT, "state_right", null))
			.thenReturn(ParameterHistoryLatestValue(value, Instant.parse("2026-06-30T19:58:00Z")))

		writer.recordCheck(99, deviceResult(value = value, propertyPath = "state_right"))

		verify(queries).insert(
			argThat {
				!changed &&
					valueBoolean == true &&
					previousValueJson == value
			},
		)
	}

	@Test
	fun `cleans up expired history using retention`() {
		val writer = writer(recordUnchanged = false)

		writer.cleanupExpiredHistory()

		verify(queries).deleteObservedBefore(Instant.parse("2025-06-30T20:00:00Z"))
	}

	private fun writer(
		recordUnchanged: Boolean,
		unchangedSampleSeconds: Long = 86_400,
	): ParameterHistoryWriter =
		ParameterHistoryWriter(
			queries = queries,
			properties = ParameterHistoryProperties(
				retentionDays = 365,
				recordUnchanged = recordUnchanged,
				unchangedSampleSeconds = unchangedSampleSeconds,
			),
			clock = clock,
		)

	private fun deviceResult(
		value: tools.jackson.databind.JsonNode,
		propertyPath: String,
	): EvaluatedDeviceCheck {
		val device = Device(
			id = 1,
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "0xabc",
			ieeeAddress = "0xabc",
			friendlyName = "Bedroom Switch",
			displayName = "Bedroom Switch",
			modelKey = "TS0012",
		)
		val effectiveRule = EffectiveRule(
			device = device,
			group = DeviceGroup(id = 1, name = "Switches", providerType = ProviderType.ZIGBEE2MQTT, modelKey = "TS0012"),
			rule = DeviceGroupRule(
				id = 10,
				groupId = 1,
				providerType = ProviderType.ZIGBEE2MQTT,
				ruleType = RuleType.DESIRED_PROPERTY,
				propertyPath = propertyPath,
				comparisonOperator = ComparisonOperator.EQUALS,
			),
			effectiveProviderType = ProviderType.ZIGBEE2MQTT,
			propertyKey = EffectiveRulePropertyKey(ProviderType.ZIGBEE2MQTT, propertyPath, null),
			status = EffectiveRuleStatus.ACTIVE,
		)
		return EvaluatedDeviceCheck(
			device = device,
			status = DeviceCheckStatus.HEALTHY,
			snapshot = DeviceSnapshot(
				providerType = ProviderType.ZIGBEE2MQTT,
				providerDeviceId = "0xabc",
				observedAt = clock.instant(),
				available = true,
			),
			checkedAt = clock.instant(),
			ruleResults = listOf(
				EvaluatedRuleCheck(
					effectiveRule = effectiveRule,
					status = RuleCheckStatus.MATCH,
					actualValue = value,
				),
			),
		)
	}
}
