-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

ALTER TABLE notification_preference
    ADD COLUMN notify_mismatch_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN notify_low_battery_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN notify_offline_stale_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN notify_fix_success_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN notify_fix_failure_enabled BOOLEAN NOT NULL DEFAULT true;

UPDATE notification_preference
SET notify_mismatch_enabled = true,
    notify_low_battery_enabled = true,
    notify_offline_stale_enabled = true,
    notify_fix_success_enabled = true,
    notify_fix_failure_enabled = true
WHERE provider = 'PUSHOVER';
