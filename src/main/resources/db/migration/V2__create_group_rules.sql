-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE device_group_rule (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES device_group(id) ON DELETE CASCADE,
    provider_type VARCHAR(40),
    rule_type VARCHAR(40) NOT NULL,
    property_path TEXT,
    endpoint TEXT,
    comparison_operator VARCHAR(40) NOT NULL,
    desired_value JSONB,
    check_mode VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    notify_on_mismatch BOOLEAN NOT NULL DEFAULT false,
    notify_on_fix_success BOOLEAN NOT NULL DEFAULT false,
    notify_on_fix_failure BOOLEAN NOT NULL DEFAULT true,
    notify_on_recovery BOOLEAN NOT NULL DEFAULT true,
    cooldown_seconds INTEGER NOT NULL DEFAULT 86400,
    retry_count INTEGER NOT NULL DEFAULT 3,
    retry_delay_seconds INTEGER NOT NULL DEFAULT 60,
    missing_property_retry_count INTEGER NOT NULL DEFAULT 3,
    missing_property_retry_delay_seconds INTEGER NOT NULL DEFAULT 10,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_group_rule_type_check CHECK (
        rule_type IN ('DESIRED_PROPERTY', 'BATTERY_THRESHOLD', 'AVAILABILITY', 'FRESHNESS')
    ),
    CONSTRAINT device_group_rule_comparison_operator_check CHECK (
        comparison_operator IN (
            'EQUALS',
            'NOT_EQUALS',
            'GREATER_THAN',
            'GREATER_THAN_OR_EQUAL',
            'LESS_THAN',
            'LESS_THAN_OR_EQUAL'
        )
    ),
    CONSTRAINT device_group_rule_check_mode_check CHECK (check_mode IN ('OBSERVE_ONLY', 'AUTO_FIX')),
    CONSTRAINT device_group_rule_severity_check CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT device_group_rule_cooldown_check CHECK (cooldown_seconds >= 0),
    CONSTRAINT device_group_rule_retry_count_check CHECK (retry_count >= 0),
    CONSTRAINT device_group_rule_retry_delay_check CHECK (retry_delay_seconds >= 0),
    CONSTRAINT device_group_rule_missing_retry_count_check CHECK (missing_property_retry_count >= 0),
    CONSTRAINT device_group_rule_missing_retry_delay_check CHECK (missing_property_retry_delay_seconds >= 0)
);

CREATE INDEX device_group_rule_group_idx ON device_group_rule (group_id);
CREATE INDEX device_group_rule_enabled_idx ON device_group_rule (enabled) WHERE enabled = true;
CREATE INDEX device_group_rule_effective_property_idx
    ON device_group_rule (group_id, provider_type, property_path, endpoint)
    WHERE enabled = true;
CREATE INDEX device_group_rule_notifications_idx
    ON device_group_rule (notify_on_mismatch, notify_on_fix_success, notify_on_fix_failure, notify_on_recovery)
    WHERE enabled = true;
