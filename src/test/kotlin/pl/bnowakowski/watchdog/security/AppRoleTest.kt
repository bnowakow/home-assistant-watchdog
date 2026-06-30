package pl.bnowakowski.watchdog.security

import kotlin.test.Test
import kotlin.test.assertEquals

class AppRoleTest {
	@Test
	fun `role model starts with admin and user`() {
		assertEquals(listOf(AppRole.ADMIN, AppRole.USER), AppRole.entries.toList())
	}
}
