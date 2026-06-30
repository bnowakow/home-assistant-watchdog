// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.notifications.NotificationQueries

@Service
class CurrentAppUserService(
	private val queries: NotificationQueries,
) {
	fun currentAppUserId(): Long =
		queries.ensureAppUser(currentEmail())

	private fun currentEmail(): String {
		val principal = SecurityContextHolder.getContext().authentication?.principal
		return when (principal) {
			is OAuth2User -> principal.getAttribute<String>("email")
				?.trim()
				?.takeIf { it.isNotBlank() }
			is String -> principal.trim().takeIf { it.isNotBlank() && it != "anonymousUser" }
			else -> null
		} ?: LOCAL_ADMIN_EMAIL
	}

	private companion object {
		const val LOCAL_ADMIN_EMAIL = "local-admin@localhost"
	}
}
