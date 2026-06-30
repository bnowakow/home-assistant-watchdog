-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_check CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT app_user_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX app_user_email_idx ON app_user (email);
CREATE INDEX app_user_role_idx ON app_user (role);
CREATE INDEX app_user_status_idx ON app_user (status);

CREATE TABLE notification_preference (
    app_user_id BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL DEFAULT 'PUSHOVER',
    pushover_user_key_encrypted TEXT,
    pushover_user_key_suffix VARCHAR(12),
    pushover_device VARCHAR(128),
    notify_recovery_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notification_preference_provider_check CHECK (provider IN ('PUSHOVER')),
    CONSTRAINT notification_preference_pushover_key_pair_check CHECK (
        (pushover_user_key_encrypted IS NULL AND pushover_user_key_suffix IS NULL)
        OR (pushover_user_key_encrypted IS NOT NULL AND pushover_user_key_suffix IS NOT NULL)
    )
);

CREATE INDEX notification_preference_recovery_idx
    ON notification_preference (notify_recovery_enabled)
    WHERE notify_recovery_enabled = true;
