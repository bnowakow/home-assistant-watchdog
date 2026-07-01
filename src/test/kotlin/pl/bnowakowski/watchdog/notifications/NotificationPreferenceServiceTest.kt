// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.kotlin.eq
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
}
