# Agent Instructions

## After every code change

Always run the test suite before considering the task complete:

```sh
make test
```

or equivalently:

```sh
./gradlew test
```

If tests fail, fix the failures before finishing.

## Coding rules

### Database

- All DB queries MUST use parameterized statements. Raw string concatenation into SQL is forbidden.
- Custom queries via `NamedParameterJdbcTemplate` MUST use named parameters such as `:param`.
- No DDL (`CREATE TABLE`, `ALTER TABLE`, etc.) outside Flyway migration scripts.
- Migration scripts live in `src/main/resources/db/migration/` and follow
  `V<version>__<snake_case_description>.sql`.
- Never edit an already-applied Flyway migration. Add a new migration instead.

### Persistence

- Persistence uses Spring Data JDBC, not JPA/Hibernate.
- Do not add `ddl-auto` settings.
- Store persisted timestamps in UTC.

### Security

- Vaadin UI authentication follows the CoZaDzban pattern: server-side OAuth/OIDC login and
  HttpOnly session cookies.
- Frontend JavaScript MUST NEVER receive OAuth access tokens or ID tokens.
- No OAuth token material may be stored in `localStorage` or `sessionStorage`.
- Use role constants/model values consistently. The initial role model is `ADMIN` and `USER`,
  with `ADMIN` behavior implemented first.

### Architecture

- Keep provider logic behind provider interfaces. The core rule/checking code should not depend
  directly on Zigbee2MQTT, Home Assistant, or future provider APIs.
- Groups are homogeneous: all devices in a group must have the same provider type and model key.
- Rules are configured on groups in the initial implementation.
