// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider

import java.time.Instant
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

data class DiscoveredDevice(
	val providerType: ProviderType,
	val providerDeviceId: String,
	val ieeeAddress: String? = null,
	val networkAddress: String? = null,
	val friendlyName: String,
	val displayName: String,
	val modelKey: String,
	val modelName: String? = null,
	val powerSource: PowerSource = PowerSource.UNKNOWN,
	val metadata: JsonNode = JsonNodeFactory.instance.objectNode(),
)

data class DeviceSnapshot(
	val providerType: ProviderType,
	val providerDeviceId: String,
	val observedAt: Instant,
	val available: Boolean,
	val lastSeenAt: Instant? = null,
	val batteryLevel: Double? = null,
	val payload: JsonNode = JsonNodeFactory.instance.objectNode(),
	val properties: Map<DevicePropertyRef, JsonNode> = emptyMap(),
)

data class DevicePropertyRef(
	val providerType: ProviderType,
	val propertyPath: String,
	val endpoint: String? = null,
	val capability: String? = null,
)

data class PropertyMetadata(
	val ref: DevicePropertyRef,
	val displayName: String,
	val valueType: PropertyValueType,
	val readable: Boolean = true,
	val writable: Boolean = false,
	val unit: String? = null,
	val allowedValues: List<String> = emptyList(),
	val metadata: JsonNode = JsonNodeFactory.instance.objectNode(),
)

enum class PropertyValueType {
	STRING,
	NUMBER,
	BOOLEAN,
	OBJECT,
	ARRAY,
	NULL,
	UNKNOWN,
}

data class FixAttemptResult(
	val status: ProviderFixStatus,
	val message: String? = null,
	val providerResponse: JsonNode? = null,
	val confirmed: Boolean = false,
	val confirmedAt: Instant? = null,
)

enum class ProviderFixStatus {
	REQUESTED,
	CONFIRMED,
	FAILED,
	TIMED_OUT,
	SKIPPED,
}
