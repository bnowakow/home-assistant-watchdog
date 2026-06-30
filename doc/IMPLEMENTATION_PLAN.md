# Home Assistant Watchdog - Implementation Plan

Bottom-up plan respecting compile-time dependencies. The first complete version should monitor
and fix Zigbee2MQTT devices through MQTT, while the core model remains provider-neutral so Home
Assistant entities and future APIs can be added without redesigning rules.

---

## Phase 1 - Project Foundation

1. Create Kotlin/Spring Boot project using Java 21 and Gradle Kotlin DSL.
2. Add Vaadin, Spring Data JDBC, PostgreSQL, Flyway, scheduling, validation, actuator, and test
   dependencies.
3. Add Spring Security/OAuth dependencies and configure Google OIDC UI authentication in the same
   server-side style as CoZaDzban.
4. Add `AGENTS.md` with project rules copied from the reference application:
   - run `make test` or `./gradlew test` after code changes
   - use Spring Data JDBC, not JPA/Hibernate
   - use Flyway for all DDL
   - keep timestamps in UTC
   - use parameterized SQL only
5. Add `.env.sample`, `compose.yaml`, `Dockerfile`, `.dockerignore`, and `Makefile`.
6. Configure PostgreSQL service with a mounted data volume for laptop and later server use.
7. Add role model with ADMIN and USER, but only define ADMIN permissions for the first version.

---

## Phase 2 - Database Migrations

8. Add `V1__create_devices_and_groups.sql`:
   - `device`
   - `device.model_key` and optional `device.model_name`
   - `device_group`
   - `device_group.provider_type` and `device_group.model_key`
   - `device_group_membership`
9. Add `V2__create_group_rules.sql`:
   - `device_group_rule`
   - rule severity/check mode/fix mode/notification flags
   - retry count/delay settings
10. Add `V3__create_check_history.sql`:
   - `check_run`
   - `device_check_result`
   - `rule_check_result`
   - `fix_attempt`
11. Add `V4__create_notifications.sql`:
   - `notification_channel`
   - `notification_event`
12. Add `V5__create_parameter_history.sql`:
    - `device_parameter_history`
    - latest-value lookup indexes
    - change-history lookup indexes
13. Add user/role/auth tables following the CoZaDzban approach if needed for allowlisted ADMIN users.
14. Add indexes for IEEE address, provider identity, group priority, check timestamps, and
    notification deduplication keys.

---

## Phase 3 - Domain Model And Persistence

15. Create Spring Data JDBC aggregate classes for devices, groups, rules, check runs, results,
    fix attempts, and notifications.
16. Store flexible provider payloads and desired values as `JSONB` where the shape depends on
    the provider.
17. Add repositories and custom query components using `NamedParameterJdbcTemplate` with named
    parameters.
18. Add service-level validation for provider identity uniqueness:
    - Zigbee2MQTT devices by IEEE address
    - Home Assistant devices by entity id or device id
    - future providers by provider-specific external id
19. Add service-level validation for homogeneous groups:
    - the first member locks the group's provider type and model key
    - every later member must match the locked provider type and model key
    - group rules may rely on attributes exposed by that single model

---

## Phase 4 - Provider Abstraction

20. Define `DeviceProvider` interface:
   - provider type
   - discovery
   - snapshot read
   - desired-state application
   - supported property metadata
   - stable model key extraction
21. Define provider-neutral models:
   - `DiscoveredDevice`
   - `DeviceSnapshot`
   - `DevicePropertyRef`
   - `PropertyMetadata`
   - `FixAttemptResult`
22. Ensure the checker depends only on the provider interface, not on MQTT or Home Assistant
    implementation details.

---

## Phase 5 - Zigbee2MQTT Provider

23. Add MQTT client configuration:
   - broker URI, default `tcp://core-mosquitto:1883`
   - username/password
   - username default/example `addons`
   - password from `.env` only, never committed
   - Zigbee2MQTT base topic, default `zigbee2mqtt-2`
   - reconnect settings
24. Subscribe to:
   - `zigbee2mqtt-2/bridge/devices`
   - `zigbee2mqtt-2/bridge/state`
   - `zigbee2mqtt-2/+/availability`
   - `zigbee2mqtt-2/+`
25. Maintain an in-memory state cache with latest payload, availability, last received time,
    battery level, and exposed property metadata.
26. Discover/update devices from `bridge/devices`, matching by IEEE address.
27. Store Zigbee2MQTT model key/model name from discovered device definition metadata.
28. Add bridge/provider health detection so Zigbee2MQTT add-on/USB-stick failures are reported
    separately from individual device failures.
29. Publish fixes to `zigbee2mqtt-2/<friendly_name>/set`.
30. Record whether a fix was confirmed by a later state update.

---

## Phase 6 - Effective Rule Resolver

31. Load all enabled groups for each enabled device.
32. Load enabled rules from those groups.
33. Filter rules by provider type and device capabilities.
34. Detect conflicting effective rules when multiple groups define the same provider/property path.
    Mark those rules as configuration errors and skip them until the user removes the duplicate
    rule from one group.
35. Produce an "effective rules" view for each device so inherited group behavior is visible in
    the UI.
36. Leave a clean extension point for later per-device overrides.

---

## Phase 7 - Scheduled Checker

37. Add scheduled check runner with configurable interval.
38. Add manual "run check now" service method.
39. For each device:
   - read snapshot from the provider
   - evaluate availability/freshness
   - evaluate battery thresholds
   - evaluate desired property rules
   - create check result records
40. Treat mains and battery devices differently:
   - mains devices default stale threshold: 2 hours
   - battery devices default stale threshold: 24 hours
41. Treat critical devices with stricter stale/offline thresholds, default 1 hour.
42. Prefer active reads/checks when the provider can perform them safely.
43. Treat Home Assistant `unavailable` state as unavailable.
44. If a checked property is missing from a provider snapshot, retry active reads a configurable
    number of times. If the property is still missing after retries, mark the rule result as
    incorrect/error.

---

## Phase 8 - Parameter History

45. Add a parameter history writer that receives the subset of snapshot properties referenced by
    effective rules.
46. Store history only when the value changes by default, not on every check.
47. Keep the latest value per `(device_id, provider_type, property_path, endpoint)` easy to query
    through indexes.
48. Store compact JSON values plus optional numeric/text projected columns for efficient filtering.
49. Add configurable retention:
    - detailed change history retention, default 12 months
    - optional unchanged sample retention, default disabled
    - optional daily rollup for numeric values such as battery level
50. Prepare the model/configuration for longer critical-device retention later, but do not implement
    differentiated retention in v1.
51. Add UI history charts/tables on the device detail page for parameters used by rules.

---

## Phase 9 - Auto-Fixer

52. Apply fixes only for rules with auto-fix enabled.
53. Apply fixes only when the provider marks the property as writable or the rule is explicitly
    allowed to use a manual property path.
54. Record every fix attempt with requested value, provider response, and confirmation status.
55. Avoid unsafe "probe" commands that could change device behavior unless explicitly configured.
56. Add retries with configurable retry count and retry delay per rule, defaulting from application
    configuration.
57. Add cooldowns so repeatedly failing devices are not hammered with commands.

---

## Phase 10 - Pushover Notifications

58. Add Pushover configuration:
   - app token from environment
   - per-user Pushover user key stored in database and encrypted at rest, following CoZaDzban
   - optional per-user Pushover device targets
59. Add notification policy at group rule level:
   - notify on mismatch
   - notify on fix success
   - notify on fix failure
   - notify on low battery
   - notify on offline/stale
   - notify on recovery
60. Add notification deduplication and cooldown by device/rule/problem type.
61. Default notification cooldown to 24 hours.
62. Enable recovery notifications by default.
63. Ensure critical thermostat problems can be configured as high-priority Pushover alerts.

---

## Phase 11 - Vaadin UI

51. Create application shell with navigation:
   - Dashboard
   - Devices
   - Groups
   - Checks
   - Notifications
   - Settings
52. Dashboard:
   - healthy/degraded/offline summary
   - critical alerts first
   - last check status
   - manual run button
53. Devices view:
   - list provider, name, external id, power source, availability, battery, last seen
   - enable/disable monitoring
   - edit criticality
54. Device detail:
   - current provider snapshot
   - group memberships
   - effective rules
   - recent check/fix/notification history
   - parameter history for rule-checked properties
55. Groups view:
   - create/edit groups
   - assign devices to groups
   - validate that every group member has the same provider type and model key
   - show the locked model/provider for non-empty groups
   - set group priority
   - configure notification defaults
56. Group rules editor:
   - choose provider type from the group's locked provider
   - choose discovered property from any group member because all members share one model
   - show common model attributes by default
   - allow manual property path for advanced devices
   - set desired value/comparison
   - set auto-fix and notification behavior

---

## Phase 12 - Home Assistant Provider

70. Add Home Assistant configuration:
   - base URL, e.g. `https://home-assistant.localdomain.bnowakowski.pl:8123`
   - long-lived access token
   - TLS settings if local certificates need special handling
71. Document token creation:
    - Home Assistant user profile
    - Long-lived access tokens
    - create token for watchdog
    - store in `.env` as `WATCHDOG_HOME_ASSISTANT_TOKEN`
72. Prefer trusted HTTPS certificate validation. If local cert validation fails, import the local
    CA/certificate into a JVM truststore before adding any explicit development-only insecure mode.
73. Implement REST reads:
   - `/api/states`
   - `/api/states/{entity_id}`
74. Implement state/attribute snapshots for non-Zigbee entities.
75. Use Home Assistant entity id as the initial stable identity, while keeping provider identity
    abstractions open for HA device registry ids and direct Shelly provider ids later.
76. Implement service-call fixes through `/api/services/{domain}/{service}` for selected safe
    actions such as `light.turn_on`, `switch.turn_on`, or configured custom service calls.
77. Add Home Assistant entity discovery and mapping into the same `device` table.
78. Add UI support for Home Assistant property paths such as `state` and
    `attributes.battery_level`.

---

## Phase 13 - Testing And Hardening

63. Add unit tests for:
   - group rule conflict resolution
   - provider filtering
   - comparison operators
   - notification dedupe
   - parameter history change detection
64. Add integration tests for:
   - Flyway migrations
   - repositories
   - scheduled check persistence
65. Add provider tests with mocked MQTT/Home Assistant clients.
66. Add UI smoke tests for group/rule/device/history flows where practical.
67. Run `make test` before considering implementation tasks complete.

---

## Phase 14 - Deployment Path

68. [x] Run locally on laptop with Dockerized PostgreSQL and local JVM Spring Boot.
69. [x] Verify against the real MQTT broker and Home Assistant instance with observe-only rules.
70. [x] Enable auto-fix for a small non-critical group.
71. [x] Enable Pushover for critical devices.
72. [x] Review parameter-history growth after several days and tune retention before moving to server.
73. [x] Move to server with the same Compose-managed PostgreSQL volume model.

See [DEPLOYMENT.md](DEPLOYMENT.md) for the operational runbook and Makefile helpers.
