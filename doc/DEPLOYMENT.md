# Deployment Path

This is the phase-14 runbook: start on a laptop with Dockerized PostgreSQL and a local JVM, verify
real providers with observe-only rules, then move the same Compose-managed PostgreSQL volume model
to a server.

## Local Laptop

1. Copy `.env.sample` to `.env` and fill in local secrets.
2. Start only PostgreSQL:
   ```sh
   make docker-up
   ```
3. Run Spring Boot on the host JVM:
   ```sh
   make run-local
   ```
4. Check the app and readiness endpoint:
   ```sh
   make app-health
   ```

The local profile reads PostgreSQL through `localhost:${POSTGRES_PORT}` and serves Vaadin on
`${APP_PORT}`.

## Observe-Only Provider Verification

Use this stage before enabling any writes.

1. Keep `APP_NOTIFICATIONS_ENABLED=false` until notification recipients are configured.
2. Set provider access in `.env`:
   ```properties
   WATCHDOG_MQTT_ENABLED=true
   WATCHDOG_MQTT_BROKER_URI=tcp://core-mosquitto:1883
   WATCHDOG_MQTT_USERNAME=addons
   WATCHDOG_MQTT_PASSWORD=...
   WATCHDOG_ZIGBEE2MQTT_BASE_TOPIC=zigbee2mqtt-2
   WATCHDOG_HOME_ASSISTANT_ENABLED=true
   WATCHDOG_HOME_ASSISTANT_BASE_URL=https://home-assistant.localdomain.bnowakowski.pl:8123
   WATCHDOG_HOME_ASSISTANT_TOKEN=...
   WATCHDOG_HOME_ASSISTANT_SKIP_CERTIFICATE_CHECKS=false
   ```
3. Create groups and rules with `check_mode = OBSERVE_ONLY`.
4. Run several checks from the UI or wait for `WATCHDOG_CHECK_INTERVAL_SECONDS`.
5. Confirm device snapshots, check results, and parameter history before changing any rule to
   `AUTO_FIX`.

To keep manual checks available while pausing the scheduler, set
`WATCHDOG_CHECK_SCHEDULED_ENABLED=false`. To queue one scheduled check as soon as the application is
ready, set `WATCHDOG_CHECK_RUN_ON_STARTUP=true`. `WATCHDOG_CHECK_RUN_TIMEOUT` limits a single run so
a stuck provider call cannot keep the run open indefinitely.

## Limited Auto-Fix

Enable writes gradually.

1. Pick a small, non-critical group.
2. Change only the intended rules from `OBSERVE_ONLY` to `AUTO_FIX`.
3. Confirm each property is writable in the provider metadata or explicitly allowed as a manual
   path.
4. Watch the fix attempts on the device detail page and application logs:
   ```sh
   make docker-app-logs
   ```

Deployment defaults for fixes are `WATCHDOG_FIX_DEFAULT_RETRY_COUNT`,
`WATCHDOG_FIX_DEFAULT_RETRY_DELAY_SECONDS`, and `WATCHDOG_FIX_DEFAULT_COOLDOWN_SECONDS`. Group
rules can override retry and cooldown behavior per checked property.

## Critical Pushover Alerts

1. Set deployment-level notification secrets:
   ```properties
   APP_NOTIFICATIONS_ENABLED=true
   APP_NOTIFICATIONS_PUSHOVER_USER_KEY_ENCRYPTION_SECRET=<base64-32-byte-key>
   APP_NOTIFICATIONS_PUSHOVER_APP_TOKEN=...
   ```
   `APP_NOTIFICATIONS_PUSHOVER_USER_KEY_ENCRYPTION_SECRET` is the app's encryption secret for
   storing Pushover user keys at rest. It is not the Pushover "Your User Key" value.
2. Configure each user's Pushover "Your User Key" and optional device targets in Settings.
3. Enable notification flags only for the critical groups/rules that should alert.
4. Keep cooldowns conservative at first; the default is 24 hours.

## Parameter-History Review

After several days, inspect growth and tune retention before moving to a server:

```sh
make docker-pg-history-stats
```

If growth is too high, reduce `WATCHDOG_PARAMETER_HISTORY_RETENTION_DAYS` or keep
`WATCHDOG_PARAMETER_HISTORY_RECORD_UNCHANGED=false`.

## Server Move

1. Install Docker and Compose on the server.
2. Copy the repository, create a server-local `.env`, and keep secrets out of git.
3. Start the full app stack:
   ```sh
   make docker-app-up
   ```
4. Install daily PostgreSQL backups:
   ```sh
   make install-pg-backup-cron
   ```
5. Keep `./docker-data/postgres` as the mounted PostgreSQL volume and
   `./docker-data/backup/postgres` as the backup location.
6. Put a reverse proxy in front of `${APP_PORT}` if exposing the UI beyond localhost, enable
   `APP_SECURITY_ENABLED=true`, configure Google OAuth credentials, and set
   `SESSION_COOKIE_SECURE=true` when served over HTTPS.
