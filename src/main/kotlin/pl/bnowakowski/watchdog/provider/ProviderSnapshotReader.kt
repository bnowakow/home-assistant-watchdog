// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider

import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.domain.Device

@Service
class ProviderSnapshotReader(
	private val providerRegistry: DeviceProviderRegistry,
) {
	fun readSnapshot(device: Device): DeviceSnapshot =
		providerRegistry.providerFor(device.providerType)
			.readSnapshot(device)
}
