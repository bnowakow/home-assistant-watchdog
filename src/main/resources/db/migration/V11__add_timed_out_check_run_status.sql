-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

ALTER TABLE check_run
    DROP CONSTRAINT check_run_status_check;

ALTER TABLE check_run
    ADD CONSTRAINT check_run_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'STALE'));
