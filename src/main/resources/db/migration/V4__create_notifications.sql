-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE notification_channel (
    id BIGSERIAL PRIMARY KEY,
    channel_type VARCHAR(40) NOT NULL,
    name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_channel_type_check CHECK (channel_type IN ('PUSHOVER')),
    CONSTRAINT notification_channel_name_unique UNIQUE (name)
);

CREATE INDEX notification_channel_enabled_idx ON notification_channel (enabled) WHERE enabled = true;
CREATE INDEX notification_channel_type_idx ON notification_channel (channel_type);

CREATE TABLE notification_event (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    device_id BIGINT REFERENCES device(id) ON DELETE SET NULL,
    rule_id BIGINT REFERENCES device_group_rule(id) ON DELETE SET NULL,
    dedupe_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    provider_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_event_status_check CHECK (status IN ('SENT', 'SKIPPED_COOLDOWN', 'FAILED')),
    CONSTRAINT notification_event_severity_check CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE INDEX notification_event_channel_created_idx ON notification_event (channel_id, created_at DESC);
CREATE INDEX notification_event_device_created_idx ON notification_event (device_id, created_at DESC);
CREATE INDEX notification_event_rule_created_idx ON notification_event (rule_id, created_at DESC);
CREATE INDEX notification_event_dedupe_idx ON notification_event (dedupe_key, created_at DESC);
CREATE INDEX notification_event_status_idx ON notification_event (status);
