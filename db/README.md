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

Every column that a query filters or joins on has an index that leads with it. Three
foreign-key columns do not: `flight_schedule.destination_code` (`V1:24`, second column of
`ix_schedule_route`), `flight_schedule.aircraft_id` (`V1:27`) and
`booking.flight_instance_id` (`V1:52`; `uq_booking_id_instance` leads with `id`). Such an
index only helps the foreign-key check when a parent row is deleted or its key changes,
or a query that filters by that column. No API deletes airports, aircraft or flight
instances, and no query lists bookings per flight, so each index would only add write
cost today. Add it in a new migration when either changes.

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
