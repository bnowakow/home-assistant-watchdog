-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE device_parameter_history (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    rule_id BIGINT REFERENCES device_group_rule(id) ON DELETE SET NULL,
    provider_type VARCHAR(40) NOT NULL,
    property_path TEXT NOT NULL,
    endpoint TEXT,
    value_json JSONB,
    value_text TEXT,
    value_number DOUBLE PRECISION,
    value_boolean BOOLEAN,
    previous_value_json JSONB,
    changed BOOLEAN NOT NULL,
    source VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    check_run_id BIGINT REFERENCES check_run(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_parameter_history_source_check CHECK (source IN ('CHECK', 'MQTT_EVENT', 'HA_EVENT', 'MANUAL'))
);

CREATE INDEX device_parameter_history_latest_idx
    ON device_parameter_history (device_id, provider_type, property_path, endpoint, observed_at DESC);
CREATE INDEX device_parameter_history_changes_idx
    ON device_parameter_history (device_id, property_path, changed, observed_at DESC);
CREATE INDEX device_parameter_history_observed_at_idx
    ON device_parameter_history (observed_at);
CREATE INDEX device_parameter_history_rule_idx ON device_parameter_history (rule_id);
CREATE INDEX device_parameter_history_check_run_idx ON device_parameter_history (check_run_id);
CREATE INDEX device_parameter_history_numeric_idx
    ON device_parameter_history (property_path, value_number, observed_at DESC)
    WHERE value_number IS NOT NULL;
CREATE INDEX device_parameter_history_text_idx
    ON device_parameter_history (property_path, value_text, observed_at DESC)
    WHERE value_text IS NOT NULL;
