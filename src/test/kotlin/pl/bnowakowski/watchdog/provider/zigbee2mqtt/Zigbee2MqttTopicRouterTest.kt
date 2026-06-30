// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import tools.jackson.databind.ObjectMapper

class Zigbee2MqttTopicRouterTest {
	@Test
	fun `subscribes to documented Zigbee2MQTT topics`() {
		val gateway = RecordingMqttGateway()

		Zigbee2MqttTopicRouter(
			mqttGateway = gateway,
			properties = Zigbee2MqttProperties(baseTopic = "zigbee2mqtt-2"),
			stateCache = mock(),
		).subscribe()

		assertEquals(
			listOf(
				"zigbee2mqtt-2/bridge/devices",
				"zigbee2mqtt-2/bridge/state",
				"zigbee2mqtt-2/+/availability",
				"zigbee2mqtt-2/+",
			),
			gateway.subscriptions.map { it.topicFilter },
		)
	}

	@Test
	fun `routes device state messages into cache`() {
		val gateway = RecordingMqttGateway()
		val cache = Zigbee2MqttStateCache(ObjectMapper())

		Zigbee2MqttTopicRouter(
			mqttGateway = gateway,
			properties = Zigbee2MqttProperties(baseTopic = "zigbee2mqtt-2"),
			stateCache = cache,
		).subscribe()

		gateway.emit("zigbee2mqtt-2/bridge/state", "online")
		gateway.emit(
			"zigbee2mqtt-2/bridge/devices",
			"""[{"ieee_address":"0xabc","friendly_name":"Kitchen Switch","definition":{"model":"TS0012"}}]""",
		)
		gateway.emit("zigbee2mqtt-2/Kitchen Switch/availability", """{"state":"online"}""")
		gateway.emit("zigbee2mqtt-2/Kitchen Switch", """{"battery":99,"state_right":"ON"}""")

		assertEquals(ZigbeeBridgeState.ONLINE, cache.bridgeHealth().state)
		assertNotNull(cache.deviceStateByIeeeAddress("0xabc"))
		assertEquals(99.0, cache.deviceStateByIeeeAddress("0xabc")?.batteryLevel)
	}

	private class RecordingMqttGateway : MqttGateway {
		val subscriptions = mutableListOf<Subscription>()

		override fun subscribe(topicFilter: String, handler: MqttMessageHandler) {
			subscriptions += Subscription(topicFilter, handler)
		}

		override fun publish(topic: String, payload: ByteArray) = Unit

		fun emit(topic: String, payload: String) {
			subscriptions.forEach { subscription ->
				if (matches(subscription.topicFilter, topic)) {
					subscription.handler.handle(topic, payload.toByteArray())
				}
			}
		}

		private fun matches(filter: String, topic: String): Boolean {
			val filterParts = filter.split('/')
			val topicParts = topic.split('/')
			if (filterParts.size != topicParts.size) {
				return false
			}
			return filterParts.zip(topicParts).all { (filterPart, topicPart) ->
				filterPart == "+" || filterPart == topicPart
			}
		}

		data class Subscription(
			val topicFilter: String,
			val handler: MqttMessageHandler,
		)
	}
}
