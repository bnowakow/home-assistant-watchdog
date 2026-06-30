// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("watchdog.notifications")
data class NotificationDeliveryProperties(
	val defaultCooldownSeconds: Long = 86_400,
)
