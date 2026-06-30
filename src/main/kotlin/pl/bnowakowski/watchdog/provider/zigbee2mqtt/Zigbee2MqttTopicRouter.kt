// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "watchdog.mqtt", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class Zigbee2MqttTopicRouter(
	private val mqttGateway: MqttGateway,
	private val properties: Zigbee2MqttProperties,
	private val stateCache: Zigbee2MqttStateCache,
) {
	private val logger = LoggerFactory.getLogger(javaClass)
	private val baseTopic = properties.normalizedBaseTopic()

	@PostConstruct
	fun subscribe() {
		mqttGateway.subscribe("$baseTopic/bridge/devices") { _, payload ->
			runCatching { stateCache.updateBridgeDevices(payload) }
				.onFailure { logger.warn("Could not parse Zigbee2MQTT bridge devices: {}", it.message) }
		}
		mqttGateway.subscribe("$baseTopic/bridge/state") { _, payload ->
			stateCache.updateBridgeState(payload)
		}
		mqttGateway.subscribe("$baseTopic/+/availability") { topic, payload ->
			topic.friendlyNameFromAvailabilityTopic()?.let {
				stateCache.updateAvailability(it, payload)
			}
		}
		mqttGateway.subscribe("$baseTopic/+") { topic, payload ->
			topic.friendlyNameFromDeviceStateTopic()?.let {
				runCatching { stateCache.updateDeviceState(it, payload) }
					.onFailure { error -> logger.warn("Could not parse Zigbee2MQTT state for {}: {}", it, error.message) }
			}
		}
	}

	private fun String.friendlyNameFromAvailabilityTopic(): String? {
		val prefix = "$baseTopic/"
		if (!startsWith(prefix) || !endsWith("/availability")) {
			return null
		}
		return removePrefix(prefix).removeSuffix("/availability").takeIf { it.isNotBlank() && it != "bridge" }
	}

	private fun String.friendlyNameFromDeviceStateTopic(): String? {
		val prefix = "$baseTopic/"
		if (!startsWith(prefix)) {
			return null
		}
		val tail = removePrefix(prefix)
		if (tail == "bridge" || tail.startsWith("bridge/") || tail.contains('/')) {
			return null
		}
		return tail.takeIf { it.isNotBlank() }
	}
}
