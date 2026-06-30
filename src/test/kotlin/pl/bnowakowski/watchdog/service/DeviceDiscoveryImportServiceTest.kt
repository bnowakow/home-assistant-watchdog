// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.persistence.DeviceIdentityQueries
import pl.bnowakowski.watchdog.provider.DeviceProvider
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.DiscoveredDevice

class DeviceDiscoveryImportServiceTest {
	private val zigbeeProvider: DeviceProvider = mock()
	private val providerRegistry: DeviceProviderRegistry = mock()
	private val deviceInventoryService: DeviceInventoryService = mock()
	private val identityQueries: DeviceIdentityQueries = mock()
	private val service = DeviceDiscoveryImportService(providerRegistry, deviceInventoryService, identityQueries)

	@Test
	fun `imports discovered devices that are not already registered`() {
		val discovered = discoveredZigbeeDevice()
		whenever(providerRegistry.allProviders()).thenReturn(listOf(zigbeeProvider))
		whenever(zigbeeProvider.discoverDevices()).thenReturn(listOf(discovered))
		whenever(identityQueries.providerIdentityExists(ProviderType.ZIGBEE2MQTT, discovered.providerDeviceId))
			.thenReturn(false)
		whenever(deviceInventoryService.save(any())).thenReturn(discovered.toSavedDevice())

		val report = service.importDiscoveredDevices()

		assertEquals(1, report.discoveredCount)
		assertEquals(1, report.importedCount)
		assertEquals(0, report.skippedExistingCount)
		assertEquals(0, report.failedCount)
		assertEquals(DeviceDiscoveryImportStatus.IMPORTED, report.results.single().status)
		verify(deviceInventoryService).save(
			argThat {
				providerType == ProviderType.ZIGBEE2MQTT &&
					providerDeviceId == "0x54ef4410005e77ba" &&
					ieeeAddress == "0x54ef4410005e77ba" &&
					friendlyName == "bathroom_switch" &&
					displayName == "Bathroom switch" &&
					modelKey == "TS0012" &&
					modelName == "Tuya Two gang switch" &&
					powerSource == PowerSource.MAINS &&
					providerMetadata == discovered.metadata
			},
		)
	}

	@Test
	fun `skips discovered devices that are already registered`() {
		val discovered = discoveredZigbeeDevice()
		whenever(providerRegistry.providerFor(ProviderType.ZIGBEE2MQTT)).thenReturn(zigbeeProvider)
		whenever(zigbeeProvider.discoverDevices()).thenReturn(listOf(discovered))
		whenever(identityQueries.providerIdentityExists(ProviderType.ZIGBEE2MQTT, discovered.providerDeviceId))
			.thenReturn(true)

		val report = service.importDiscoveredDevices(ProviderType.ZIGBEE2MQTT)

		assertEquals(1, report.discoveredCount)
		assertEquals(0, report.importedCount)
		assertEquals(1, report.skippedExistingCount)
		assertEquals(0, report.failedCount)
		assertEquals(DeviceDiscoveryImportStatus.SKIPPED_EXISTING, report.results.single().status)
		verify(deviceInventoryService, never()).save(any())
	}

	private fun discoveredZigbeeDevice(): DiscoveredDevice =
		DiscoveredDevice(
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "0x54ef4410005e77ba",
			ieeeAddress = "0x54ef4410005e77ba",
			friendlyName = "bathroom_switch",
			displayName = "Bathroom switch",
			modelKey = "TS0012",
			modelName = "Tuya Two gang switch",
			powerSource = PowerSource.MAINS,
		)

	private fun DiscoveredDevice.toSavedDevice(): pl.bnowakowski.watchdog.domain.Device =
		pl.bnowakowski.watchdog.domain.Device(
			id = 10,
			providerType = providerType,
			providerDeviceId = providerDeviceId,
			ieeeAddress = ieeeAddress,
			friendlyName = friendlyName,
			displayName = displayName,
			modelKey = modelKey,
			modelName = modelName,
			powerSource = powerSource,
			providerMetadata = metadata,
		)
}
