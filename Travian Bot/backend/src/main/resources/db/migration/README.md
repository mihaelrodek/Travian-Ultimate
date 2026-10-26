# Flyway migrations

Versioned SQL migrations applied at startup.

## Generating the V1 baseline

The project currently runs with `hibernate.ddl-auto=update`, so the schema is
defined by JPA entities. To switch to Flyway-managed migrations:

1. In `application.yml`, uncomment the two `schema-generation` lines under
   `spring.jpa.properties`.
2. Start the backend once: `mvn spring-boot:run`. Hibernate writes
   `schema-dump.sql` next to the working directory.
3. Move it here as `V1__init.sql`. Strip any `drop` statements at the top.
4. Re-comment the schema-generation lines, switch `ddl-auto` to `validate`,
   and set `spring.flyway.enabled: true` — all in the same commit. Flyway is
   kept disabled until then so there's never a window where both Flyway and
   Hibernate mutate the schema.
5. From now on, schema changes are V2, V3, ... migrations only.

## Naming

`V{version}__{description}.sql` — e.g. `V2__add_account_proxy_index.sql`.
