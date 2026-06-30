// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.persistence

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.bnowakowski.watchdog.checks.CheckRunQueries
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.domain.RuleType
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
	properties = [
		"watchdog.check.enabled=false",
		"watchdog.mqtt.enabled=false",
		"watchdog.home-assistant.enabled=false",
		"app.notifications.enabled=false",
		"logging.file.name=",
	],
)
@Testcontainers
class PersistenceIntegrationTest {
	@Autowired
	private lateinit var jdbc: NamedParameterJdbcTemplate

	@Autowired
	private lateinit var deviceRepository: DeviceRepository

	@Autowired
	private lateinit var deviceGroupRepository: DeviceGroupRepository

	@Autowired
	private lateinit var deviceGroupRuleRepository: DeviceGroupRuleRepository

	@Autowired
	private lateinit var checkRunQueries: CheckRunQueries

	@Autowired
	private lateinit var deviceGroupMembershipQueries: DeviceGroupMembershipQueries

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@BeforeEach
	fun cleanDatabase() {
		listOf(
			"fix_attempt",
			"rule_check_result",
			"device_check_result",
			"check_run",
			"device_parameter_history",
			"notification_event",
			"notification_channel",
			"device_group_rule",
			"device_group_membership",
			"device_group",
			"notification_preference",
			"device",
			"app_user",
		).forEach { table ->
			jdbc.update("DELETE FROM $table", emptyMap<String, Any?>())
		}
	}

	@Test
	fun `flyway applies all migrations to postgresql`() {
		val versions = jdbc.queryForList(
			"""
			SELECT version
			FROM flyway_schema_history
			WHERE success = true
			ORDER BY installed_rank
			""".trimIndent(),
			emptyMap<String, Any?>(),
			String::class.java,
		)

		assertTrue(versions.containsAll(listOf("1", "2", "3", "4", "5", "6", "7", "8")))
		assertEquals(8, versions.size)
	}

	@Test
	fun `repositories persist device group and rule json fields`() {
		val savedDevice = deviceRepository.save(
			Device(
				providerType = ProviderType.ZIGBEE2MQTT,
				providerDeviceId = "0x54ef4410005e77ba",
				ieeeAddress = "0x54ef4410005e77ba",
				friendlyName = "bedroom_switch",
				displayName = "Bedroom Switch",
				modelKey = "TS0012",
				modelName = "Two gang wall switch",
				powerSource = PowerSource.MAINS,
				providerMetadata = objectMapper.readTree("""{"friendly_name":"bedroom_switch"}"""),
				lastSeenAt = Instant.parse("2026-06-30T18:00:00Z"),
			),
		)
		val savedGroup = deviceGroupRepository.save(
			DeviceGroup(
				name = "Bedroom switches",
				providerType = ProviderType.ZIGBEE2MQTT,
				modelKey = "TS0012",
			),
		)
		val savedRule = deviceGroupRuleRepository.save(
			DeviceGroupRule(
				groupId = requireNotNull(savedGroup.id),
				providerType = ProviderType.ZIGBEE2MQTT,
				ruleType = RuleType.DESIRED_PROPERTY,
				propertyPath = "operation_mode_right",
				comparisonOperator = ComparisonOperator.EQUALS,
				desiredValue = objectMapper.readTree(""""decoupled""""),
			),
		)

		val loadedDevice = deviceRepository.findById(requireNotNull(savedDevice.id)).orElseThrow()
		val loadedRule = deviceGroupRuleRepository.findById(requireNotNull(savedRule.id)).orElseThrow()

		assertEquals("bedroom_switch", loadedDevice.providerMetadata["friendly_name"].asText())
		assertTrue(deviceRepository.existsByProviderTypeAndIeeeAddress(ProviderType.ZIGBEE2MQTT, "0x54ef4410005e77ba"))
		assertEquals("decoupled", assertNotNull(loadedRule.desiredValue).asText())
	}

	@Test
	fun `device group membership queries lock unlocked group model`() {
		val savedGroup = deviceGroupRepository.save(DeviceGroup(name = "Bedroom switches"))

		val updated = deviceGroupMembershipQueries.lockModel(
			groupId = requireNotNull(savedGroup.id),
			providerType = ProviderType.ZIGBEE2MQTT,
			modelKey = "WS-EUK04",
			updatedAt = Instant.parse("2026-06-30T20:30:00Z"),
		)

		assertEquals(1, updated)
		val lock = deviceGroupMembershipQueries.findModelLock(requireNotNull(savedGroup.id))
		assertEquals(ProviderType.ZIGBEE2MQTT, lock?.providerType)
		assertEquals("WS-EUK04", lock?.modelKey)
	}

	@Test
	fun `check run queries persist completed run with device and rule results`() {
		val device = deviceRepository.save(
			Device(
				providerType = ProviderType.HOME_ASSISTANT,
				providerDeviceId = "light.bedroom_lamp",
				friendlyName = "light.bedroom_lamp",
				displayName = "Bedroom Lamp",
				modelKey = "home-assistant:light",
			),
		)
		val group = deviceGroupRepository.save(
			DeviceGroup(
				name = "HA lights",
				providerType = ProviderType.HOME_ASSISTANT,
				modelKey = "home-assistant:light",
			),
		)
		val rule = deviceGroupRuleRepository.save(
			DeviceGroupRule(
				groupId = requireNotNull(group.id),
				providerType = ProviderType.HOME_ASSISTANT,
				ruleType = RuleType.DESIRED_PROPERTY,
				propertyPath = "state",
				comparisonOperator = ComparisonOperator.EQUALS,
				desiredValue = objectMapper.readTree(""""on""""),
			),
		)
		val startedAt = Instant.parse("2026-06-30T19:00:00Z")
		val checkRunId = checkRunQueries.createRun(CheckRunTriggerType.SCHEDULED, startedAt)
		val deviceResultId = checkRunQueries.insertDeviceResult(
			checkRunId = checkRunId,
			deviceId = requireNotNull(device.id),
			status = DeviceCheckStatus.DEGRADED,
			snapshot = objectMapper.readTree("""{"state":"off"}"""),
			checkedAt = startedAt.plusSeconds(2),
		)
		checkRunQueries.insertRuleResult(
			deviceCheckResultId = deviceResultId,
			ruleId = requireNotNull(rule.id),
			status = RuleCheckStatus.MISMATCH,
			actualValue = objectMapper.readTree(""""off""""),
			expectedValue = objectMapper.readTree(""""on""""),
			message = "Expected state to equal on",
		)
		checkRunQueries.completeRun(
			checkRunId = checkRunId,
			status = CheckRunStatus.COMPLETED,
			finishedAt = startedAt.plusSeconds(5),
			summary = objectMapper.readTree("""{"devices":1,"degraded":1}"""),
		)

		val persisted = jdbc.queryForMap(
			"""
			SELECT cr.status AS run_status,
				cr.summary ->> 'degraded' AS degraded_count,
				dcr.status AS device_status,
				dcr.snapshot ->> 'state' AS snapshot_state,
				rcr.status AS rule_status,
				rcr.actual_value #>> '{}' AS actual_value,
				rcr.expected_value #>> '{}' AS expected_value
			FROM check_run cr
			JOIN device_check_result dcr ON dcr.check_run_id = cr.id
			JOIN rule_check_result rcr ON rcr.device_check_result_id = dcr.id
			WHERE cr.id = :checkRunId
			""".trimIndent(),
			mapOf("checkRunId" to checkRunId),
		)

		assertEquals(CheckRunStatus.COMPLETED.name, persisted["run_status"])
		assertEquals("1", persisted["degraded_count"])
		assertEquals(DeviceCheckStatus.DEGRADED.name, persisted["device_status"])
		assertEquals("off", persisted["snapshot_state"])
		assertEquals(RuleCheckStatus.MISMATCH.name, persisted["rule_status"])
		assertEquals("off", persisted["actual_value"])
		assertEquals("on", persisted["expected_value"])
	}

	companion object {
		@Container
		@JvmStatic
		private val postgres = PostgreSQLContainer("postgres:16-alpine")

		@JvmStatic
		@DynamicPropertySource
		fun databaseProperties(registry: DynamicPropertyRegistry) {
			registry.add("spring.datasource.url", postgres::getJdbcUrl)
			registry.add("spring.datasource.username", postgres::getUsername)
			registry.add("spring.datasource.password", postgres::getPassword)
		}
	}
}
