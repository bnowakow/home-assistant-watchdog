// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.service

import java.time.Instant
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.persistence.DeviceGroupMembershipQueries
import pl.bnowakowski.watchdog.persistence.DeviceGroupRepository
import pl.bnowakowski.watchdog.persistence.DeviceRepository

@Service
class DeviceGroupService(
	private val deviceRepository: DeviceRepository,
	private val deviceGroupRepository: DeviceGroupRepository,
	private val membershipQueries: DeviceGroupMembershipQueries,
) {
	@Transactional
	fun addDeviceToGroup(deviceId: Long, groupId: Long) {
		val device = requireNotNull(deviceRepository.findByIdOrNull(deviceId)) {
			"Device $deviceId does not exist"
		}
		requireNotNull(deviceGroupRepository.findByIdOrNull(groupId)) {
			"Device group $groupId does not exist"
		}

		val lock = requireNotNull(membershipQueries.findModelLock(groupId)) {
			"Device group $groupId does not exist"
		}

		when {
			lock.providerType == null && lock.modelKey == null -> {
				membershipQueries.lockModel(groupId, device.providerType, device.modelKey, Instant.now())
				rejectIfExistingMembersDoNotMatch(groupId, device)
			}
			lock.providerType == null || lock.modelKey == null -> throw InconsistentGroupModelLockException(groupId)
			lock.providerType != device.providerType || lock.modelKey != device.modelKey ->
				throw GroupModelMismatchException(
					groupId = groupId,
					deviceId = deviceId,
					expectedProviderType = lock.providerType,
					expectedModelKey = lock.modelKey,
					actualProviderType = device.providerType,
					actualModelKey = device.modelKey,
				)
		}

		membershipQueries.addMembership(deviceId, groupId)
	}

	private fun rejectIfExistingMembersDoNotMatch(groupId: Long, device: Device) {
		val mismatches = membershipQueries.membersWithDifferentModel(groupId, device.providerType, device.modelKey)
		if (mismatches.isNotEmpty()) {
			throw GroupModelMismatchException(
				groupId = groupId,
				deviceId = mismatches.first(),
				expectedProviderType = device.providerType,
				expectedModelKey = device.modelKey,
				actualProviderType = device.providerType,
				actualModelKey = "<unknown>",
			)
		}
	}
}
