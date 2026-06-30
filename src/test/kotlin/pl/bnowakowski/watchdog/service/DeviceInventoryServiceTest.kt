// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.persistence.DeviceIdentityQueries
import pl.bnowakowski.watchdog.persistence.DeviceRepository

class DeviceInventoryServiceTest {
	private val deviceRepository: DeviceRepository = mock()
	private val identityQueries: DeviceIdentityQueries = mock()
	private val service = DeviceInventoryService(deviceRepository, identityQueries)

	@Test
	fun `rejects duplicated provider identity before saving`() {
		val device = sampleDevice(providerDeviceId = "0x54ef4410005e77ba")
		whenever(identityQueries.providerIdentityExists(ProviderType.ZIGBEE2MQTT, device.providerDeviceId, null))
			.thenReturn(true)

		assertFailsWith<DuplicateProviderIdentityException> {
			service.save(device)
		}

		verify(deviceRepository, never()).save(any())
	}

	@Test
	fun `rejects duplicated zigbee ieee address before saving`() {
		val device = sampleDevice(providerDeviceId = "bathroom-switch", ieeeAddress = "0x54ef4410005e77ba")
		whenever(identityQueries.providerIdentityExists(ProviderType.ZIGBEE2MQTT, device.providerDeviceId, null))
			.thenReturn(false)
		whenever(identityQueries.zigbeeIeeeAddressExists(device.ieeeAddress!!, null))
			.thenReturn(true)

		assertFailsWith<DuplicateZigbeeIeeeAddressException> {
			service.save(device)
		}

		verify(deviceRepository, never()).save(any())
	}

	@Test
	fun `saves device after provider identity validation passes`() {
		val device = sampleDevice(providerDeviceId = "bathroom-switch", ieeeAddress = "0x54ef4410005e77ba")
		whenever(deviceRepository.save(device)).thenReturn(device.copy(id = 10))

		service.save(device)

		verify(deviceRepository).save(eq(device))
	}

	private fun sampleDevice(
		providerDeviceId: String,
		ieeeAddress: String = providerDeviceId,
	): Device =
		Device(
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = providerDeviceId,
			ieeeAddress = ieeeAddress,
			friendlyName = "bathroom_switch",
			displayName = "Bathroom - Switch",
			modelKey = "TS0012",
			modelName = "Aqara switch",
			powerSource = PowerSource.MAINS,
		)
}
