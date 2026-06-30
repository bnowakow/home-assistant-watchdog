-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE check_run (
    id BIGSERIAL PRIMARY KEY,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT check_run_trigger_type_check CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'STARTUP')),
    CONSTRAINT check_run_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT check_run_finished_after_started_check CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX check_run_started_at_idx ON check_run (started_at DESC);
CREATE INDEX check_run_status_idx ON check_run (status);

CREATE TABLE device_check_result (
    id BIGSERIAL PRIMARY KEY,
    check_run_id BIGINT NOT NULL REFERENCES check_run(id) ON DELETE CASCADE,
    device_id BIGINT NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    checked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT device_check_result_status_check CHECK (status IN ('HEALTHY', 'DEGRADED', 'OFFLINE', 'UNKNOWN'))
);

CREATE INDEX device_check_result_run_idx ON device_check_result (check_run_id);
CREATE INDEX device_check_result_device_checked_idx ON device_check_result (device_id, checked_at DESC);
CREATE INDEX device_check_result_status_idx ON device_check_result (status);

CREATE TABLE rule_check_result (
    id BIGSERIAL PRIMARY KEY,
    device_check_result_id BIGINT NOT NULL REFERENCES device_check_result(id) ON DELETE CASCADE,
    rule_id BIGINT NOT NULL REFERENCES device_group_rule(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    actual_value JSONB,
    expected_value JSONB,
    message TEXT,
    CONSTRAINT rule_check_result_status_check CHECK (status IN ('MATCH', 'MISMATCH', 'SKIPPED', 'ERROR'))
);

CREATE INDEX rule_check_result_device_check_idx ON rule_check_result (device_check_result_id);
CREATE INDEX rule_check_result_rule_idx ON rule_check_result (rule_id);
CREATE INDEX rule_check_result_status_idx ON rule_check_result (status);

CREATE TABLE fix_attempt (
    id BIGSERIAL PRIMARY KEY,
    rule_check_result_id BIGINT NOT NULL REFERENCES rule_check_result(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    requested_value JSONB,
    provider_response JSONB,
    requested_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT fix_attempt_status_check CHECK (status IN ('REQUESTED', 'CONFIRMED', 'FAILED', 'TIMED_OUT', 'SKIPPED')),
    CONSTRAINT fix_attempt_confirmed_after_requested_check CHECK (confirmed_at IS NULL OR confirmed_at >= requested_at)
);

CREATE INDEX fix_attempt_rule_result_idx ON fix_attempt (rule_check_result_id);
CREATE INDEX fix_attempt_status_idx ON fix_attempt (status);
CREATE INDEX fix_attempt_requested_at_idx ON fix_attempt (requested_at DESC);
