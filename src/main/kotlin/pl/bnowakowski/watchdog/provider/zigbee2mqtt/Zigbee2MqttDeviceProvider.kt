// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import java.nio.charset.StandardCharsets
import java.time.Clock
import org.springframework.stereotype.Component
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.provider.DiscoveredDevice
import pl.bnowakowski.watchdog.provider.FixAttemptResult
import pl.bnowakowski.watchdog.provider.PropertyMetadata
import pl.bnowakowski.watchdog.provider.ProviderFixStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class Zigbee2MqttDeviceProvider(
	private val properties: Zigbee2MqttProperties,
	private val stateCache: Zigbee2MqttStateCache,
	private val mqttGateway: MqttGateway?,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) : DeviceProvider {
	override val providerType: ProviderType = ProviderType.ZIGBEE2MQTT

	override fun discoverDevices(): List<DiscoveredDevice> =
		stateCache.discoveredDevices()

	override fun readSnapshot(device: Device): DeviceSnapshot {
		val state = stateCache.deviceStateByIeeeAddress(device.providerDeviceId)
		return DeviceSnapshot(
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = device.providerDeviceId,
			observedAt = clock.instant(),
			available = state?.available ?: false,
			lastSeenAt = state?.lastReceivedAt,
			batteryLevel = state?.batteryLevel,
			payload = state?.payload ?: objectMapper.createObjectNode(),
			properties = state?.payload?.toPropertyMap().orEmpty(),
		)
	}

	override fun applyDesiredState(
		device: Device,
		property: DevicePropertyRef,
		desiredValue: JsonNode,
	): FixAttemptResult {
		val mqtt = mqttGateway
			?: return FixAttemptResult(
				status = ProviderFixStatus.SKIPPED,
				message = "MQTT is disabled",
			)
		val discoveredDevice = stateCache.discoveredDeviceByIeeeAddress(device.providerDeviceId)
			?: return FixAttemptResult(
				status = ProviderFixStatus.FAILED,
				message = "Device ${device.providerDeviceId} is not known from Zigbee2MQTT bridge/devices",
			)
		val payload = objectMapper.createObjectNode().also {
			it.set(property.propertyPath, desiredValue)
		}
		val topic = "${properties.normalizedBaseTopic()}/${discoveredDevice.friendlyName}/set"

		return runCatching {
			mqtt.publish(topic, objectMapper.writeValueAsBytes(payload))
			stateCache.recordPendingFix(
				friendlyName = discoveredDevice.friendlyName,
				propertyPath = property.propertyPath,
				desiredValue = desiredValue,
				requestedAt = clock.instant(),
			)
			FixAttemptResult(
				status = ProviderFixStatus.REQUESTED,
				message = "Published desired state to $topic",
				providerResponse = objectMapper.createObjectNode()
					.put("topic", topic)
					.put("payload", payload.toString()),
				confirmed = false,
			)
		}.getOrElse {
			FixAttemptResult(
				status = ProviderFixStatus.FAILED,
				message = it.message,
			)
		}
	}

	override fun supportedProperties(device: Device): List<PropertyMetadata> =
		stateCache.supportedProperties(device.providerDeviceId)

	override fun modelKey(device: DiscoveredDevice): String =
		device.modelKey

	fun bridgeHealth(): ZigbeeBridgeHealth =
		stateCache.bridgeHealth()

	private fun JsonNode.toPropertyMap(): Map<DevicePropertyRef, JsonNode> {
		if (!isObject) {
			return emptyMap()
		}
		return properties().asSequence().associate { (key, value) ->
			DevicePropertyRef(
				providerType = ProviderType.ZIGBEE2MQTT,
				propertyPath = key,
			) to value
		}
	}
}
