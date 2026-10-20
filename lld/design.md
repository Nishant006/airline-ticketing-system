# Low-level design

Implemented design: 2026-10-20. [HLD](../hld/architecture.md) explains the major tradeoffs;
the execution log records acceptance evidence and remaining submission gates.

## Packages and classes

| Package | Responsibilities |
|---|---|
| `controller` | HTTP transport for schedules, flights and bookings |
| `service` | Narrow use-case interfaces and shared date policy |
| `service.impl` | Business rules, transactions and internal JPA mapping |
| `model` | Immutable commands/results, booking status, flight snapshots and shared seat-layout rules |
| `repository` | Spring Data JPA interfaces, projected queries and the instance upsert |
| `entity` | Airport, aircraft, schedule, instance, booking and seat mappings |
| `dto.request` | Specific validated schedule and booking request records |
| `dto.response` | Specific schedule, flight search, seat map and booking response records |
| `config` | UTC Clock, validated booking/read-pool properties, lazy datasource routing, weekday conversion and correlation filter |
| `exception` | Business codes/exceptions, persistence-constraint inspection and RFC 9457 handling |
| `validation` | Shared canonical input formats |

The Java root is organized by layer, not by feature. `controller` owns HTTP transport, `service` owns business rules and
transactions, `repository` contains Spring Data JPA interfaces, `entity` contains ORM
mappings, and `dto.request`/`dto.response` contain distinct records. Configuration,
exceptions and input patterns have root-level packages. Constructor injection is generated
by Lombok. Explicit local types are required; there are no `var` declarations.

`ScheduleServiceImpl` uses `FlightScheduleRepository`, `AirportRepository` and
`AircraftRepository`. `FlightServiceImpl` projects `FlightAvailability` and delegates
operating-flight snapshots to `FlightLookupService`, whose implementation uses an
explicit aircraft entity graph. `FlightInstanceServiceImpl` performs a native upsert and
separate ID lookup. `BookingServiceImpl` validates and coordinates without a transaction;
`BookingTransactionServiceImpl` persists the booking and ordered `BookingSeat` entities.
These two services are separate for transaction proxy correctness, not naming ceremony.
`CancellationServiceImpl` owns cancellation and release in a single transaction using
`BookingRepository` and `BookingSeatRepository`. `FlightDatePolicy` centralizes the
UTC horizon and exact departure cutoff. All seven service contracts have narrow interfaces;
collaborators inject those interfaces, not implementation classes. `FlightDatePolicy`
remains a concrete shared policy rather than a ceremonial interface pair.

The user selected conventional flat Spring Data repositories over the intermediate
ports/JPA-adapter split. Implementations may depend on JPA, but no public service
contract exchanges entities or transport DTOs. This is layered architecture, not a
persistence-independent application core. There are no pass-through repository wrappers.

Entities are internal persistence models; controllers expose `CreateScheduleRequest`,
`ScheduleResponse`, `CreateBookingRequest`, `BookingResponse`, `FlightSearchResponse`
and `SeatMapResponse`. No generic DTO is shared across unrelated use cases.
Controllers convert transport requests to commands and application results to response
DTOs. Search's JPQL constructor projects `model.FlightAvailability`, never an API DTO.
Shared `SeatLayout` rules are reused by aircraft entities and immutable operating-flight
snapshots. Application models have no Spring, Jackson or JPA dependencies. OSIV is disabled.
IDENTITY-generated seat IDs imply individual ordered inserts, not JDBC batching.

## API contracts

The five operations and their JSON fields are defined in
[spec.md](../features/ATS-1/spec.md#api-contract) and exposed through OpenAPI.
Create schedule and create booking return 201; search, seat map and cancel return 200.
There are no schedule/booking retrieval endpoints or idempotency headers.
Success mappings explicitly produce JSON, so incompatible Accept headers cause 406
before controller invocation or mutation. Cancellation still allows an empty body.
Both clock fields use standard Jackson strict HH:mm parsing; 24:00 is rejected.

Schedule request: flight number, source/destination IATA codes, departure/arrival UTC
times, aircraft ID and a nonempty collection of distinct weekdays.
Booking request: flight number, UTC flight date and one or more `{name, seat}` pairs.
Booking response: reference UUID, number, date, passenger count, labels and status.
Seat-map response: number/date plus the complete configured list of `{label, status}`.

## Validation and domain rules

Jakarta Validation handles missing/malformed fields, including validated service inputs.
Hibernate Validator's `@UniqueElements` rejects repeated operation days. Names have only a structural
nonnull requirement; empty, Unicode and long names are allowed. Aircraft configuration
determines valid seats. Labels are uppercase, canonical and distinct within one request.

Application rules verify referenced airports/aircraft, different route endpoints,
operation weekday, `[today, today+365)` UTC horizon and departure cutoff. `Clock` is
injected and evaluated at use time, never captured as a startup date.

Database PK/FK/unique/check constraints protect persistence. A composite FK ties each
seat allocation to its booking's flight instance. Global flight-number uniqueness is a
documented simplifying identity assumption, not an additional hiring requirement.

## Error handling

RFC 9457 problem details carry a stable `errorCode` and response correlation header.
Malformed requests are 400; missing operating flight/booking is 404; duplicate schedule,
occupied seats and lifecycle conflicts are 409; outside-window dates, invalid referenced
data and absent aircraft seats are 422. Temporary database failure is 503, unexpected
failure is an explicit logged 500. Names and request bodies are never logged.

| HTTP | Stable codes |
|---|---|
| 400 | `VALIDATION_FAILED`, `SAME_ORIGIN_DESTINATION`, `DUPLICATE_SEAT` |
| 404 | `FLIGHT_NOT_FOUND`, `BOOKING_NOT_FOUND`, `REQUEST_NOT_FOUND` |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` |
| 409 | `DUPLICATE_SCHEDULE`, `SEAT_UNAVAILABLE`, `ALREADY_CANCELLED`, `FLIGHT_DEPARTED` |
| 422 | `UNKNOWN_REFERENCE`, `DATE_OUT_OF_WINDOW`, `SEAT_NOT_ON_AIRCRAFT` |
| 503 / 500 | `SERVICE_UNAVAILABLE` / `INTERNAL_ERROR` |

503 includes `Retry-After: 1`, but writes are not idempotent and their outcome can be
unknown after a connection/commit failure. This is not permission to blindly retry.
Hibernate-wrapped JDBC failures also pass through Spring's SQL exception translator:
PostgreSQL shutdown 57P01 is 503; unclassified ORM failures remain explicit 500.
Framework validation errors expose field names/messages, never rejected values.

`RequestCorrelationFilter` accepts exactly one 1-64 character `X-Correlation-ID`
using `[A-Za-z0-9._-]`, or generates a UUID. Malformed/repeated headers get a generated
ID and the existing handler's 400 problem response. MDC includes a bounded instance
identifier and is cleared in `finally`; the response always echoes the correlation ID.
Debug request logs include method, status and elapsed time, not URLs/bodies/names.
ORM JDBC exception logging is suppressed because constraint messages can embed
passenger values. Database and unexpected-failure logs retain exception/root-cause
types, validated SQLSTATE/constraint identifiers and the deepest available application
source location. Missing or malformed database metadata is shown as `-`. Raw exception
messages, SQL text and request values are never passed to the logger; diagnostic fields
are not added to API responses. Cause traversal detects cycles.

Only the active-seat constraint becomes `SEAT_UNAVAILABLE`; unrelated integrity failures
must not be disguised as seat conflicts. Conflict enrichment runs after rollback and
describes a current snapshot, which may have changed again since the failed insert.

## Transaction boundaries

| Component | Boundary |
|---|---|
| `ScheduleService` | One transaction for parent and weekdays; explicit flush surfaces write failures |
| `FlightService` | Read-only queries; no materialization |
| `FlightInstanceService` | Short transaction: lookup; if absent, DO NOTHING insert then separate SELECT; commits before booking starts |
| `BookingServiceImpl` | Propagation.NEVER rejects an ambient transaction before persistence; otherwise calls instance/booking proxies sequentially |
| `BookingTransactionService` | One transaction for booking plus deterministically ordered seat inserts |
| `CancellationService` | One transaction for lookup, departure/status guards, conditional booking update and release |

READ COMMITTED is pinned in the datasource. Runtime failures roll back the write unit.
No nested REQUIRES_NEW, no manual commits, no catch-and-continue in an aborted transaction.
An unused instance left by a failed booking is harmless.

### Read/write pool isolation

`DataSourceConfiguration` exposes two physical Hikari pools and a primary Spring
`LazyConnectionDataSourceProxy`. It honors Boot `JdbcConnectionDetails` (including
Testcontainers service connections), with `DataSourceProperties` for ordinary startup.
The write pool retains `spring.datasource.hikari` settings: max 10/acquisition 20000 ms.
Read-only transactions route to max 5/minIdle 1, acquisition 250 ms; initialization
sets UTC, PostgreSQL `statement_timeout=500` and `lock_timeout=250` (milliseconds).
Positive limits and Hikari's minimum 250 ms acquisition are configuration-validated.
Both pools use READ COMMITTED
and the same primary; this is not an asynchronous read replica.

`FlightLookupServiceImpl` deliberately defaults to a writable REQUIRED transaction so
standalone booking validation/conflict snapshots use write capacity. Inside the existing
read-only seat-map transaction, it joins that transaction and routes to the read pool.
Nontransactional JDBC and Flyway use write capacity. The total default connection budget
is 15 per replica/45 for three. No request acquires nested REQUIRES_NEW connections.
Busy/cancelled reads return 503, never empty results. Deadlines cancel actual PostgreSQL
work, not an asynchronous wrapper that leaves queries running. These are database
budgets, not bounds on network, cold connection establishment or HTTP queueing.

## Search, booking and cancellation algorithms

Search derives ISO weekday, filters route/day and LEFT JOINs instances/active seats.
Capacity minus active count returns availability, including zero. The active predicate
belongs in the join. Seat map expands configured labels and marks the active set.

Booking validates first, obtains the unique instance ID, inserts a UUID booking and its
ordered allocations, then commits atomically. The partial unique index arbitrates
competing owners. No pre-check is claimed to prevent a concurrent insert.

Cancellation reads by UUID, checks guards, conditionally updates CONFIRMED to CANCELLED
and releases all seats before commit. Concurrent duplicate cancellation has one winner;
the loser receives 409. Cancellation after UTC departure is rejected by user policy.
The historical label count must equal the bulk release count and be nonzero, otherwise
the transaction fails and rolls back. Bulk JPQL bypasses the persistence context:
the response explicitly uses CANCELLED and no stale managed booking is modified again.
The fetched associations and scalar label query avoid per-passenger entity loading.

## Testing design

Unit tests cover aircraft entity logic, DTO validation and fixed-clock horizon rules. MVC tests cover
JSON validation/status/error contracts. PostgreSQL integration tests exercise the actual
migrations, constraints, atomic rollback and concurrency. Architecture tests prohibit
controller access to repositories/entities and reverse persistence dependencies on
services/controllers. DTOs cannot depend on service classes or persistence APIs.
Request and response packages cannot depend on each other, and DTOs cannot reference
JPA entity classes. Booking-window configuration is validated to exactly 365 dates.
Additional rules require service interfaces and independent application models,
forbid API DTOs in repositories/services, and prevent injection of service implementations.
`ReadIsolationIT` borrows physical pools (not lazy handles): exhausted reads yield 503
while booking succeeds; exhausted writes leave search/maps working; table locks and
pg_sleep trigger deadlines, then rollback/reuse restores successful searches.

Concurrency scenarios include same seat/set, overlapping/disjoint sets, first-instance
races, concurrent cancellation, release/rebooking and separate app instances sharing a
database. Connection pools and worker counts must not accidentally serialize the tests.
Use real commits and isolate fixture cleanup outside concurrent work.

`CrossInstanceIT` uses independent web application contexts/pools against one database
and tests actual DB unavailability. An isolated Compose acceptance run additionally
used three separate JVMs: one 201/two 409s for one seat, followed by cancellation and
rebooking through other replicas. The schema-only dump was restored into an empty
database, and `tests/smoke.sh` exercised the built image via public HTTP.

Measure complete search HTTP requests; do not substitute an annotation or forced
EXPLAIN plan for the clarified latency target.

## Query audit and measured performance

Measured after review remediation: macOS arm64, Java 21.0.5, 16 visible processors,
PostgreSQL 16.15 in Docker and one application with separate read-5/write-10 pools. Dataset:
500 daily schedules (100 matching the searched route), 30 dates each, 15,000 instances,
30,000 bookings, 300,000 active and 300,000 released allocations. Allocation volume
is a disclosed synthetic assumption, not derived from DAU.

After 10 warmups, 100 sequential HTTP searches had p50/p95/max of
2.267/2.702/3.215 ms. Another 200 HTTP searches at eight workers had
4.324/7.332/10.835 ms. Each returned all 100 results and executed one statement with
zero entity hydration. These local warm-cache, read-only measurements are not a
production/mixed-workload or cold-cache guarantee.

`EXPLAIN (ANALYZE, BUFFERS)` of Hibernate's actual emitted query reported 1.106 ms
execution and used `ix_schedule_route`, `uq_instance`, and `uq_booking_seat_active`.
A sequential scan of the 3,500-row operating-day table was legitimate. No index hints,
disabled sequential scans, duplicate index, cache or query rewrite was needed.
`SearchPerformanceIT` reproduces the dataset and writes SQL, timings and full plan to
`target/search-performance.txt`.

`MixedLoadIT` shares `PerformanceDataset` rather than duplicating seed SQL. Each client
performs three cycles of 25 searches then one disjoint single-seat booking over real HTTP.
The same JVM hosts the application and client; PostgreSQL runs in Docker, with JaCoCo
enabled and warm caches. Results from the final remediation run:

| Clients | Searches | Bookings (all 201) | Search p95 ms | Booking p95 ms | Search 503 | Phase seconds |
|---|---|---|---|---|---|---|
| 16 | 1200 | 48 | 15.049 | 166.547 | 0 | 0.879 |
| 64 | 4800 | 192 | 34.228 | 9.918 | 0 | 2.268 |
| 192 | 14400 | 576 | 97.745 | 4.835 | 1 | 6.120 |

Search percentiles in this historical table include both successful and shed responses;
no response exceeded one second. The one 503 is counted, not treated as a successful search. All 816 disjoint
bookings own their intended seats; 64 same-seat contenders yield one 201/63x409 and
the database has zero duplicate active allocations. `target/mixed-load.txt` contains
the report. These short closed-loop phases do not establish sustained/open-loop
production capacity, a cold-cache guarantee or a DAU-to-QPS conversion.

Current reports separate all-response (`searchAll`) and successful-only (`search200`)
latencies. The local baseline and exploratory-stress acceptance rules are documented
in [test guidance](../tests/README.md).

Current query costs: search is one aggregate statement; seat map is two statements
(schedule/aircraft then active labels), with work proportional to emitted labels.
Existing-instance resolution is one scalar lookup; first resolution adds an upsert and
fresh lookup. Booking uses ordered individual inserts because IDs are generated with
IDENTITY; `saveAllAndFlush` is not batching. No per-result or per-seat read query exists.
Cancellation is four statements: fetched lookup, scalar labels, conditional booking
update and bulk release. Schedule creation performs bounded reference lookups followed
by one parent and at most seven weekday inserts; it does not load whole reference tables.

## Modularity and scaling decisions

Controllers own HTTP concerns; services own use cases; repositories own query behavior;
entities delegate aircraft layout to shared `SeatLayout` and do not expose mutable weekdays.
Booking validation, persistence/conflict translation and schedule construction have
focused methods. Distinct request/response records and architecture checks guard layer
boundaries. The writer's separate Spring proxy is required for rollback-before-
conflict-read semantics; its interface expresses that capability, not generic CRUD.

At the clarified scale, retain the present indexes and live reads. If measurements
later show write round-trip costs dominate, consider pooled sequence IDs with verified
Hibernate batching in a new migration; do not assume an ORM setting batches IDENTITY
inserts. As historical data grows, measure active-index size, vacuum behavior and retention
before introducing partitioning. Add bounded pagination/lazy loading only if measured
search volume requires an agreed API change. Read/write isolation is implemented with
an explicit connection budget; further pool increases require database-capacity evidence.
A future cache requires an agreed staleness policy and must never arbitrate seat ownership.

Additional replicas can share the PostgreSQL invariant without process-local locks.
This does not imply unlimited throughput: total pool demand, database I/O, index
contention and admission control remain capacity constraints. Do not add distributed
services, queues or generic extension frameworks merely to claim scalability.
