-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE device (
    id BIGSERIAL PRIMARY KEY,
    provider_type VARCHAR(40) NOT NULL,
    provider_device_id TEXT NOT NULL,
    ieee_address TEXT,
    network_address TEXT,
    friendly_name TEXT NOT NULL,
    display_name TEXT NOT NULL,
    model_key TEXT NOT NULL,
    model_name TEXT,
    power_source VARCHAR(20) NOT NULL,
    criticality VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    provider_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_provider_identity_unique UNIQUE (provider_type, provider_device_id),
    CONSTRAINT device_power_source_check CHECK (power_source IN ('MAINS', 'BATTERY', 'UNKNOWN')),
    CONSTRAINT device_criticality_check CHECK (criticality IN ('NORMAL', 'IMPORTANT', 'CRITICAL'))
);

CREATE INDEX device_ieee_address_idx ON device (ieee_address);
CREATE INDEX device_provider_identity_idx ON device (provider_type, provider_device_id);
CREATE INDEX device_model_idx ON device (provider_type, model_key);
CREATE INDEX device_enabled_idx ON device (enabled) WHERE enabled = true;
CREATE INDEX device_last_seen_at_idx ON device (last_seen_at);

CREATE TABLE device_group (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    provider_type VARCHAR(40),
    model_key TEXT,
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    notification_defaults JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_group_model_lock_check CHECK (
        (provider_type IS NULL AND model_key IS NULL)
        OR (provider_type IS NOT NULL AND model_key IS NOT NULL)
    )
);

CREATE INDEX device_group_provider_model_idx ON device_group (provider_type, model_key);
CREATE INDEX device_group_priority_idx ON device_group (priority DESC, id);
CREATE INDEX device_group_enabled_idx ON device_group (enabled) WHERE enabled = true;

CREATE TABLE device_group_membership (
    device_id BIGINT NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES device_group(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, group_id)
);

CREATE INDEX device_group_membership_group_idx ON device_group_membership (group_id);
