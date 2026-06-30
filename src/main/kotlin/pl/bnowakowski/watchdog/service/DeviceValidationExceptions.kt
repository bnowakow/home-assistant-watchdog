// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import pl.bnowakowski.watchdog.domain.ProviderType

open class DeviceValidationException(message: String) : RuntimeException(message)

class DuplicateProviderIdentityException(
	providerType: ProviderType,
	providerDeviceId: String,
) : DeviceValidationException("Device provider identity already exists: $providerType/$providerDeviceId")

class DuplicateZigbeeIeeeAddressException(
	ieeeAddress: String,
) : DeviceValidationException("Zigbee IEEE address already exists: $ieeeAddress")

class MissingProviderIdentityException(
	providerType: ProviderType,
) : DeviceValidationException("Provider device id is required for $providerType devices")

class MissingZigbeeIeeeAddressException :
	DeviceValidationException("IEEE address is required for Zigbee2MQTT devices")

class GroupModelMismatchException(
	groupId: Long,
	deviceId: Long,
	expectedProviderType: ProviderType,
	expectedModelKey: String,
	actualProviderType: ProviderType,
	actualModelKey: String,
) : DeviceValidationException(
	"Device $deviceId does not match group $groupId model lock: " +
		"expected $expectedProviderType/$expectedModelKey but got $actualProviderType/$actualModelKey",
	)

class InconsistentGroupModelLockException(
	groupId: Long,
) : DeviceValidationException("Group $groupId has an incomplete provider/model lock")
