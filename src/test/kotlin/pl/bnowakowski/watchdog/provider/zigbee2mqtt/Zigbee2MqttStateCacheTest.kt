// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.provider.PropertyValueType
import tools.jackson.databind.ObjectMapper

class Zigbee2MqttStateCacheTest {
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T18:00:00Z"), ZoneOffset.UTC)
	private val cache = Zigbee2MqttStateCache(objectMapper, clock)

	@Test
	fun `discovers devices and exposed properties from bridge devices payload`() {
		cache.updateBridgeDevices(
			"""
			[
			  {
			    "ieee_address": "0x54ef4410005e77ba",
			    "network_address": "0x3D5E",
			    "friendly_name": "Bathroom - Switch - Top",
			    "power_source": "Mains",
			    "definition": {
			      "model": "TS0012",
			      "vendor": "Tuya",
			      "description": "Two gang switch",
			      "exposes": [
			        {"type": "enum", "name": "operation mode right", "property": "operation_mode_right", "endpoint": "right", "access": 7, "values": ["control_relay", "decoupled"]},
			        {"type": "binary", "name": "state right", "property": "state_right", "endpoint": "right", "access": 7}
			      ]
			    }
			  }
			]
			""".trimIndent().toByteArray(),
		)

		val discovered = cache.discoveredDevices().single()
		assertEquals(ProviderType.ZIGBEE2MQTT, discovered.providerType)
		assertEquals("0x54ef4410005e77ba", discovered.providerDeviceId)
		assertEquals("TS0012", discovered.modelKey)
		assertEquals("Tuya Two gang switch", discovered.modelName)
		assertEquals(PowerSource.MAINS, discovered.powerSource)

		val properties = cache.supportedProperties("0x54ef4410005e77ba")
		assertEquals(2, properties.size)
		assertEquals("operation_mode_right", properties.first().ref.propertyPath)
		assertEquals("right", properties.first().ref.endpoint)
		assertEquals(PropertyValueType.STRING, properties.first().valueType)
		assertTrue(properties.first().writable)
	}

	@Test
	fun `tracks bridge health and device state`() {
		cache.updateBridgeState("online".toByteArray())
		cache.updateBridgeDevices(
			"""
			[{"ieee_address":"0x1","friendly_name":"Bedroom Thermostat","definition":{"model":"TS0601"}}]
			""".trimIndent().toByteArray(),
		)
		cache.updateAvailability("Bedroom Thermostat", """{"state":"offline"}""".toByteArray())
		cache.updateDeviceState("Bedroom Thermostat", """{"battery":17,"local_temperature":21.5}""".toByteArray())

		val health = cache.bridgeHealth()
		assertEquals(ZigbeeBridgeState.ONLINE, health.state)
		assertTrue(health.healthy)
		assertEquals(Instant.parse("2026-06-30T18:00:00Z"), health.lastSeenAt)

		val state = cache.deviceStateByIeeeAddress("0x1")
		assertEquals(17.0, state?.batteryLevel)
		assertFalse(state?.available ?: true)
		assertEquals(Instant.parse("2026-06-30T18:00:00Z"), state?.lastReceivedAt)
	}

	@Test
	fun `confirms pending fix from later matching state update`() {
		cache.updateBridgeDevices(
			"""
			[{"ieee_address":"0x1","friendly_name":"Bedroom Switch","definition":{"model":"TS0012"}}]
			""".trimIndent().toByteArray(),
		)
		cache.recordPendingFix(
			friendlyName = "Bedroom Switch",
			propertyPath = "operation_mode_right",
			desiredValue = objectMapper.readTree(""""decoupled""""),
		)

		cache.updateDeviceState("Bedroom Switch", """{"operation_mode_right":"decoupled"}""".toByteArray())

		val confirmation = cache.fixConfirmation("Bedroom Switch", "operation_mode_right")
		assertNotNull(confirmation)
		assertTrue(confirmation.confirmed)
		assertEquals(Instant.parse("2026-06-30T18:00:00Z"), confirmation.confirmedAt)
	}
}
