// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.persistence

import org.springframework.data.repository.CrudRepository
import pl.bnowakowski.watchdog.domain.CheckRun
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckResult
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.DeviceParameterHistory
import pl.bnowakowski.watchdog.domain.FixAttempt
import pl.bnowakowski.watchdog.domain.NotificationChannel
import pl.bnowakowski.watchdog.domain.NotificationEvent
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.domain.RuleCheckResult

interface DeviceRepository : CrudRepository<Device, Long> {
	fun existsByProviderTypeAndProviderDeviceId(providerType: ProviderType, providerDeviceId: String): Boolean
	fun existsByProviderTypeAndIeeeAddress(providerType: ProviderType, ieeeAddress: String): Boolean
}

interface DeviceGroupRepository : CrudRepository<DeviceGroup, Long>

interface DeviceGroupRuleRepository : CrudRepository<DeviceGroupRule, Long> {
	fun findAllByGroupId(groupId: Long): List<DeviceGroupRule>
}

interface CheckRunRepository : CrudRepository<CheckRun, Long>

interface DeviceCheckResultRepository : CrudRepository<DeviceCheckResult, Long>

interface RuleCheckResultRepository : CrudRepository<RuleCheckResult, Long>

interface FixAttemptRepository : CrudRepository<FixAttempt, Long>

interface NotificationChannelRepository : CrudRepository<NotificationChannel, Long>

interface NotificationEventRepository : CrudRepository<NotificationEvent, Long>

interface DeviceParameterHistoryRepository : CrudRepository<DeviceParameterHistory, Long>
