// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import tools.jackson.databind.JsonNode

@Table("check_run")
data class CheckRun(
	@Id
	val id: Long? = null,
	@Column("trigger_type")
	val triggerType: CheckRunTriggerType,
	val status: CheckRunStatus,
	@Column("started_at")
	val startedAt: Instant = Instant.now(),
	@Column("finished_at")
	val finishedAt: Instant? = null,
	val summary: JsonNode = JsonDefaults.emptyObject(),
)

@Table("device_check_result")
data class DeviceCheckResult(
	@Id
	val id: Long? = null,
	@Column("check_run_id")
	val checkRunId: Long,
	@Column("device_id")
	val deviceId: Long,
	val status: DeviceCheckStatus,
	val snapshot: JsonNode = JsonDefaults.emptyObject(),
	@Column("checked_at")
	val checkedAt: Instant = Instant.now(),
)

@Table("rule_check_result")
data class RuleCheckResult(
	@Id
	val id: Long? = null,
	@Column("device_check_result_id")
	val deviceCheckResultId: Long,
	@Column("rule_id")
	val ruleId: Long,
	val status: RuleCheckStatus,
	@Column("actual_value")
	val actualValue: JsonNode? = null,
	@Column("expected_value")
	val expectedValue: JsonNode? = null,
	val message: String? = null,
)

@Table("fix_attempt")
data class FixAttempt(
	@Id
	val id: Long? = null,
	@Column("rule_check_result_id")
	val ruleCheckResultId: Long,
	val status: FixAttemptStatus,
	@Column("requested_value")
	val requestedValue: JsonNode? = null,
	@Column("provider_response")
	val providerResponse: JsonNode? = null,
	@Column("requested_at")
	val requestedAt: Instant = Instant.now(),
	@Column("confirmed_at")
	val confirmedAt: Instant? = null,
)
