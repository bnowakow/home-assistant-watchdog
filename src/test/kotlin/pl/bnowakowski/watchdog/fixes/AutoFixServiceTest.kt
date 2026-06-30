// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.fixes

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.checks.EvaluatedRuleCheck
import pl.bnowakowski.watchdog.checks.PersistedRuleCheckResult
import pl.bnowakowski.watchdog.domain.CheckMode
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.FixAttemptStatus
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.notifications.NotificationService
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.FixAttemptResult
import pl.bnowakowski.watchdog.provider.PropertyMetadata
import pl.bnowakowski.watchdog.provider.PropertyValueType
import pl.bnowakowski.watchdog.provider.ProviderFixStatus
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRulePropertyKey
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import tools.jackson.databind.ObjectMapper

class AutoFixServiceTest {
	private val objectMapper = ObjectMapper()
	private val provider: DeviceProvider = mock()
	private val queries: FixAttemptQueries = mock()
	private val notificationService: NotificationService = mock()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T21:00:00Z"), ZoneOffset.UTC)

	@Test
	fun `applies auto fix for mismatched writable property`() {
		val result = persistedResult(checkMode = CheckMode.AUTO_FIX, status = RuleCheckStatus.MISMATCH)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.supportedProperties(result.evaluatedRuleCheck.effectiveRule.device)).thenReturn(listOf(property(writable = true)))
		whenever(provider.applyDesiredState(any(), any(), any())).thenReturn(
			FixAttemptResult(status = ProviderFixStatus.REQUESTED, message = "published"),
		)

		service().maybeFix(result)

		verify(provider).applyDesiredState(
			result.evaluatedRuleCheck.effectiveRule.device,
			DevicePropertyRef(ProviderType.ZIGBEE2MQTT, "state_right", null),
			objectMapper.readTree(""""ON""""),
		)
		verify(queries).insertAttempt(
			ruleCheckResultId = 500,
			status = FixAttemptStatus.REQUESTED,
			requestedValue = objectMapper.readTree(""""ON""""),
			providerResponse = objectMapper.createObjectNode().put("status", "REQUESTED").put("message", "published"),
			requestedAt = clock.instant(),
			confirmedAt = null,
		)
		verify(notificationService).notifyFixResult(result, FixAttemptStatus.REQUESTED, "published")
	}

	@Test
	fun `does not fix observe only mismatch`() {
		val result = persistedResult(checkMode = CheckMode.OBSERVE_ONLY, status = RuleCheckStatus.MISMATCH)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)

		service().maybeFix(result)

		verify(provider, never()).applyDesiredState(any(), any(), any())
		verify(queries, never()).insertAttempt(any(), any(), any(), any(), any(), any())
	}

	@Test
	fun `records skipped attempt when property is not writable`() {
		val result = persistedResult(checkMode = CheckMode.AUTO_FIX, status = RuleCheckStatus.MISMATCH)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(provider.supportedProperties(result.evaluatedRuleCheck.effectiveRule.device)).thenReturn(listOf(property(writable = false)))

		service().maybeFix(result)

		verify(provider, never()).applyDesiredState(any(), any(), any())
		verify(queries).insertAttempt(
			ruleCheckResultId = eq(500),
			status = eq(FixAttemptStatus.SKIPPED),
			requestedValue = eq(objectMapper.readTree(""""ON"""")),
			providerResponse = argThat { get("message").asText().contains("not mark") },
			requestedAt = eq(clock.instant()),
			confirmedAt = eq(null),
		)
	}

	@Test
	fun `records skipped attempt when cooldown is active`() {
		val result = persistedResult(checkMode = CheckMode.AUTO_FIX, status = RuleCheckStatus.MISMATCH)
		whenever(provider.providerType).thenReturn(ProviderType.ZIGBEE2MQTT)
		whenever(queries.latestAttemptAt(10, 1)).thenReturn(Instant.parse("2026-06-30T20:59:00Z"))

		service().maybeFix(result)

		verify(provider, never()).applyDesiredState(any(), any(), any())
		verify(queries).insertAttempt(
			ruleCheckResultId = eq(500),
			status = eq(FixAttemptStatus.SKIPPED),
			requestedValue = eq(null),
			providerResponse = argThat { get("message").asText().contains("cooldown") },
			requestedAt = eq(clock.instant()),
			confirmedAt = eq(null),
		)
	}

	private fun service(): AutoFixService =
		AutoFixService(
			providerRegistry = DeviceProviderRegistry(listOf(provider)),
			queries = queries,
			properties = FixProperties(defaultRetryCount = 1, defaultRetryDelaySeconds = 0, defaultCooldownSeconds = 300),
			notificationService = notificationService,
			objectMapper = objectMapper,
			clock = clock,
			sleeper = FixRetrySleeper { },
		)

	private fun persistedResult(
		checkMode: CheckMode,
		status: RuleCheckStatus,
	): PersistedRuleCheckResult =
		PersistedRuleCheckResult(
			evaluatedRuleCheck = EvaluatedRuleCheck(
				effectiveRule = effectiveRule(checkMode),
				status = status,
				actualValue = objectMapper.readTree(""""OFF""""),
				expectedValue = objectMapper.readTree(""""ON""""),
			),
			ruleCheckResultId = 500,
		)

	private fun effectiveRule(checkMode: CheckMode): EffectiveRule {
		val device = Device(
			id = 1,
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "0xabc",
			ieeeAddress = "0xabc",
			friendlyName = "Bedroom Switch",
			displayName = "Bedroom Switch",
			modelKey = "TS0012",
		)
		return EffectiveRule(
			device = device,
			group = DeviceGroup(id = 1, name = "Switches", providerType = ProviderType.ZIGBEE2MQTT, modelKey = "TS0012"),
			rule = DeviceGroupRule(
				id = 10,
				groupId = 1,
				providerType = ProviderType.ZIGBEE2MQTT,
				ruleType = RuleType.DESIRED_PROPERTY,
				propertyPath = "state_right",
				comparisonOperator = ComparisonOperator.EQUALS,
				desiredValue = objectMapper.readTree(""""ON""""),
				checkMode = checkMode,
				cooldownSeconds = 300,
				retryCount = 1,
				retryDelaySeconds = 0,
			),
			effectiveProviderType = ProviderType.ZIGBEE2MQTT,
			propertyKey = EffectiveRulePropertyKey(ProviderType.ZIGBEE2MQTT, "state_right", null),
			status = EffectiveRuleStatus.ACTIVE,
		)
	}

	private fun property(writable: Boolean): PropertyMetadata =
		PropertyMetadata(
			ref = DevicePropertyRef(ProviderType.ZIGBEE2MQTT, "state_right"),
			displayName = "state right",
			valueType = PropertyValueType.STRING,
			writable = writable,
		)
}
