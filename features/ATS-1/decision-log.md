# ATS-1: design decisions

**Authority.** [assignment-brief.md](assignment-brief.md) (“the brief”) and its seven
interviewer replies (lines 346-372) are the source of truth. “Brief 161-174” means lines
of that file. User decisions recorded in [spec.md](spec.md) and the
[execution log](execution-log.md) come next.

**References.** Code references are `file:line` at commit `bca3c84`. Java paths are
relative to `src/main/java/com/airline/reservation/`; SQL is in `db/migrations/`
(`V1` = `V1__baseline_schema.sql`, `V2` = `V2__seed_reference_data.sql`). Scenario IDs
S1-S13 and run IDs (H0-H4, F1-F3, R1-R6) refer to
[NFR evidence](../../tests/nfr-evidence.md), the only document that records measured
numbers. This log explains *why*; it repeats no measurements.

**History.** Corrected 2026-10-19 against the brief; stateless scaling clarified
2026-10-20; option tables added 2026-10-21 after a three-way audit. Restructured on
2026-10-21 as a documentation-only change (no code change). This version gives every
decision a driver, an invariant, source references, examples, consequences and
contextual verdicts, and adds D22 (REST design) and D23 (validation layers). Older
entries in the execution log are history, not instructions.

## How to read this log

Every decision has eight parts:

| Part | Content |
|---|---|
| Driver | The requirement that forces the decision, labelled by source: **Brief** (line), **Reply** (interviewer reply line), **User policy** (a decision the user made, recorded in spec or execution log), **Assumption** (brief 295-307) or **Engineering choice** (judgment delegated to the implementer) |
| Invariant | The property that must always hold |
| Implementation | Classes, SQL objects and `file:line` references |
| Options | Every realistic approach, including each strategy the brief names, with a verdict |
| Example | A concrete scenario |
| Consequences | Transaction, concurrency and failure effects |
| Limitations and evidence | What the choice does not do, and the scenarios that test what it does |
| Revisit when | Observable triggers that would change the decision |

Option tables use the same columns: *Approach · Verdict · How it works · Advantages ·
Costs and failure modes · Why chosen / why not now · When preferable (architecture)*.

| Verdict | Meaning |
|---|---|
| **Chosen** | Implemented in this repository |
| **Complement** | Implemented together with the chosen option; not a substitute for it |
| **Viable alternative** | Would meet every stated requirement here; “When preferable” says when it would be the better choice |
| **Unsuitable here** | Would violate the brief, an interviewer reply or a decision the user made explicitly (recorded in spec or execution log) *in this design*; the row says where the approach is sound. Design choices that the spec records without attributing them to the user count as engineering choices, so alternatives to them stay viable |

Verdicts are contextual. “Unsuitable here” never means “wrong everywhere”.

## Summary

| # | Decision | Chosen | Driver |
|---|---|---|---|
| [D1](#d1-flight-instance-generation) | Flight instance generation | Hybrid: compute flights from schedules for reads; create the instance row on first booking | Brief 89-111 |
| [D2](#d2-seat-concurrency-and-double-booking) | Seat concurrency | Partial unique index on active seats, inside one booking transaction | Brief 161-174; replies 346-348, 370-372 |
| [D3](#d3-seat-inventory-representation) | Seat inventory | Labels derived from the aircraft layout; one row per booked seat | Brief 53-66, 127-141 |
| [D4](#d4-cancellation-lifecycle) | Cancellation | Conditional status update and bulk release in one transaction | Brief 176-183; user policy |
| [D5](#d5-availability-and-read-model) | Availability | Live count of active seat rows; no counter, no cache | Brief 113-141; reply 358-361 |
| [D6](#d6-transaction-boundaries) | Transaction boundaries | `NEVER` coordinator; separate short transactions; READ COMMITTED | Brief 191, 265-271 |
| [D7](#d7-read-protection-and-connection-pools) | Read protection | Separate read pool with short deadlines; write pool kept for writes | Reply 363-365 |
| [D8](#d8-deployment-topology) | Deployment | One stateless Spring Boot service + PostgreSQL | Reply 346-348 |
| [D9](#d9-on-demand-scaling) | Scaling | Identical replicas within a database connection budget | Replies 346-353 |
| [D10](#d10-persistence-and-schema-ownership) | Persistence | Spring Data JPA + Flyway SQL migrations; one native upsert | Brief 238-247; user policy |
| [D11](#d11-code-organization) | Code organization | Package-by-layer, enforced by ArchUnit | Brief 188-189; user policy |
| [D12](#d12-service-contracts) | Service contracts | Narrow use-case interfaces with immutable records | Brief 189, 195 |
| [D13](#d13-orm-fetching-and-writes) | ORM fetching and writes | Search and cancellation-lookup projections; one to-one entity graph; OSIV off; IDENTITY ids | Brief 196 |
| [D14](#d14-time-and-booking-horizon) | Time and horizon | UTC, injected `Clock`, `[today, today + 365 days)` | Brief 37, 92-93; assumptions 305-307 |
| [D15](#d15-public-identity) | Public identity | Flight = number + date; booking reference = random UUID | Brief 151 |
| [D16](#d16-api-scope-and-retries) | API scope and retries | The five required operations; no idempotency keys | Brief 29-37 |
| [D17](#d17-http-negotiation-and-strict-clocks) | HTTP preflight | Content negotiation before the handler; strict `HH:mm` | Brief 190, 193 |
| [D18](#d18-errors-and-logging) | Errors and logging | RFC 9457 problem details + stable codes; correlation ID; no payload logging | Brief 192, 194 |
| [D19](#d19-verification-strategy) | Verification | JUnit + ArchUnit + real PostgreSQL (Testcontainers) | Brief 195 |
| [D20](#d20-latency-acceptance) | Latency acceptance | Every search returns 200 in < 1 s in defined baselines | Reply 363-365 |
| [D21](#d21-libraries-and-documentation) | Libraries and documentation | JDK/Spring/Lombok facilities; one decision log; one evidence ledger | Brief 188, 202-279 |
| [D22](#d22-rest-resource-design) | REST resource design | Versioned resource URIs; cancellation as a state-transition command | Brief 190 |
| [D23](#d23-validation-layers) | Validation layers | Request DTO → service contract → domain rule → database constraint | Brief 193 |

## Non-functional requirements (brief 185-196)

| Line | NFR | How it is met | Not done, and why | Decisions | Evidence |
|---|---|---|---|---|---|
| 188 | Clean architecture | Layered dependencies enforced by ArchUnit rules (`ArchitectureTest`): controllers → service interfaces → repositories; DTOs, models and entities kept apart | Not strict hexagonal (“ports and adapters”): no second adapter exists | D11, D21 | S1 |
| 189 | Separation of concerns | DTO ↔ command/model ↔ entity mapping at each boundary; narrow use-case interfaces; booking coordinator separate from the transactional writer | – | D6, D11, D12 | S1 |
| 190 | RESTful API design | Versioned resource URIs, precise status codes, cancellation as a command sub-resource, JSON negotiation before writes, problem details | No hypermedia links or `Location` header: the brief fixes the response fields and has no retrieval endpoint | D16, D17, D22 | S4, S12 |
| 191 | Transaction handling | `NEVER` coordinator, short instance transaction, one all-or-nothing booking transaction, conditional cancellation, all-or-nothing schedule creation; a read that exceeds its statement deadline rolls back before its connection is reused; READ COMMITTED plus constraints | No SERIALIZABLE, no distributed transactions | D2, D4, D6, D7 | S2, S3, S5, S6, S7 |
| 192 | Error handling | RFC 9457 problem details with stable `errorCode`; exact constraint-to-error mapping; 503 + `Retry-After` when the database is unavailable | – | D18 | S1, S4, S7, S8 |
| 193 | Validation | Four layers: request DTO, service contract, domain rule, database constraint | No passenger validation (brief 159) | D17, D23 | S1, S2, S4 |
| 194 | Logging | Correlation ID in MDC on every line; key=value business events; no passenger names or request bodies | No log shipping or tracing: one service | D18 | S1, S4 |
| 195 | Testability | Injected `Clock`, interface-based services, real PostgreSQL, latch-synchronized concurrency tests | – | D12, D19 | S1-S10 |
| 196 | Performance “beyond reasonable implementation” | One-statement search projection, live counts, targeted fetches, isolated read pool | No cache, replica or batching: not needed at the stated scale | D5, D7, D13, D20 | S9, S10 |

## Brief-named strategies and where they are decided

| Brief text (line) | Strategy | Decision | Verdict here |
|---|---|---|---|
| “Materialize flight instances” (108) | Full materialization | D1 | Viable alternative |
| “Generate them dynamically” (109) | Pure dynamic | D1 | Viable alternative |
| “Hybrid approach” (110) | Compute for reads, create on first booking | D1 | **Chosen** |
| “Database locking” (170) | Unique-index arbitration (the index entry acts as the lock) | D2 | **Chosen** |
| “Optimistic locking” (171) | Compare-and-set: `@Version`, or a conditional `UPDATE … WHERE status = …` | D2, D4 | Viable for seats (D2); the conditional-update form is **Chosen** for cancellation (D4) |
| “Pessimistic locking” (172) | `SELECT … FOR UPDATE` on seat, flight or booking rows | D2, D4 | Viable alternative |
| “Transactions” (173) | Atomic unit of work | D2, D6 | **Complement**: gives atomicity, not arbitration |
| “Versioning” (174) | Version column (the usual optimistic-locking mechanism) | D2, D4 | Viable alternative |
| “PNR or UUID” (151) | Booking reference format | D15 | UUID **Chosen**; PNR viable |

---

## D1. Flight instance generation

**Driver.** Brief 89-111: generate, or logically expose, bookable flight instances for 365
days and document the approach; the brief offers three approaches (108-110). Assumption
303: schedules do not change after creation. Replies 350-356: about 500 schedules and 25
searches per booking.

**Invariant.** A flight (schedule, date) exists only if the schedule operates on that
weekday and the date is inside the window (D14). At most one `flight_instance` row exists
per (schedule, date).

**Implementation.**
- Search and seat map compute flights from `flight_schedule` and `flight_schedule_day`
  for the requested date (`repository/FlightScheduleRepository.java:14-35`,
  `service/impl/FlightLookupServiceImpl.java:28-32`). Neither writes.
- The booking path calls `FlightInstanceServiceImpl.getOrCreate`
  (`service/impl/FlightInstanceServiceImpl.java:17-28`): `findId`; if absent,
  `insertIfAbsent` (`INSERT … ON CONFLICT ON CONSTRAINT uq_instance DO NOTHING`,
  `repository/FlightInstanceRepository.java:15-23`), then a separate `findId`.
- `uq_instance (schedule_id, flight_date)` (`V1:46`) guarantees one row per flight.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| Full materialization | Viable alternative | A job inserts one row per operating date for the next 365 days and extends the horizon daily | Plain joins; a row exists for per-date data (status, aircraft swap, price) | A rolling job plus a backfill for each new schedule; up to 182,500 mostly empty rows (500 daily schedules × 365); a missed job run silently hides dates | Nothing per-date exists to store, and the job adds a failure mode | Per-date operations: cancel one date, swap aircraft, dynamic pricing, operations tooling over instances |
| Pure dynamic | Viable alternative | No instance table; bookings reference (schedule, date) directly | No extra table and no write before booking | Every booking and seat row and every constraint carries (schedule_id, flight_date); per-flight state later needs a migration | A real row gives bookings a compact foreign key and one place for future per-flight state | Read-only timetable services, or when bookings live in another system |
| **Hybrid: compute for reads, create on first booking** | **Chosen** | As implemented above | No job; rows only for flights that have bookings; stable key for D2's index | Reads must outer-join a possibly missing row; a failed first booking leaves an empty instance | Fits immutable schedules (assumption 303) and the 25:1 read mix; creation is race-free through the unique constraint | Immutable schedules at moderate scale (this brief) |

**Example.** The brief's schedule XY101 operates on Mondays (brief 94-106); assume it was
created for DXB → LHR. 2026-11-02 is a Monday.
1. `GET /api/v1/flights?originCode=DXB&destinationCode=LHR&date=2026-11-02` lists XY101
   with full capacity although no instance row exists; the outer join counts zero seats.
2. The first booking for that date inserts the instance. A later booking, or a
   concurrent first booking that lost the insert race, finds the same id.
3. A search for Tuesday 2026-11-03 does not list XY101.

**Consequences.**
- Concurrency: if two first bookings race, one inserts the row. The other's
  `ON CONFLICT DO NOTHING` waits for that short transaction, inserts nothing, and its
  next statement (a new READ COMMITTED snapshot) sees the committed row.
- Failure: if the booking fails after the instance committed, an instance without seats
  remains. Reads treat “no row” and “row without seats” the same, and application code
  never deletes instances (spec, “Materialize and book”).
- Transactions: D6 explains why creation is its own short transaction.

**Limitations and evidence.** Per-date changes cannot be represented; schedules are
immutable (assumption 303). Evidence: S3 (`InstanceMaterializationIT`: concurrent first
creation returns one committed id; an existing instance needs no write; a separate
statement sees a row committed after the first lookup), S4 (`FlightSearchIT`,
`SeatMapIT`).

**Revisit when** per-date overrides (cancel one date, aircraft swap), schedule edits or
per-instance pricing enter scope; then move to rolling materialization.

## D2. Seat concurrency and double booking

**Driver.** Brief 161-174: prevent double booking, keep seats consistent under concurrent
requests and document the strategy. Reply 370-372: zero double booking. Reply 346-348:
this must hold across several application instances. Assumption 300 (“one seat belongs
to only one booking”) is refined to one *active* booking, so released rows keep history.

**Invariant.** For each (flight instance, seat label), at most one `booking_seat` row has
status `BOOKED`, whichever replica wrote it. A booking holds all of its seats or none.

**Implementation.**
- `uq_booking_seat_active ON booking_seat (flight_instance_id, seat_label) WHERE status =
  'BOOKED'` (`V1:77`).
- `BookingTransactionServiceImpl.create` (`service/impl/BookingTransactionServiceImpl.java:32-47`)
  runs in one transaction. It rechecks the window and departure (36-37), creates the
  reference (38), then `persist` (49-68) inserts the booking and its seats sorted by
  label (58).
- Only a unique violation of `uq_booking_seat_active` becomes `SeatConflictException`
  (62-64). The check is Hibernate's `UNIQUE` constraint kind, which Hibernate derives
  from SQLSTATE 23505, plus the constraint name
  (`exception/PersistenceConstraints.java:12-21`). Any other integrity error is rethrown
  and ends as 500.
- After the rollback, `BookingServiceImpl.createBooking`
  (`service/impl/BookingServiceImpl.java:72-89`) reads the currently booked seats and
  returns 409 `SEAT_UNAVAILABLE` (D6).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| JVM lock (`synchronized`, in-memory seat map) | Unsuitable here | One process serializes bookings in memory | Trivial; no database round trip to decide | Other replicas bypass it; state is lost on restart | Violates reply 346-348 (multiple instances) | One process owns all state, such as an in-memory design exercise |
| Check-then-insert without a constraint | Unsuitable here | `SELECT` the seat; if free, `INSERT` | Simple | Under READ COMMITTED both transactions see the seat free and both insert | Allows double booking (reply 370-372) | Only as a friendly pre-check in front of a constraint |
| **Partial unique index + one booking transaction** | **Chosen** | One `BOOKED` row per seat, inserted in label order. A second active row for the same seat waits for the first writer, then fails with 23505 → rollback → 409 | Enforced by the database for every replica; seat-level granularity, so disjoint seats never block each other; no retry loop; cancelled history kept as `RELEASED` rows | PostgreSQL-specific DDL; the loser waits for the winner's commit; the error mapping must match the constraint name exactly | Encodes “one active owner per seat” directly, with no inventory rows, counters or retries | One relational primary owns seat state (this design) |
| Per-seat conditional `UPDATE` (compare-and-set) | Viable alternative | Pre-created inventory rows; `UPDATE seat SET status='BOOKED', booking_id=? WHERE instance_id=? AND label=? AND status='AVAILABLE'`; 0 rows means the seat was lost → roll back | Seat-level; no version column; a natural place for holds | Needs one inventory row per seat per instance (D3); every update's row count must be checked | Inventory rows serve nothing else in scope | Seat holds with expiry, or per-seat pricing |
| Per-seat `SELECT … FOR UPDATE` | Viable alternative | Lock the inventory rows in label order, check them, then update | Explicit lock order; easy to read | The same inventory rows; lock waits, `lock_timeout` handling, deadlocks if the order is not enforced | As above | Multi-step checkout that must inspect seats before writing |
| Flight-row `SELECT … FOR UPDATE` | Viable alternative | Lock the `flight_instance` row, check seats, insert | One lock per flight; simple reasoning | Serializes every booking on a flight, even `1A` vs `30F`; popular flights queue | Unneeded serialization; seat-level arbitration is enough | Flight-level invariants such as overbooking limits or fare-bucket caps |
| Optimistic `@Version` / version column | Viable alternative | Read a version; write `WHERE version = ?`; 0 rows → retry | No lock held while a user decides | Needs a mutable row to version; a flight-level version makes disjoint seats conflict; needs a retry loop | Inserts into the index need no version; retries add latency for nothing | Low-contention edits of a mutable aggregate |
| SERIALIZABLE isolation | Viable alternative | PostgreSQL's serializable snapshot isolation aborts one of two conflicting transactions (40001) | Protects invariants that no constraint can express | Every writer needs a retry loop; false-positive aborts under load | The invariant is a uniqueness rule, which a constraint enforces exactly | Cross-row invariants without a natural constraint |
| Distributed lock (Redis, ZooKeeper) | Viable alternative | A lease per seat or flight held outside the database | Coordinates several databases or services | Lease expiry and fencing tokens; another system to run; the database still records the outcome | One shared database already provides an exact, durable lock | Seat ownership spread across several databases or services |
| Transactions | Complement | Group the booking row and all seat rows into one atomic unit | All-or-nothing booking | Atomicity alone does not choose a winner (see check-then-insert) | Used with the index | Always, together with an arbitration mechanism |

**Example: overlapping requests.** The seat map showed `1A`, `1B` and `1C` free to users A
and B; the map may be stale (reply 358-361).
1. A requests `1A, 1B`; B requests `1B, 1C`. Validation checks that the labels exist on
   the aircraft but does not read occupancy; the index is the only arbiter.
2. Each transaction inserts its booking row (distinct UUIDs), then its seats in label
   order. A inserts `1A`; B inserts `1B` (not yet committed).
3. A tries `1B`. PostgreSQL finds B's in-progress entry in `uq_booking_seat_active`, so
   A waits.
4. B inserts `1C` and commits. A's insert fails with SQLSTATE 23505 on that index, and
   A's transaction rolls back, removing `1A` and A's booking row.
5. After the rollback, a fresh read finds `1B` booked. A receives 409 `SEAT_UNAVAILABLE`
   with `currentlyUnavailableSeats: ["1B"]`.
6. Had B rolled back instead, A's wait would end, its insert would succeed and A would
   win. Waiting, rather than failing on sight of an uncommitted row, is why a loser only
   loses to a committed owner.

**Example: lock granularity.** A books `1A` and B books `30F` on the same flight at the
same moment. With the index they touch different keys and both commit in parallel. With
a flight-row `FOR UPDATE`, B waits for A's commit. With a flight-level `@Version`, the
later committer fails and must retry. With a JVM lock on two replicas, nothing
coordinates them at all.

**Consequences.**
- Transaction: one READ COMMITTED transaction per booking; a conflict on any seat rolls
  back the whole booking (brief 143-159).
- Concurrency: contenders for the same seat wait only for each other; different seats
  proceed in parallel. Every booking inserts in the same label order, so two bookings
  cannot wait on each other's seat entries in a cycle. PostgreSQL's deadlock detector
  remains the backstop for any other lock pattern.
- Failure: a crash before commit rolls everything back, so nothing is ever half-booked.
  A booking that races with the cancellation of the same seat waits for the
  cancellation (D4).
- Seat inserts use lexical label order (`10A` before `2A`); any total order prevents
  cycles. Responses do not expose it: a booking returns seats in request order (seat
  *i* belongs to passenger *i*), and a cancellation returns them in natural row order
  (`2A` before `10A`, `repository/BookingSeatRepository.java:20-26`).

**Limitations and evidence.** There are no seat holds: a user can lose a seat between
viewing the map and booking, and is told so with 409 (reply 358-361). The partial index
is PostgreSQL-specific. Evidence: S2 (`DatabaseSchemaIT`: raw SQL rejects a second active
seat and allows a released seat to be rebooked), S4 (`BookingIT`: conflict rolls back
every seat; deterministic insert order; unrelated integrity errors are not reported as
seat conflicts), S5 (`BookingConcurrencyIT`, service layer: same seat, reversed and
overlapping sets, disjoint seats, full-flight contention), S8 (`CrossInstanceIT`: two
application contexts), S10 (`MixedLoadIT` hot seat over HTTP: exactly one 201).

**Revisit when** seat holds, payment during checkout, flight-level limits such as
overbooking, or seat ownership across several databases enter scope.

## D3. Seat inventory representation

**Driver.** Brief 53-66: a fixed seat configuration per aircraft (assumption 302). Brief
127-141: a seat map with Available/Booked, labels that follow the aircraft and real-time
state.

**Invariant.** A flight's seat labels are exactly its aircraft's layout. A seat is Booked
exactly when an active (`BOOKED`) row exists for it.

**Implementation.**
- `aircraft.rows_count` and `aircraft.seat_letters` define the layout, with database
  checks `ck_aircraft_rows` (1-99) and `ck_aircraft_letters` (`V1:14-15`).
- `model/SeatLayout.java:9-42` derives the labels (`seatLabels`), the capacity and
  `containsSeat`.
- The seat map marks a label Booked if `findBookedSeats` returns it
  (`repository/BookingSeatRepository.java:12-18`), otherwise Available.
- Booking rejects labels that are not on the aircraft with 422 `SEAT_NOT_ON_AIRCRAFT`
  before any write (`service/impl/BookingServiceImpl.java:61`).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Derived layout + one row per booked seat** | **Chosen** | Labels are computed from rows × letters. A `BOOKED` row means booked; no row, or only `RELEASED` rows, means available | No per-flight inventory; each layout is defined once; works with D1's lazy instances and D2's index | Availability is computed on every read (D5); no place for per-seat attributes | The smallest model that satisfies the seat map with fixed layouts | Fixed layouts without holds or seat pricing (this brief) |
| Per-instance seat inventory rows | Viable alternative | One row per (instance, seat) with a status, created with the instance; creation can be lazy, on first booking | Lockable rows for D2's conditional `UPDATE` or `FOR UPDATE`; per-seat attributes; holds | About 180 rows per instance, created race-free before the first booking; layout changes need a data migration | Adds rows and an initialization step that nothing in scope needs | Seat holds, per-seat pricing or blocking, aircraft swaps |
| Bitmap or JSON column per instance | Viable alternative | One column encodes every seat's state | Compact; a one-row read | Every booking rewrites one hot row and needs optimistic or pessimistic control, with D2's flight-level costs | Concentrates contention on one row per flight | Small aircraft, very read-heavy, few writers |

**Example.** The seeded `AT72-001` (ATR 72) has 18 rows × `ABCD` = 72 seats, `1A` to `18D`
(`V2:10`). If `3B` has a `BOOKED` row and `3C` only a `RELEASED` row, the seat map shows
`3B` Booked and `3C` Available. A booking for `19A` or `3E` matches the label format but
not the aircraft, so it is rejected with 422 before an instance is created.

**Consequences.** A booking inserts only its own seats (1 to n rows), so the write cost
follows the booking size, not the aircraft size. A cancellation turns rows into
`RELEASED`, which drops them from the partial index (D2, D4). Reads compute availability
from active rows (D5).

**Limitations and evidence.** There are no per-seat attributes, holds or blocked seats.
Evidence: S1 (`AircraftTest`), S2 (`DatabaseSchemaIT`: non-canonical labels and duplicate
letters rejected), S4 (`SeatMapIT`; `BookingIT`: an invalid seat does not even create the
instance).

**Revisit when** seat holds, blocked seats or per-seat pricing are required; then use
per-instance inventory rows, ideally created lazily per instance.

## D4. Cancellation lifecycle

**Driver.** Brief 176-183: change the booking status, release the seats and make them
bookable again; refunds are out of scope. User policy (spec 53-55): a repeated
cancellation returns 409 `ALREADY_CANCELLED`, and a cancellation at or after departure
returns 409 `FLIGHT_DEPARTED`.

**Invariant.** A booking moves from `CONFIRMED` to `CANCELLED` exactly once. Its seats
move from `BOOKED` to `RELEASED` in the same transaction, or nothing changes.

**Implementation.** `CancellationServiceImpl.cancel`
(`service/impl/CancellationServiceImpl.java:27-65`) runs in one transaction:
1. `findDetailsByReference` (404 `BOOKING_NOT_FOUND`), then `requireCancellable` (53):
   `CANCELLED` → 409 `ALREADY_CANCELLED`; departed → 409 `FLIGHT_DEPARTED`.
2. `findAssignedSeats` (32) reads the seats for the response.
3. `cancelConfirmed` (33; `repository/BookingRepository.java:22-29`) is equivalent to
   `UPDATE booking SET status = 'CANCELLED', cancelled_at = ? WHERE id = ? AND status =
   'CONFIRMED'`. A row count other than 1 → 409 `ALREADY_CANCELLED`.
4. `releaseAllSeats` (60-65; `repository/BookingSeatRepository.java:28-34`) sets
   `BOOKED` → `RELEASED`. If the count is 0 or differs from step 2, it throws
   `IllegalStateException`: the transaction rolls back and the client gets 500.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Conditional status update + bulk release, one transaction** | **Chosen** | The `WHERE status = 'CONFIRMED'` predicate arbitrates and the row count decides the winner; seats are released in bulk with a count check | Exactly one concurrent canceller wins; status and seats commit or roll back together; history kept | Bulk JPQL updates bypass the persistence context, so the response uses values read before the update | Race-safe in one statement, with no version column and no explicit lock | Single-aggregate state transitions (this design) |
| Load, mutate, save (no version, no predicate) | Unsuitable here | Read the entity, set `CANCELLED`, save | Simple | Two concurrent cancellers both succeed and both return 200 | Violates the recorded 409 repeat-cancel policy | Single-user tools where concurrent edits cannot happen |
| `@Version` on booking | Viable alternative | Optimistic check on save; the loser gets an optimistic-lock exception to translate to 409 | Standard JPA; protects multi-field edits | A version column plus exception translation, for the same outcome | The predicate is the same compare-and-set with less code | Bookings edited in several fields by several actors |
| `SELECT … FOR UPDATE` / `@Lock(PESSIMISTIC_WRITE)` on booking | Viable alternative | Lock the row, check the status, update | Explicit; also protects follow-up reads | Holds the row lock from the read until commit, with no benefit over one conditional update | Same correctness with a longer lock | Cancellation that must read related state under the lock, such as a refund calculation |
| Delete seat rows on cancel | Viable alternative | Remove the allocations instead of releasing them | Smaller table | Loses the booking's seat history | `RELEASED` rows are cheap and already ignored by the partial index | Strict data-minimization or retention rules |
| Idempotent repeat cancel → 200 | Unsuitable here | A second cancel returns the original result | Friendly to client retries | Contradicts the recorded user policy | User decision: repeat → 409 | Public APIs with automatic client retries |

**Example: two concurrent cancellations.** Two requests cancel booking R at once, and both
read `CONFIRMED` in step 1. The first `UPDATE` locks and changes the row. The second waits
on the row lock, then re-evaluates `status = 'CONFIRMED'` against the committed row (READ
COMMITTED), matches 0 rows and returns 409 `ALREADY_CANCELLED`. Exactly one caller gets
200.

**Example: cancellation racing with a new booking.** R holds `12C`, and user X books
`12C` while R is being cancelled. X's insert finds R's entry in `uq_booking_seat_active`,
whose row the uncommitted cancellation is updating, so X waits. If the cancellation
commits, the released row no longer qualifies for the index and X gets 201. If it rolls
back, X gets 409. There are never two owners.

**Consequences.**
- Transaction: one READ COMMITTED transaction; any failure rolls back both the status and
  the seats.
- Concurrency: the booking row lock orders concurrent cancellers, and the predicate makes
  the later one a no-op.
- Failure: a release count that does not match indicates corrupt state, so the
  cancellation rolls back with 500 instead of releasing only part of the booking.

**Limitations and evidence.** Only whole bookings can be cancelled; partial cancellation
and refunds are out of scope (brief 183). Evidence: S6 (`CancellationIT`: seats released
and both read APIs current; departed booking rejected; injected release failure rolls back
status and seats; simultaneous cancellations have one winner; a cancel/rebook race never
creates two owners).

**Revisit when** partial cancellation, refunds or booking edits enter scope; then use
`@Version` or a pessimistic lock on the booking aggregate.

## D5. Availability and read model

**Driver.** Brief 113-125: search returns available seats, only for flights operating on
the date. Brief 141: the seat map reflects real-time booking state. Reply 358-361: search
may be stale if the booking step tells the user about changes. Reply 363-365: search in
about 1 s; lazy loading is allowed.

**Invariant.** Available seats = aircraft capacity − active (`BOOKED`) rows for the
flight, computed from committed data at query time.

**Implementation.**
- Search is one JPQL projection (`repository/FlightScheduleRepository.java:22-35`). It
  joins schedule, aircraft and operating day, left-joins the instance and its active
  seats, computes `rows × letters − count(active seats)`, filters by route and ISO
  weekday, orders by departure time and flight number, and returns `FlightAvailability`
  records (no entities).
- `FlightServiceImpl.search` (`service/impl/FlightServiceImpl.java:30-39`) first applies
  the date window and the origin ≠ destination rule, then drops rows whose UTC departure
  instant has been reached (`FlightDatePolicy.hasDeparted`).
- The seat map lists active labels (`repository/BookingSeatRepository.java:12-18`)
  against the layout (D3).
- There is no counter column and no cache.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Live count of active rows** | **Chosen** | One aggregate per query, supported by `ix_schedule_route`, `uq_instance` and `uq_booking_seat_active` | Always the committed truth; nothing to invalidate or reconcile; search and seat map never drift apart | Cost grows with booked seats per matching flight (bounded by capacity) | Well under 1 s on the full S9 fixture | Moderate scale on one primary (this brief) |
| Counter column on the instance (`available_seats`) | Viable alternative | Booking and cancellation adjust it in the same transaction | O(1) read | Every booking on a flight updates one hot row; drifts if any write path forgets it; D2's arbitration is still needed | Adds write contention to speed up a read that is already fast | When the count query is proven dominant under measured load |
| Cache (Caffeine or Redis) with TTL or invalidation | Viable alternative | Cache search results per (route, date); seat maps stay uncached | Offloads the database | Invalidation across replicas; counts older than the database; another system for Redis. A cached seat map would break “real-time” (brief 141) | No measured read bottleneck; reply 358-361 permits staleness but does not require it | Sustained read saturation of the primary |
| Read replica | Viable alternative | Route search queries to a replica; seat maps stay on the primary | Scales reads horizontally | Replication lag; a lagging seat map would break brief 141; more infrastructure | One primary is enough at the stated scale | Read volume beyond one primary |
| Materialized view refreshed periodically | Viable alternative | Search availability precomputed per flight; seat maps read live rows | Cheap reads | Stale between refreshes; refresh cost grows with data | Not needed at this scale | Reporting and analytics reads |

**Example: why a counter can drift.** An A320 flight has 30 × 6 = 180 seats. A booking takes
three seats, so the counter reads 177. The booking is then cancelled, but a manual SQL
fix releases the seats without updating the counter: search still says 177 while the seat
map shows 180 free, and a reconciliation job becomes necessary. The live count cannot drift from the seat map,
because both are computed from the same rows.

**Departed flights today (search policy).** Search rejects a date outside the window
(422 `DATE_OUT_OF_WINDOW`), matches route and weekday in SQL, and then omits flights
whose UTC departure instant has been reached (`service/FlightDatePolicy.java:28-31`).
Booking uses the same boundary (`requireBeforeDeparture`, 33-37), so search never lists
a flight that a fresh booking would refuse as departed. The filter runs in Java on at
most one row per matching schedule, so the SQL statement and its plan are unchanged.
Search is still a snapshot: a flight that departs between search and booking returns
409 `FLIGHT_DEPARTED` (reply 358-361). The user chose this on 2026-10-22 (spec 134),
reversing the 2026-10-21 policy that listed departed flights (execution log 105).
Seat maps of departed flights remain readable.

**Consequences.** Reads run in read-only transactions on the read pool (D7) and never
write. A result is a snapshot: a seat can be taken between search and booking, and the
booking then returns 409 naming the seats (reply 358-361, D2).

**Limitations and evidence.** There are no holds, and counts age as soon as they are
returned. Evidence: S4 (`FlightSearchIT`, including
`departedFlightsAreHiddenOnlyFromTodaysSearch`, which also reads a departed flight's
seat map; `SeatMapIT`), S9 (`SearchPerformanceIT`: one SQL
statement, no entity loads, every search under 1 s with 500 schedules and 600,000 seat
rows), S10.

**Revisit when** measured read load saturates the primary; then cache or replicate search,
never the booking path.

## D6. Transaction boundaries

**Driver.** Brief 191 (transaction handling) and 265-271 (booking algorithm: transaction
flow). Reply 358-361 and the spec (“Search and seat map”): a conflict is reported to the
user with fresh details. Reply 363-365: booking stability.

**Invariant.** A booking is all-or-nothing. No statement runs in a transaction after a
failed statement. The conflict read happens only after the rollback has finished. A
request holds at most one connection at a time.

**Implementation.**
- `BookingServiceImpl` is `@Transactional(propagation = NEVER)`
  (`service/impl/BookingServiceImpl.java:28`). It runs outside any transaction and fails
  fast if a caller wraps it in one.
- Unit 1: `FlightLookupService.resolve` reads the flight in a read-write transaction
  (`service/impl/FlightLookupServiceImpl.java:21, 28`).
- Unit 2: `FlightInstanceService.getOrCreate` commits the instance before the booking
  starts (`service/impl/FlightInstanceServiceImpl.java:15-28`).
- Unit 3: `BookingTransactionService.create`, in a separate bean so Spring's proxy
  applies, inserts the booking and its seats
  (`service/impl/BookingTransactionServiceImpl.java:32-68`).
- Unit 4, only after a seat conflict: `FlightLookupService.unavailableSeats` reads in a
  new transaction (`service/impl/FlightLookupServiceImpl.java:46-47`).
- Both pools use READ COMMITTED (`application.yml:15`; `config/DataSourceConfiguration.java:28-58`).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Sequential short transactions under a `NEVER` coordinator** | **Chosen** | As above | The rollback finishes before the conflict read; one connection at a time; the instance commits at once, so concurrent first bookers do not wait for a whole booking | A failed booking can leave an empty instance (harmless, D1); more boundaries to reason about | Correct proxy and rollback semantics with the shortest lock on a new instance | Hybrid instance creation (this design) |
| One write transaction for upsert + inserts; conflict read after rollback | Viable alternative | The coordinator opens one transaction for instance and booking; after a rollback, a new transaction reads | No empty instance on failure; one boundary fewer | Concurrent first bookers of a new flight wait on the uncommitted instance insert until the first booking ends | A longer critical section on new flights for little gain | When an empty instance must never exist |
| Conflict read inside the failed transaction | Unsuitable here | Catch 23505 and query in the same transaction | No extra boundary | PostgreSQL rejects every later statement in an aborted transaction (SQLSTATE 25P02), so the 409 cannot name the seats | Breaks the conflict report the spec requires | Flows that need no statement after a failure |
| Savepoint around the seat inserts | Viable alternative | Roll back to a savepoint, then read in the same transaction | One transaction | Hibernate's session must be discarded after a failed flush, so savepoints with JPA are fragile | Sequential transactions are simpler and safer with JPA | Plain JDBC code that needs partial rollback |
| `REQUIRES_NEW` inner transactions | Viable alternative | Nested independent commits | Isolated inner work | The outer transaction keeps its connection while the inner one takes a second: two connections per request, which can starve the pool under load (D7) | Doubles connection demand for no benefit | Independent audit writes with a capacity budget |
| `INSERT … ON CONFLICT DO UPDATE … RETURNING id` | Viable alternative | One statement always returns the id | One round trip | Writes a new row version on every booking; inside a combined transaction it would also serialize all bookings of the flight on that row | An unnecessary write on the hot path | When the row must be updated anyway |
| SERIALIZABLE booking transactions | Viable alternative | Highest isolation level | Fewer anomalies to reason about | A retry loop around every writer; false-positive aborts | The index already arbitrates exactly, so READ COMMITTED suffices (D2) | Complex multi-row invariants |

The upsert is `DO NOTHING` followed by a separate `SELECT` because `DO NOTHING` returns no
id when it skips the insert. Under READ COMMITTED each statement takes a new snapshot, so
the `SELECT` sees a row that a competitor committed after the first lookup.

**Example.** A two-seat booking where `1B` was just taken:
1. The coordinator (no transaction) checks that the seats are distinct.
2. Unit 1 resolves the flight and commits; the coordinator checks departure and seat
   labels.
3. Unit 2 finds or creates the instance and commits.
4. Unit 3 inserts the booking and `1A`, fails on `1B` and rolls back.
5. Unit 4 reads the booked seats (`["1B"]`), and the client receives 409.

The request never holds two connections at once.

**Consequences.**
- Failure: if unit 3 fails for any other reason, the original exception propagates (500
  or 503, D18) and unit 4 does not run.
- Concurrency: the instance insert holds its lock only for unit 2.
- Correctness does not depend on unit 1's read staying valid: unit 3 rechecks the window
  and departure, and the index decides the seats.

**Limitations and evidence.** An empty instance can remain after a failed first booking.
The departure check runs at the start of unit 3, not at commit (D14). Evidence: S3; S4
(`BookingIT`: conflict rolls back every seat before the fresh-snapshot response; the
coordinator rejects an ambient transaction before any write); S5.

**Revisit when** one workflow must change several aggregates atomically (for example
booking plus payment), or empty instances become a reporting problem.

## D7. Read protection and connection pools

**Driver.** Reply 363-365: search in about 1 s; for booking, “ultimate stability is key”
and time may vary with load. Reply 346-348: limits apply per replica. Brief 196: no
optimization beyond a reasonable implementation.

**Invariant.** Search traffic can never take the connections that bookings need. A search
either answers within its deadlines or fails fast with 503, never with a misleading empty
result.

**Implementation.** `config/DataSourceConfiguration.java`:
- Write pool (21-26): Boot's `spring.datasource.hikari.*` binding; `airline-write-pool`,
  maximum 10, connection timeout 20 s, READ COMMITTED, `SET TIME ZONE 'UTC'`
  (`application.yml:10-16`).
- Read pool (28-46): `airline-read-pool`, maximum 5, minimum idle 1, acquire timeout
  250 ms, read-only, READ COMMITTED. Each connection sets `statement_timeout` 500 ms and
  `lock_timeout` 250 ms (`application.yml:74-79`; `config/ReadDataSourceProperties.java`).
- Router (48-58): the `@Primary` `DataSource` is a `LazyConnectionDataSourceProxy` over the
  write pool, with `setReadOnlyDataSource(read pool)`. Read-only transactions
  (`FlightServiceImpl`, `@Transactional(readOnly = true)`) get read connections. All other
  transactions get write connections, including the booking path's own reads in
  `FlightLookupServiceImpl`.
- Both pools use Boot's `JdbcConnectionDetails` when present (Testcontainers
  `@ServiceConnection`, Docker Compose), otherwise `DataSourceProperties` (60-73).
- A pool timeout returns 503 with `Retry-After: 1`
  (`exception/GlobalExceptionHandler.java:68-82`).

**Can Spring Boot auto-configuration replace this class?**

| Question | Answer |
|---|---|
| Can auto-configuration replace it? | Only with a single pool. `DataSourceAutoConfiguration` builds one Hikari pool from `spring.datasource.*` and backs off when the application defines its own `DataSource`. No property creates a second pool or read-only routing. |
| What does the class reuse from Boot? | `DataSourceProperties`, the `spring.datasource.hikari.*` binding for the write pool, and `JdbcConnectionDetails`. Only the read-pool settings and the router are custom. |
| Why `LazyConnectionDataSourceProxy` and not `AbstractRoutingDataSource`? | Routing must see the transaction's read-only flag when the connection is fetched. The lazy proxy fetches the real connection at the first statement, after the flag is set; its Spring 7 Javadoc documents this use, and `setReadOnlyDataSource` (since Spring 6.1.2) routes read-only transactions with no custom code. An eager router can fetch the connection before the flag is visible. |
| When could the class be deleted? | If one pool were accepted, for example with per-transaction timeouts plus an in-application bulkhead for search. That would lose the tested isolation between search and booking (S7). |

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Two pools on one primary, routed by the read-only flag** | **Chosen** | As above | Search overload fails fast (503 + `Retry-After: 1`) and never consumes booking connections; bookings wait patiently for a write connection | One configuration class; 15 connections per replica to budget | Tested isolation in both directions (S7) with little code | One primary serving mixed read and write traffic (this design) |
| One Boot-configured pool | Viable alternative | Default Hikari from `spring.datasource.*` | No custom code | A search spike can hold every connection, so bookings wait or time out | Gives bookings no protection from search load, which reply 363-365 prioritizes | Low, uniform traffic |
| One pool + per-transaction timeouts + search bulkhead (semaphore or Resilience4j) | Viable alternative | Limit concurrent searches in the application; `@Transactional(timeout = …)` | One pool; fine-grained limits | A new dependency or a custom limiter; per-JVM limits tuned against the pool size; connections are still shared | Two pools give the same protection with standard parts | Several downstream dependencies to protect |
| Read pool on a read replica | Viable alternative | Point the read pool at a replica | Takes read load off the primary | Replication lag (D5); more infrastructure | Not needed at the stated scale | Read volume beyond one primary |

**Example: connection budget.** Each replica holds at most 10 write + 5 read = 15
connections. Three replicas need at most 45, below PostgreSQL's default `max_connections`
of 100, which leaves room for administration and migrations. Seven replicas (105) would
exceed the default; then raise `max_connections`, shrink the pools or add a pooler (D9).

**Example: overload.** A burst of searches occupies all five read connections. The next
search waits at most 250 ms for a connection, then receives 503 with `Retry-After: 1`,
never an empty list. A booking arriving at the same time takes a write connection
immediately. The reverse also holds: with all ten write connections busy, searches still
run on the read pool.

**Consequences.**
- Failure: search waits are bounded by the acquire (250 ms), statement (500 ms) and lock
  (250 ms) deadlines. A statement timeout cancels the query, and the transaction rolls
  back before the connection is reused.
- A booking waits up to 20 s for a write connection rather than failing fast, trading
  latency for stability (reply 363-365).
- The limits bound database waits only, not HTTP queueing, network time or opening new
  connections.
- Virtual threads stay off (`spring.threads.virtual.enabled: false`,
  `application.yml:29-31`). The pools, not request threads, bound database concurrency,
  so virtual threads would admit more concurrent requests that then wait on the same
  15 connections; brief 196 asks for no optimization beyond a reasonable implementation.

**Limitations and evidence.** Both pools use the same primary, so this isolates
connections, not database CPU or I/O. Evidence: S7 (`ReadIsolationIT`: an exhausted read
pool gives a prompt 503 with `Retry-After: 1` while bookings proceed; an exhausted write
pool does not block search or seat maps; a table lock gives a prompt 503 and the read pool
recovers; the statement deadline cancels database work); S10 (`MixedLoadIT` stress phases
count 503s separately).

**Revisit when** replica count × 15 approaches `max_connections`, a read replica is
introduced, or measured contention is CPU or I/O rather than connections.

## D8. Deployment topology

**Driver.** Reply 346-348: design for multiple instances; the reviewer runs one. Reply
350-353: focus on functionality at moderate scale. Brief 188 (clean architecture).

**Invariant.** No request depends on which replica serves it. All shared state lives in
PostgreSQL.

**Implementation.**
- One Spring Boot artifact contains controllers, services and repositories (D11).
- No in-memory session, cache or lock holds shared state. ArchUnit
  `SERVICES_DO_NOT_DEPEND_ON_HTTP_OR_SESSION_STATE` keeps HTTP and session types out of
  services.
- `docker-compose.yml`: `postgres:16-alpine` plus `app`. Replicas are optional with
  `--scale app=3` (line 2), on host ports 8080-8082 (line 36).
- Every log line carries `instanceId` and `correlationId`
  (`config/RequestCorrelationFilter.java:35-52`; D18).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Stateless modular monolith + one PostgreSQL primary** | **Chosen** | One artifact; identical replicas; all state in the database | Local ACID transactions; one deployable to run and review | Search and booking scale together; the primary is the shared limit | Meets replies 346-348 and 350-353 with the fewest moving parts | Moderate scale, one team, one data store |
| Microservices (schedule, search, booking) | Viable alternative | Separate deployables, each owning its data | Independent scaling and releases | Seat availability spans services: a shared database couples them, separate data needs sagas and eventual consistency; more network failure modes and contracts | No requirement needs independent deployment; reply 350-353 asks for focus on functionality | Independent teams; very different load per domain |
| Asynchronous booking through Kafka (request topic → consumer partitioned by flight → result) | Viable alternative | The API enqueues the booking; a consumer writes it; the client polls or is notified | Absorbs write bursts; per-flight ordering from the partition key | The 409 with the conflicting seats (reply 358-361) arrives later by polling or notification; broker operations, retries and dead-letter handling | Bookings are 1 in 26 requests (reply 355-356); a synchronous 201/409 is simpler | Flash-sale bursts beyond one primary, where asynchronous confirmation is acceptable |
| Transactional outbox + Kafka for downstream events | Viable alternative | Write an event row in the booking transaction; a relay or CDC (Debezium) publishes it | Reliable events without dual writes | Needs consumers the brief does not define | No consumer is in scope | Notifications, analytics or a search index fed by booking events |

**Example.** The reviewer starts one replica with `docker compose up --build -d --wait`.
The same image with `--scale app=3` gives three interchangeable replicas; nothing in the
code changes.

**Consequences.** Search cannot be scaled separately from booking. The primary's CPU, I/O
and connections bound total capacity (D7, D9).

**Limitations and evidence.** Evidence: S8 (`CrossInstanceIT`: two application contexts on
one database); S11 (historical three-replica Compose run, `tests/nfr-evidence.md`).

**Revisit when** separate teams own search and booking, or write bursts exceed what one
primary can absorb.

## D9. On-demand scaling

**Driver.** Replies 346-348 and 350-353. User policy (spec 13-14): no fixed peak request
rate and no production autoscaler are assumed.

**Invariant.** Adding a replica adds capacity without changing correctness. The database
remains the only arbiter.

**Implementation.**
- Replicas are identical (`docker compose up --build -d --wait --scale app=3`).
- The readiness group includes the database; liveness does not (`application.yml:50-56`).
  A database outage takes replicas out of rotation without restarting them.
- The Compose healthcheck calls readiness (`docker-compose.yml:38`).
- Pool sizes are per replica (D7).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Identical stateless replicas behind a readiness-aware balancer** | **Chosen** | Same image N times; any replica serves any request | No ownership or failover logic; correctness comes from the database (D2) | Replicas × 15 connections must fit `max_connections` (D7); the primary is the write limit | The simplest model that stays correct across JVMs (S8) | Up to the capacity of one primary |
| Sticky routing or JVM-owned inventory (in-memory seat maps, `synchronized`) | Unsuitable here | Pin a flight or user to one JVM and lock in memory | Fast local locking | Another JVM can sell the same seat; failover loses ownership and state | Violates the multi-instance design (reply 346-348) and the zero double booking goal | A single-process LLD exercise or simulator |
| Autoscaler (Kubernetes HPA, cloud scaling group) | Viable alternative | Replica count follows CPU or request metrics | Elastic capacity | Platform manifests outside the deliverables; more replicas do not relieve database contention | Not part of this assignment (spec 13-14); a platform concern | A real deployment with SLOs |
| Connection pooler (PgBouncer) | Viable alternative | Many client connections share fewer server connections | More replicas per database | In transaction pooling, session-level `SET` is not reliable, so the UTC zone and read deadlines (D7) would move to role defaults or `SET LOCAL`; one more component | Three replicas fit the default limit (D7) | Replicas × 15 near `max_connections` |
| Shard by flight or date | Viable alternative | Several primaries, each owning a key range | Write capacity grows with shards | Cross-shard search, routing and re-sharding | 500 schedules (reply 350-353) fit one primary | Write volume beyond one primary |

**Example.** Replicas A and B receive bookings for seat `12C` on the same flight at the same
moment. Each inserts in its own transaction. The partial unique index admits one; the other
receives 409 naming `12C`. The replicas never talk to each other.

**Consequences.** Scaling is operational (`--scale`), not a code change. Throughput is
bounded by the primary, not by replica count.

**Limitations and evidence.** No peak request rate is claimed (spec 13-14). Evidence: S8;
S11.

**Revisit when** replicas × 15 approaches `max_connections`, or booking writes saturate the
primary.

## D10. Persistence and schema ownership

**Driver.** Brief 238-247 (database schema deliverable: migrations, keys, constraints,
indexes). User policy: Spring Data JPA (spec 83; execution log, audit 4). Brief 196.

**Invariant.** Reviewed SQL owns the schema. The application never changes it at runtime,
and the JPA mappings must match it at startup.

**Implementation.**
- Migrations live in `db/migrations/` (V1 schema, V2 seed) and are packaged as
  `classpath:db/migration` (`pom.xml` resource mapping; `application.yml:17-20`).
- Hibernate runs with `ddl-auto: validate` and `hibernate.jdbc.time_zone: UTC`
  (`application.yml:24, 28`).
- There is one native statement, the instance upsert
  `ON CONFLICT ON CONSTRAINT uq_instance DO NOTHING`
  (`repository/FlightInstanceRepository.java:20-22`). Everything else uses derived queries
  or JPQL.
- `db/schema.sql` is a generated snapshot for reading, not a second initialization path
  (`db/README.md:22`).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Spring Data JPA + Flyway SQL migrations; Hibernate validates** | **Chosen** | Flyway applies versioned SQL at startup; Hibernate checks the mappings | Standard Spring stack; DDL reviewed as plain SQL, including the partial index; startup fails fast on mapping drift | Generated SQL must be inspected (S9 counts statements); one native query | User-directed JPA; the partial index (D2) needs hand-written DDL anyway | Relational CRUD with a few hot queries |
| Spring JDBC (`JdbcClient`) or jOOQ only | Unsuitable here | Explicit SQL for every query | Full control; no ORM behaviour to learn | More mapping code; jOOQ adds code generation | The user directed Spring Data JPA (spec 83; execution log, audit 4); no query goes beyond JPQL plus one native statement | SQL-heavy reporting or complex queries |
| Hibernate `ddl-auto=update` | Unsuitable here | Hibernate alters tables at startup | Fast prototyping | Cannot express the partial unique index; unreviewed, unversioned changes on a shared database | The schema is a deliverable (brief 238-247) and holds the correctness guarantee | Throwaway prototypes |
| Liquibase | Viable alternative | Changelogs in XML, YAML or SQL | Preconditions and rollback support | Another format to review | Plain SQL in `db/migrations` reads directly as the schema deliverable | Teams that need rollbacks or several database vendors |
| `spring.sql.init` (`schema.sql` at every start) | Viable alternative | Boot runs scripts on startup | No extra library | No version history; every replica runs the scripts | Replicas need versioned migrations that apply once | Embedded demo databases |

**Example.** A developer adds a field to `Booking` without a migration. The application
refuses to start with a schema-validation error rather than altering the shared table.

**Consequences.** Concurrent replica starts are safe because Flyway serializes migrations
with a PostgreSQL advisory lock. Schema changes need a migration and a review.

**Limitations and evidence.** Evidence: S2 (`DatabaseSchemaIT` asserts the constraints and
indexes); `AirlineReservationApplicationIT.contextLoadsAndMigrationsApply`.

**Revisit when** reporting queries outgrow JPQL, or schema changes need zero-downtime
expand-and-contract steps.

## D11. Code organization

**Driver.** Brief 188-189 (clean architecture, separation of concerns); brief 217-221
(LLD: modules, classes, services, APIs). User policy (spec 72-81): root-level
package-by-layer, no feature packages, no repository ports and adapters.

**Invariant.** Dependencies point one way: controller → service contract → implementation
→ repository → entity. No layer depends on the layer above it.

**Implementation.** Packages under `com.airline.reservation`: `controller`, `dto.request`,
`dto.response`, `service` (contracts and `FlightDatePolicy`), `service.impl`, `model`,
`repository`, `entity`, `exception`, `validation` and `config`. ArchUnit rules in
`src/test/java/com/airline/reservation/ArchitectureTest.java` enforce the direction, for
example:
- `CONTROLLERS_USE_SERVICES_AND_DTOS`: controllers never touch repositories or entities.
- `APPLICATION_CONTRACTS_ARE_PERSISTENCE_AND_TRANSPORT_INDEPENDENT`: service contracts and
  models never reference entities, repositories, DTOs, JPA or Spring Data.
- `COLLABORATORS_DEPEND_ON_SERVICE_CONTRACTS`: nothing depends on `service.impl`.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Package-by-layer; service interfaces; flat Spring Data repositories** | **Chosen** | As above | Familiar to Spring teams; one place per concern; direction enforced by tests (S1) | A feature change touches several packages; implementations know JPA | User decision (spec 72-81) | One team, a few closely related workflows |
| Package-by-feature (`schedule`, `search`, `booking`) | Unsuitable here | Each feature owns its controller, service and repository | High cohesion; package-private boundaries | Shared entities (flight instance, booking) cross features anyway | Spec 74: “Do not group packages by feature” | Many features with separate owners |
| Hexagonal (ports and adapters) | Unsuitable here | Domain core with repository ports; JPA and HTTP adapters | Persistence-independent core | One extra interface and mapping per port, each with a single adapter | Spec 79-80: the user chose layers over ports and adapters | Several storage or transport adapters; long-lived domain |
| Maven multi-module layering (`api`, `domain`, `persistence`) | Viable alternative | Layers as separate modules | The compiler enforces the direction | More build files; slower builds | ArchUnit gives the same checks in one module | Large codebases shared by several teams |

**Example.** Cancelling a booking touches `BookingController` →
`CancellationService` → `CancellationServiceImpl` → `BookingRepository` and
`BookingSeatRepository`. If a controller imported `BookingRepository`, the build fails in
`ArchitectureTest`.

**Consequences.** Layer rules are build failures, not conventions. Implementations are not
persistence-independent, by design (spec 79-80).

**Limitations and evidence.** Evidence: S1 (`ArchitectureTest`, `SourceConventionsTest`).

**Revisit when** a second team owns a workflow, or a second storage or transport adapter
appears.

## D12. Service contracts

**Driver.** Brief 189 (separation of concerns), 195 (testability). User policy (spec
75-81): use-case DTOs, never entities; narrow service interfaces; immutable commands and
results; no catch-all DTOs.

**Invariant.** No JPA entity or HTTP type crosses a service contract. The JSON shape and
the table shape can change independently.

**Implementation.**
- Contracts are interfaces in `service` (`SERVICE_CONTRACTS_ARE_INTERFACES`).
- Inputs and outputs are immutable records in `model`: `CreateScheduleCommand` and
  `CreateBookingCommand` in; `ScheduleDetails`, `FlightAvailability`, `SeatMap`,
  `BookingResult` and `BookingDetails` out.
- Request DTOs map themselves with `toCommand()`
  (`dto/request/CreateBookingRequest.java:17`); response DTOs with static `from(...)`
  (`dto/response/BookingResponse.java:20`). Controllers only call these and the service.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Narrow use-case interfaces with immutable records** | **Chosen** | For example `BookingService.create(CreateBookingCommand)` returns `BookingResult` | No entity or JSON leakage; contracts are easy to mock (S1); records cannot be changed after creation | Mapping code at two edges | Meets brief 189 and 195; enforced by ArchUnit | Any service with a public API |
| Generic CRUD services returning entities to controllers | Unsuitable here | `save` and `findAll`; entities serialized to JSON | Fewer types | API shape tied to table shape; with open-session-in-view off (D13) a lazy association fails during rendering | Spec 75-76 forbids entities in the API; mixes persistence and transport (brief 189) | Internal admin tools; Spring Data REST |
| An interface for every helper (`FlightDatePolicy`, `SeatLayout`) | Viable alternative | Abstract each collaborator | Uniform mocking | Indirection with one implementation each | Pure policies are tested directly; only use-case services are contracts | Plugin systems |
| MapStruct mappers | Viable alternative | Generated mapping code | Less hand-written mapping | An annotation processor and dependency | Each mapping is a few lines; spec 90 prefers existing JDK and Spring facilities | Large DTO graphs |

**Example.** `POST /api/v1/bookings` → `CreateBookingRequest` (validated DTO) →
`toCommand()` → `CreateBookingCommand` → `BookingService.create` → `BookingResult` →
`BookingResponse.from(...)`. The `Booking` entity never leaves `service.impl`.

**Consequences.** Renaming a JSON field touches only a DTO. Renaming a column touches only
an entity and a migration.

**Limitations and evidence.** Evidence: S1 (`ArchitectureTest`); S4 (`HttpContractsIT`).

**Revisit when** the API needs a second representation of the same use case (for example a
versioned v2 response).

## D13. ORM fetching and writes

**Driver.** Brief 196 (reasonable performance). Reply 363-365 (search in about 1 s).
Engineering choice recorded in spec 84-86: search projects `FlightAvailability`;
operating-flight resolution fetches the aircraft explicitly; open-session-in-view is off;
native SQL only for the upsert.

**Invariant.** Statement counts do not grow with the number of results or seats read. No
lazy load runs outside a transaction.

**Implementation.**
- Search is one JPQL constructor expression into `FlightAvailability` records, with the
  live count (`repository/FlightScheduleRepository.java:22-35`). No entity is loaded.
- Seat maps and conflict checks read seat labels with scalar queries
  (`repository/BookingSeatRepository.java:12-26`).
- Cancellation looks up the booking with one joined JPQL constructor expression into
  `BookingDetails` (`repository/BookingRepository.java:13-20`). No entity is loaded, so
  nothing managed can disagree with the bulk updates that follow, and nothing is
  dirty-checked at commit.
- One to-one entity graph reads one row by a unique key with no collection:
  `FlightScheduleRepository.findOperating` fetches `aircraft` (14-20).
- Every `@ManyToOne` is `LAZY` (`entity/`); open-session-in-view is off
  (`application.yml:22`).
- Cancellation uses status-guarded bulk updates (`BookingRepository.java:22-29`,
  `BookingSeatRepository.java:28-34`; D4).
- IDs use `IDENTITY`, so seat inserts are one statement per seat.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Projections for search and the cancellation lookup; scalar seat queries; one to-one entity graph; open-session-in-view off** | **Chosen** | As above | Fixed statement counts; nothing lazy outside a transaction; small payloads | A few explicit queries to maintain | Plain Spring Data features give one statement per search (S9) and no entity loads in cancellation | Read-heavy APIs on JPA |
| Lazy loading with open-session-in-view (Spring Boot's default) | Viable alternative | Entities load on access, including during JSON rendering | Least code | N+1 queries per result row; the persistence context and its connection stay open until the response is written | Spec 85 records it as off; hidden queries and longer connection holds would weaken the pool isolation (D7) | Server-rendered pages with simple graphs |
| Eager to-one fetching (`FetchType.EAGER`, the JPA default for to-one) | Viable alternative | Associations always loaded | No lazy-initialization errors | In JPQL, each eager association not join-fetched costs an extra select per row (N+1); every use case pays for every association | Use cases need different graphs; explicit graphs keep each query minimal | Small associations that every use case needs |
| DTO projection for flight resolution as well | Viable alternative | `findOperating` returns a record from JPQL instead of the schedule with its aircraft | No managed entities on any path | More query text and one more record | `resolve` maps one row once and no bulk update follows it, so a managed copy cannot go stale | Wide rows or large entities |
| `@BatchSize` or `default_batch_fetch_size` | Viable alternative | Lazy associations load in batches with `IN` lists | Fixes N+1 without explicit queries | Still several statements; tuning per association | No lazy association is traversed in a loop | Graphs with collections |
| `SEQUENCE` IDs + JDBC batching (`hibernate.jdbc.batch_size`, `order_inserts`) | Viable alternative | Hibernate pre-allocates IDs and sends inserts in batches | Fewer round trips for many seats | A migration from IDENTITY; batch settings to tune | Bookings carry a few seats; one insert per seat is acceptable here | Large write batches with measured round-trip cost |

**Example.** A search for DXB→LHR on a date with many matching schedules is one SQL
statement whether or not instances exist (left joins, D1). A seat map is two statements:
resolve the flight with its aircraft, then read its booked seat labels.

**Consequences.** `reWriteBatchedInserts=true` in the JDBC URL (`application.yml:7`,
`docker-compose.yml:32`) currently has no effect: Hibernate does not batch inserts with
IDENTITY IDs and no batch size is configured, so the driver has nothing to rewrite. It
matters only after a move to SEQUENCE IDs with batching.

**Limitations and evidence.** Search counts `seatLabel`, a column held in the partial
index `uq_booking_seat_active`, so the active-seat join can be an index-only scan
([NFR evidence §7.3](../../tests/nfr-evidence.md#73-manual-sql-probe-active-seat-count-column));
heap pages are still visited until vacuum marks them all-visible. Hibernate renders the
enum constants in these JPQL statements as SQL literals (`status = 'BOOKED'`), so
matching the partial index does not depend on bind values or on PostgreSQL's plan-cache
mode. Evidence: S9 (`SearchPerformanceIT` asserts one prepared
statement and zero entity loads per search); `CancellationIT` asserts four prepared
statements and zero entity loads per cancellation.

**Revisit when** bookings with many seats make insert round trips measurable, or a use case
needs a collection fetch.

## D14. Time and booking horizon

**Driver.** Brief 37 and assumption 305 (“up to one year”); brief 92-93 (“365 days from
the current date”); assumption 306 (UTC); assumption 307 (time-zone conversion out of
scope). Engineering choice recorded in spec 41-45: UTC everywhere; exactly 365 dates as
an explicit interpretation of the brief. User policy (spec 134, 2026-10-22): search omits
flights whose UTC departure instant has been reached.

**Invariant.** A travel date is valid when `today ≤ date < today + 365 days`, where
`today` is the UTC date of an injected `Clock`. A flight has departed once the clock
reaches its UTC departure instant; it is then omitted from search, and booking or
cancellation is refused.

**Implementation.**
- `service/FlightDatePolicy.java`:
  - `requireInWindow` (20-26): `date.isBefore(today) || !date.isBefore(today.plusDays(windowDays))`
    raises `DATE_OUT_OF_WINDOW` (422).
  - `hasDeparted` (28-31) is the single definition: `!now.isBefore(departureInstant)`.
  - `requireBeforeDeparture` (33-37) delegates to it and raises `FLIGHT_DEPARTED` (409).
- `windowDays` is fixed by `config/BookingProperties.java:10` (`@Min(365) @Max(365)`) and
  `application.yml:73`.
- `config/TimeConfiguration.java:11` provides `Clock.systemUTC()`; tests inject a fixed
  clock.
- The window is checked by search (`FlightServiceImpl.java:31`), flight resolution
  (`FlightLookupServiceImpl.java:29`) and again at the start of the booking transaction
  (`BookingTransactionServiceImpl.java:36`).
- Departure is filtered from search results (`FlightServiceImpl.java:37`), checked by the
  coordinator (`BookingServiceImpl.java:42`), at the start of
  the booking transaction (`BookingTransactionServiceImpl.java:37`) and by cancellation
  (`CancellationServiceImpl.java:57`).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **UTC injected `Clock`; half-open window of exactly 365 dates** | **Chosen** | As above | Exactly 365 dates in every year; identical on every replica; deterministic tests | Departure is checked at the start of the write, not at commit | Meets brief 92-93 and assumption 306 | Single-zone or UTC-only systems |
| `plusYears(1)` or an inclusive end date | Unsuitable here | `date ≤ today.plusYears(1)` | Reads like “one year” | 366 dates when the window contains 29 February, or always with an inclusive end | Contradicts “365 days” (brief 92-93) | Products that promise one calendar year |
| Host-local time (`LocalDate.now()` in the JVM zone) | Unsuitable here | Uses the server's default zone | No configuration | Replicas in different zones disagree about “today” near midnight | Conflicts with assumption 306 (UTC) and with identical behaviour on every replica (reply 346-348) | Single-host tools in one zone |
| Airport-local times with zone conversion | Unsuitable here | Store schedules in each airport's zone; convert for departure | Realistic for travellers | Zone data, daylight-saving gaps and overlaps | Out of scope (assumption 307; brief 29-30) | A real multi-zone airline |
| Departure check at commit (trigger or final statement) | Viable alternative | Re-check the departure time just before commit | Narrows the gap between check and commit | Cannot remove it: time still passes before the commit completes; more code | The gap is the length of one short transaction | Strict regulatory cut-offs |
| **Search omits departed flights with a Java filter after the query** | **Chosen** | `hasDeparted` is applied to each `FlightAvailability` row returned by the unchanged JPQL | One definition of “departed” shared with booking (DRY); SQL text and plans unchanged; tested with the fixed `Clock` | Departed rows are still read and counted, then discarded (at most one per matching schedule) | User decision 2026-10-22 (spec 134): search no longer lists flights that a fresh booking would refuse | User-facing search (this brief) |
| Departure predicate in the JPQL query | Viable alternative | Add `date > today OR departureTime > now` with the current time as a parameter | Departed rows never leave the database | Duplicates the departure rule in SQL; adds a clock-dependent parameter to the hot query, so its plan must be re-verified | The Java filter discards at most one row per schedule, so pushing it down saves almost nothing | Large result sets where filtering in the database saves transfer |
| List departed flights; refuse only at booking | Viable alternative | Search matches route and weekday only; booking returns 409 `FLIGHT_DEPARTED` | Simplest query; search does not depend on the time of day | Users see flights they cannot book, even from a fresh query | Previous policy (2026-10-21, execution log 105), reversed by the user on 2026-10-22 | Back-office views that must show the whole day |

**Example.** On 2026-10-21 (UTC) the valid dates are 2026-10-21 to 2027-10-20 inclusive:
365 dates. On 2027-10-21, `plusYears(1)` would allow 366 dates, because that window
contains 2028-02-29; the chosen rule still allows 365. A flight departing today at 09:00
UTC is listed by a search at 08:59 and omitted from one at 09:00 or later; it still
appears when searching tomorrow's date. A booking that starts at 09:00 returns 409
`FLIGHT_DEPARTED`.

**Consequences.** Every replica computes the same window and the same departure boundary.
A booking that starts just before departure can commit just after it.

**Limitations and evidence.** A flight can depart between search and booking; booking then
returns 409 `FLIGHT_DEPARTED` (reply 358-361). Seat maps of departed flights remain
readable.
Evidence: S1 (`FlightDatePolicyTest`: exactly 365 dates across a leap year; exact UTC
departure boundary for `hasDeparted` and `requireBeforeDeparture`); S4
(`FlightSearchIT.windowBoundariesAreEnforcedOnSearch`;
`SeatMapIT.windowBoundariesAreEnforcedOnSeatMap`;
`FlightSearchIT.departedFlightsAreHiddenOnlyFromTodaysSearch`: 07:59, 08:00 and 08:01
departures against a fixed 08:00 UTC clock, and the departed flight's seat map still
returns all 180 seats); S6
(`CancellationIT.departedBookingCannotBeCancelled`).

**Revisit when** airport-local times enter scope, or the business needs a hard cut-off
before departure (for example check-in closing).

## D15. Public identity

**Driver.** Brief 151: “Booking Reference (PNR or UUID)”. Assumption 303: schedules do not
change after creation. Engineering choice recorded in spec 46-49: one immutable schedule
per flight number; UUID references; no custom PNR generator.

**Invariant.** A booking reference identifies exactly one booking and cannot be guessed. A
flight is identified by `(flightNumber, flightDate)`.

**Implementation.**
- `UUID.randomUUID()` (version 4) in the booking transaction
  (`BookingTransactionServiceImpl.java:38`).
- `uq_booking_reference` (`V1:56`) is the final guard; an actual collision would surface
  as a 500, not as a silent duplicate.
- `uq_schedule_flight_number` (`V1:29`) makes the flight number unique;
  `uq_instance (schedule_id, flight_date)` (`V1:46`) makes the flight on a date unique.
- Surrogate `IDENTITY` primary keys stay internal.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Random UUID reference; flight = number + date** | **Chosen** | As above | 122 random bits: collisions negligible, no retry loop; not enumerable; no database round trip | 36 characters; awkward to read aloud | Brief 151 allows UUID; spec 49 | References consumed by programs |
| 6-character PNR (for example `K7Q2ZD`) | Viable alternative | Random alphanumeric code with a uniqueness retry | Human-friendly; airline convention | Collisions grow with volume, so inserts need a retry on `uq_booking_reference`; short codes are easier to guess, so lookups usually add a surname check | Brief allows it; a UUID needs no retry code | Call centres, printed itineraries, GDS integration |
| Time-ordered UUID (version 7) | Viable alternative | Timestamp prefix + random bits | Still a UUID; index-friendly ordering | Reveals creation time; the JDK has no built-in generator | The reference is not the primary key, so ordering brings no benefit | When the UUID is the primary key |
| Sequential number (`BIGSERIAL`) as the reference | Unsuitable here | Expose the database ID | Short and ordered | Guessable: anyone could cancel another booking by trying nearby numbers | Neither PNR nor UUID (brief 151) | Internal identifiers only |
| Versioned schedule identity (number + version or validity period) | Viable alternative | Several schedules per flight number over time | Supports schedule changes | More model, API and migration work | Schedules do not change (assumption 303) | Schedule changes in scope |
| Flight-instance ID in URLs | Viable alternative | `/flights/{instanceId}/seat-map` | Short, stable keys | Never-booked flights have no instance row under D1, so the ID would not exist yet | Fits only full materialization (D1) | Fully materialized inventories |

**Example.** `POST /api/v1/bookings` returns `"bookingReference": "6f1d…"` (a UUID). The
customer cancels with `POST /api/v1/bookings/6f1d…/cancel`. A well-formed but unknown
reference returns 404 `BOOKING_NOT_FOUND`. A malformed one returns 400.

**Consequences.** References reveal nothing about volume or order. Flight number `XY101`
on 2026-11-02 always means the same flight.

**Limitations and evidence.** Without a second factor, possession of the reference is
enough to cancel (authentication is out of scope, spec 227-231). Evidence: S4 (`BookingIT`
checks the six response fields; `HttpContractsIT` returns 400 for a malformed reference);
S6 (`CancellationIT` cancels by reference and returns 404 for an unknown one).

**Revisit when** humans must type references, or schedules become editable.

## D16. API scope and retries

**Driver.** Brief 29-30: functionality not explicitly mentioned is out of scope. Brief
32-37 (scope). Spec 227-232: no listing or retrieval APIs, no idempotency keys (deferred).

**Invariant.** The API exposes exactly the brief's workflows. Each call is a complete
operation; no call needs a follow-up call to finish.

**Implementation.** Five endpoints (D22): create schedule, search, seat map, book, cancel.
Airports and aircraft are preloaded by `V2`, with no CRUD (brief 304). A 503 response says
“do not blindly retry writes” (D18).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Exactly the operations the brief names** | **Chosen** | As above | Matches the brief; nothing extra to secure or maintain | A lost 201 leaves the client without its reference, and no API can find it | Brief 29-30 | This assignment |
| `Idempotency-Key` header with the stored first response | Viable alternative | The server stores key → response in the booking transaction and replays it for a repeated key | Safe retries of `POST /bookings` | A new table, key expiry, concurrent requests with the same key, a contract for clients | Spec 232 defers it; the brief does not ask for it | Public APIs on unreliable networks |
| Client-supplied booking reference | Viable alternative | The client sends its own UUID; a retry hits `uq_booking_reference` and the server returns the existing booking | Idempotency with the existing constraint | The server must check that the retry matches the original request; the client must generate good UUIDs | Same reason as above | Mobile or offline-first clients |
| Additional endpoints (booking retrieval or listing, airport or aircraft CRUD, authentication, payments) | Unsuitable here | Invented operations | Convenience | Untested scope; misrepresents the assignment | Brief 29-30; spec 227-232 | When the brief adds those workflows |

**Example.** A client books seats `3A` and `3B` and the 201 is lost. Retrying the same
request returns 409 `SEAT_UNAVAILABLE` naming `3A` and `3B`, now held by the client's own
first booking, whose reference the client never received. Retrying with different seats
would create a second booking.

**Consequences.** Clients must not blindly retry a booking after a timeout. This is the
main operational cost of the minimal scope and is documented rather than hidden.

**Limitations and evidence.** Evidence: S4 (`HttpContractsIT`: unknown routes and methods);
S12 (Postman/Newman collection in `postman/`, runs H4 and R6).

**Revisit when** clients retry automatically (mobile networks, gateways), or customers need
to look up their bookings.

## D17. HTTP negotiation and strict clocks

**Driver.** Brief 190 (RESTful API design) and 193 (validation). Engineering choice
recorded in spec 127-130: times use `HH:mm` and are parsed strictly; response media types
are negotiated before the handler runs, so an incompatible `Accept` header returns 406
without writing anything.

**Invariant.** A request rejected for a protocol reason (unknown route, wrong method,
unsupported body type, unacceptable response type, malformed time) is rejected before any
service method runs: no schedule, flight instance, booking or cancellation is written. A
stored time is exactly the `HH:mm` value the client sent, from `00:00` to `23:59`.

**Implementation.**
- Class-level `produces = MediaType.APPLICATION_JSON_VALUE` on every controller
  (`controller/ScheduleController.java:18-20`, `BookingController.java:21`,
  `FlightController.java:20`). Spring MVC matches `Accept` while it selects the handler,
  so `Accept: application/xml` ends in 406 before the method is called.
- Request bodies are JSON records read with `@RequestBody`. Any other `Content-Type` fails
  with 415 while arguments are resolved, still before the method body runs. Cancellation
  takes no body (`BookingController.java:34-35`), so an empty `POST` needs no
  `Content-Type`.
- `dto/request/CreateScheduleRequest.java:21-22`:
  `@JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE)`.
- `exception/GlobalExceptionHandler.java:117-153` (`handleExceptionInternal`) gives
  Spring's protocol errors specific codes: 404 `REQUEST_NOT_FOUND`, 405
  `METHOD_NOT_ALLOWED`, 415 `UNSUPPORTED_MEDIA_TYPE`, 406 `NOT_ACCEPTABLE`. Every other
  client error (unreadable JSON, a bad time, a missing parameter) is 400
  `VALIDATION_FAILED`.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **`produces` on the mapping; strict `HH:mm`** | **Chosen** | As above | A 406 or 415 always means nothing was written; times are stored exactly as sent | Clients must accept JSON (or `*/*`); `24:00` and `09:30:00` are rejected rather than repaired | Error responses must describe the real outcome (brief 192); validation must not alter data (brief 193) | APIs with one representation |
| Negotiate only when writing the response (no `produces`) | Unsuitable here | The handler runs first; the response converter is chosen afterwards | No annotation | A booking commits, then the client receives 406: its seats are held by a booking whose reference it never sees, and no API can find it (D16) | Reports a committed write as a failure (brief 192) | Read-only endpoints, where running the handler first has no side effect |
| Ignore `Accept` and always send JSON | Viable alternative | Treat the response as not subject to negotiation, which HTTP permits (RFC 9110 §12.5.1) | Writes never fail because of `Accept` | The client receives a type it said it cannot read | Spec 129-130 chose an explicit 406 | Internal APIs whose clients always parse JSON |
| Explicit `consumes = JSON` on the write mappings | Viable alternative | Reject other content types during handler selection | The OpenAPI document states the request type | Same outcome as today (415 before the method runs); one more attribute per mapping | No behavioural gain | When generated API documentation must list request types |
| Lenient time parsing | Unsuitable here | `24:00` is read as `00:00` | Accepts sloppy input | Silently stores a different time from the one sent, moving a departure by a day | Validation must reject bad input, not rewrite it (brief 193) | Interactive input where the parsed value is shown back for confirmation |
| Default ISO `LocalTime` parsing | Viable alternative | Accepts `09:30`, `09:30:15` and `09:30:15.5` | No annotation | Seconds are stored but responses show `HH:mm`, so two schedules can differ invisibly | Spec 127-128 fixes `HH:mm` | APIs that need second precision |

**Example.** `POST /api/v1/bookings` with `Accept: application/xml` returns 406
`NOT_ACCEPTABLE` and the `booking` table is unchanged. A schedule with
`"departureTime": "24:00"` returns 400 `VALIDATION_FAILED` and nothing is stored;
`"00:00"` and `"23:59"` are accepted.

**Consequences.** Every rejection in this group is safe to retry once the client fixes the
request. Content validation (D23) runs only after negotiation and parsing succeed.

**Limitations and evidence.** Negotiation does not help when a response is lost after a
commit (D16). Evidence: S4 (`HttpContractsIT`):
- `unsupportedResponseTypesAreRejectedBeforeAnyWrite`: schedule, booking and cancellation
  with `Accept: application/xml` return 406 and change no rows.
- `scheduleClocksAreStrictRatherThanSilentlyNormalized`: `24:00`, `25:00`, `23:60`,
  `12:00:01` and `1:00` return 400 for both time fields.
- `protocolFailuresRetainTheirStatusAndSpecificCode`: 405, 415 and 404 with their codes.

**Revisit when** a second representation is needed (for example CSV for a partner), or
times need seconds.

## D18. Errors and logging

**Driver.** Brief 192 (error handling) and 194 (logging). Reply 358-361: stale search is
acceptable only if the booking step tells the user what changed. Reply 363-365: booking
stability matters more than speed. Engineering choice recorded in spec 142-146 and
200-201: RFC 9457 problem details with a stable `errorCode`; no payloads or passenger
names in logs; correlation context bounded, returned and cleaned up.

**Invariant.** Every error response is `application/problem+json` with a stable
`errorCode` that clients can branch on. Only the named constraint `uq_booking_seat_active`
becomes a seat conflict. No response or log line contains passenger names, request
bodies, SQL text or stack traces. Every log line written while serving a request carries
its correlation ID and instance ID.

**Implementation.**
- `exception/ErrorCode.java` has 18 codes. `GlobalExceptionHandler.java:38-44` builds
  each problem with `type` `urn:airline:error:<code>` (for example
  `urn:airline:error:seat-unavailable`) and an `errorCode` property. `business` (47-66)
  maps codes to statuses:

  | Status | Codes |
  |---|---|
  | 400 | `VALIDATION_FAILED`, `SAME_ORIGIN_DESTINATION`, `DUPLICATE_SEAT` |
  | 404 | `FLIGHT_NOT_FOUND`, `BOOKING_NOT_FOUND`, `REQUEST_NOT_FOUND` |
  | 405, 406, 415 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` |
  | 409 | `DUPLICATE_SCHEDULE`, `SEAT_UNAVAILABLE`, `ALREADY_CANCELLED`, `FLIGHT_DEPARTED` |
  | 422 | `UNKNOWN_REFERENCE`, `DATE_OUT_OF_WINDOW`, `SEAT_NOT_ON_AIRCRAFT` |
  | 503 | `SERVICE_UNAVAILABLE`, with `Retry-After: 1` |
  | 500 | `INTERNAL_ERROR` |

- **Seat conflict.** `BookingTransactionServiceImpl.java:61-64` converts a unique
  violation of `uq_booking_seat_active` (Hibernate's `UNIQUE` constraint kind and the
  constraint name, checked by `exception/PersistenceConstraints.java`) into
  `SeatConflictException`, and nothing else. After the rollback,
  `BookingServiceImpl.java:79-87` reads which requested seats
  are now taken and raises 409 `SEAT_UNAVAILABLE` with `currentlyUnavailableSeats`.
  `ScheduleServiceImpl.java:70-74` maps only `uq_schedule_flight_number` to 409
  `DUPLICATE_SCHEDULE`. Every other integrity violation stays a 500.
- **Database unavailable.** `databaseUnavailable` (`GlobalExceptionHandler.java:68-82`)
  maps `TransientDataAccessException`, `DataAccessResourceFailureException` and
  `TransactionException` to 503 + `Retry-After: 1` with the detail “A write outcome may be
  unknown; do not blindly retry writes.” `jpaFailure` (84-96) classifies Hibernate-wrapped
  JDBC errors with Spring's `SQLExceptionSubclassTranslator`, so a PostgreSQL shutdown is
  also a 503; unclassified ORM failures stay 500.
- `server.error.include-message: never` and `include-stacktrace: never`
  (`application.yml:39-40`).
- **Correlation.** `config/RequestCorrelationFilter.java` runs first. It accepts one
  `X-Correlation-ID` of 1-64 characters from `[A-Za-z0-9._-]`; without one it generates a
  UUID; a malformed or repeated header gets a fresh UUID and a 400. It puts
  `correlationId` and `instanceId` (from `airline.instance-id`, default `HOSTNAME`) into
  the MDC, echoes the header, and removes both keys in `finally` (lines 41-75). The
  console pattern prints both on every line (`application.yml:65`).
- **Events** are key=value: `booking_confirmed` (`BookingServiceImpl.java:46-51`),
  `booking_cancelled` (`BookingController.java:37-38`), `schedule_created`
  (`ScheduleController.java:30`), `request_rejected` (`GlobalExceptionHandler.java:64`,
  111, 150-151). `database_operation_unconfirmed` and `unexpected_request_failure` go
  through `logFailure` (155-165), which writes exception type, root type, SQLSTATE,
  constraint name and the first application stack frame, never exception messages.
  Hibernate's SQL error loggers are off (`application.yml:68-69`), because PostgreSQL
  error details can contain row values such as passenger names.
- **Success events are logged only after commit.** `booking_cancelled` and
  `schedule_created` are written by the controllers, after the `@Transactional` service
  call has returned and committed. `booking_confirmed` is written by the coordinator,
  which runs with `Propagation.NEVER` (`BookingServiceImpl.java:28`), so the booking
  transaction has already committed. A rollback therefore never leaves a success line in
  the log.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **RFC 9457 problem details + stable `errorCode`; named-constraint mapping; MDC correlation; metadata-only failure logs** | **Chosen** | As above | Standard media type with built-in Spring support (`ProblemDetail`); clients branch on codes; a seat conflict names the seats; logs are traceable per request and free of personal data | Diagnosing a 500 relies on SQLSTATE, constraint and stack-frame metadata, not messages | Meets reply 358-361 (the booking tells the user which seats changed) and brief 192, 194 | Any HTTP API |
| Spring Boot's default `/error` body | Unsuitable here | `timestamp`, `status`, `error`, `path` | No code to write | No stable code and no seat list, so a user who saw stale search results is not told what changed | Fails reply 358-361 | Prototypes |
| Custom error envelope (`{"code", "message"}`) | Viable alternative | Own record returned by `@ControllerAdvice` | Any shape | A house format clients must learn; duplicates what `ProblemDetail` provides | Spec 142 chose the standard | Clients that already parse a house format |
| Map every integrity violation to 409 | Unsuitable here | Catch `DataIntegrityViolationException` broadly | Simple | A schema bug becomes “seats unavailable”: the user is told seats changed when they did not, and the bug is hidden | Fails reply 358-361 and brief 192 | Tables where every violation means the same business conflict |
| Retry transient failures inside the server | Viable alternative | Spring Retry around the booking call | Fewer 503s reach clients | Safe only when the failure happened before the commit was sent; after a connection loss during commit the outcome is unknown, and a retry returns 409 for the client's own seats | The client decides, with the outcome stated in the 503 (D16) | Idempotent operations, or writes protected by idempotency keys |
| Log request bodies and SQL with bound values | Viable alternative | Debug logging of payloads and parameters | Fast local debugging | Passenger names in logs; larger logs | Spec 146 and 200 keep payloads and names out of logs | Local debugging behind a flag that is off by default |
| Structured JSON logs | Viable alternative | Spring Boot's built-in structured logging (`logging.structured.format.console`) | Machine-readable fields for a log platform | Harder to read on a console | One service with console logs; a configuration change when needed | Central log aggregation (ELK, Loki, CloudWatch) |
| Distributed tracing (OpenTelemetry, W3C `traceparent`) | Viable alternative | Trace and span IDs propagated across services | End-to-end traces | Collector and agent infrastructure | One service; the correlation ID covers the single hop | Several services or asynchronous hops |

**Example.** A booking for seat `3A` loses the race (abridged response):

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
X-Correlation-ID: 3f2c…

{
  "type": "urn:airline:error:seat-unavailable",
  "status": 409,
  "detail": "Selected seats are no longer available. Refresh the seat map before choosing again.",
  "errorCode": "SEAT_UNAVAILABLE",
  "currentlyUnavailableSeats": ["3A"]
}
```

The log line reads `[<instance>/3f2c…] … request_rejected status=409
code=SEAT_UNAVAILABLE`; no passenger name appears.

**Consequences.** Clients branch on the status and `errorCode`, never on `detail` text. A
503 tells the client that a write may or may not have happened. A 500 always means a
defect or an unclassified failure, logged with metadata that can be found by
correlation ID.

**Limitations and evidence.** Logs go to the console only: no aggregation, metrics
dashboards or tracing (one service). Evidence:
- S1 (`GlobalExceptionHandlerTest`, 7 tests): 503 wording and `Retry-After`; a
  Hibernate-wrapped PostgreSQL shutdown is a 503; an unclassified failure is a 500; no
  exception messages, SQL or passenger values in logs; invalid metadata and cyclic causes
  are handled.
- S4: `HttpContractsIT` (problem details without echoing the payload; correlation ID
  echoed, generated, validated and cleared; no passenger names in errors or logs);
  `BookingIT` (409 lists `currentlyUnavailableSeats`;
  `unrelatedIntegrityFailureIsNotDisguisedAsSeatConflict`).
- S7 (`ReadIsolationIT`: 503 + `Retry-After: 1`); S8 (`CrossInstanceIT`: database down
  gives 503).

**Revisit when** logs are shipped to a central platform (switch to structured output), or
a second service joins a request path (add tracing).

## D19. Verification strategy

**Driver.** Brief 195 (testability); evaluation criteria 284 (correctness), 289
(concurrency handling) and 293 (testing approach). Reply 370-372 (zero double booking)
must be demonstrated, not asserted. Engineering choice recorded in spec 197-200: real
PostgreSQL rather than H2, plus direct database, concurrency, DTO and architecture tests.
User decision on 2026-10-21 (execution log 104): no `MixedLoadIT` instrumentation or
diagnostic re-runs.

**Invariant.** Every correctness claim in this log maps to an automated test that runs
against the production database engine with the real Flyway migrations. Latency claims
come only from recorded runs in `tests/nfr-evidence.md`.

**Implementation.**
- **Unit and slice tests** (`*Test`, Surefire, no Docker): `ArchitectureTest` (12 ArchUnit
  rules), `GlobalExceptionHandlerTest`, `ScheduleControllerTest` (`@WebMvcTest` with a
  `@MockitoBean` service), DTO validation tests, `FlightDatePolicyTest`, `AircraftTest`,
  `BookingPropertiesTest`, `SourceConventionsTest`.
- **Integration tests** (`*IT`, Failsafe, Docker): one PostgreSQL 16 container
  (`postgres:16-alpine`) wired with `@ServiceConnection`
  (`src/test/java/com/airline/reservation/TestcontainersConfiguration.java:20-26`).
  Flyway applies `V1` and `V2` as in production. A fixed `Clock`
  (`FixedClockConfiguration`) makes dates deterministic.
- **Races.** Contenders wait on a shared start latch (`IntegrationSupport.java:118-119`)
  and hit the database together (`BookingConcurrencyIT`, `InstanceMaterializationIT`,
  `CancellationIT`, `ScheduleIT`). `CrossInstanceIT` runs two Spring web contexts with
  separate pools against one database. Faults are injected in the database: test-only
  triggers and check constraints, `LOCK TABLE`, `pg_sleep`, and stopping the container.
- **Latency.** `SearchPerformanceIT` and `MixedLoadIT` send real HTTP requests with the
  JDK `HttpClient` to the server started on a random port (D20).
- `spotless:check` runs in `verify`. JaCoCo reports coverage without a threshold
  (`pom.xml:218`).
- **Manual.** Postman/Newman collection (`postman/`), `tests/api.http`, `tests/smoke.sh`.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **JUnit 5 + ArchUnit; Testcontainers PostgreSQL; latch-started races; in-JVM HTTP load** | **Chosen** | As above | Proves the partial index, the upsert, rollbacks and pool behaviour on the real engine; deterministic clock; architecture rules fail the build | Needs Docker; slower than unit tests; load runs share one laptop with the database | Only the real engine can prove D1, D2 and D7 | Services whose correctness depends on database features |
| H2 or another in-memory database | Unsuitable here | The same tests against an embedded engine | Fast; no Docker | Cannot express a partial unique index (`WHERE status = 'BOOKED'`); different locking and upsert behaviour, so a passing test proves nothing about D2 | Would leave reply 370-372 unproven | JPA mapping tests with no vendor-specific SQL |
| Mocked repositories (Mockito) | Complement | Stubbed collaborators | Fast and isolated | Cannot see constraints, locks or transactions | Used only where no database behaviour is involved (`ScheduleControllerTest`, `FlightDatePolicyTest`) | Branching logic in services and controllers |
| Embedded PostgreSQL binaries | Viable alternative | A library starts a real PostgreSQL process without Docker | Real engine, no Docker | Another dependency; per-OS binaries; version tied to the library | Testcontainers is Spring Boot's supported path (`@ServiceConnection`) | CI agents without Docker |
| Shared external test database | Viable alternative | Tests connect to a running instance (for example the Compose database) | No container start-up | State leaks between runs; parallel CI jobs collide | Each run gets a fresh container | Very large schemas that are slow to create |
| Black-box HTTP tests (Postman/Newman) | Complement | Scripted requests against a running app | Exercises the public contract end to end; useful for demos | No fault injection, SQL counts or precise race control | Kept alongside the automated suite (S12) | Acceptance demos and smoke checks |
| Diagnose the intermittent `MixedLoadIT` reset (instrumentation, repeated runs) | Unsuitable here | Add per-request logging; rerun until it reproduces | Might find the cause | Effort; instrumentation can change timing | The user declined it on 2026-10-21 (execution log 104) | When the failure reaches the 16-client baseline or CI |

**Example.** `BookingConcurrencyIT.concurrentFirstBookingsForOneSeatHaveExactlyOneOwner`
starts 24 callers that each book seat `1A` on a flight with no instance row yet. All wait
on one latch and then run together. The test requires exactly one confirmed booking,
`SEAT_UNAVAILABLE` for every other caller, and exactly one row each in
`flight_instance`, `booking` and `booking_seat`. On H2 the partial index could not even be
created, so this race would test nothing.

**Consequences.** `./mvnw verify` needs Docker. A green build shows that the guarantees
hold on PostgreSQL 16, not on other engines.

**Limitations and evidence.** `BookingConcurrencyIT` calls the service layer, not HTTP;
HTTP races are covered by S8, the S10 hot seat and S12. `MixedLoadIT` has failed
intermittently with a connection reset while opening a connection; the cause is unknown
and no wrong or duplicate booking was ever observed (evidence §8). Evidence: every
scenario S1-S13 and the run register (evidence §5).

**Revisit when** a second database engine must be supported, CI has no Docker (embedded
PostgreSQL), or the reset appears outside the stress phases.

## D20. Latency acceptance

**Driver.** Reply 363-365: search within one second "with a provision to serve lazy
loading if needed"; for booking, "ultimate stability is key, the time can change based on
load and concurrent attempts". Replies 350-356: 10k DAU, 500 schedules, 25 searches per
booking. Brief 196: no performance optimisation is required beyond a reasonable
implementation. Engineering choice made under the user's delegated judgment and recorded
in spec 211-221.

**Invariant.** In the defined baselines every search returns 200 and the slowest one
finishes in under one second. Bookings are judged by outcome, not time: every disjoint
booking succeeds and no seat ever has two active owners. No document presents an assumed
request rate as measured or guaranteed (spec 220-221).

**Implementation.** Test paths are under `src/test/java/com/airline/reservation/`.
- **Search baseline (S9).** `SearchPerformanceIT` seeds `PerformanceDataset`: 500
  schedules, 100 of them on the measured route, 30 dates each, with booked and released
  seats (evidence §3). After 10 warm-up requests it sends 100 sequential searches, then
  200 more from 8 workers (`SearchPerformanceIT.java:53-67`, `109-128`). Every response
  must be 200 and list all 100 flights (lines 135-137). The slowest sequential search and
  the slowest concurrent search must each finish in under one second (lines 102-105).
  Hibernate statistics must show one prepared statement and no entity load per search
  (lines 61-67). The `EXPLAIN (ANALYZE, BUFFERS)` plan is recorded, not asserted, because
  spec 212-213 forbids forcing a particular plan.
- **Mixed baseline and stress (S10).** `MixedLoadIT` sends 20 warm-up searches, then runs
  phases of 16, 64 and 192 closed-loop clients (`MixedLoadIT.java:65-70`). Each client
  runs three cycles of 25 searches and one booking of seat `1B` on a flight-date that no
  other mixed-load booking targets, the 25:1 ratio of reply 355-356 (lines 113-120).
  - In every phase each search must return 200 or a 503 that carries
    `SERVICE_UNAVAILABLE` and `Retry-After: 1`, and every booking must return 201
    (lines 143-144, 205-210). A phase in which every search is shed fails (line 147).
  - Only the 16-client baseline must have no 503 and every search under one second
    (lines 149-153).
  - A 64-client hot-seat race must give exactly one 201 and 63 responses with 409
    (lines 174-186).
  - At the end no seat may have two active rows, and every disjoint booking must own its
    seat (lines 73-89).
- Percentiles are printed for diagnosis only; the gates are status codes and the slowest
  response (evidence ledger, introduction). Booking time is reported, never asserted.
- Read deadlines that turn a slow read into a 503 instead of a late 200 are D7. Caches,
  counters and read replicas are compared in D5.

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Slowest-response gate: every baseline search returns 200 in under one second; stress phases reported separately** | **Chosen** | The build fails if any baseline search is not 200 or takes one second or longer. In the 64- and 192-client phases, 503s are counted and successful-search latency is reported; the build fails only on an all-shed phase, a failed booking or a duplicate seat | Strictest reading of reply 363-365 for the samples taken; easy to audit; needs no statistics | Short runs on one machine; a single pause (garbage collection, a busy laptop) fails the build; says nothing about production capacity | Matches the acceptance rule recorded in spec 214-219; overload stays visible instead of being averaged away | Local acceptance tests with small samples and no agreed SLO |
| Percentile gate (p95 or p99 under one second) | Viable alternative | Pass if the chosen percentile of a run is under one second | Standard SLO form; tolerates rare outliers; stable with large samples | Allows up to 5% (p95) or 1% (p99) of searches to exceed one second; with 100-200 samples the p99 is decided by one or two requests | No percentile SLO was agreed (spec 216); with small samples the maximum is stricter and simpler; percentiles are still printed | Production SLOs with sustained traffic, many samples and an error budget |
| Mean latency under one second | Unsuitable here | Pass if the average is under one second | Easy to compute and explain | A mean puts no bound on how many searches exceed one second: fast responses offset slow ones | Could pass while a large share of searches miss the one-second target of reply 363-365 | Throughput and capacity trend dashboards, shown next to percentiles |
| Sustained open-loop test at a target arrival rate (Gatling, k6, JMeter) | Viable alternative | Requests arrive at a fixed rate, independent of response time, for minutes or hours against a deployed stack | Finds the saturation point; avoids the closed-loop blind spot in which slower responses reduce the offered load | Needs an agreed peak rate, which 10k DAU and 25:1 do not determine (spec 220-221); needs production-like hardware and another tool | Choosing a peak rate would present an assumption as a requirement; brief 196 asks only for a reasonable implementation | Before a production launch, once a peak rate and an SLO are agreed |
| Lazy loading or pagination of search results (page and size, or a keyset cursor) | Viable alternative | Return the first page of flights; fetch more on demand | Bounds response size and time as results grow | More requests; cursor state; pages can disagree with each other (acceptable as stale search, reply 358-361); clients must handle paging | Reply 364 allows it "if needed". Searches that return all 100 matching flights finish far below one second (S9, S10), so it is not needed now (spec 234-235) | Routes with many more flights per day, slow clients or slow networks |
| Booking deadline (fail a booking that takes longer than a target) | Unsuitable here | Short write-pool or statement timeouts make slow bookings fail fast | Predictable booking latency | Under load, bookings that would have succeeded fail instead | Reply 365 puts booking stability above speed, so the write pool waits up to 20 s instead (D7) | Systems with an agreed booking SLO and safe client retries |

**Example.** The 16-client baseline sends 16 × 3 × 25 = 1,200 searches and 48 bookings.
One search that takes a second or longer, or one 503, fails the build. In the 192-client
phase (14,400 searches, 576 bookings) a search rejected with 503 and `Retry-After: 1` is
counted and reported, but a failed booking, a phase in which no search succeeds or a seat
with two owners still fails the build.

**Consequences.** A green build shows headroom on the documented dataset and machine
(evidence §2-§3), not production capacity. Because the gate is the maximum, machine noise
can fail it; that is preferred to hiding a real tail. Stress phases may legitimately show
503s, because the read pool sheds load by design (D7).

**Limitations and evidence.** Closed-loop clients send their next request only after the
previous response, so when the server slows down the offered load drops and queueing delay
is under-reported. Clients, server and database share one machine. The seat map has no
latency test; reply 363-365 sets a target for search only. `MixedLoadIT` has failed
intermittently with a connection reset (evidence §8). Evidence: S9a, S9b and S10 in Table B
(evidence §6); evidence §9 lists what the runs do not prove.

**Revisit when** a percentile SLO or a peak request rate is agreed (switch to an open-loop
test with a percentile gate), a baseline search approaches one second or result sets grow
(pagination), the service runs on dedicated hardware (repeat the runs there), or the reset
appears in the baseline phase.

## D21. Libraries and documentation

**Driver.** Brief 188 (clean architecture); evaluation criteria 290-292 (documentation,
design decisions, readability); brief 202-279 (required HLD, LLD, schema and README
content; 213 encourages architecture diagrams and 225 makes sequence diagrams optional).
User policy: Spring Data JPA (execution log 14), and do not hand-write what a library
already provides, with Lombok, Apache Commons and
`StringUtils` as the user's examples (recorded in the execution log entry for this
restructuring). Engineering choice recorded in spec 83-90: Lombok, Jakarta Validation,
Hibernate Validator's `@UniqueElements`, and "Use existing JDK/Spring facilities before
adding another utility dependency" (spec 90).

**Invariant.** Every dependency replaces code that would otherwise be written by hand, and
no two dependencies do the same job. Measured numbers live only in the evidence ledger and
design reasons only in this log; other documents link to them instead of copying them.

**Implementation.**
- **Runtime** (`pom.xml:29-75`): Spring Boot 4.1.1 starters for Web MVC, validation, Data
  JPA, Flyway (with its PostgreSQL module) and Actuator; the PostgreSQL driver;
  springdoc-openapi 3.1.1 (Swagger UI at `/swagger-ui.html`, `application.yml:58-61`);
  Lombok at compile time only.
- **Test and build** (`pom.xml:77-119`): Spring Boot test starters, Testcontainers
  PostgreSQL and ArchUnit 1.5.1; Spotless 3.10.4 checks formatting; JaCoCo 0.8.15 reports
  coverage without a threshold (`pom.xml:218`).
- **JDK 21** covers the utility work: records for every request, response, command and
  result type; `List.copyOf` and `Set.copyOf` for immutable copies (for example
  `model/CreateBookingCommand.java:16`, `entity/FlightSchedule.java:76`);
  `Objects.requireNonNull` (`entity/Aircraft.java:33-35`). Blank and format checks are Bean
  Validation annotations (D23), not string helpers.
- **Lombok**: `@RequiredArgsConstructor` for constructor injection and `@Slf4j` for loggers.
  All six entities use `@Getter` with a protected no-argument constructor (for example
  `entity/Booking.java:23-24`); `FlightSchedule` adds `@Builder` (line 61). No entity uses
  `@Data`, `@Setter` or generated `equals`/`hashCode`: generated equality over database IDs
  and lazy associations breaks hash-based collections and can initialise proxies.
- **Mapping** is explicit: static `from(...)` factories on the four response records (for
  example `dto/response/BookingResponse.java:20`) and `toCommand()` on the two request
  records (`dto/request/CreateBookingRequest.java:17`,
  `dto/request/CreateScheduleRequest.java:25`).
- **Documents**: README (setup, algorithms, assumptions), `hld/architecture.md`,
  `lld/design.md`, `db/` (schema, ER diagram, migrations), this log and
  `tests/nfr-evidence.md`. Diagrams are Mermaid source inside the Markdown, with rendered
  SVG copies in `hld/diagrams/`, indexed in `hld/diagrams/README.md`; the brief's layout
  has a diagram folder only under `hld/` (315-316).

*Libraries*

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **JDK 21 + Spring Boot starters + Lombok + Jakarta/Hibernate Validator + springdoc** | **Chosen** | Use what the platform already provides; add a library only when it removes hand-written code | Small dependency tree; one way to do each job; versions managed by the Spring Boot parent | A helper the JDK lacks (for example abbreviating text) would need a few lines if it were ever required | Applies the user's "do not reinvent" policy without duplicating JDK features (spec 90) | Spring Boot services on a current JDK |
| Apache Commons Lang (`StringUtils`, `Validate`) | Viable alternative | Static null-safe helpers for strings, arrays and preconditions | Familiar to most Java teams; null-safe | Overlaps the JDK (`String.isBlank`, `strip`, `Objects.requireNonNull`) and Bean Validation (`@NotBlank`, `@Pattern`); one more dependency to patch; two styles for the same check | No method here would get shorter: input checks are declarative annotations (D23) and the only null checks are `Objects.requireNonNull` | Older JDKs, or code that needs helpers the JDK lacks (for example `StringUtils.abbreviate`) |
| Guava | Viable alternative | Immutable collections, preconditions, multimaps, caches | Broad, well-tested utilities | Large dependency; `List.copyOf`, `Set.copyOf` and records already cover what this code needs; its caches would add in-process state, which the design avoids (D5, D8) | Nothing to replace | Code that needs multimaps, range sets or graph utilities |
| MapStruct | Viable alternative | An annotation processor generates DTO, command and entity mappers | Removes boilerplate for many wide, mostly one-to-one DTOs; checked at compile time | Another annotation processor that must be ordered with Lombok; generated code to read when debugging | Six small hand-written factories (`from`, `toCommand`) are shorter than mapper interfaces plus configuration | Many DTOs with dozens of fields each |
| Lombok `@Value` or `@Data` classes instead of records | Viable alternative | Generated immutable (`@Value`) or mutable (`@Data`) classes | Works on JDKs older than 16 | Records give immutability, equality and accessors in the language itself, with no annotation processor | Java 21 is the baseline (spec 64) | Projects on Java 8-15 |

*Documentation*

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **One decision log, one evidence ledger, linked from README, HLD and LLD; Mermaid diagrams with rendered SVG copies** | **Chosen** | Reasons live here; measured numbers live in `tests/nfr-evidence.md` with run provenance; other documents summarise and link | One place to change each fact; numbers carry their run, date and conditions | Readers follow links for details | Copies of numbers in several documents had drifted; the user approved the drift fixes on 2026-10-21 (execution log 101-103) | Projects with several documents that cite the same facts |
| Copy measured numbers into README, HLD and LLD | Viable alternative | Each document states its own figures | Numbers visible without a link | Copies drift: the README, HLD and LLD once quoted figures from different runs without dates | Replaced by links to the ledger | Reports generated from a single run, where every copy comes from the same source |
| One file per decision (ADR, for example the MADR template) | Viable alternative | `docs/adr/NNNN-title.md`, each with a status (proposed, accepted, superseded) | Clear history per decision; small diffs; status lifecycle | 23 files plus an index; the cross-decision tables (summary, NFR map, brief crosswalk) need a separate home | The brief asks for design decisions in the README and the HLD (257-258, 209); one log linked from both is easier to review | Long-lived systems in which decisions are superseded over years |
| springdoc OpenAPI contract and Swagger UI | Complement | Generates an OpenAPI 3.1 document from controllers and DTOs | Contract always matches the code; interactive try-out | Describes *what*, not *why* | Kept for the API contract; the LLD and this log explain the reasons | Public or partner-facing APIs |
| PlantUML or draw.io diagrams | Viable alternative | Text (PlantUML) or XML (draw.io) sources rendered to images | Fuller UML notation (PlantUML); free layout (draw.io) | PlantUML needs a separate renderer and GitHub does not render it inline; draw.io files diff poorly | Mermaid renders on GitHub from text that diffs well; SVG copies serve other viewers | Teams with existing PlantUML tooling or large hand-laid-out diagrams |

**Example.** A seat label could be checked with a helper such as
`StringUtils.isBlank(seat) || !seat.matches(...)`. The request record already declares
`@NotNull @Pattern(regexp = InputFormats.SEAT_LABEL)`, which also reports the field path
in the 400 response (D18, D23). The helper would add a dependency and a second way to say
the same thing.

**Consequences.** Upgrades follow the Spring Boot parent; only springdoc, ArchUnit, Spotless
and JaCoCo carry their own versions (`pom.xml:22-25`). The bar for a new library is that it removes code.
A change in measured numbers touches one file.

**Limitations and evidence.** The library policy is enforced by review, not by a build
rule. `ArchitectureTest` enforces layering (D11), `SourceConventionsTest` enforces explicit
imports and local types, and Spotless enforces formatting (S1, S13).

**Revisit when** the same hand-written helper appears in two places (adopt a library),
DTOs become numerous and wide (MapStruct), decisions start being superseded (ADR files), or
the API gains external consumers (publish the OpenAPI document as a versioned artifact).

## D22. REST resource design

**Driver.** Brief 190 (RESTful API design) and 285 (API design is an evaluation
criterion). Brief 70 asks for administrative APIs that create schedules; brief 303 says
schedules do not change after creation. Brief 113-183 define the customer operations
(search, seat map, booking, cancellation), and brief 150-156 list the booking response
fields. Engineering choice recorded in spec 113-123 (the five paths and their status codes),
140 (no `Location` URL that cannot be fetched) and 231 (admin authentication is an external
deployment boundary, not a URL prefix).

**Invariant.** Paths name resources and the HTTP method states the intent. A GET never
changes state. Each state change is one POST that either completes or changes nothing.
Every response body is either the resource or an RFC 9457 problem (D18).

**Implementation.**

| Operation (brief) | Method and path | Success | Code |
|---|---|---|---|
| Create schedule (70) | `POST /api/v1/admin/flight-schedules` | 201 with the schedule | `controller/ScheduleController.java:18-28` |
| Search (113-125) | `GET /api/v1/flights?originCode=DXB&destinationCode=LHR&date=2027-03-02` | 200 with an array; `[]` when nothing matches (spec 133) | `controller/FlightController.java:32-36` |
| Seat map (127-141) | `GET /api/v1/flights/{flightNumber}/seat-map?date=2027-03-02` | 200 with every seat and its status | `controller/FlightController.java:25-28` |
| Book (143-159) | `POST /api/v1/bookings` | 201 with the six fields of brief 150-156 | `controller/BookingController.java:28-30` |
| Cancel (176-183) | `POST /api/v1/bookings/{bookingReference}/cancel` | 200 with the booking, now `CANCELLED` | `controller/BookingController.java:34-35` |

- Every path starts with `/api/v1`, and every controller declares
  `produces = application/json`, so content negotiation fails before a handler runs (D17).
- Flights are addressed by flight number and date, bookings by their UUID reference (D15).
- The admin prefix only groups the back-office API so that a gateway can protect it; the
  application does not authenticate (spec 231; brief 29-30 treats unmentioned functions as
  a black box).
- Controllers only translate: request record to command, result to response record (D11,
  D12).

*Cancellation*

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **`POST /bookings/{bookingReference}/cancel`, returning the booking** | **Chosen** | A state-transition command on the booking; 200 with the booking in its new state | Names the domain action; the booking stays as history (D4); a repeat changes nothing and returns 409 `ALREADY_CANCELLED` | A verb in the path, which noun-only REST style avoids; POST does not tell clients or proxies that a retry is safe | Brief 180-182 describe a transition (change status, release seats), not an edit of a field. Google's resource-oriented API guidelines (AIP-136) use the same pattern for actions that standard methods cannot express: POST, a verb suffix (`:cancel`), the resource returned | Workflow transitions with side effects: cancel, check in, confirm |
| `DELETE /bookings/{bookingReference}` | Viable alternative | Cancelling is modelled as deleting the booking | A standard method that HTTP defines as idempotent, so clients may retry it | DELETE removes the association between the URI and the resource (RFC 9110 §9.3.5), so a later GET would be expected to fail, yet the cancelled booking remains as history (D4) | Brief 180 asks to change the status, not to remove the booking | Resources that really cease to exist: drafts, temporary holds |
| `PATCH /bookings/{bookingReference}` with `{"bookingStatus": "CANCELLED"}` | Viable alternative | Partial update of the status field (JSON Merge Patch, RFC 7396) | Generic; the same endpoint could edit other fields later | Presents the status as freely writable: the server must still reject every other transition and field, and accept the `application/merge-patch+json` media type | One transition exists, so a generic update adds checks without adding capability | Resources with several independently editable fields |
| `POST /bookings/{bookingReference}/cancellations` | Viable alternative | Creates a cancellation resource (201) | Room for a reason, a refund or a partial cancellation on each record | A resource with no fields of its own today, and a second representation to maintain | Refunds (brief 183) and partial cancellation (spec 228) are out of scope | Cancellations that carry data: refunds, reasons, partial cancellations |

*Queries*

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **GET with query parameters; seat map as a sub-resource of the flight** | **Chosen** | Search filters the flights collection by three parameters; the seat map belongs to a flight number, with the date as a parameter | Safe and idempotent (RFC 9110 §9.2), so clients and gateways may retry; URLs can be shared and logged; one `date` parameter with ISO parsing in both queries | Only flat values fit in a query string | The brief's criteria are three flat values (brief 115-118); sorting and filtering are out of scope (brief 125) | Read APIs with a few scalar filters |
| `POST /flights/search` with a JSON body | Viable alternative | Criteria sent as a request body | Room for nested or long criteria | Responses are not cacheable by default and cannot be bookmarked; the method no longer signals a read-only call | Three flat criteria need no body | Complex criteria: multi-city, many filters, saved searches |
| Date in the path (`/flights/{flightNumber}/dates/{date}/seat-map`) | Viable alternative | The flight-date becomes a path resource | Each flight-date gets a URI without a query string | The date would be a path segment in one query and a parameter in the other; D15 compares addressing by instance ID | One rule for the `date` input across both queries | When flight-dates gain several sub-resources (status, manifest) |

*Versioning and contract style*

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Major version in the path (`/api/v1`), JSON bodies, problem-detail errors** | **Chosen** | Every path starts with `/api/v1`; bodies are `application/json` (D17); errors are `application/problem+json` (D18) | The version is visible in every URL and log line; routing by prefix; v1 and v2 can run side by side | A breaking change moves clients to new URLs | The simplest scheme, visible in every request | Internal and partner APIs with few major versions |
| Version in a header or media type (for example `Accept: application/vnd.airline.v1+json`) | Viable alternative | Clients request a version through content negotiation | Resource URIs stay the same across versions | Harder to try from a browser or `curl`; caches must vary on the header; the 406 preflight (D17) would have to know versions | No second version exists | Public APIs that must keep URIs stable for years |
| Hypermedia links (HATEOAS, for example HAL `_links`) | Viable alternative | Responses carry links to the next actions, such as cancel or seat map | Clients follow links instead of building URLs | Adds fields the brief does not list (brief 150-156); clients still need the documentation | The response fields are fixed by the brief | Public APIs with many independent clients |
| `201 Created` with a `Location` header | Unsuitable here | `Location` gives the URL of the new booking or schedule | Standard pointer to the created resource | The URL must answer a GET, which needs a retrieval endpoint; a `Location` that cannot be fetched misleads clients (spec 140) | Retrieval is out of scope (brief 29-30; D16). `Location` is optional for 201 (RFC 9110 §15.3.2), and the body already carries the booking reference | When booking or schedule retrieval enters scope |
| `PUT`, `PATCH` or `DELETE` on `/flight-schedules/{flightNumber}` | Unsuitable here | Full CRUD on schedules | Familiar shape | Lets a schedule change after creation | Brief 303 | When schedules may change (D15 compares versioned schedules) |
| GraphQL or gRPC | Unsuitable here | One query endpoint (GraphQL) or binary RPC over HTTP/2 (gRPC) | Clients choose the fields (GraphQL); compact, typed contracts (gRPC) | Not resource-oriented; separate tooling and error model | Brief 190 asks for RESTful API design | GraphQL to aggregate many resources for varied clients; gRPC between internal services |

**Example.** One booking through the cancel command (`CancellationIT:60`;
`HttpContractsIT:70`; test paths under `src/test/java/com/airline/reservation/`):

```text
POST /api/v1/bookings/{reference}/cancel    → 200, "bookingStatus": "CANCELLED"
POST /api/v1/bookings/{reference}/cancel    → 409, "errorCode": "ALREADY_CANCELLED"
POST /api/v1/bookings/{random UUID}/cancel  → 404, "errorCode": "BOOKING_NOT_FOUND"
POST /api/v1/bookings/not-a-uuid/cancel     → 400, "errorCode": "VALIDATION_FAILED"
```

The client can tell "already cancelled" from "never existed". After a `DELETE`, a repeated
call commonly answers 404 or 204, which hides that difference.

**Consequences.** One handler method per brief operation, so each operation has one entry
point to test and document (springdoc publishes the same five operations, D21). Adding
retrieval later is additive: a `GET /api/v1/bookings/{bookingReference}` would also allow a
`Location` header on 201 without changing any existing path. A breaking change would need
`/api/v2`.

**Limitations and evidence.** A client that loses the 201 response cannot look the booking
up, because there is no retrieval endpoint (D16). The admin prefix is not access control
(spec 231). No test checks the generated OpenAPI document. Evidence: `HttpContractsIT` and
`CancellationIT` (S4, S6) for status codes and the cancel contract; `ScheduleControllerTest`
(S1) for the admin endpoint; the Postman collection (S12, H4 and R6) called all five
operations over HTTP.

**Revisit when** booking retrieval enters scope (add the GET and a `Location` header),
cancellations carry data such as refunds (cancellation resource), a breaking change is
needed (`/api/v2` or media-type versioning), or the API gains external consumers (publish
the OpenAPI document; consider hypermedia).

## D23. Validation layers

**Driver.** Brief 193 (validation) and 192 (error handling). Brief 157-159: passenger
information may be minimal (a name), and "No passenger validation is required."
Engineering choice recorded in spec 125-130 (request fields and strict clocks), 142-146
(error mapping) and 289 (names are structurally non-null only).

**Invariant.** Malformed input gets a 400 before any service logic runs. A well-formed
request that breaks a business rule gets a stable code (400, 404, 409 or 422). The
database enforces the rules that must hold even if the code is wrong. A rejected request
leaves no partial write, and an unexpected constraint violation is reported as a server
error (500), never disguised as a client error.

**Implementation.** Layers in the order a request meets them:

| Layer | Checks | Where | Rejection |
|---|---|---|---|
| Preflight | `Accept` and `Content-Type` (D17); `X-Correlation-ID` must be one value of 1-64 letters, digits, dots, underscores or hyphens (D18) | `produces` on every controller; `config/RequestCorrelationFilter.java:44-64` | 406 or 415; 400 `VALIDATION_FAILED` |
| 1. Request | Bean Validation on the request records: `@NotNull`, `@Pattern` with the shared formats (`validation/InputFormats.java:4-6`), `@NotBlank @Size(max = 16)` aircraft ID, `@NotEmpty @UniqueElements` weekdays, `@NotEmpty` passengers with `@Valid` elements; strict `HH:mm` times. Query and path parameters: `@Pattern`, ISO dates, the `UUID` type | `dto/request/CreateScheduleRequest.java:18-24`, `dto/request/CreateBookingRequest.java:12-28`, `controller/FlightController.java:27-28, 34-36`, `controller/BookingController.java:35` | 400 `VALIDATION_FAILED`; for a JSON body the problem adds an `errors` list of `{field, message}` (`exception/GlobalExceptionHandler.java:117-153`) |
| 2. Service contract | `@Validated` services check `@NotNull @Valid` command records, which repeat the request constraints with the same `InputFormats` constants | `service/ScheduleService.java:9`, `service/BookingService.java:9`, `service/impl/ScheduleServiceImpl.java:23`, `service/impl/BookingServiceImpl.java:25`, `model/CreateScheduleCommand.java:15-21`, `model/CreateBookingCommand.java:12-20` | 400 `VALIDATION_FAILED` without field paths (`GlobalExceptionHandler.java:109-115`) |
| 3. Domain rules | Rules that need reference data, the schedule or the clock (list below) | Services and `service/FlightDatePolicy.java` | A stable `errorCode` with 400, 404, 409 or 422 (`GlobalExceptionHandler.java:46-66`) |
| 4. Database | Check, unique and foreign-key constraints (list below) | `V1` | Two named unique violations become 409; any other violation becomes 500 `INTERNAL_ERROR` |

Domain rules (layer 3):
- `SAME_ORIGIN_DESTINATION` (400) for schedule creation and search
  (`service/impl/ScheduleServiceImpl.java:34-35`, `service/impl/FlightServiceImpl.java:33-34`).
- `UNKNOWN_REFERENCE` (422): an airport or aircraft that is not preloaded
  (`ScheduleServiceImpl.java:81`).
- `DATE_OUT_OF_WINDOW` (422) and `FLIGHT_DEPARTED` (409) (`service/FlightDatePolicy.java:24,
  35`; D14); `FLIGHT_NOT_FOUND` (404) when the flight does not operate on that date
  (`service/impl/FlightLookupServiceImpl.java:36`).
- `DUPLICATE_SEAT` (400) and `SEAT_NOT_ON_AIRCRAFT` (422, naming the seat)
  (`service/impl/BookingServiceImpl.java:55-70`, using `model/SeatLayout.java:26-32`).
- `BOOKING_NOT_FOUND` (404) and `ALREADY_CANCELLED` (409)
  (`service/impl/CancellationServiceImpl.java:50, 68`).
- Entity invariants guard reference data loaded from the database: the `Aircraft`
  constructor and the `SeatLayout` record (rows 1-99; 1-10 distinct letters A-Z) throw
  `IllegalArgumentException` (`entity/Aircraft.java:32-41`, `model/SeatLayout.java:12-20`).

Database (layer 4, `V1`):
- Checks: airport code (6), aircraft rows and distinct seat letters (14-17), flight-number
  format and origin ≠ destination (30-31), weekday range (38), booking and seat status
  values (58, 73), `cancelled_at` set exactly when a booking is cancelled (59-61), seat-label
  format (74).
- Unique: `uq_schedule_flight_number` (29), `uq_instance` (46, consumed by `ON CONFLICT DO
  NOTHING`, D1), `uq_booking_reference` (56), `uq_booking_seat_active` (77-79). The
  composite foreign key `fk_seat_booking` (71-72) keeps every seat row on its booking's
  flight instance.
- Only two violations are translated, both by constraint name through
  `exception/PersistenceConstraints.java`. `uq_booking_seat_active` becomes 409
  `SEAT_UNAVAILABLE` with `currentlyUnavailableSeats`, read after the rollback
  (`service/impl/BookingTransactionServiceImpl.java:61-66`, `BookingServiceImpl.java:77-88`).
  `uq_schedule_flight_number` becomes 409 `DUPLICATE_SCHEDULE`
  (`ScheduleServiceImpl.java:70-74`). Any other violation is a 500, logged with SQL state
  and constraint name but no payload (`GlobalExceptionHandler.java:155-198`).

| Approach | Verdict | How it works | Advantages | Costs and failure modes | Why chosen / why not now | When preferable (architecture) |
|---|---|---|---|---|---|---|
| **Four layers: request, service contract, domain rule, database constraint** | **Chosen** | As above | Field-level 400s at the edge; services stay safe when called without HTTP; rules that need the clock or data live in one service; the database holds even if code is wrong | Format constraints are declared twice (request and command records) | Brief 192-193; each layer catches what the one before it cannot | Layered services with more than one entry point, or that may gain one |
| Request records only | Viable alternative | Validate at the controller; services trust their callers | One declaration per rule | Any caller that skips the controller (a test, a batch job, a future message consumer) reaches services unchecked | The service contract costs one annotation per method and reuses the same constants | Thin services reachable only through HTTP |
| Service contract only | Viable alternative | `@Validated` services check commands; request records carry no constraints | One declaration per rule | Violation paths name Java parameters (for example `create.command.passengers[0].seat`), not JSON fields; mapping them back to the request body is extra code | Field-level 400s for JSON bodies come with `@Valid @RequestBody` at no extra cost | Services with several entry points, HTTP being one of them |
| Database constraints only | Unsuitable here | Checks, unique keys and foreign keys reject bad data | One source of truth | Rules that depend on today's date (window, departure) cannot be check constraints; violations surface as 500 without saying which field is wrong | Brief 192-193 ask for validation and error handling a client can act on | Bulk loads in which the database is the only gate |
| Semantic passenger-name validation (non-blank, length, character set) | Unsuitable here | Reject names that look wrong | Catches typing errors | Rejects legitimate names (single letters, non-Latin scripts, long names) | Brief 159: "No passenger validation is required." Names are non-null only | When a future brief adds passenger details such as travel documents |
| Normalise input (upper-case codes, trim spaces) | Viable alternative | Accept `dxb` or ` 1A` and store `DXB` or `1A` | Friendlier to hand-typed input | Silently changes what the client sent and hides client bugs; every layer must agree on the canonical form | A strict 400 tells the client exactly what to fix, the same reason as strict clocks (D17) | Consumer-facing forms where people type codes |
| Origin ≠ destination as a class-level constraint (custom `ConstraintValidator`) | Viable alternative | A custom annotation on the request record compares two fields | Rejected at the edge together with the other field errors | Search takes the same pair as query parameters, not a record, so the service check would remain; one more class to maintain | One service check per operation returns the stable code `SAME_ORIGIN_DESTINATION`, and `ck_schedule_route` backs it in the database | Many cross-field rules on request bodies |
| Precondition helpers in services (Apache Commons `Validate`, Guava `Preconditions`) | Viable alternative | Imperative checks at the start of each method | Familiar; no annotation processing | Status codes and messages are mapped by hand; checks are hidden in method bodies; duplicates Bean Validation | Declarative annotations already produce structured 400s (D21) | Code without Bean Validation, or checks that annotations cannot express |

**Example.** One booking request meeting each layer, with the seeded aircraft (`V2:8-10`;
test paths under `src/test/java/com/airline/reservation/`):
1. Seat `01A` fails the seat-label pattern: 400 `VALIDATION_FAILED`, and no service method
   runs (`HttpContractsIT:27-75`).
2. Seat `1F` on a flight flown by the ATR 72 (`AT72-001`, letters `ABCD`) is well-formed
   but not on the aircraft: 422 `SEAT_NOT_ON_AIRCRAFT`, before even the flight-instance row
   is created (`BookingIT:117`).
3. Two concurrent requests for the same free seat pass layers 1-3; `uq_booking_seat_active`
   admits one, and the other gets 409 `SEAT_UNAVAILABLE` (D2; S5, S10).
4. A passenger named `""` is accepted and stored unchanged (brief 159; `BookingIT:104`).

**Consequences.** A format change edits one constant in `InputFormats`, which the request
records, the command records and `FlightController` share; a new field constraint is
declared on two records. Bean Validation reports every invalid body field in one response;
domain rules stop at the first failure. Because the database repeats the essential rules
(seat-label format, origin ≠ destination, one active owner per seat), a code path that
skipped layers 1-3 still could not store a duplicate seat or a malformed label.

**Limitations and evidence.** Layer-2 rejections and invalid query or path parameters
return no field paths. An entity-invariant failure would surface as a 500;
`ck_aircraft_rows` and `ck_aircraft_letters` keep such reference data out of the database
in the first place. Evidence: `CreateBookingRequestTest` (labels `01A`, `1a`,
` 1A`, `0A` and `100A` rejected; an empty passenger list rejected; an empty name accepted),
`CreateScheduleRequestTest`, `ScheduleControllerTest` and `AircraftTest` (S1); `ScheduleIT`
(duplicate weekdays; same origin and destination) and `DatabaseSchemaIT` (non-canonical
labels, duplicate aircraft letters, a seat on another booking's instance) (S2);
`HttpContractsIT` (malformed input, strict clocks; seat `01A` yields exactly one `errors`
entry, `{field: "passengers[0].seat", message}`, without the passenger name) and `BookingIT`
(names stored unchanged; an unrelated integrity failure is not reported as a seat
conflict) (S4). Layer 2 is tested without HTTP: `ScheduleIT` and `BookingIT` call the
services with a null command and with one invalid field (`xy101`, `01A`), expect a
`ConstraintViolationException` whose single path ends in that field, and find no row
written; `GlobalExceptionHandlerTest` maps that exception to 400 `VALIDATION_FAILED` with
no field list and keeps the rejected value out of the body and the log.

**Revisit when** a second entry point appears, such as a message consumer or batch import
(add field paths to its 400), a future brief adds passenger details with rules, cross-field
rules multiply (class-level constraints), or clients need lenient input (normalise at the
edge, consistently).

## Maintaining this log

- Change a decision by editing its section and its summary row together, and cite code at
  the new commit.
- Record measured numbers only in [NFR evidence](../../tests/nfr-evidence.md) and link to
  them from here.
- Work in small todos, run a self-audit after every five completed todos, and fix its
  findings before the next todo starts (execution log 5).
