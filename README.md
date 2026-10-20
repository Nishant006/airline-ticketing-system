# Airline Ticketing System

Single-airline backend implementing reference airports/aircraft, schedule creation,
rolling flight instances, search, live seat maps, atomic multi-seat booking and
cancellation. PostgreSQL, not application-local locks, arbitrates seat ownership.

Design: [HLD](hld/architecture.md), [HLD PDF](hld/architecture.pdf),
[architecture diagram](hld/diagrams/architecture.svg), [LLD](lld/design.md),
[schema](db/schema.sql), [ER diagram](db/er-diagram.md).

## Setup instructions

Use JDK 21 and a running Docker daemon with Docker Compose v2. Maven is supplied by
`./mvnw`; the first build needs network access to dependency and container registries.
Docker alone is sufficient for the Compose run path. The optional smoke walkthrough
also needs Bash, curl and Python 3 (standard library only).

```bash
java -version
docker info
docker compose version
```

On macOS with multiple JDKs, select Java 21 for Maven:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
```

## Dependencies

| Dependency | Purpose |
|---|---|
| Java 21 / Spring Boot 4.1.1 | Stateless MVC application, configuration, lifecycle and dependency injection |
| Spring Data JPA / Hibernate | Repositories, DTO projections, explicit fetch plans and managed transactions |
| PostgreSQL 16 / JDBC driver | Durable state, partial unique active-seat index and race-safe native upsert |
| Flyway / PostgreSQL module | Versioned schema and reference-data initialization; Hibernate only validates |
| Jakarta Validation / Hibernate Validator | Structural request validation, including unique operation weekdays |
| Lombok | Constructor injection, entity getters and logging without blanket setters |
| springdoc-openapi 3.1.1 | OpenAPI and Swagger UI |
| Actuator | Database-aware readiness and independent process liveness |
| JUnit / Spring Test / Testcontainers | Unit, HTTP and real-PostgreSQL behavior/concurrency tests |
| ArchUnit / Spotless / JaCoCo | Layer rules, formatting and coverage reporting |

Versions are pinned directly or managed by the Spring Boot BOM in `pom.xml`.
No cache, messaging broker, distributed lock service or cloud account is required.

## Build

```bash
./mvnw clean verify
```

This runs formatting, unit/architecture checks and PostgreSQL integration tests.
Produces `target/airline-reservation-0.0.1-SNAPSHOT.jar`.

```bash
./mvnw clean verify -DskipITs  # unit/architecture/formatting checks without Docker
./mvnw spotless:apply        # apply the repository formatter
```

## Run

### Docker Compose

```bash
docker compose up --build -d --wait
bash tests/smoke.sh http://localhost:8080
```

Flyway initializes the database automatically. The walkthrough creates a uniquely
numbered schedule, searches, books, proves conflict, cancels and rebooks. It cancels
its final reservation but intentionally leaves the schedule and cancelled history.
The image build skips tests; run `./mvnw clean verify` separately.

| URL | Purpose |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Interactive API documentation |
| `http://localhost:8080/v3/api-docs` | OpenAPI document |
| `http://localhost:8080/actuator/health/readiness` | Whether the instance can serve requests; includes database health |
| `http://localhost:8080/actuator/health/liveness` | Process liveness; does not fail merely because the database is down |

If default ports are occupied, supply both ends of the application port range:

```bash
APP_PORT=18880 APP_PORT_MAX=18882 POSTGRES_PORT=15442 \
  docker compose -p airline-review up --build -d --wait
bash tests/smoke.sh http://localhost:18880
```

An optional multi-instance run uses the same PostgreSQL primary:

```bash
docker compose up --build -d --wait --scale app=3
docker compose ps
```

Each JVM gets a port from 8080-8082; no load balancer is needed for the review.
Increase `APP_PORT_MAX` before starting more than three local replicas.
The default is one replica. Pool limits must be budgeted across all replicas:
each JVM permits 10 write plus 5 read connections (15 total, 45 for three replicas),
and this local PostgreSQL permits 100 total.
That arithmetic leaves headroom; it is not a throughput guarantee.

For on-demand deployment, add identical instances behind a readiness-aware load
balancer; no sticky sessions or transfer of JVM-local reservation state is needed.
Compose demonstrates manual replica scaling, not a shipped load balancer or
autoscaler. Cloud/orchestrator provisioning is outside this assignment's scope.

Bound scaling by the shared database, including old/new replicas during a rollout:

```text
max_simultaneous_replicas * (read_pool_max + write_pool_max)
    + other_connections + operational_reserve <= postgres_max_connections
```

The user accepted stateless scaling without a fixed peak-RPS requirement. Additional
app replicas do not eliminate database I/O limits or contention for the same seat;
scale only within database headroom rather than assuming unlimited capacity.

Stop with `docker compose down`. Only when intentionally discarding local data,
use `docker compose down -v`. For a named project or custom ports, use the same
project name and environment settings for subsequent Compose commands.

### Local JVM

```bash
docker compose up -d --wait postgres
./mvnw spring-boot:run
# Or, after building:
java -jar target/airline-reservation-0.0.1-SNAPSHOT.jar
```

Defaults connect to `localhost:5432/airline`, user/password `airline`.
Override `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD` and `SERVER_PORT` as needed. These credentials are
local-development defaults, not production deployment settings.

### Database capacity and read deadlines

Spring's `LazyConnectionDataSourceProxy` routes read-only search/seat-map transactions
to a separate pool on the same PostgreSQL primary, not a lagging replica. Ordinary
transactions, booking lookups/writes, Flyway and nontransactional JDBC use the write
pool. Booking never needs two simultaneous connections.

| Property | Default |
|---|---|
| `spring.datasource.hikari.maximum-pool-size` | 10 write connections |
| `spring.datasource.hikari.connection-timeout` | 20000 ms |
| `airline.datasource.read.maximum-pool-size` | 5 read connections |
| `airline.datasource.read.connection-timeout-ms` | 250 ms |
| `airline.datasource.read.statement-timeout-ms` | 500 ms per SQL statement |
| `airline.datasource.read.lock-timeout-ms` | 250 ms per lock wait |

Override these using Spring configuration/environment variables. Read acquisition
must be at least Hikari's 250 ms minimum; other read limits must be positive.
Read saturation, SQL cancellation and lock timeouts return explicit 503 responses,
not empty results. Write waits are intentionally separate. These database budgets
do not bound network, cold connection establishment, HTTP queueing or scheduling
and are not a hard end-to-end one-second guarantee.

## Database initialization

`db/migrations/` is the only executable schema source. Maven packages it into
`classpath:db/migration`; Flyway runs on startup in both jar and container modes.
Hibernate uses `ddl-auto=validate` and OSIV is disabled.

| Migration | Contents |
|---|---|
| `V1__baseline_schema.sql` | Seven tables, identity keys, foreign keys, checks and query/uniqueness indexes |
| `V2__seed_reference_data.sql` | Airports DXB/LHR/BOM/SIN; aircraft A320-001/B738-001/AT72-001 |

A320-001 has 30 rows of ABCDEF; B738-001 has 32 rows of ABCDEF; AT72-001 has
18 rows of ABCD. No schedules or bookings are hidden in initialization. Create a
schedule through the API or run the walkthrough. Airport/aircraft CRUD is absent.

`db/schema.sql` is a generated schema-only reading/restoration artifact, not a
second startup initializer; reference data remains in V2. See [database notes](db/README.md).

## Testing

```bash
./mvnw test                  # unit, MVC slice, architecture and source conventions
./mvnw verify                # also PostgreSQL/HTTP/contention/outage integration tests
bash tests/smoke.sh          # walkthrough against an already running localhost:8080
```

Automated tests use Maven's `src/test/java` layout, rather than the illustrative
top-level `tests/` path. That directory holds the [IntelliJ HTTP collection and
walkthrough](tests/README.md). `*Test` uses Surefire; `*IT` uses Failsafe and Docker.
Coverage is reported at `target/site/jacoco/index.html`.

Coverage includes schema invariants, rollback injection, concurrent first-instance
creation, competing/overlapping/disjoint seat sets, cancellation races, rebooking,
independent application contexts, real database outage, privacy and request errors.
`SearchPerformanceIT` records real HTTP timings and Hibernate's emitted SQL/plan in
`target/search-performance.txt`; it is not a mocked repository benchmark.
`MixedLoadIT` shares the same dataset and records exact 25:1 HTTP traffic and
hot-seat contention in `target/mixed-load.txt`. `ReadIsolationIT` covers physical
pool exhaustion, booking independence, SQL/lock deadlines and recovery.

## API overview

All five operations are under `/api/v1`. Times are strict UTC `HH:mm`; dates are ISO
`YYYY-MM-DD`. Weekdays are `MONDAY` through `SUNDAY`.
Invalid times such as `24:00` are rejected rather than normalized. Success responses
are JSON; an incompatible `Accept` header is rejected with 406 before any write.

| Method | Path | Success |
|---|---|---|
| POST | `/admin/flight-schedules` | 201, schedule |
| GET | `/flights?originCode=DXB&destinationCode=LHR&date=YYYY-MM-DD` | 200, search array |
| GET | `/flights/{flightNumber}/seat-map?date=YYYY-MM-DD` | 200, complete seat map |
| POST | `/bookings` | 201, booking |
| POST | `/bookings/{bookingReference}/cancel` | 200, cancelled booking |

Schedule body:

```json
{
  "flightNumber": "XY101",
  "originCode": "DXB",
  "destinationCode": "LHR",
  "departureTime": "09:30",
  "arrivalTime": "13:45",
  "aircraftId": "A320-001",
  "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"]
}
```

Booking body, replacing the date placeholder with an operating date in the window:

```json
{
  "flightNumber": "XY101",
  "flightDate": "<YYYY-MM-DD>",
  "passengers": [{"name": "Example", "seat": "1A"}, {"name": "", "seat": "1B"}]
}
```

Booking/cancellation responses contain `bookingReference` (UUID), `flightNumber`,
`flightDate`, `passengerCount`, `assignedSeats` and `bookingStatus`.
Search returns only `flightNumber`, `departureTime`, `arrivalTime`, `availableSeats`;
empty matches are `[]` and sold-out flights remain with zero availability.
Seat maps contain `flightNumber`, `flightDate` and all `{label,status}` entries,
where status is `AVAILABLE` or `BOOKED`. Names are never exposed in seat maps.

Errors are RFC 9457 `application/problem+json` with a stable `errorCode`.
400 means malformed input; 404 means missing operating flight/booking;
409 means duplicate schedule, unavailable seat or lifecycle conflict;
422 means invalid references, nonexistent configured seat or out-of-window date.
Framework errors preserve 404/405/406/415. Temporary database failure returns 503
with `Retry-After: 1`; a write outcome might be unknown, **not safely retryable**.
Unexpected failures return sanitized 500 errors.

`X-Correlation-ID` is echoed/generated for every request. If supplied, it must be
one 1-64 character value using letters, digits, `.`, `_` or `-`; otherwise 400.
Logs carry instance/correlation identifiers, not names, bodies or SQL parameters.
Database and unexpected failures also log safe root-cause types, validated SQLSTATE/
constraint identifiers and an application source location, without raw exception messages.

## Design decisions

Use a stateless, layered modular monolith, not microservices. Java packages are
`controller`, `service`, `service.impl`, `repository`, `entity`, `model`,
`dto.request`, `dto.response`, `config`, `exception` and `validation`.
Services expose narrow interfaces with immutable application commands/results.
Controllers map transport DTOs; implementations map JPA entities internally and
never exchange them between services. `repository/` contains flat Spring Data
interfaces, without pass-through adapters or a separate JPA wrapper package.
This is conventional layered design, not a persistence-independent application
core: service implementations intentionally depend on JPA. Explicit types/imports
and focused methods are enforced.

Use JPA for standard persistence, an application-model projection for search, explicit entity
graphs for required associations and one PostgreSQL native instance upsert.
Flyway owns schema evolution. IDENTITY-generated seat inserts are ordered
individual statements: `saveAllAndFlush` is **not** claimed to batch them.

Use hybrid instance generation, live committed-state reads and the partial unique
active-seat index, avoiding counters/cache invalidation and process-local locks.
The transaction writer is a separate Spring bean so rollback completes before
conflict enrichment. [HLD](hld/architecture.md) and [LLD](lld/design.md) explain
alternatives, costs and extension boundaries.

At 500 schedules and 600,000 synthetic allocation rows, a local warm-cache HTTP
sample after remediation had eight-worker search p95 7.332 ms. A separate 25:1
mixed run at 16/64/192 clients had search p95 15.049/34.228/97.745 ms.
All 816 disjoint bookings succeeded; at 192 clients, one of 14,400 searches
returned an overload 503. No measured search response exceeded one second.
These short closed-loop samples are not sustained, cold-cache or production
capacity guarantees. Existing indexes
were used; no speculative cache/index was added. Bounded pagination/lazy loading
is a future API change only if measured search scale requires it. See the LLD for
the exact dataset and conditional batching/retention/pool optimizations.

Current automated acceptance checks every measured warm read-only search and every
search in the 16-client mixed baseline for 200 and latency under one second, not just
p95. The 64/192-client phases remain exploratory stress; overload 503s are reported
separately from successful-search latency. These are local engineering choices, not
an agreed peak-RPS contract. See [test guidance](tests/README.md).

## Search algorithm

### Flights are identified

The public key is `(flightNumber, flightDate)`; global flight-number uniqueness is
an explicit schedule identity assumption. Reads need no pre-existing instance row.

### Schedules are matched

Validate the rolling UTC date window, derive ISO weekday and match source,
destination and operation day in one repository query. No extra sorting/filter API.

### Flight instances are generated

Search logically exposes matching instances. A physical row is created only on
first booking, uniquely by `(schedule_id, flight_date)`. Re-evaluating the window
per request avoids a midnight scheduler or pre-generation backlog.

### Seat availability is computed

One aggregate JPQL projection subtracts active allocations from aircraft capacity.
Outer joins preserve unmaterialized and sold-out flights; the active predicate
stays in the join. Seat maps expand all configured labels and mark active ones.
Reads are committed snapshots, not holds; a later conflict informs callers with 409.

## Booking algorithm

### Seat validation

Require one or more name/seat pairs, canonical labels, no duplicate requested label,
and membership in the fixed aircraft layout. Names have only a nonnull structural
requirement, with no semantic/format/nonblank check. Availability is enforced at write.

### Seat locking strategy

PostgreSQL's `uq_booking_seat_active` uniquely indexes `(instance,seat)` only for
`BOOKED` allocations. Database index-conflict waiting arbitrates ownership; there
is no explicit `FOR UPDATE`, `@Version`, `synchronized` or distributed lock.

### Transaction flow

Validate/resolve, then commit a short instance lookup/upsert transaction, followed
by a separate atomic booking transaction. The coordinator uses `Propagation.NEVER`
and rejects an ambient transaction before persistence, preserving rollback-before-
conflict-read ordering when composed with other application code.
After `ON CONFLICT DO NOTHING`, a separate READ COMMITTED SELECT sees the winner.
No nested connection acquisition or catch-and-continue in an aborted transaction.

### Booking creation

Generate a standard UUID, insert the booking, then insert every selected allocation
in deterministic label order. Recheck date/departure policy inside the write unit.
Commit all or roll back all; return the six-field response only after commit.
A failed booking may leave an empty reusable instance, never a partial reservation.

### Concurrency handling

The active-seat constraint selects the winner across threads/JVMs. Translate only
that known constraint into 409 after rollback; do not automatically retry a lost
seat. Optional conflict labels are a fresh snapshot, not permanent ownership.
Ordering reduces deadlock risk, not a universal no-deadlock guarantee. Other
temporary failures explicitly return 503 rather than a false successful booking.

## Cancellation algorithm

### Booking lookup

Resolve the UUID with its flight instance/schedule. Missing references return 404.
Reject repeated cancellation and cancellation at/after UTC departure with 409.

### Seat release

Read assigned labels, then bulk-change allocations from `BOOKED` to `RELEASED`.
Retain historical rows. Commit removes the active-index ownership so rebooking is
possible and search/seat-map availability reflects the release.

### Transaction flow

One transaction performs lookup, conditional booking update and all-seat release.
Require the release count to match the allocation count; a mismatch/failure rolls
back both status and seats. No refund or external callback participates.

### Status updates

Conditionally update `CONFIRMED` to `CANCELLED` and set the UTC cancellation instant.
Affected-row count arbitrates concurrent cancellations: exactly one succeeds and
the rest receive 409. Release/rebooking races either see committed release or
truthfully conflict; they never create two active owners.

## Assumptions

Single airline; one booking belongs to one flight instance; each seat has at most
one **active** owner, while released historical allocations remain. Aircraft maps
and created schedules are immutable, with preloaded airports/aircraft.

All schedule times/dates use UTC; audit instants use `TIMESTAMPTZ`. No airport-local
conversion or arrival-date calculation is provided. Arrival may precede departure.
The agreed window is exactly 365 dates: `[today, today+365)`, not a calendar-year
interval; an injected UTC clock is evaluated per request, including leap years.

One immutable schedule per flight number, UUID references, departure cutoff and
409 for repeated/departed cancellation are explicit design/user policy choices.
There are no seat holds, safe write retries, idempotency keys or booking lookup API.
A lost response/connection can leave an unknown committed outcome; UUID generation
does not solve recovery for a caller that never received its reference.

## Out of scope

Airport/aircraft CRUD, schedule mutation, accounts, implemented authentication,
payments/refunds, pricing, notifications, connecting/multi-airline itineraries,
partial cancellation, custom sorting/filtering, queues and cloud infrastructure.
The administrative URL is **not authorization**: a real deployment must protect it
at a trusted boundary. A booking reference is not an identity system. No in-scope
flow needs an external black box, so no fabricated payment/auth stub is invoked.
