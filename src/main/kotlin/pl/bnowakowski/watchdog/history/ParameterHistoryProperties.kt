// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.history

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("watchdog.parameter-history")
data class ParameterHistoryProperties(
	@field:Min(1)
	val retentionDays: Long = 365,
	val recordUnchanged: Boolean = false,
	@field:Min(1)
	val unchangedSampleSeconds: Long = 86_400,
)
