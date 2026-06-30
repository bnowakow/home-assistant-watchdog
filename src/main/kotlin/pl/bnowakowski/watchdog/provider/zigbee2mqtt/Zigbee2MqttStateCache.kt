// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.DiscoveredDevice
import pl.bnowakowski.watchdog.provider.PropertyMetadata
import pl.bnowakowski.watchdog.provider.PropertyValueType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory

@Component
class Zigbee2MqttStateCache(
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val discoveredByIeeeAddress = ConcurrentHashMap<String, DiscoveredZigbeeDevice>()
	private val discoveredByFriendlyName = ConcurrentHashMap<String, DiscoveredZigbeeDevice>()
	private val statesByFriendlyName = ConcurrentHashMap<String, ZigbeeDeviceState>()
	@Volatile
	private var bridgeState: ZigbeeBridgeState = ZigbeeBridgeState.UNKNOWN
	@Volatile
	private var bridgeLastSeenAt: Instant? = null

	fun updateBridgeDevices(payload: ByteArray) {
		val root = objectMapper.readTree(payload)
		val devices = when {
			root.isArray -> root
			root.has("devices") && root["devices"].isArray -> root["devices"]
			else -> JsonNodeFactory.instance.arrayNode()
		}

		devices.forEach { node ->
			val ieeeAddress = node.text("ieee_address") ?: return@forEach
			val friendlyName = node.text("friendly_name") ?: ieeeAddress
			val definition = node["definition"]
			val modelKey = definition?.text("model")
				?: definition?.text("model_id")
				?: node.text("model_id")
				?: node.text("model")
				?: "unknown"
			val modelName = listOfNotNull(
				definition?.text("vendor"),
				definition?.text("description"),
			).joinToString(" ").ifBlank { null }
			val device = DiscoveredZigbeeDevice(
				ieeeAddress = ieeeAddress,
				networkAddress = node.text("network_address"),
				friendlyName = friendlyName,
				displayName = node.text("friendly_name") ?: friendlyName,
				modelKey = modelKey,
				modelName = modelName,
				powerSource = parsePowerSource(node.text("power_source")),
				metadata = node,
				properties = parseExposes(definition?.get("exposes") ?: node["exposes"]),
			)
			discoveredByIeeeAddress[ieeeAddress] = device
			discoveredByFriendlyName[friendlyName] = device
		}
	}

	fun updateBridgeState(payload: ByteArray) {
		bridgeState = parseBridgeState(payload.decodeToString())
		bridgeLastSeenAt = clock.instant()
	}

	fun updateAvailability(friendlyName: String, payload: ByteArray) {
		val available = parseAvailability(payload)
		val now = clock.instant()
		statesByFriendlyName.merge(
			friendlyName,
			ZigbeeDeviceState(
				friendlyName = friendlyName,
				available = available,
				lastReceivedAt = now,
			),
		) { previous, update ->
			previous.copy(
				available = update.available,
				lastReceivedAt = update.lastReceivedAt,
			)
		}
	}

	fun updateDeviceState(friendlyName: String, payload: ByteArray) {
		val root = objectMapper.readTree(payload)
		val now = clock.instant()
		statesByFriendlyName.merge(
			friendlyName,
			ZigbeeDeviceState(
				friendlyName = friendlyName,
				payload = root,
				lastReceivedAt = now,
				batteryLevel = root.numeric("battery"),
				available = statesByFriendlyName[friendlyName]?.available ?: true,
			),
		) { previous, update ->
			previous.copy(
				payload = update.payload,
				lastReceivedAt = update.lastReceivedAt,
				batteryLevel = update.batteryLevel ?: previous.batteryLevel,
			)
		}
	}

	fun discoveredDevices(): List<DiscoveredDevice> =
		discoveredByIeeeAddress.values
			.sortedBy { it.friendlyName }
			.map { it.toDiscoveredDevice() }

	fun discoveredDeviceByIeeeAddress(ieeeAddress: String): DiscoveredZigbeeDevice? =
		discoveredByIeeeAddress[ieeeAddress]

	fun deviceStateByIeeeAddress(ieeeAddress: String): ZigbeeDeviceState? =
		discoveredByIeeeAddress[ieeeAddress]
			?.friendlyName
			?.let(statesByFriendlyName::get)

	fun supportedProperties(ieeeAddress: String): List<PropertyMetadata> =
		discoveredByIeeeAddress[ieeeAddress]?.properties.orEmpty()

	fun bridgeHealth(): ZigbeeBridgeHealth =
		ZigbeeBridgeHealth(
			state = bridgeState,
			lastSeenAt = bridgeLastSeenAt,
			healthy = bridgeState == ZigbeeBridgeState.ONLINE,
		)

	private fun parseExposes(exposes: JsonNode?): List<PropertyMetadata> {
		if (exposes == null || !exposes.isArray) {
			return emptyList()
		}

		val metadata = mutableListOf<PropertyMetadata>()
		exposes.forEach { expose ->
			collectExposeMetadata(expose, metadata)
		}
		return metadata.distinctBy { it.ref }
	}

	private fun collectExposeMetadata(expose: JsonNode, metadata: MutableList<PropertyMetadata>) {
		val property = expose.text("property")
		if (property != null) {
			metadata += PropertyMetadata(
				ref = DevicePropertyRef(
					providerType = ProviderType.ZIGBEE2MQTT,
					propertyPath = property,
					endpoint = expose.text("endpoint"),
					capability = expose.text("name") ?: expose.text("type"),
				),
				displayName = expose.text("name") ?: property,
				valueType = expose.valueType(),
				readable = expose["access"]?.intValue()?.let { it and 1 == 1 } ?: true,
				writable = expose["access"]?.intValue()?.let { it and 2 == 2 } ?: false,
				unit = expose.text("unit"),
				allowedValues = expose["values"]?.mapNotNull { it.textValue() }.orEmpty(),
				metadata = expose,
			)
		}

		expose["features"]?.takeIf { it.isArray }?.forEach {
			collectExposeMetadata(it, metadata)
		}
	}

	private fun parsePowerSource(value: String?): PowerSource =
		when (value?.lowercase()) {
			"battery" -> PowerSource.BATTERY
			"mains", "ac", "dc" -> PowerSource.MAINS
			else -> PowerSource.UNKNOWN
		}

	private fun parseBridgeState(payload: String): ZigbeeBridgeState =
		when (payload.trim().trim('"').lowercase()) {
			"online" -> ZigbeeBridgeState.ONLINE
			"offline" -> ZigbeeBridgeState.OFFLINE
			else -> ZigbeeBridgeState.UNKNOWN
		}

	private fun parseAvailability(payload: ByteArray): Boolean {
		val raw = payload.decodeToString().trim()
		val parsed = runCatching { objectMapper.readTree(payload) }.getOrNull()
		val value = parsed?.text("state") ?: parsed?.text("availability") ?: raw.trim('"')
		return value.equals("online", ignoreCase = true) || value.equals("available", ignoreCase = true)
	}
}

data class DiscoveredZigbeeDevice(
	val ieeeAddress: String,
	val networkAddress: String?,
	val friendlyName: String,
	val displayName: String,
	val modelKey: String,
	val modelName: String?,
	val powerSource: PowerSource,
	val metadata: JsonNode,
	val properties: List<PropertyMetadata>,
) {
	fun toDiscoveredDevice(): DiscoveredDevice =
		DiscoveredDevice(
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = ieeeAddress,
			ieeeAddress = ieeeAddress,
			networkAddress = networkAddress,
			friendlyName = friendlyName,
			displayName = displayName,
			modelKey = modelKey,
			modelName = modelName,
			powerSource = powerSource,
			metadata = metadata,
		)
}

data class ZigbeeDeviceState(
	val friendlyName: String,
	val payload: JsonNode = JsonNodeFactory.instance.objectNode(),
	val available: Boolean? = null,
	val lastReceivedAt: Instant,
	val batteryLevel: Double? = null,
)

data class ZigbeeBridgeHealth(
	val state: ZigbeeBridgeState,
	val lastSeenAt: Instant?,
	val healthy: Boolean,
)

enum class ZigbeeBridgeState {
	ONLINE,
	OFFLINE,
	UNKNOWN,
}

private fun JsonNode.text(field: String): String? =
	get(field)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

private fun JsonNode.numeric(field: String): Double? =
	get(field)?.takeIf { it.isNumber }?.doubleValue()

private fun JsonNode.valueType(): PropertyValueType =
	when (text("type")?.lowercase()) {
		"binary", "boolean" -> PropertyValueType.BOOLEAN
		"numeric", "number" -> PropertyValueType.NUMBER
		"enum", "text", "string" -> PropertyValueType.STRING
		"composite" -> PropertyValueType.OBJECT
		else -> PropertyValueType.UNKNOWN
	}
