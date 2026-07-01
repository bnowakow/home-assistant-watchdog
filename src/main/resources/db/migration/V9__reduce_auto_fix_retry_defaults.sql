-- SPDX-License-Identifier: GPL-3.0-or-later
-- Copyright (C) 2026 https://bnowakowski.pl

ALTER TABLE device_group_rule
    ALTER COLUMN retry_count SET DEFAULT 1,
    ALTER COLUMN retry_delay_seconds SET DEFAULT 10;

UPDATE device_group_rule
SET retry_count = 1,
    retry_delay_seconds = 10,
    updated_at = now()
WHERE retry_count = 3
  AND retry_delay_seconds = 60;
