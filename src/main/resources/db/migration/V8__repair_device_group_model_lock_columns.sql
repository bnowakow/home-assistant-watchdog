-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

ALTER TABLE device_group
    ADD COLUMN IF NOT EXISTS provider_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS model_key TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'device_group_model_lock_check'
            AND conrelid = 'device_group'::regclass
    ) THEN
        ALTER TABLE device_group
            ADD CONSTRAINT device_group_model_lock_check CHECK (
                (provider_type IS NULL AND model_key IS NULL)
                OR (provider_type IS NOT NULL AND model_key IS NOT NULL)
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS device_group_provider_model_idx
    ON device_group (provider_type, model_key);
