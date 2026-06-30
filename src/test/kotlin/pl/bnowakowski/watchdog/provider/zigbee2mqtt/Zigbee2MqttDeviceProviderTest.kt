// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.ProviderFixStatus
import tools.jackson.databind.ObjectMapper

class Zigbee2MqttDeviceProviderTest {
	private val objectMapper = ObjectMapper()
	private val clock = Clock.fixed(Instant.parse("2026-06-30T18:30:00Z"), ZoneOffset.UTC)
	private val cache = Zigbee2MqttStateCache(objectMapper, clock)
	private val mqttGateway = RecordingMqttGateway()
	private val provider = Zigbee2MqttDeviceProvider(
		properties = Zigbee2MqttProperties(baseTopic = "zigbee2mqtt-2"),
		stateCache = cache,
		mqttGateway = mqttGateway,
		objectMapper = objectMapper,
		clock = clock,
	)

	@Test
	fun `reads provider-neutral snapshot from cached Zigbee state`() {
		cache.updateBridgeDevices(
			"""[{"ieee_address":"0xabc","friendly_name":"Bedroom Switch","definition":{"model":"TS0012"}}]"""
				.toByteArray(),
		)
		cache.updateAvailability("Bedroom Switch", "online".toByteArray())
		cache.updateDeviceState("Bedroom Switch", """{"battery":88,"state_right":"ON"}""".toByteArray())

		val snapshot = provider.readSnapshot(device())

		assertEquals(ProviderType.ZIGBEE2MQTT, snapshot.providerType)
		assertTrue(snapshot.available)
		assertEquals(88.0, snapshot.batteryLevel)
		assertEquals("ON", snapshot.properties[DevicePropertyRef(ProviderType.ZIGBEE2MQTT, "state_right")]?.asText())
	}

	@Test
	fun `publishes desired state to friendly name set topic`() {
		cache.updateBridgeDevices(
			"""[{"ieee_address":"0xabc","friendly_name":"Bedroom Switch","definition":{"model":"TS0012"}}]"""
				.toByteArray(),
		)

		val result = provider.applyDesiredState(
			device = device(),
			property = DevicePropertyRef(ProviderType.ZIGBEE2MQTT, "operation_mode_right", endpoint = "right"),
			desiredValue = objectMapper.readTree(""""decoupled""""),
		)

		assertEquals(ProviderFixStatus.REQUESTED, result.status)
		assertFalse(result.confirmed)
		assertEquals("zigbee2mqtt-2/Bedroom Switch/set", mqttGateway.published.single().topic)
		assertEquals("""{"operation_mode_right":"decoupled"}""", mqttGateway.published.single().payload.decodeToString())
		assertFalse(cache.fixConfirmation("Bedroom Switch", "operation_mode_right")?.confirmed ?: true)
	}

	private fun device(): Device =
		Device(
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "0xabc",
			ieeeAddress = "0xabc",
			friendlyName = "Bedroom Switch",
			displayName = "Bedroom Switch",
			modelKey = "TS0012",
		)

	private class RecordingMqttGateway : MqttGateway {
		val published = mutableListOf<PublishedMessage>()

		override fun subscribe(topicFilter: String, handler: MqttMessageHandler) = Unit

		override fun publish(topic: String, payload: ByteArray) {
			published += PublishedMessage(topic, payload)
		}
	}

	private data class PublishedMessage(
		val topic: String,
		val payload: ByteArray,
	)
}
