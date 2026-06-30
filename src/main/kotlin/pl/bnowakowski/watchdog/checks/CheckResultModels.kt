// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Instant
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceCheckStatus
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import pl.bnowakowski.watchdog.provider.DeviceSnapshot
import pl.bnowakowski.watchdog.rules.EffectiveRule
import tools.jackson.databind.JsonNode

data class CheckRunReport(
	val checkRunId: Long,
	val triggerType: CheckRunTriggerType,
	val status: CheckRunStatus,
	val startedAt: Instant,
	val finishedAt: Instant?,
	val deviceResults: List<EvaluatedDeviceCheck>,
)

data class EvaluatedDeviceCheck(
	val device: Device,
	val status: DeviceCheckStatus,
	val snapshot: DeviceSnapshot?,
	val checkedAt: Instant,
	val ruleResults: List<EvaluatedRuleCheck>,
	val message: String? = null,
)

data class EvaluatedRuleCheck(
	val effectiveRule: EffectiveRule,
	val status: RuleCheckStatus,
	val actualValue: JsonNode? = null,
	val expectedValue: JsonNode? = null,
	val message: String? = null,
)

data class PersistedRuleCheckResult(
	val evaluatedRuleCheck: EvaluatedRuleCheck,
	val ruleCheckResultId: Long,
)
