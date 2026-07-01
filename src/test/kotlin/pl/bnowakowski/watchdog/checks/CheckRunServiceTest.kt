// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionOperations
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.fixes.AutoFixService
import pl.bnowakowski.watchdog.history.ParameterHistoryWriter
import pl.bnowakowski.watchdog.notifications.NotificationService
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.rules.EffectiveRule
import pl.bnowakowski.watchdog.rules.EffectiveRulePropertyKey
import pl.bnowakowski.watchdog.rules.EffectiveRuleResolver
import pl.bnowakowski.watchdog.rules.EffectiveRuleStatus
import pl.bnowakowski.watchdog.rules.EffectiveRulesView
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class CheckRunServiceTest {
	private val queries: CheckRunQueries = mock()
	private val resolver: EffectiveRuleResolver = mock()
	private val evaluator: CheckEvaluator = mock()
	private val parameterHistoryWriter: ParameterHistoryWriter = mock()
	private val autoFixService: AutoFixService = mock()
	private val notificationService: NotificationService = mock()
	private val transactionOperations = TransactionOperations.withoutTransaction()
	private val properties = CheckProperties(runTimeout = Duration.ofSeconds(5))
	private val objectMapper = ObjectMapper()
	private val progressTracker = CheckRunProgressTracker()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T19:00:00Z"), ZoneOffset.UTC)
	private val service = CheckRunService(
		queries,
		resolver,
		evaluator,
		parameterHistoryWriter,
		autoFixService,
		notificationService,
		transactionOperations,
		properties,
		objectMapper,
		progressTracker,
		clock,
	)

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
		verify(notificationService).notifyRuleResult(any())
		verify(autoFixService).maybeFix(any())
	}

	@Test
	fun `manual check records skipped result without evaluating skipped devices`() {
		val device = device().copy(skipChecks = true)
		whenever(queries.createRun(CheckRunTriggerType.MANUAL, clock.instant())).thenReturn(42)
		whenever(queries.loadEnabledDevices()).thenReturn(listOf(device))
		whenever(queries.insertDeviceResult(eq(42), eq(1), eq(DeviceCheckStatus.SKIPPED), any(), eq(clock.instant())))
			.thenReturn(100)

		val report = service.runManualCheck()

		assertEquals(DeviceCheckStatus.SKIPPED, report.deviceResults.single().status)
		assertEquals("Device checks are skipped", report.deviceResults.single().message)
		verify(resolver, never()).resolveForDevice(1)
		verify(evaluator, never()).evaluate(any())
		verify(queries).insertDeviceResult(eq(42), eq(1), eq(DeviceCheckStatus.SKIPPED), any(), eq(clock.instant()))
		verify(queries, never()).insertRuleResult(any(), any(), any(), any(), any(), any())
		verify(parameterHistoryWriter).recordCheck(42, report.deviceResults.single())
	}

	@Test
	fun `rejects overlapping check run`() {
		val device = device()
		val runStarted = CountDownLatch(1)
		val releaseRun = CountDownLatch(1)
		whenever(queries.createRun(CheckRunTriggerType.MANUAL, clock.instant())).thenReturn(42)
		whenever(queries.loadEnabledDevices()).thenReturn(listOf(device))
		whenever(resolver.resolveForDevice(1)).thenAnswer {
			runStarted.countDown()
			assertTrue(releaseRun.await(2, TimeUnit.SECONDS))
			null
		}

		val thread = Thread { service.runManualCheck() }
		thread.start()
		assertTrue(runStarted.await(2, TimeUnit.SECONDS))

		assertFailsWith<CheckRunAlreadyRunningException> {
			service.runManualCheck()
		}

		releaseRun.countDown()
		thread.join(2_000)
	}

	@Test
	fun `marks run timed out when timeout expires`() {
		val timeoutService = CheckRunService(
			queries,
			resolver,
			evaluator,
			parameterHistoryWriter,
			autoFixService,
			notificationService,
			transactionOperations,
			CheckProperties(runTimeout = Duration.ofMillis(10)),
			objectMapper,
			CheckRunProgressTracker(),
			clock,
		)
		val device = device()
		whenever(queries.createRun(CheckRunTriggerType.MANUAL, clock.instant())).thenReturn(42)
		whenever(queries.loadEnabledDevices()).thenReturn(listOf(device))
		whenever(resolver.resolveForDevice(1)).thenAnswer {
			Thread.sleep(250)
			null
		}

		val error = assertFailsWith<CheckRunTimedOutException> {
			timeoutService.runManualCheck()
		}

		assertEquals(
			"Check run exceeded the configured run timeout of 10 milliseconds (PT0.01S). " +
				"The run was stopped and marked as timed out.",
			error.message,
		)
		val summary = argumentCaptor<JsonNode>()
		verify(queries).completeRun(eq(42), eq(CheckRunStatus.TIMED_OUT), eq(clock.instant()), summary.capture())
		assertEquals(
			objectMapper.createObjectNode()
				.put(
					"error",
					"Check run exceeded the configured run timeout of 10 milliseconds (PT0.01S). " +
						"The run was stopped and marked as timed out.",
				)
				.put("timeout", "PT0.01S"),
			summary.firstValue,
		)
	}

	@Test
	fun `startup recovery marks stale running runs stale`() {
		val recovery = CheckRunStartupRecovery(queries, transactionOperations, objectMapper, clock)
		whenever(queries.markRunningRunsStale(eq(clock.instant()), any())).thenReturn(1)

		recovery.markStaleRunningRuns()

		verify(queries).markRunningRunsStale(eq(clock.instant()), any())
	}

	@Test
	fun `publishes progress while run is active and clears it afterwards`() {
		val device = device()
		val view = EffectiveRulesView(device, listOf(rule(device)))
		val evaluated = EvaluatedDeviceCheck(
			device = device,
			status = DeviceCheckStatus.HEALTHY,
			snapshot = null,
			checkedAt = clock.instant(),
			ruleResults = emptyList(),
		)
		val evaluationCompleted = CountDownLatch(1)
		val releaseRun = CountDownLatch(1)
		whenever(queries.createRun(CheckRunTriggerType.MANUAL, clock.instant())).thenReturn(42)
		whenever(queries.loadEnabledDevices()).thenReturn(listOf(device))
		whenever(resolver.resolveForDevice(1)).thenReturn(view)
		whenever(evaluator.evaluate(view)).thenAnswer {
			evaluated
		}
		whenever(queries.insertDeviceResult(eq(42), eq(1), eq(DeviceCheckStatus.HEALTHY), any(), eq(clock.instant())))
			.thenAnswer {
				evaluationCompleted.countDown()
				assertTrue(releaseRun.await(2, TimeUnit.SECONDS))
				100
			}

		val thread = Thread { service.runManualCheck() }
		thread.start()
		assertTrue(evaluationCompleted.await(2, TimeUnit.SECONDS))

		val progress = progressTracker.snapshot()
		assertEquals(42, progress?.checkRunId)
		assertEquals(1, progress?.totalDevices)
		assertEquals(1, progress?.completedDevices)
		assertEquals("Bedroom Switch", progress?.currentDeviceName)

		releaseRun.countDown()
		thread.join(2_000)
		assertEquals(null, progressTracker.snapshot())
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
