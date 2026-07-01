-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

ALTER TABLE device
    ADD COLUMN skip_checks BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE device_check_result
    DROP CONSTRAINT device_check_result_status_check;

ALTER TABLE device_check_result
    ADD CONSTRAINT device_check_result_status_check
        CHECK (status IN ('HEALTHY', 'DEGRADED', 'OFFLINE', 'UNKNOWN', 'SKIPPED'));
