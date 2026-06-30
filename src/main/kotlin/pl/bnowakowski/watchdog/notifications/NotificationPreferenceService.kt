// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import org.springframework.stereotype.Service

@Service
class NotificationPreferenceService(
	private val queries: NotificationQueries,
	private val encryptor: PushoverUserKeyEncryptor,
	private val pushoverClient: PushoverClient,
) {
	fun savePushoverPreference(
		appUserId: Long,
		input: NotificationPreferenceInput,
	): NotificationPreference {
		val userKey = input.pushoverUserKey?.trim()?.takeIf { it.isNotBlank() }
			?: throw IllegalArgumentException("Pushover user key is required")
		val devices = PushoverDevices.normalize(input.pushoverDevices)
		pushoverClient.validateUser(userKey, devices)
		return queries.upsertPushoverPreference(
			appUserId = appUserId,
			pushoverUserKeyEncrypted = encryptor.encrypt(userKey),
			pushoverUserKeySuffix = userKey.takeLast(KEY_SUFFIX_LENGTH),
			pushoverDevices = devices,
			notifyRecoveryEnabled = input.notifyRecoveryEnabled,
		)
	}

	private companion object {
		const val KEY_SUFFIX_LENGTH = 4
	}
}
