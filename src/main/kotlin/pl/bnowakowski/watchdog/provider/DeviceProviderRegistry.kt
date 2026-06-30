// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider

import org.springframework.stereotype.Component
import pl.bnowakowski.watchdog.domain.ProviderType

@Component
class DeviceProviderRegistry(
	providers: List<DeviceProvider>,
) {
	private val providersByType: Map<ProviderType, DeviceProvider> =
		providers.associateByUniqueProviderType()

	fun providerFor(providerType: ProviderType): DeviceProvider =
		providersByType[providerType]
			?: throw UnknownDeviceProviderException(providerType)

	fun allProviders(): List<DeviceProvider> =
		providersByType.values.toList()

	private fun List<DeviceProvider>.associateByUniqueProviderType(): Map<ProviderType, DeviceProvider> {
		val duplicates = groupingBy { it.providerType }
			.eachCount()
			.filterValues { it > 1 }
			.keys

		if (duplicates.isNotEmpty()) {
			throw DuplicateDeviceProviderException(duplicates)
		}

		return associateBy { it.providerType }
	}
}

class UnknownDeviceProviderException(
	providerType: ProviderType,
) : RuntimeException("No device provider registered for $providerType")

class DuplicateDeviceProviderException(
	providerTypes: Set<ProviderType>,
) : RuntimeException("Multiple device providers registered for: ${providerTypes.joinToString()}")
