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

- **[DEPLOYMENT.md](DEPLOYMENT.md)** - phase-14 runbook for laptop verification, observe-only
  provider checks, limited auto-fix rollout, Pushover enablement, history review, and server
  deployment.
- Devices can be bulk-imported in the application from **Devices** using **Import discovered
  devices**. Leave the provider empty to import from all registered providers, or choose a single
  provider such as Zigbee2MQTT or Home Assistant. Existing devices with the same provider/external
  id are skipped.
- Devices can be manually registered in the application from **Devices**. Choose the provider,
  enter the external provider id, provide model metadata, and set power source/criticality.
  Zigbee2MQTT devices require an IEEE address; when left blank, the UI uses the external id as
  the IEEE address. After creating a device, assign it to a homogeneous group from **Groups** and
  configure rules on that group.
- Home Assistant integration uses the REST API when `WATCHDOG_HOME_ASSISTANT_ENABLED=true`.
  Create a token in Home Assistant from the user profile page under **Long-lived access tokens**,
  name it for this watchdog app, and store it only in local/deployment environment as
  `WATCHDOG_HOME_ASSISTANT_TOKEN`. Prefer a JVM-trusted HTTPS certificate for
  `WATCHDOG_HOME_ASSISTANT_BASE_URL`; if a local certificate is not trusted, import the issuing
  CA/certificate into the JVM truststore. As a local escape hatch, set
  `WATCHDOG_HOME_ASSISTANT_SKIP_CERTIFICATE_CHECKS=true` to skip Home Assistant certificate
  validation; keep it disabled outside trusted local networks.
- Runtime scheduling can be disabled independently from the checker with
  `WATCHDOG_CHECK_SCHEDULED_ENABLED=false`. Set `WATCHDOG_CHECK_RUN_ON_STARTUP=true` to queue one
  scheduled check when the application is ready; `WATCHDOG_CHECK_RUN_TIMEOUT` bounds each run.
- Default fix cooldowns use `WATCHDOG_FIX_DEFAULT_COOLDOWN_SECONDS`. Rule-level cooldown,
  retry, and missing-property retry columns can override the deployment defaults.
- `make docker-pg-backup` dumps the Compose PostgreSQL database into
  `docker-data/backup/postgres/`.
- `make install-pg-backup-cron` installs a daily database backup cron job for the current user.
  Override `CRON_SCHEDULE` to change the schedule, for example:
  `make install-pg-backup-cron CRON_SCHEDULE="30 3 * * *"`.

## Repository Helpers

- `make codex-commit` stages changes, asks Codex for a concise commit message, commits, and can
  optionally push after showing the unpushed diff summary.
