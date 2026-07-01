// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import jakarta.validation.constraints.Min
import java.time.Duration
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("watchdog.check")
data class CheckProperties(
	val enabled: Boolean = true,
	val scheduledEnabled: Boolean = true,
	val runOnStartup: Boolean = false,
	@field:DurationMin(seconds = 1)
	val runTimeout: Duration = Duration.ofMinutes(2),
	@field:Min(1)
	val intervalSeconds: Long = 300,
	@field:Min(0)
	val missingPropertyRetryCount: Int = 3,
	@field:Min(0)
	val missingPropertyRetryDelaySeconds: Long = 10,
	@field:Min(1)
	val staleMainsSeconds: Long = 7_200,
	@field:Min(1)
	val staleBatterySeconds: Long = 86_400,
	@field:Min(1)
	val staleCriticalSeconds: Long = 3_600,
)
