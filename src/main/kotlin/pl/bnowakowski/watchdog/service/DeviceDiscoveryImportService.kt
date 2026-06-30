// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.persistence.DeviceIdentityQueries
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.DiscoveredDevice

@Service
class DeviceDiscoveryImportService(
	private val providerRegistry: DeviceProviderRegistry,
	private val deviceInventoryService: DeviceInventoryService,
	private val identityQueries: DeviceIdentityQueries,
) {
	@Transactional
	fun importDiscoveredDevices(providerType: ProviderType? = null): DeviceDiscoveryImportReport {
		val providers = providerType
			?.let { listOf(providerRegistry.providerFor(it)) }
			?: providerRegistry.allProviders()
		val results = providers.flatMap { provider ->
			provider.discoverDevices().map { discovered ->
				importDevice(discovered)
			}
		}

		return DeviceDiscoveryImportReport(results)
	}

	private fun importDevice(discovered: DiscoveredDevice): DeviceDiscoveryImportResult {
		if (identityQueries.providerIdentityExists(discovered.providerType, discovered.providerDeviceId)) {
			return DeviceDiscoveryImportResult(
				providerType = discovered.providerType,
				providerDeviceId = discovered.providerDeviceId,
				displayName = discovered.displayName,
				status = DeviceDiscoveryImportStatus.SKIPPED_EXISTING,
			)
		}

		return runCatching {
			deviceInventoryService.save(discovered.toDevice())
		}.fold(
			onSuccess = {
				DeviceDiscoveryImportResult(
					providerType = discovered.providerType,
					providerDeviceId = discovered.providerDeviceId,
					displayName = discovered.displayName,
					status = DeviceDiscoveryImportStatus.IMPORTED,
					deviceId = it.id,
				)
			},
			onFailure = {
				DeviceDiscoveryImportResult(
					providerType = discovered.providerType,
					providerDeviceId = discovered.providerDeviceId,
					displayName = discovered.displayName,
					status = DeviceDiscoveryImportStatus.FAILED,
					message = it.message,
				)
			},
		)
	}

	private fun DiscoveredDevice.toDevice(): Device =
		Device(
			providerType = providerType,
			providerDeviceId = providerDeviceId.trim(),
			ieeeAddress = ieeeAddress?.trim()?.takeIf { it.isNotBlank() },
			networkAddress = networkAddress?.trim()?.takeIf { it.isNotBlank() },
			friendlyName = friendlyName.trim().ifBlank { displayName.trim().ifBlank { providerDeviceId.trim() } },
			displayName = displayName.trim().ifBlank { friendlyName.trim().ifBlank { providerDeviceId.trim() } },
			modelKey = modelKey.trim(),
			modelName = modelName?.trim()?.takeIf { it.isNotBlank() },
			powerSource = powerSource,
			providerMetadata = metadata,
		)
}

data class DeviceDiscoveryImportReport(
	val results: List<DeviceDiscoveryImportResult>,
) {
	val discoveredCount: Int = results.size
	val importedCount: Int = results.count { it.status == DeviceDiscoveryImportStatus.IMPORTED }
	val skippedExistingCount: Int = results.count { it.status == DeviceDiscoveryImportStatus.SKIPPED_EXISTING }
	val failedCount: Int = results.count { it.status == DeviceDiscoveryImportStatus.FAILED }
}

data class DeviceDiscoveryImportResult(
	val providerType: ProviderType,
	val providerDeviceId: String,
	val displayName: String,
	val status: DeviceDiscoveryImportStatus,
	val deviceId: Long? = null,
	val message: String? = null,
)

enum class DeviceDiscoveryImportStatus {
	IMPORTED,
	SKIPPED_EXISTING,
	FAILED,
}
