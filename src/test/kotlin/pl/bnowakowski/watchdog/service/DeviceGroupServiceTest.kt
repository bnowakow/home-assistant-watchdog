// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.persistence.DeviceGroupMembershipQueries
import pl.bnowakowski.watchdog.persistence.DeviceGroupRepository
import pl.bnowakowski.watchdog.persistence.DeviceRepository
import pl.bnowakowski.watchdog.persistence.GroupModelLock

class DeviceGroupServiceTest {
	private val deviceRepository: DeviceRepository = mock()
	private val deviceGroupRepository: DeviceGroupRepository = mock()
	private val membershipQueries: DeviceGroupMembershipQueries = mock()
	private val service = DeviceGroupService(deviceRepository, deviceGroupRepository, membershipQueries)

	@Test
	fun `first member locks the group model before adding membership`() {
		val device = sampleDevice(id = 11, modelKey = "TS0012")
		whenever(deviceRepository.findById(11)).thenReturn(Optional.of(device))
		whenever(deviceGroupRepository.findById(7)).thenReturn(Optional.of(DeviceGroup(id = 7, name = "Switches")))
		whenever(membershipQueries.findModelLock(7)).thenReturn(GroupModelLock(null, null))
		whenever(membershipQueries.membersWithDifferentModel(7, ProviderType.ZIGBEE2MQTT, "TS0012")).thenReturn(emptyList())

		service.addDeviceToGroup(deviceId = 11, groupId = 7)

		verify(membershipQueries).lockModel(eq(7), eq(ProviderType.ZIGBEE2MQTT), eq("TS0012"), any())
		verify(membershipQueries).addMembership(11, 7)
	}

	@Test
	fun `rejects device with different model than group lock`() {
		val device = sampleDevice(id = 12, modelKey = "TS0601")
		whenever(deviceRepository.findById(12)).thenReturn(Optional.of(device))
		whenever(deviceGroupRepository.findById(7)).thenReturn(Optional.of(DeviceGroup(id = 7, name = "Switches")))
		whenever(membershipQueries.findModelLock(7)).thenReturn(GroupModelLock(ProviderType.ZIGBEE2MQTT, "TS0012"))

		assertFailsWith<GroupModelMismatchException> {
			service.addDeviceToGroup(deviceId = 12, groupId = 7)
		}

		verify(membershipQueries, never()).addMembership(any(), any())
	}

	private fun sampleDevice(
		id: Long,
		modelKey: String,
	): Device =
		Device(
			id = id,
			providerType = ProviderType.ZIGBEE2MQTT,
			providerDeviceId = "device-$id",
			ieeeAddress = "0x$id",
			friendlyName = "switch_$id",
			displayName = "Switch $id",
			modelKey = modelKey,
			modelName = "Switch",
			powerSource = PowerSource.MAINS,
		)
}
