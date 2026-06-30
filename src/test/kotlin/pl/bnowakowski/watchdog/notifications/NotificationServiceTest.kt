// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.checks.EvaluatedRuleCheck
import pl.bnowakowski.watchdog.checks.PersistedRuleCheckResult
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.NotificationEventStatus
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.domain.Severity
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRulePropertyKey
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import tools.jackson.databind.ObjectMapper

class NotificationServiceTest {
	private val queries: NotificationQueries = mock()
	private val encryptor: PushoverUserKeyEncryptor = mock()
	private val client: PushoverClient = mock()
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T22:00:00Z"), ZoneOffset.UTC)

	@Test
	fun `sends high priority notification for critical low battery rule`() {
		val result = persistedResult(
			ruleType = RuleType.BATTERY_THRESHOLD,
			status = RuleCheckStatus.MISMATCH,
			severity = Severity.CRITICAL,
			notifyOnLowBattery = true,
		)
		whenever(queries.ensurePushoverChannel()).thenReturn(7)
		whenever(queries.findPushoverRecipients()).thenReturn(listOf(recipient()))
		whenever(encryptor.decrypt("encrypted")).thenReturn("user-key")

		service().notifyRuleResult(result)

		val message = argumentCaptor<PushoverMessage>()
		verify(client).send(message.capture())
		kotlin.test.assertEquals(1, message.firstValue.priority)
		kotlin.test.assertEquals("user-key", message.firstValue.userKey)
		verify(queries).insertEvent(
			channelId = eq(7),
			deviceId = eq(1),
			ruleId = eq(10),
			dedupeKey = eq("pushover:1:10:LOW_BATTERY"),
			status = eq(NotificationEventStatus.SENT),
			severity = eq(Severity.CRITICAL),
			message = any(),
			providerResponse = any(),
			createdAt = eq(clock.instant()),
		)
	}

	@Test
	fun `records cooldown skip instead of sending repeated notification`() {
		val result = persistedResult(
			ruleType = RuleType.DESIRED_PROPERTY,
			status = RuleCheckStatus.MISMATCH,
			notifyOnMismatch = true,
		)
		whenever(queries.ensurePushoverChannel()).thenReturn(7)
		whenever(queries.latestSentAt("pushover:1:10:MISMATCH")).thenReturn(Instant.parse("2026-06-30T21:30:00Z"))

		service(cooldownSeconds = 3_600).notifyRuleResult(result)

		verify(client, never()).send(any())
		verify(queries).insertEvent(
			channelId = eq(7),
			deviceId = eq(1),
			ruleId = eq(10),
			dedupeKey = eq("pushover:1:10:MISMATCH"),
			status = eq(NotificationEventStatus.SKIPPED_COOLDOWN),
			severity = eq(Severity.WARNING),
			message = any(),
			providerResponse = any(),
			createdAt = eq(clock.instant()),
		)
	}

	@Test
	fun `sends recovery only after previous unhealthy result`() {
		val result = persistedResult(
			ruleType = RuleType.DESIRED_PROPERTY,
			status = RuleCheckStatus.MATCH,
			notifyOnRecovery = true,
		)
		whenever(queries.previousRuleStatus(1, 10, 500)).thenReturn(RuleCheckStatus.MISMATCH.name)
		whenever(queries.ensurePushoverChannel()).thenReturn(7)
		whenever(queries.findPushoverRecipients()).thenReturn(listOf(recipient(notifyRecoveryEnabled = true)))
		whenever(encryptor.decrypt("encrypted")).thenReturn("user-key")

		service().notifyRuleResult(result)

		verify(client).send(any())
		verify(queries).insertEvent(
			channelId = eq(7),
			deviceId = eq(1),
			ruleId = eq(10),
			dedupeKey = eq("pushover:1:10:RECOVERY"),
			status = eq(NotificationEventStatus.SENT),
			severity = eq(Severity.INFO),
			message = any(),
			providerResponse = any(),
			createdAt = eq(clock.instant()),
		)
	}

	private fun service(cooldownSeconds: Long = 86_400): NotificationService =
		NotificationService(
			queries = queries,
			encryptor = encryptor,
			pushoverClient = client,
			notificationProperties = NotificationProperties(
				enabled = true,
				encryptionKey = "1234567890123456",
				pushover = NotificationProperties.Pushover(appToken = "app-token"),
			),
			deliveryProperties = NotificationDeliveryProperties(defaultCooldownSeconds = cooldownSeconds),
			objectMapper = objectMapper,
			clock = clock,
		)

	private fun recipient(notifyRecoveryEnabled: Boolean = true): NotificationRecipient =
		NotificationRecipient(
			appUserId = 2,
			email = "admin@example.com",
			pushoverUserKeyEncrypted = "encrypted",
			pushoverDevices = listOf("phone"),
			notifyRecoveryEnabled = notifyRecoveryEnabled,
		)

	private fun persistedResult(
		ruleType: RuleType,
		status: RuleCheckStatus,
		severity: Severity = Severity.WARNING,
		notifyOnMismatch: Boolean = false,
		notifyOnLowBattery: Boolean = false,
		notifyOnOfflineStale: Boolean = false,
		notifyOnRecovery: Boolean = true,
	): PersistedRuleCheckResult {
		val device = Device(
			id = 1,
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "0xabc",
			ieeeAddress = "0xabc",
			friendlyName = "Bedroom Thermostat",
			displayName = "Bedroom Thermostat",
			modelKey = "TS0601",
		)
		val rule = EffectiveRule(
			device = device,
			group = DeviceGroup(id = 20, name = "Critical thermostats", providerType = ProviderType.ZIGBEE2MQTT, modelKey = "TS0601"),
			rule = DeviceGroupRule(
				id = 10,
				groupId = 20,
				providerType = ProviderType.ZIGBEE2MQTT,
				ruleType = ruleType,
				propertyPath = "battery",
				comparisonOperator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
				desiredValue = objectMapper.readTree("20"),
				severity = severity,
				notifyOnMismatch = notifyOnMismatch,
				notifyOnLowBattery = notifyOnLowBattery,
				notifyOnOfflineStale = notifyOnOfflineStale,
				notifyOnRecovery = notifyOnRecovery,
			),
			effectiveProviderType = ProviderType.ZIGBEE2MQTT,
			propertyKey = EffectiveRulePropertyKey(ProviderType.ZIGBEE2MQTT, "battery", null),
			status = EffectiveRuleStatus.ACTIVE,
		)
		return PersistedRuleCheckResult(
			evaluatedRuleCheck = EvaluatedRuleCheck(
				effectiveRule = rule,
				status = status,
				actualValue = objectMapper.readTree("10"),
				expectedValue = objectMapper.readTree("20"),
				message = "battery is low",
			),
			ruleCheckResultId = 500,
		)
	}
}
