// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.fixes

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("watchdog.fix")
data class FixProperties(
	@field:Min(0)
	val defaultRetryCount: Int = 3,
	@field:Min(0)
	val defaultRetryDelaySeconds: Long = 60,
	@field:Min(0)
	val defaultCooldownSeconds: Long = 300,
)
