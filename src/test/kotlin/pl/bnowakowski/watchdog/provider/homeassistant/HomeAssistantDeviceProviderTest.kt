// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.homeassistant

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.ProviderFixStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class HomeAssistantDeviceProviderTest {
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T18:30:00Z"), ZoneOffset.UTC)
	private val client = RecordingHomeAssistantClient()
	private val provider = HomeAssistantDeviceProvider(
		properties = HomeAssistantProperties(enabled = true, token = "token"),
		client = client,
		objectMapper = objectMapper,
		clock = clock,
	)

	@Test
	fun `discovers entities with entity id identity and model metadata`() {
		client.states += HomeAssistantEntityState(
			entityId = "sensor.remote_battery",
			state = "74",
			attributes = objectMapper.readTree(
				"""{"friendly_name":"Remote Battery","device_class":"battery","unit_of_measurement":"%"}""",
			),
		)

		val discovered = provider.discoverDevices().single()

		assertEquals(ProviderType.HOME_ASSISTANT, discovered.providerType)
		assertEquals("sensor.remote_battery", discovered.providerDeviceId)
		assertEquals("Remote Battery", discovered.displayName)
		assertEquals("battery:%", discovered.modelKey)
		assertEquals(PowerSource.BATTERY, discovered.powerSource)
	}

	@Test
	fun `reads state and flattened attributes into provider snapshot`() {
		client.entityStates["light.desk"] = HomeAssistantEntityState(
			entityId = "light.desk",
			state = "on",
			attributes = objectMapper.readTree("""{"friendly_name":"Desk","battery_level":92,"color":{"mode":"rgb"}}"""),
			lastUpdated = "2026-06-30T18:25:00Z",
		)

		val snapshot = provider.readSnapshot(device("light.desk"))

		assertTrue(snapshot.available)
		assertEquals(92.0, snapshot.batteryLevel)
		assertEquals(Instant.parse("2026-06-30T18:25:00Z"), snapshot.lastSeenAt)
		assertEquals("on", snapshot.properties[DevicePropertyRef(ProviderType.HOME_ASSISTANT, "state")]?.asText())
		assertEquals(
			"rgb",
			snapshot.properties[DevicePropertyRef(ProviderType.HOME_ASSISTANT, "attributes.color.mode")]?.asText(),
		)
	}

	@Test
	fun `treats unavailable Home Assistant state as unavailable`() {
		client.entityStates["switch.fan"] = HomeAssistantEntityState(
			entityId = "switch.fan",
			state = "unavailable",
		)

		val snapshot = provider.readSnapshot(device("switch.fan"))

		assertFalse(snapshot.available)
	}

	@Test
	fun `calls safe light service for state fixes`() {
		val result = provider.applyDesiredState(
			device = device("light.desk"),
			property = DevicePropertyRef(ProviderType.HOME_ASSISTANT, "state"),
			desiredValue = objectMapper.readTree(""""off""""),
		)

		assertEquals(ProviderFixStatus.REQUESTED, result.status)
		assertEquals("light", client.serviceCalls.single().domain)
		assertEquals("turn_off", client.serviceCalls.single().service)
		assertEquals("light.desk", client.serviceCalls.single().payload.path("entity_id").asText())
	}

	@Test
	fun `skips unsupported Home Assistant service calls`() {
		val result = provider.applyDesiredState(
			device = device("sensor.temperature"),
			property = DevicePropertyRef(ProviderType.HOME_ASSISTANT, "state"),
			desiredValue = objectMapper.readTree(""""on""""),
		)

		assertEquals(ProviderFixStatus.SKIPPED, result.status)
		assertTrue(client.serviceCalls.isEmpty())
	}

	@Test
	fun `calls configured custom Home Assistant service for matching property`() {
		val customProvider = HomeAssistantDeviceProvider(
			properties = HomeAssistantProperties(
				enabled = true,
				token = "token",
				serviceCalls = listOf(
					HomeAssistantServiceCallMapping(
						propertyPath = "state",
						entityId = "climate.bedroom",
						desiredValue = "heat",
						domain = "climate",
						service = "set_hvac_mode",
					),
				),
			),
			client = client,
			objectMapper = objectMapper,
			clock = clock,
		)

		val result = customProvider.applyDesiredState(
			device = device("climate.bedroom"),
			property = DevicePropertyRef(ProviderType.HOME_ASSISTANT, "state"),
			desiredValue = objectMapper.readTree(""""heat""""),
		)

		assertEquals(ProviderFixStatus.REQUESTED, result.status)
		assertEquals("climate", client.serviceCalls.single().domain)
		assertEquals("set_hvac_mode", client.serviceCalls.single().service)
		assertEquals("climate.bedroom", client.serviceCalls.single().payload.path("entity_id").asText())
		assertEquals("heat", client.serviceCalls.single().payload.path("value").asText())
	}

	@Test
	fun `returns no data when provider is disabled or token is missing`() {
		val disabledProvider = HomeAssistantDeviceProvider(
			properties = HomeAssistantProperties(enabled = false, token = ""),
			client = client,
			objectMapper = objectMapper,
			clock = clock,
		)

		assertTrue(disabledProvider.discoverDevices().isEmpty())
		assertNull(disabledProvider.readSnapshot(device("light.desk")).lastSeenAt)
	}

	private fun device(entityId: String): Device =
		Device(
			providerType = ProviderType.HOME_ASSISTANT,
			providerDeviceId = entityId,
			friendlyName = entityId,
			displayName = entityId,
			modelKey = entityId.substringBefore('.'),
		)

	private class RecordingHomeAssistantClient : HomeAssistantClient {
		val states = mutableListOf<HomeAssistantEntityState>()
		val entityStates = mutableMapOf<String, HomeAssistantEntityState>()
		val serviceCalls = mutableListOf<HomeAssistantServiceCall>()

		override fun states(): List<HomeAssistantEntityState> =
			states

		override fun state(entityId: String): HomeAssistantEntityState? =
			entityStates[entityId]

		override fun callService(
			domain: String,
			service: String,
			payload: JsonNode,
		): JsonNode {
			serviceCalls += HomeAssistantServiceCall(domain, service, payload)
			return ObjectMapper().createArrayNode()
		}
	}
}
