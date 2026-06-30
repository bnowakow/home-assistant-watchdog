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

- Home Assistant integration uses the REST API when `WATCHDOG_HOME_ASSISTANT_ENABLED=true`.
  Create a token in Home Assistant from the user profile page under **Long-lived access tokens**,
  name it for this watchdog app, and store it only in local/deployment environment as
  `WATCHDOG_HOME_ASSISTANT_TOKEN`. Prefer a JVM-trusted HTTPS certificate for
  `WATCHDOG_HOME_ASSISTANT_BASE_URL`; if a local certificate is not trusted, import the issuing
  CA/certificate into the JVM truststore before considering any development-only insecure TLS mode.
- `make docker-pg-backup` dumps the Compose PostgreSQL database into
  `docker-data/backup/postgres/`.
- `make install-pg-backup-cron` installs a daily database backup cron job for the current user.
  Override `CRON_SCHEDULE` to change the schedule, for example:
  `make install-pg-backup-cron CRON_SCHEDULE="30 3 * * *"`.

## Repository Helpers

- `make codex-commit` stages changes, asks Codex for a concise commit message, commits, and can
  optionally push after showing the unpushed diff summary.
