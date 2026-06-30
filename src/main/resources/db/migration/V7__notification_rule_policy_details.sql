-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

ALTER TABLE device_group_rule
    ADD COLUMN notify_on_low_battery BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN notify_on_offline_stale BOOLEAN NOT NULL DEFAULT false;

DROP INDEX device_group_rule_notifications_idx;

CREATE INDEX device_group_rule_notifications_idx
    ON device_group_rule (
        notify_on_mismatch,
        notify_on_fix_success,
        notify_on_fix_failure,
        notify_on_low_battery,
        notify_on_offline_stale,
        notify_on_recovery
    )
    WHERE notify_on_mismatch = true
       OR notify_on_fix_success = true
       OR notify_on_fix_failure = true
       OR notify_on_low_battery = true
       OR notify_on_offline_stale = true
       OR notify_on_recovery = true;
