// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

object JsonDefaults {
	fun emptyObject(): JsonNode = JsonNodeFactory.instance.objectNode()
}
