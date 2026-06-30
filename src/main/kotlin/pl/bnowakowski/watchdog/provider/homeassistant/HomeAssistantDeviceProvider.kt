// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.homeassistant

import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Component
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.provider.DevicePropertyRef
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.provider.DiscoveredDevice
import pl.bnowakowski.watchdog.provider.FixAttemptResult
import pl.bnowakowski.watchdog.provider.PropertyMetadata
import pl.bnowakowski.watchdog.provider.PropertyValueType
import pl.bnowakowski.watchdog.provider.ProviderFixStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Component
class HomeAssistantDeviceProvider(
	private val properties: HomeAssistantProperties,
	private val client: HomeAssistantClient?,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) : DeviceProvider {
	override val providerType: ProviderType = ProviderType.HOME_ASSISTANT

	override fun discoverDevices(): List<DiscoveredDevice> {
		val homeAssistant = enabledClient()
			?: return emptyList()

		return homeAssistant.states().map { state ->
			val friendlyName = state.attributes.path("friendly_name").asText(state.entityId)
			DiscoveredDevice(
				providerType = ProviderType.HOME_ASSISTANT,
				providerDeviceId = state.entityId,
				friendlyName = friendlyName,
				displayName = friendlyName,
				modelKey = state.modelKey(),
				modelName = state.attributes.optionalText("device_class")
					?: state.attributes.optionalText("manufacturer")
					?: state.domain(),
				powerSource = state.powerSource(),
				metadata = objectMapper.createObjectNode()
					.put("domain", state.domain())
					.set("attributes", state.attributes.deepCopy()),
			)
		}
	}

	override fun readSnapshot(device: Device): DeviceSnapshot {
		val entityState = enabledClient()?.state(device.providerDeviceId)
		val observedAt = clock.instant()

		return DeviceSnapshot(
			providerType = ProviderType.HOME_ASSISTANT,
			providerDeviceId = device.providerDeviceId,
			observedAt = observedAt,
			available = entityState?.state != null && entityState.state != "unavailable" && entityState.state != "unknown",
			lastSeenAt = entityState?.lastUpdated?.toInstantOrNull(),
			batteryLevel = entityState?.batteryLevel(),
			payload = entityState?.toPayload() ?: objectMapper.createObjectNode(),
			properties = entityState?.toPropertyMap().orEmpty(),
		)
	}

	override fun applyDesiredState(
		device: Device,
		property: DevicePropertyRef,
		desiredValue: JsonNode,
	): FixAttemptResult {
		val homeAssistant = enabledClient()
			?: return FixAttemptResult(
				status = ProviderFixStatus.SKIPPED,
				message = "Home Assistant provider is disabled",
			)
		val serviceCall = safeServiceCall(device.providerDeviceId, property, desiredValue)
			?: return FixAttemptResult(
				status = ProviderFixStatus.SKIPPED,
				message = "No safe Home Assistant service call is configured for ${property.propertyPath}",
			)

		return runCatching {
			val response = homeAssistant.callService(serviceCall.domain, serviceCall.service, serviceCall.payload)
			FixAttemptResult(
				status = ProviderFixStatus.REQUESTED,
				message = "Called Home Assistant service ${serviceCall.domain}.${serviceCall.service}",
				providerResponse = response,
				confirmed = false,
			)
		}.getOrElse {
			FixAttemptResult(
				status = ProviderFixStatus.FAILED,
				message = it.message,
			)
		}
	}

	override fun supportedProperties(device: Device): List<PropertyMetadata> {
		val entityState = enabledClient()?.state(device.providerDeviceId)
			?: return emptyList()

		return entityState.toPropertyMap().map { (ref, value) ->
			PropertyMetadata(
				ref = ref,
				displayName = ref.propertyPath,
				valueType = value.propertyValueType(),
				readable = true,
				writable = ref.propertyPath == "state" && entityState.supportsStateServiceCalls(),
				allowedValues = if (ref.propertyPath == "state" && entityState.supportsStateServiceCalls()) {
					listOf("on", "off")
				} else {
					emptyList()
				},
			)
		}.sortedBy { it.ref.propertyPath }
	}

	override fun modelKey(device: DiscoveredDevice): String =
		device.modelKey

	private fun enabledClient(): HomeAssistantClient? =
		client?.takeIf { properties.enabled && properties.token.isNotBlank() }

	private fun safeServiceCall(
		entityId: String,
		property: DevicePropertyRef,
		desiredValue: JsonNode,
	): HomeAssistantServiceCall? {
		configuredServiceCall(entityId, property, desiredValue)?.let { return it }
		if (property.providerType != ProviderType.HOME_ASSISTANT || property.propertyPath != "state") {
			return null
		}
		val domain = entityId.substringBefore('.', missingDelimiterValue = "")
		if (domain !in setOf("light", "switch")) {
			return null
		}
		val desiredState = desiredValue.asText("").lowercase()
		val service = when (desiredState) {
			"on" -> "turn_on"
			"off" -> "turn_off"
			else -> return null
		}
		val payload = objectMapper.createObjectNode()
			.put("entity_id", entityId)

		return HomeAssistantServiceCall(domain, service, payload)
	}

	private fun configuredServiceCall(
		entityId: String,
		property: DevicePropertyRef,
		desiredValue: JsonNode,
	): HomeAssistantServiceCall? {
		if (property.providerType != ProviderType.HOME_ASSISTANT) {
			return null
		}
		val desiredText = desiredValue.asText("")
		val mapping = properties.serviceCalls.firstOrNull {
			it.propertyPath == property.propertyPath &&
				(it.entityId == null || it.entityId == entityId) &&
				(it.desiredValue == null || it.desiredValue == desiredText)
		} ?: return null
		val payload = objectMapper.createObjectNode()
			.put("entity_id", entityId)
			.set("value", desiredValue)
		return HomeAssistantServiceCall(mapping.domain, mapping.service, payload)
	}

	private fun HomeAssistantEntityState.toPayload(): ObjectNode =
		objectMapper.createObjectNode()
			.put("entity_id", entityId)
			.put("state", state)
			.set("attributes", attributes.deepCopy())

	private fun HomeAssistantEntityState.toPropertyMap(): Map<DevicePropertyRef, JsonNode> =
		buildMap {
			put(DevicePropertyRef(ProviderType.HOME_ASSISTANT, "state"), objectMapper.valueToTree(state))
			attributes.flatten("attributes").forEach { (path, value) ->
				put(DevicePropertyRef(ProviderType.HOME_ASSISTANT, path), value)
			}
		}

	private fun JsonNode.flatten(prefix: String): Map<String, JsonNode> =
		when {
			isObject -> buildMap {
				properties().forEach { (key, value) ->
					putAll(value.flatten("$prefix.$key"))
				}
			}
			else -> mapOf(prefix to this)
		}

	private fun HomeAssistantEntityState.modelKey(): String =
		listOfNotNull(
			attributes.optionalText("model"),
			attributes.optionalText("device_class"),
			attributes.optionalText("unit_of_measurement"),
		).joinToString(":")
			.ifBlank { domain() }

	private fun HomeAssistantEntityState.powerSource(): PowerSource =
		when {
			batteryLevel() != null -> PowerSource.BATTERY
			attributes.optionalText("device_class") == "battery" -> PowerSource.BATTERY
			domain() in setOf("light", "switch", "climate") -> PowerSource.MAINS
			else -> PowerSource.UNKNOWN
		}

	private fun HomeAssistantEntityState.batteryLevel(): Double? =
		attributes.optionalNumber("battery_level")
			?: attributes.optionalNumber("battery")
			?: state.toDoubleOrNull().takeIf { attributes.optionalText("device_class") == "battery" }

	private fun HomeAssistantEntityState.supportsStateServiceCalls(): Boolean =
		domain() in setOf("light", "switch")

	private fun HomeAssistantEntityState.domain(): String =
		entityId.substringBefore('.', missingDelimiterValue = entityId)

	private fun JsonNode.optionalText(field: String): String? =
		path(field).asText("").trim().takeIf { it.isNotBlank() }

	private fun JsonNode.optionalNumber(field: String): Double? =
		path(field).takeIf { it.isNumber }?.asDouble()

	private fun String.toInstantOrNull(): Instant? =
		runCatching { Instant.parse(this) }.getOrNull()

	private fun JsonNode.propertyValueType(): PropertyValueType =
		when {
			isTextual -> PropertyValueType.STRING
			isNumber -> PropertyValueType.NUMBER
			isBoolean -> PropertyValueType.BOOLEAN
			isObject -> PropertyValueType.OBJECT
			isArray -> PropertyValueType.ARRAY
			isNull -> PropertyValueType.NULL
			else -> PropertyValueType.UNKNOWN
		}
}
