// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.history.ParameterHistoryWriter
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRulePropertyKey
import pl.bnowakowski.watchdog.rules.EffectiveRuleResolver
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import pl.bnowakowski.watchdog.rules.EffectiveRulesView
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import tools.jackson.databind.ObjectMapper

class CheckRunServiceTest {
	private val queries: CheckRunQueries = mock()
	private val resolver: EffectiveRuleResolver = mock()
	private val evaluator: CheckEvaluator = mock()
	private val parameterHistoryWriter: ParameterHistoryWriter = mock()
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T19:00:00Z"), ZoneOffset.UTC)
	private val service = CheckRunService(queries, resolver, evaluator, parameterHistoryWriter, objectMapper, clock)

	@Test
	fun `manual check creates run and persists results`() {
		val device = device()
		val view = EffectiveRulesView(device, listOf(rule(device)))
		val evaluated = EvaluatedDeviceCheck(
			device = device,
			status = DeviceCheckStatus.HEALTHY,
			snapshot = DeviceSnapshot(
				providerType = ProviderType.ZIGBEE2MQTT,
				providerDeviceId = "0xabc",
				observedAt = clock.instant(),
				available = true,
				payload = objectMapper.readTree("""{"state_right":"ON"}"""),
			),
			checkedAt = clock.instant(),
			ruleResults = listOf(
				EvaluatedRuleCheck(
					effectiveRule = rule(device),
					status = RuleCheckStatus.MATCH,
					actualValue = objectMapper.readTree(""""ON""""),
					expectedValue = objectMapper.readTree(""""ON""""),
				),
			),
		)
		whenever(queries.createRun(CheckRunTriggerType.MANUAL, clock.instant())).thenReturn(42)
		whenever(queries.loadEnabledDevices()).thenReturn(listOf(device))
		whenever(resolver.resolveForDevice(1)).thenReturn(view)
		whenever(evaluator.evaluate(view)).thenReturn(evaluated)
		whenever(queries.insertDeviceResult(eq(42), eq(1), eq(DeviceCheckStatus.HEALTHY), any(), eq(clock.instant())))
			.thenReturn(100)

		val report = service.runManualCheck()

		assertEquals(42, report.checkRunId)
		assertEquals(CheckRunStatus.COMPLETED, report.status)
		verify(queries).insertRuleResult(
			deviceCheckResultId = 100,
			ruleId = 10,
			status = RuleCheckStatus.MATCH,
			actualValue = objectMapper.readTree(""""ON""""),
			expectedValue = objectMapper.readTree(""""ON""""),
			message = null,
		)
		verify(queries).completeRun(eq(42), eq(CheckRunStatus.COMPLETED), eq(clock.instant()), any())
		verify(parameterHistoryWriter).recordCheck(42, evaluated)
		verify(parameterHistoryWriter).cleanupExpiredHistory()
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

	private fun rule(device: Device): EffectiveRule =
		EffectiveRule(
			device = device,
			group = DeviceGroup(id = 1, name = "Group", providerType = ProviderType.ZIGBEE2MQTT, modelKey = "TS0012"),
			rule = DeviceGroupRule(
				id = 10,
				groupId = 1,
				providerType = ProviderType.ZIGBEE2MQTT,
				ruleType = RuleType.DESIRED_PROPERTY,
				propertyPath = "state_right",
				comparisonOperator = ComparisonOperator.EQUALS,
				desiredValue = objectMapper.readTree(""""ON""""),
			),
			effectiveProviderType = ProviderType.ZIGBEE2MQTT,
			propertyKey = EffectiveRulePropertyKey(ProviderType.ZIGBEE2MQTT, "state_right", null),
			status = EffectiveRuleStatus.ACTIVE,
		)
}
