// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.homeassistant

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

data class HomeAssistantEntityState(
	val entityId: String,
	val state: String,
	val attributes: JsonNode = JsonNodeFactory.instance.objectNode(),
	val lastChanged: String? = null,
	val lastUpdated: String? = null,
)

data class HomeAssistantServiceCall(
	val domain: String,
	val service: String,
	val payload: JsonNode,
)
