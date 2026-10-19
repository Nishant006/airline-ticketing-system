# Database

PostgreSQL 16 is the production and integration-test engine. Flyway alone creates and
versions the schema. Spring Data JPA handles application persistence; Hibernate validates
the existing schema with `ddl-auto=validate`, never creates or updates it. Native SQL is
limited to the PostgreSQL instance upsert. JDBC is used in test fixtures to check the
database independently of ORM mappings.

`migrations/` is the executable source of truth. Maven packages this directory as
`classpath:db/migration`, so the same migrations work in a checkout, executable jar,
Docker image and Testcontainers tests.

| Migration | Contents |
|---|---|
| `V1__baseline_schema.sql` | Seven domain tables, PK/FK/check/unique constraints, active-seat partial unique index and query indexes |
| `V2__seed_reference_data.sql` | Four airports and three fixed aircraft configurations; no precreated schedules/bookings |

Create a schedule through the administrative API before searching. Airport and aircraft
CRUD are intentionally absent. Applied migrations are immutable; evolve them with new
versioned scripts. Multiple startup instances rely on Flyway's database migration lock.

`schema.sql` is a generated schema-only snapshot for reading, not a second initialization
mechanism. The ER diagram documents actual relationships. Do not duplicate migrations
under `src/main/resources`.

Regenerate the snapshot from a freshly migrated Compose database:

```bash
docker compose exec -T postgres pg_dump -U airline -d airline \
  --schema-only --no-owner --no-privileges \
  --exclude-table=flyway_schema_history > db/schema.sql
```

The PostgreSQL 16.15 dump contains psql `\restrict`/`\unrestrict` directives; restore
using a compatible psql client, not a JDBC SQL-script runner. The snapshot excludes
seed data and was restored successfully into an empty PostgreSQL database. Do not
restore it over a database already initialized by Flyway.
