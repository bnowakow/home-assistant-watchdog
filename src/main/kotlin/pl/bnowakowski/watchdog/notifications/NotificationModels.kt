// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import java.time.Instant
import pl.bnowakowski.watchdog.domain.Severity

data class NotificationRecipient(
	val appUserId: Long,
	val email: String,
	val pushoverUserKeyEncrypted: String,
	val pushoverDevices: List<String>,
	val notifyRecoveryEnabled: Boolean,
)

data class NotificationPreference(
	val appUserId: Long,
	val pushoverUserKeyEncrypted: String?,
	val pushoverUserKeySuffix: String?,
	val pushoverDevices: List<String>,
	val notifyRecoveryEnabled: Boolean,
	val createdAt: Instant?,
	val updatedAt: Instant?,
)

data class NotificationPreferenceInput(
	val pushoverUserKey: String?,
	val pushoverDevices: Collection<String>,
	val notifyRecoveryEnabled: Boolean = true,
)

data class PushoverMessage(
	val userKey: String,
	val devices: Collection<String>,
	val title: String,
	val message: String,
	val priority: Int = 0,
)

data class PushoverUserValidation(
	val devices: List<String>,
)

data class NotificationDecision(
	val deviceId: Long?,
	val ruleId: Long?,
	val problemType: NotificationProblemType,
	val severity: Severity,
	val message: String,
	val shouldNotify: Boolean,
)

enum class NotificationProblemType {
	MISMATCH,
	LOW_BATTERY,
	OFFLINE_STALE,
	RECOVERY,
	FIX_SUCCESS,
	FIX_FAILURE,
}

internal object PushoverDevices {
	fun normalize(devices: Collection<String>): List<String> =
		devices
			.flatMap { it.split(",") }
			.map { it.trim() }
			.filter { it.isNotBlank() }
			.distinct()

	fun parse(value: String?): List<String> =
		normalize(value?.split(",") ?: emptyList())

	fun format(devices: Collection<String>): String? =
		normalize(devices).joinToString(",").takeIf { it.isNotBlank() }
}
