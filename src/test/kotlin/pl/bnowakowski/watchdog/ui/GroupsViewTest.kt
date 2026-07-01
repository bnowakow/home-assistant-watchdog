// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tools.jackson.databind.ObjectMapper

class GroupsViewTest {
	private val objectMapper = ObjectMapper()

	@Test
	fun `parses blank desired value as null`() {
		assertNull(parseDesiredValue(objectMapper, "  "))
	}

	@Test
	fun `preserves valid json desired values`() {
		val value = parseDesiredValue(objectMapper, """{"state":"on"}""")

		assertEquals("on", value?.get("state")?.asText())
	}

	@Test
	fun `treats unquoted desired values as strings`() {
		val value = parseDesiredValue(objectMapper, "Bedroom")

		assertEquals("Bedroom", value?.asText())
	}
}
