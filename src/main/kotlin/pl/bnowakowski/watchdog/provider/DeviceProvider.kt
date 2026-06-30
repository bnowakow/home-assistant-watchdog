// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider

import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.ProviderType
import tools.jackson.databind.JsonNode

interface DeviceProvider {
	val providerType: ProviderType

	fun discoverDevices(): List<DiscoveredDevice>

	fun readSnapshot(device: Device): DeviceSnapshot

	fun applyDesiredState(
		device: Device,
		property: DevicePropertyRef,
		desiredValue: JsonNode,
	): FixAttemptResult

	fun supportedProperties(device: Device): List<PropertyMetadata>

	fun modelKey(device: DiscoveredDevice): String
}
