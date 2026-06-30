// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.ProviderType

class ProviderSnapshotReaderTest {
	@Test
	fun `reads snapshots through registered provider`() {
		val device = Device(
			providerType = ProviderType.HOME_ASSISTANT,
			providerDeviceId = "light.bedroom",
			friendlyName = "light.bedroom",
			displayName = "Bedroom Lamp",
			modelKey = "ha-light",
		)
		val snapshot = DeviceSnapshot(
			providerType = ProviderType.HOME_ASSISTANT,
			providerDeviceId = "light.bedroom",
			observedAt = Instant.parse("2026-06-30T18:00:00Z"),
			available = true,
		)
		val provider = mock<DeviceProvider>()
		whenever(provider.providerType).thenReturn(ProviderType.HOME_ASSISTANT)
		whenever(provider.readSnapshot(device)).thenReturn(snapshot)

		val reader = ProviderSnapshotReader(DeviceProviderRegistry(listOf(provider)))

		assertEquals(snapshot, reader.readSnapshot(device))
		verify(provider).readSnapshot(device)
	}
}
