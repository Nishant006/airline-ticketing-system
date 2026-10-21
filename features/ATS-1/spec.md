# ATS-1: implementation contract

Status: FR1-FR9 implemented; final acceptance gate recorded in the execution log.
Design corrected on 2026-10-19; clause audit on 2026-10-20.

## Authority and scope

`assignment-brief.md`, including the hiring team's supplementary instruction and
clarifications, is authoritative and must not be edited. This document records design
choices, not additional hiring requirements. It replaces the contradictory earlier draft.
The 2026-10-19 user decisions below supersede earlier execution-log entries.
On 2026-10-20 the user additionally accepted a stateless backend, delegated scaling
judgment and confirmed that a fixed peak request rate is unnecessary for this assignment.
This clarification does not add a peak-RPS guarantee or a production autoscaler.

Implement the nine functional requirements as vertical slices. Each slice includes its
domain rules, persistence, API where required, tests, and related documentation. Audit
and fix after every five completed implementation todos. Completion requires behavioral
evidence, not just a heading or a successful application context.

| FR | Required behavior | Implementation boundary |
|---|---|---|
| 1 | Preloaded airport code, name, city, country; no CRUD | Reference migration and database assertions |
| 2 | Preconfigured aircraft ID, type, fixed seat configuration; no CRUD | Reference migration, immutable aircraft model, layout tests |
| 3 | Administrative schedule creation with all seven fields | Atomic schedule and operating-day inserts |
| 4 | Logically expose flights for a rolling 365-day window | Hybrid generation; unique lazy instance on the booking path |
| 5 | Route/date search; operating-day match; four response fields | One aggregate query; no read-time writes or N+1 |
| 6 | Complete aircraft seat map; available/booked only | Live active-seat lookup, including never-booked flights |
| 7 | One or more selected seats; name-only passengers; six response fields | One atomic booking transaction |
| 8 | Zero double-booking under concurrent requests | PostgreSQL partial unique index, ordered seat writes, concurrency tests |
| 9 | Cancel booking, change status, release all seats, allow rebooking | One cancellation transaction; no refunds |

Required deliverables are the HLD, LLD, working backend, database schema/migrations and
README. Automated tests follow Maven's `src/test/java` layout; `tests/` contains the
HTTP walkthrough. The README must cover dependencies, build, run, database
initialization, major choices, all four search algorithm points, all five booking
algorithm points, all four cancellation algorithm points, and assumptions.

## Confirmed policies and explicit assumptions

- All schedule times and travel dates use UTC. No airport-local zones or conversion.
  Audit instants use `TIMESTAMPTZ`. Arrival time may precede departure time; no duration
  or arrival-date calculation is required by the API.
- Exactly 365 dates: `today <= travelDate < today + 365 days`, using an injected UTC
  `Clock` at request time. This boundary is an explicit interpretation of the brief.
- One immutable schedule per flight number is an identity assumption. A flight is
  addressed by `(flightNumber, flightDate)`. Global flight-number uniqueness is enforced
  by the database; different schedules for the same number are not introduced.
- Booking references are UUIDs, explicitly allowed by FR7. No custom PNR generator.
- Booking statuses are `CONFIRMED` and `CANCELLED`. Public seat states are `AVAILABLE`
  and `BOOKED`; historical seat allocations use internal `RELEASED` status.
- Booking is rejected once its UTC departure instant is reached.
- As selected by the user, repeated cancellation returns `409 ALREADY_CANCELLED`;
  cancellation at or after departure returns `409 FLIGHT_DEPARTED`. These are policies,
  not requirements stated by the hiring team. Unknown booking references return `404`.
- A passenger is paired with one seat. Passenger count is derived from allocations.
  Names are supplied as text, with no format, nonblank, or plausibility validation.
- IATA codes, flight numbers and seat labels use the documented uppercase format.
  Noncanonical labels are rejected, not silently rewritten. Duplicate labels within a
  request are malformed input, not a competing-customer conflict.

## Architecture and libraries

One stateless Java 21 / Spring Boot service and one PostgreSQL primary. The reviewer
runs one instance; correctness must also hold with multiple instances sharing the
database. No process-local lock or mutable inventory cache participates in correctness.
Scale on demand by adding identical replicas behind a readiness-aware load balancer,
without sticky sessions. Size maximum simultaneous replicas, including rollout surge,
against the shared database connection budget. Compose provides manual replica scaling;
production load balancing and autoscaler provisioning remain deployment concerns.

Use the user's latest package-by-layer decision: root-level `controller`, `service`,
`service.impl`, `repository`, `entity`, `model`, `dto.request`, `dto.response`, `config`, `exception` and
`validation` packages under `com.airline.reservation`. Do not group packages by feature.
Controllers depend on services and expose use-case-specific
request/response records, never JPA entities. Services expose narrow interfaces and
immutable application commands/results. Implementations use flat Spring Data
repositories and map entities internally, without passing entities between services.
The user chose this conventional layered structure over strict repository ports and
adapters; implementations are not persistence-independent. No catch-all DTO or
pass-through repository wrappers are introduced. Use explicit types, never `var`.

Spring Data JPA repositories handle persistence and Hibernate maps the entities.
Search projects application `FlightAvailability`, never an API DTO. Operating-flight resolution explicitly fetches the
aircraft, and Open EntityManager in View is disabled. Native SQL is limited to the
PostgreSQL-specific instance upsert. Spring manages transactions and exception
translation; Flyway owns schema changes and Hibernate only validates the schema.
Lombok supplies constructors/getters/loggers, Jakarta Validation handles structural
input rules, and Hibernate Validator's `@UniqueElements` validates operation days.
Use existing JDK/Spring facilities before adding another utility dependency.

## Database contract

Seven tables: `airport`, `aircraft`, `flight_schedule`, `flight_schedule_day`,
`flight_instance`, `booking`, `booking_seat`.

`db/migrations/` is executable schema truth; `db/schema.sql` is a generated snapshot.
Use consistent columns: `origin_code`, `destination_code`, `schedule_id`.
Define each constraint/index once. Every secondary index must name an actual query.

Key invariants:

- Unique schedule `flight_number`.
- Unique instance `(schedule_id, flight_date)`.
- Unique booking UUID.
- A composite booking/instance foreign key prevents a seat allocation pointing at a
  different instance than its booking.
- `uq_booking_seat_active` is unique on `(flight_instance_id, seat_label)` only where
  `status = 'BOOKED'`. Released historical allocations do not block rebooking.
- Aircraft capacity and valid labels come from fixed configuration. Availability is
  derived from active allocations, never a separately maintained counter.

## API contract

Exactly five required operations:

| Method | Path | Response |
|---|---|---|
| POST | `/api/v1/admin/flight-schedules` | 201, created schedule |
| GET | `/api/v1/flights?originCode=DXB&destinationCode=LHR&date=YYYY-MM-DD` | 200, search-result array |
| GET | `/api/v1/flights/{flightNumber}/seat-map?date=YYYY-MM-DD` | 200, flight/date and complete seat list |
| POST | `/api/v1/bookings` | 201, booking response |
| POST | `/api/v1/bookings/{bookingReference}/cancel` | 200, cancelled booking response |

Create schedule supplies `flightNumber`, `originCode`, `destinationCode`,
`departureTime`, `arrivalTime`, `aircraftId`, `daysOfOperation`.
Times use `HH:mm`; dates use ISO `yyyy-MM-dd`; weekdays use `MONDAY` through `SUNDAY`.
Clock parsing is strict: 24:00, seconds and malformed hour/minute values return 400.
JSON response media types are negotiated before invocation; incompatible Accept
headers return 406 without creating schedules/bookings or cancelling reservations.

Search returns `flightNumber`, `departureTime`, `arrivalTime`, `availableSeats` (count).
An empty match returns `200 []`; a sold-out flight remains in results with count zero.
Search does not add a departed-flight filter; bookability is enforced on the write path.

Booking supplies `flightNumber`, `flightDate`, and
`passengers: [{"name": "Example", "seat": "12A"}]`.
Response fields are `bookingReference`, `flightNumber`, `flightDate`, `passengerCount`,
`assignedSeats`, `bookingStatus`. Seat maps never expose passenger names.
There is no extra GET endpoint behind an invented `Location` URL.

Errors use RFC 9457 `application/problem+json` and a stable `errorCode`.
Malformed input is 400; missing flight/booking is 404; duplicate schedules, seat
conflicts and lifecycle conflicts are 409; out-of-window dates, unknown references
and labels absent from an aircraft are 422. Temporary database failures are 503;
unexpected failures remain explicit 500 responses and are logged without payloads.

## Algorithms and concurrency

### Search and seat map

Validate date, derive its ISO weekday, match route and operation days, then compute
capacity minus the active allocations on the matching instance. Search **does read**
`flight_instance` through an outer join but neither requires a pre-existing row nor
writes one. Put `booking_seat.status = 'BOOKED'` in the join condition.

A live read reflects committed state at its database snapshot, not a seat hold.
Availability can change between response and booking. A later booking conflict returns
409 and explains that the selected seats are unavailable. Any post-rollback seat list
is a fresh snapshot, not a permanent guarantee or an exact history of the conflict.

### Materialize and book

A nontransactional coordinator calls separate transactional components:
`Propagation.NEVER` rejects an ambient transaction before persistence. This is an
explicit composition contract, not an instruction to nest REQUIRES_NEW transactions.

1. Resolve/validate the immutable flight and requested labels.
2. In a short transaction, find the existing instance; if absent, use
   `INSERT ... ON CONFLICT DO NOTHING`, followed by a **separate** SELECT under
   `READ COMMITTED`. Instances are never deleted by application code. Commit.
3. In one booking transaction, insert the booking and its seats in a deterministic
   label order. Commit all or roll back all.
4. Catch a known active-seat uniqueness conflict only after rollback; return 409.

A separate SELECT gets a new READ COMMITTED snapshot. Do not confuse this with a
same-statement CTE/UNION lookup. Do not issue a no-op UPDATE on every booking to get an
existing ID. Do not catch an ordinary failed INSERT and continue an aborted transaction.
Sequential transactions avoid nested `REQUIRES_NEW` connection acquisition.

The partial index arbitrates both sequential and concurrent conflicts across JVMs.
There is no `@Version`, inventory counter, distributed lock or seat-conflict retry.
Multi-seat writes can deadlock if acquired in conflicting orders; test actual SQL
behavior rather than claiming annotations prove ordering. Unexpected transient failures
must be surfaced explicitly, not reported as successful bookings.

### Cancel

Resolve booking, reject an already-cancelled/departed booking, conditionally change
`CONFIRMED` to `CANCELLED`, and release its allocations in the same transaction.
Affected-row count arbitrates simultaneous cancellations. Cancellation and rebooking
in separate calls can race: a booking may succeed after committed release or receive
a conflict if it precedes release. Neither outcome may corrupt or partially sell seats.

## Nonfunctional acceptance

- Real PostgreSQL integration tests, not an H2 substitute for the partial index.
- Direct database uniqueness/FK checks and concurrent same-seat, overlapping-set,
  disjoint-seat, first-materialization, cancellation and rebooking scenarios.
- Entity-logic, DTO validation and architecture tests; no passenger names in logs.
- Correlation context is bounded, returned to the caller and cleaned up after requests.
- Readiness explicitly includes database health; liveness does not.
- Pool sizes, acquisition timeouts and query timeouts are operational choices, not
  proofs of a one-second HTTP response or hard capacity reservations.
- Search/seat-map read-only transactions use a separate max-5 pool with 250 ms
  acquisition, 500 ms SQL and 250 ms lock limits. The max-10 write pool retains 20 s
  acquisition; booking lookups use it rather than borrowing read capacity.
  Defaults permit 15 connections per JVM/45 for three replicas. Busy reads return
  typed 503 with Retry-After, not successful empty results; write outcome uncertainty
  still forbids blind replay. `ReadIsolationIT` proves isolation, deadlines and recovery.
- Measure search HTTP latency with 500 schedules and a documented dataset.
  Query-count and EXPLAIN evidence support that measurement; do not force a particular
  index scan or forbid legitimate sequential scans on small tables.
- Under the user's delegated judgment, all measured warm read-only searches and all
  searches in the existing 16-client mixed phase must return 200 in under one second.
  This local engineering baseline is not an agreed peak load or a percentile SLO.
  The 64/192-client phases are exploratory stress: count 503 separately, report
  successful-search latency separately and reject an all-shed phase. All disjoint
  bookings must still succeed and no phase may produce duplicate seat ownership.
- 10k DAU and 25:1 traffic do not determine QPS without additional assumptions.
  Do not label assumed QPS as measured or guaranteed.
- Seat-map work includes output size; booking/cancellation involve multiple SQL round
  trips even when they use only one transaction.

## Exclusions

Airport/aircraft CRUD, schedule mutation, customer accounts, implemented authentication,
payments/refunds, notifications, pricing, seat holds, partial cancellation, multi-airline
or connecting itineraries, custom search sorting/filtering, caches, queues and cloud
infrastructure are not implemented. No unnecessary stub calls are added to core flows.
Admin authentication is an external deployment boundary, not provided by a URL prefix.
Listing/retrieval APIs and optional idempotency are deferred.

Lazy loading is a clarified provision if measured search behavior needs it, not a blanket
prohibition on future pagination and not a claim automatically satisfied by the seat map.

## Clause-level implementation audit

Source line numbers refer to the local `assignment-brief.md` (374 lines, including
clarifications). Its relocation and wrapper-link edit did not change any requirement.
Examples illustrate their adjacent requirement; they are not separate requirements
for hardcoded flight numbers/dates. Optional approaches/diagrams are not cumulatively
mandatory. Evidence below names actual code/tests, not earlier planning assertions.

### FR1-FR9

| Source lines | Audited clause | Implementation and evidence |
|---|---|---|
| 43-45 | Airports pre-exist; seeding permitted | V2 seeds DXB/LHR/BOM/SIN; `DatabaseSchemaIT.airportsHaveAllRequiredFieldsAndStableIataCodes` reads the real migrated database |
| 46 | No airport CRUD | Only schedule, flight and booking controllers; OpenAPI exposes no reference-data controller |
| 48 | Airport IATA code | `Airport.code`, schema PK/format check; seed assertion |
| 49 | Airport name | `Airport.name`, nonnull schema and nonblank seed assertion |
| 50 | Airport city | `Airport.city`, nonnull schema and nonblank seed assertion |
| 51 | Airport country | `Airport.country`, nonnull schema and nonblank seed assertion |
| 55-57 | Preconfigured aircraft ID | V2 aircraft IDs, `Aircraft.id`, real database configuration test |
| 58 | Aircraft type | `Aircraft.type`, nonnull schema/seed values |
| 59-64 | Fixed row/letter layout, including 30 ABCDEF example | `Aircraft.rowsCount/seatLetters`, layout entity tests, V2 A320-001, alternate ATR layout |
| 65 | Immutable map | No aircraft mutation API; labels derived from fixed configuration, not editable allocations |
| 66 | No aircraft CRUD | No aircraft controller or endpoint |
| 70 | Administrative schedule creation API | `ScheduleController` POST returns 201; `ScheduleIT`; real image walkthrough |
| 72 | Flight number | Validated request and response; database uniqueness; concurrent creation test |
| 73 | Source airport | `originCode` resolves seeded airport; unknown-reference test |
| 74 | Destination airport | `destinationCode` resolves seeded airport; same-route and unknown-reference tests |
| 75 | Departure time | `departureTime` HH:mm -> UTC `LocalTime` -> schedule response |
| 76 | Arrival time | `arrivalTime` HH:mm; schedule round-trip includes overnight example |
| 77 | Aircraft | `aircraftId` resolves real fixed configuration; unknown reference rejected |
| 78, 79-87 | Operating weekdays and example | Distinct nonempty ISO weekdays; atomic parent/day creation and injected-failure rollback tests |
| 91 | Schedule is template | `FlightSchedule` separate from `FlightInstance`; FK and unique instance key |
| 92-106 | Rolling 365-day instance exposure | Shared `FlightDatePolicy`; search/map without materialization; boundary/leap/clock tests and booking boundary regression |
| 107-111 | Choose and document generation approach | Hybrid logical reads/lazy physical booking row, explained in HLD/LLD/README; `InstanceMaterializationIT` proves concurrent first creation |
| 115-118 | Search source, destination, date | `FlightController` query parameters; `FlightSearchIT` route and weekday mismatch checks |
| 120 | Search flight number | `FlightSearchResponse.flightNumber`; HTTP projection test |
| 121 | Search departure time | `FlightSearchResponse.departureTime`; HTTP walkthrough verifies field |
| 122 | Search arrival time | `FlightSearchResponse.arrivalTime`; HTTP walkthrough verifies field |
| 123 | Search available-seat count | Capacity minus BOOKED allocations; sold-out/released tests |
| 124 | Only operating flights for date | JPQL schedule-day join and ISO weekday; unmatched route/day return [] |
| 125 | No extra sorting/filtering | No additional query parameters or sorting/filter API |
| 129 | Per-flight seat map | `FlightController` seat-map endpoint; never-booked and unknown-flight cases |
| 130-132 | Only AVAILABLE/BOOKED public states | `SeatMap.Status` mapped to `SeatMapResponse`; complete-list assertions |
| 133-140 | Labels from configured layout | All 180 A320 or 72 ATR labels; `AircraftTest`/`SeatMapIT`; no synthetic seat table |
| 141 | Live committed booking state | Read-only active-label query; booking/cancellation tests verify both read APIs after commit |
| 145-149 | Select flight, view seats, book one or multiple | Search/map plus `BookingController`; one-seat contention and multi-seat atomic booking tests |
| 151 | UUID/PNR reference | Standard UUID; persisted unique reference; response UUID assertion |
| 152 | Booking flight number | `BookingResponse.flightNumber`, HTTP booking response assertion |
| 153 | Booking flight date | `BookingResponse.flightDate`, HTTP booking response assertion |
| 154 | Passenger count | Derived from allocation count, not a second mutable counter |
| 155 | Assigned seats | Deterministic saved labels; response assertion and actual insert-order proof |
| 156 | Booking status | CONFIRMED response only after transaction commit |
| 157-159 | Name-only, no passenger semantic validation | Request name is structurally nonnull only; empty, Unicode and long names accepted in DTO/integration tests; map/error/log privacy tests |
| 163-165 | No double booking or two active owners | Actual PostgreSQL `uq_booking_seat_active`; raw constraint test and sequential conflict test |
| 167 | Consistency under concurrent requests | `BookingConcurrencyIT`: same/reversed/overlapping/disjoint/full-flight sets; no partial loser booking |
| 168-174 | Document concurrency strategy; examples are alternatives | HLD/LLD/README explain transactions plus partial unique index; no invented requirement to implement every locking technique |
| 178 | Cancel an existing booking | `BookingController.cancel` delegates to `CancellationService`; known/unknown UUID tests |
| 180 | Change booking status | Conditional CONFIRMED -> CANCELLED with UTC instant; repeat/concurrent cancellation tests |
| 181 | Release every reserved seat | One bulk release and exact count check in same transaction; injected release failure proves rollback |
| 182 | Allow future booking of released seats | Cancellation search/map/rebooking test, cancellation/rebooking race, cross-instance test and HTTP walkthrough |
| 183 | Refunds excluded | No refund/payment code or side effect |

### Nonfunctional, deliverable and clarification clauses

| Source lines | Audited obligation | Evidence / boundary |
|---|---|---|
| 15-37 | Single-airline backend and one-year scope | Five required operations, no airline/itinerary abstraction; agreed exact 365-date interpretation |
| 187-190 | Architecture, concerns, REST | Root package-by-layer, use-case DTOs, controller/service/repository boundaries; ArchUnit; documented HTTP codes |
| 191-193 | Transactions, errors, validation | Proxied transaction services; full rollback tests; RFC 9457; structural DTO and date policy tests |
| 194-196 | Logging, testability, reasonable performance | Correlation/MDC/privacy tests; injected clock; real PostgreSQL; one-query projection and measured HTTP latency |
| 202-213 | HLD architecture/components/interactions/flow/decisions/assumptions/concurrency/generation; diagram encouraged | Completed `hld/architecture.md` with five Mermaid diagrams (HLD-1 to HLD-5); `hld/architecture.pdf` printed with them; SVG copies indexed in `hld/diagrams/README.md` |
| 215-225 | LLD modules/classes/services/APIs/validation/errors/transactions; sequence diagram optional | Completed `lld/design.md` with class (LLD-1a to LLD-3), optional sequence (LLD-4 to LLD-7) and state (LLD-8a, LLD-8b) diagrams |
| 227-236 | Executable backend with setup | Image built, Compose readiness and real public-API walkthrough; README build/run instructions |
| 238-247 | Schema, table definitions, PK/FK/constraints/indexes; ER preferred | V1/V2, generated/restored `db/schema.sql`, `db/er-diagram.md`, raw database constraint tests |
| 249-258 | README setup/dependencies/build/run/initialization/decisions | Concrete sections and commands; no placeholder headings |
| 259-265 | Four search algorithm explanations | README identification, matching, hybrid generation, capacity minus active allocation |
| 266-272 | Five booking algorithm explanations | README validation, index-based arbitration, sequential transactions, UUID creation, post-rollback conflict |
| 273-280 | Four cancellation points and assumptions | README lookup/release/transaction/status plus explicit adopted and additional policies |
| 281-293 | Evaluation dimensions | Evidence in corresponding feature, architecture, schema, performance and documentation rows; no claim that scale is unlimited |
| 295-307 | Assumptions: one airline, one active seat owner, one instance/booking, fixed maps/schedules, preloads, horizon, UTC/no conversions | Schema/API boundaries plus README; historical RELEASED owners do not violate one ACTIVE owner |
| 309-325 | Expected layout | HLD PDF/diagrams, LLD, src, schema/migrations, tests and Compose present; Maven test-location deviation explained |
| 329-335 | Working backend/docs; external black boxes need not be implemented | No authentication/payment integration inserted; deployment gateway boundary disclosed |
| 346-348 | Multiple-instance design, single reviewer instance | Stateless code and database invariant; two-context automated test plus three separate Compose JVM proof; default one replica |
| 350-353 | Moderate 10k DAU/500 schedules | 500-schedule measured dataset; DAU is not converted into invented QPS |
| 355-356, 367-368 | 25:1 search/booking mix | `MixedLoadIT`: 20,400 searches/816 disjoint bookings, 16/64/192 local clients; all bookings 201 in passing runs; 64/192-client phases may shed searches as explicit 503s, and the test fails intermittently with a connect-time reset (see [NFR evidence](../../tests/nfr-evidence.md) §5-§8). Short closed-loop evidence, not production/soak capacity certification |
| 358-361 | Stale read permitted only with booking-state feedback | No server cache; committed read snapshot can still age before click; known conflict returns 409 with fresh conflict details |
| 363-365 | One-second search; provision for lazy loading if needed; stable bookings prioritized | Read-only and 25:1 real HTTP measurements below one second, with overload 503 counted separately; approved read pool/deadlines protect booking capacity. No pagination needed at measured result size; no hard real-time/production guarantee |
| 370-372 | Strong booking consistency and zero double booking | Database active-seat constraint, atomic writes, cross-JVM same-seat and cancellation/rebooking evidence |

The clause audit found a missing direct write-path boundary assertion, not a missing
implementation: `BookingIT.bookingUsesTheSame365DateWindowAndExactDepartureBoundary`
now proves first/last date acceptance, exclusion outside the window and rejection at
the exact departure instant before materialization.

Submission boundary: the user explicitly elected to keep the relocated brief and
historical plan tracked. They have not been untracked, republished or removed from
history. This is a disclosed user decision, not a clean-submission privacy claim.
