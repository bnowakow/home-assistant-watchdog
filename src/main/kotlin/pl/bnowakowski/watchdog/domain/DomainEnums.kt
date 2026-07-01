// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.domain

enum class ProviderType {
	ZIGBEE2MQTT,
	HOME_ASSISTANT,
	CUSTOM_HTTP,
	CUSTOM_COMMAND,
}

enum class PowerSource {
	MAINS,
	BATTERY,
	UNKNOWN,
}

enum class Criticality {
	NORMAL,
	IMPORTANT,
	CRITICAL,
}

enum class RuleType {
	DESIRED_PROPERTY,
	BATTERY_THRESHOLD,
	AVAILABILITY,
	FRESHNESS,
}

enum class ComparisonOperator {
	EQUALS,
	NOT_EQUALS,
	GREATER_THAN,
	GREATER_THAN_OR_EQUAL,
	LESS_THAN,
	LESS_THAN_OR_EQUAL,
}

enum class CheckMode {
	OBSERVE_ONLY,
	AUTO_FIX,
}

enum class Severity {
	INFO,
	WARNING,
	CRITICAL,
}

enum class CheckRunTriggerType {
	SCHEDULED,
	MANUAL,
	STARTUP,
}

enum class CheckRunStatus {
	RUNNING,
	COMPLETED,
	FAILED,
	TIMED_OUT,
	STALE,
}

enum class DeviceCheckStatus {
	HEALTHY,
	DEGRADED,
	OFFLINE,
	UNKNOWN,
	SKIPPED,
}

enum class RuleCheckStatus {
	MATCH,
	MISMATCH,
	SKIPPED,
	ERROR,
}

enum class FixAttemptStatus {
	REQUESTED,
	CONFIRMED,
	FAILED,
	TIMED_OUT,
	SKIPPED,
}

enum class NotificationChannelType {
	PUSHOVER,
}

enum class NotificationEventStatus {
	SENT,
	SKIPPED_COOLDOWN,
	FAILED,
}

enum class ParameterHistorySource {
	CHECK,
	MQTT_EVENT,
	HA_EVENT,
	MANUAL,
}
