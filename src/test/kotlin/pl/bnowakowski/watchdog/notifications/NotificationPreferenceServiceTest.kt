// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationPreferenceServiceTest {
	private val queries: NotificationQueries = mock()
	private val encryptor: PushoverUserKeyEncryptor = mock()
	private val pushoverClient: PushoverClient = mock()
	private val service = NotificationPreferenceService(queries, encryptor, pushoverClient)

	@Test
	fun `imports devices from pushover user validation`() {
		whenever(pushoverClient.validateUser("user-key", emptyList())).thenReturn(
			PushoverUserValidation(listOf("phone", "tablet")),
		)

		assertEquals(listOf("phone", "tablet"), service.importPushoverDevices(" user-key "))

		verify(pushoverClient).validateUser(eq("user-key"), eq(emptyList()))
	}

	@Test
	fun `requires pushover user key before importing devices`() {
		assertFailsWith<IllegalArgumentException> {
			service.importPushoverDevices(" ")
		}
	}

	@Test
	fun `preserves existing pushover key when saving without one`() {
		whenever(queries.findPushoverPreference(42L)).thenReturn(
			NotificationPreference(
				appUserId = 42L,
				pushoverUserKeyEncrypted = "encrypted",
				pushoverUserKeySuffix = "1234",
				pushoverDevices = listOf("phone"),
				notifyMismatchEnabled = true,
				notifyLowBatteryEnabled = true,
				notifyOfflineStaleEnabled = true,
				notifyRecoveryEnabled = true,
				notifyFixSuccessEnabled = true,
				notifyFixFailureEnabled = true,
				createdAt = Instant.parse("2026-06-30T00:00:00Z"),
				updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
			),
		)
		whenever(
			queries.upsertPushoverPreference(
				appUserId = eq(42L),
				pushoverUserKeyEncrypted = eq("encrypted"),
				pushoverUserKeySuffix = eq("1234"),
				pushoverDevices = eq(listOf("tablet")),
				notifyMismatchEnabled = eq(false),
				notifyLowBatteryEnabled = eq(true),
				notifyOfflineStaleEnabled = eq(true),
				notifyRecoveryEnabled = eq(true),
				notifyFixSuccessEnabled = eq(true),
				notifyFixFailureEnabled = eq(true),
			),
		).thenReturn(
			NotificationPreference(
				appUserId = 42L,
				pushoverUserKeyEncrypted = "encrypted",
				pushoverUserKeySuffix = "1234",
				pushoverDevices = listOf("tablet"),
				notifyMismatchEnabled = false,
				notifyLowBatteryEnabled = true,
				notifyOfflineStaleEnabled = true,
				notifyRecoveryEnabled = true,
				notifyFixSuccessEnabled = true,
				notifyFixFailureEnabled = true,
				createdAt = Instant.parse("2026-06-30T00:00:00Z"),
				updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
			),
		)

		val result = service.savePushoverPreference(
			appUserId = 42L,
			input = NotificationPreferenceInput(
				pushoverUserKey = " ",
				pushoverDevices = listOf("tablet"),
				notifyMismatchEnabled = false,
			),
		)

		assertEquals("encrypted", result.pushoverUserKeyEncrypted)
		assertEquals("1234", result.pushoverUserKeySuffix)
		verify(pushoverClient, never()).validateUser(any(), any())
		verify(encryptor, never()).encrypt(any())
	}
}
