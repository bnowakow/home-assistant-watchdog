# doc/

Project documentation, specifications, and implementation plans.

## Specifications

- **[SPEC.yaml](SPEC.yaml)** - source of truth for the Home Assistant Watchdog system.
  It captures architecture, provider abstractions, database shape, business rules,
  notification behavior, and UI requirements.

## Plans

- **[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** - phased implementation plan for
  building the application bottom-up while keeping Zigbee2MQTT as the first integration
  and leaving room for Home Assistant and future provider APIs.

## Operations

- `make docker-pg-backup` dumps the Compose PostgreSQL database into
  `docker-data/backup/postgres/`.
- `make install-pg-backup-cron` installs a daily database backup cron job for the current user.
  Override `CRON_SCHEDULE` to change the schedule, for example:
  `make install-pg-backup-cron CRON_SCHEDULE="30 3 * * *"`.

## Repository Helpers

- `make codex-commit` stages changes, asks Codex for a concise commit message, commits, and can
  optionally push after showing the unpushed diff summary.
