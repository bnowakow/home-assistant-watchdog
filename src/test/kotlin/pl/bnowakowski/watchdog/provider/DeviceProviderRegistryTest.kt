// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.ProviderType

class DeviceProviderRegistryTest {
	@Test
	fun `resolves provider by type`() {
		val provider = provider(ProviderType.ZIGBEE2MQTT)
		val registry = DeviceProviderRegistry(listOf(provider))

		assertEquals(provider, registry.providerFor(ProviderType.ZIGBEE2MQTT))
	}

	@Test
	fun `rejects duplicate providers for the same type`() {
		assertFailsWith<DuplicateDeviceProviderException> {
			DeviceProviderRegistry(
				listOf(
					provider(ProviderType.HOME_ASSISTANT),
					provider(ProviderType.HOME_ASSISTANT),
				),
			)
		}
	}

	@Test
	fun `fails clearly when provider is missing`() {
		val registry = DeviceProviderRegistry(emptyList())

		assertFailsWith<UnknownDeviceProviderException> {
			registry.providerFor(ProviderType.CUSTOM_HTTP)
		}
	}

	private fun provider(providerType: ProviderType): DeviceProvider =
		mock<DeviceProvider>().also {
			whenever(it.providerType).thenReturn(providerType)
		}
}
