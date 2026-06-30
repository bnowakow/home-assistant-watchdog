// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.persistence.DeviceIdentityQueries
import pl.bnowakowski.watchdog.persistence.DeviceRepository

@Service
class DeviceInventoryService(
	private val deviceRepository: DeviceRepository,
	private val identityQueries: DeviceIdentityQueries,
) {
	@Transactional
	fun save(device: Device): Device {
		validateProviderIdentity(device)
		return deviceRepository.save(device)
	}

	fun validateProviderIdentity(device: Device) {
		val providerDeviceId = device.providerDeviceId.trim()
		if (providerDeviceId.isBlank()) {
			throw MissingProviderIdentityException(device.providerType)
		}
		if (identityQueries.providerIdentityExists(device.providerType, providerDeviceId, device.id)) {
			throw DuplicateProviderIdentityException(device.providerType, providerDeviceId)
		}

		if (device.providerType == ProviderType.ZIGBEE2MQTT) {
			val ieeeAddress = device.ieeeAddress?.trim().orEmpty()
			if (ieeeAddress.isBlank()) {
				throw MissingZigbeeIeeeAddressException()
			}
			if (identityQueries.zigbeeIeeeAddressExists(ieeeAddress, device.id)) {
				throw DuplicateZigbeeIeeeAddressException(ieeeAddress)
			}
		}
	}
}
