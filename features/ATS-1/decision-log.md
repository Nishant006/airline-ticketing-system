# ATS-1: design decisions

Corrected 2026-10-19 against the authoritative brief and user decisions; stateless
scaling scope clarified 2026-10-20; expanded into per-decision option tables on
2026-10-21 after a three-way audit. Historical decisions in the execution log are
not current implementation instructions. See [spec.md](spec.md) for the contract
and [NFR evidence](../../tests/nfr-evidence.md) for measured results.

## How to read this log

Each decision has the same shape:

- **Requirement**: the brief line or interviewer reply that forces the decision.
- **Options table**: every realistic option, including each strategy the brief names.
  - **Verdict**: *Bad* (violates a requirement here), *Good* (valid, but not the best
    fit now), or *Chosen*.
  - **Why chosen / why not now**: the reason for this assignment's scope.
  - **Better when**: the architecture or scale at which that option wins.
- **Revisit when**: concrete triggers for changing the decision.

Verdicts are contextual. A *Good* option is not wrong; it is the better choice under
the "Better when" conditions.

## Summary

| # | Decision | Chosen option | Requirement |
|---|---|---|---|
| [D1](#d1-flight-instance-generation) | Flight instance generation | Hybrid: project from schedules; persist instance lazily on first booking | FR4 |
| [D2](#d2-seat-concurrency-and-double-booking) | Seat concurrency | Partial unique index on active seats + one booking transaction | FR8, reply 370-372 |
| [D3](#d3-seat-inventory-representation) | Seat inventory representation | Derived from aircraft layout + active allocation rows | FR2, FR6 |
| [D4](#d4-cancellation-lifecycle) | Cancellation | Conditional status update + bulk release in one transaction | FR9 |
| [D5](#d5-availability-and-read-model) | Availability | Live count of active allocations; no counter or cache | FR5, FR6, reply 358-361 |
| [D6](#d6-transaction-boundaries) | Transaction boundaries | `NEVER` coordinator; separate instance and booking transactions; READ COMMITTED | NFR 191 |
| [D7](#d7-read-protection-and-connection-pools) | Read protection | Separate read pool with short deadlines; write pool protected | Reply 363-365 |
| [D8](#d8-deployment-topology) | Deployment | One stateless Spring Boot app + PostgreSQL | Reply 346-348 |
| [D9](#d9-on-demand-scaling) | Scaling | Identical replicas within a DB connection budget | Reply 346-348 |
| [D10](#d10-persistence-and-schema-ownership) | Persistence | Spring Data JPA + Flyway; one native upsert | Deliverable 4 |
| [D11](#d11-code-organization) | Code organization | Package-by-layer, flat Spring Data repositories | NFR 188-189 |
| [D12](#d12-service-contracts) | Service contracts | Narrow use-case interfaces with immutable models | NFR 189, 195 |
| [D13](#d13-orm-fetching-and-writes) | ORM fetching and writes | Search projection, targeted entity graphs, OSIV off, IDENTITY inserts | NFR 196 |
| [D14](#d14-time-and-booking-horizon) | Time and horizon | UTC, injected clock, `[today, today+365)` | FR4 |
| [D15](#d15-public-identity) | Public identity | Flight number + date; UUID booking reference | FR7 |
| [D16](#d16-api-scope-and-retries) | API scope | Five required operations; no idempotency keys | Scope 32-37 |
| [D17](#d17-http-negotiation-and-strict-clocks) | HTTP preflight | JSON negotiation before the handler runs; strict `HH:mm` | NFR 190, 193 |
| [D18](#d18-errors-and-logging) | Errors and logging | RFC 9457 + stable codes; correlation ID; no payload logging | NFR 192, 194 |
| [D19](#d19-verification-strategy) | Verification | JUnit + ArchUnit + real PostgreSQL (Testcontainers) | NFR 195 |
| [D20](#d20-latency-acceptance) | Latency acceptance | Slowest response < 1 s and status 200 in a defined baseline | Reply 363-365 |
| [D21](#d21-libraries-and-documentation) | Libraries and documentation | JDK/Spring facilities; one decision log + one evidence ledger | NFR 188, deliverables |

### Brief-named strategies and where they are decided

| Brief text | Strategy | Decision | Verdict |
|---|---|---|---|
| FR4 "Materialize flight instances" | Full materialization | D1 | Good |
| FR4 "Generate them dynamically" | Pure dynamic | D1 | Good |
| FR4 "Hybrid approach" | Hybrid | D1 | **Chosen** |
| FR8 "Database locking" | Unique constraint / index lock | D2 | **Chosen** |
| FR8 "Optimistic locking" | `@Version` / compare-and-set | D2 | Good |
| FR8 "Pessimistic locking" | `SELECT … FOR UPDATE` | D2 | Good |
| FR8 "Transactions" | Atomic unit around the writes | D2, D6 | **Chosen** (necessary but not sufficient alone) |
| FR8 "Versioning" | Version column (an optimistic-locking implementation) | D2 | Good |
| FR7 "PNR or UUID" | Booking reference format | D15 | UUID **Chosen** |

---

## D1. Flight instance generation

**Requirement:** FR4 (brief 89-111): expose bookable instances for 365 days and
document the approach. **Context:** 500 schedules × 365 dates ≈ 182,500 possible
instances. Schedules are immutable, and searches outnumber bookings 25:1.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when (architecture / scale) |
|---|---|---|---|---|---|---|---|
| Full materialization | Good | No | A job inserts one `flight_instance` row per operating date for the next 365 days | Simple reads and joins; a row exists for per-date overrides | Needs a daily rolling-horizon job, a backfill for each new schedule, and up to ~182,500 rows mostly unused | No per-date operational data exists in scope, and a scheduler adds a failure mode | Per-date changes (cancel one date, swap aircraft, dynamic pricing), or an ops team that edits instances |
| Pure dynamic | Good | No | No instance table; flights are computed from schedule + date on every request | Zero storage, no job | Bookings need a durable FK target; would key on (schedule, date) everywhere, making references and constraints awkward | Booking integrity is easier with a real row to reference | Read-only timetable services, or when bookings live in another system |
| **Hybrid** | **Chosen** | **Yes** | Search and seat map compute flights from the schedule; the first booking creates the row with `INSERT … ON CONFLICT DO NOTHING` then `SELECT` | No job, no wasted rows; stable FK for bookings and the seat index | Read queries must tolerate a missing instance row; a failed first booking can leave an empty instance | Fits immutable schedules and the 25:1 read mix; concurrency-safe creation is proven | Current scope: immutable schedules, moderate scale |

**Evidence:** `InstanceMaterializationIT` (24 concurrent first bookers → one row),
`FlightSearchIT`, `SeatMapIT`. **Revisit when:** per-date overrides, schedule edits or
per-instance pricing enter scope; then switch to rolling materialization.

## D2. Seat concurrency and double booking

**Requirement:** FR8 (brief 161-174) and reply 370-372: prevent double booking across
concurrent requests and instances; document the strategy. **Context:** multiple
stateless replicas share one PostgreSQL primary; a booking holds 1..n seats and must
be all-or-nothing.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when (architecture / scale) |
|---|---|---|---|---|---|---|---|
| JVM lock (`synchronized`, in-memory map) | Bad | No | One process serializes bookings in memory | Trivial | Other replicas bypass it; state lost on restart | Violates the multi-instance clarification | Single-process demo or an in-memory LLD exercise |
| Check-then-insert, no constraint | Bad | No | `SELECT` free seats, then `INSERT` | Simple | Race: two transactions both see the seat free (READ COMMITTED does not prevent it) | Allows double booking | Never as the only protection |
| **Database locking: partial unique index + transaction** | **Chosen** | **Yes** | `uq_booking_seat_active ON booking_seat(flight_instance_id, seat_label) WHERE status='BOOKED'`. All seats are inserted in one transaction in sorted order; a duplicate waits on the index, then fails → rollback → 409 | Database enforces the invariant for any replica; locks only the contested seat; disjoint seats run in parallel; released history stays | PostgreSQL-specific partial index; the loser waits for the winner's commit; constraint name must be mapped precisely | Directly encodes "one active owner per seat" with no extra rows or counters | Current design: insert-only allocation, no seat holds |
| Pessimistic: `SELECT … FOR UPDATE` on per-seat inventory rows | Good | No | Pre-created seat rows are locked in order, then updated to BOOKED | Explicit lock order; natural place for holds/TTL | Needs materialized seat rows (D3); deadlock and timeout handling | Requires inventory materialization that D1/D3 avoid | Seat holds with expiry, multi-step checkout, per-seat pricing |
| Pessimistic: `FOR UPDATE` on the flight row | Good | No | Lock the flight instance, check seats, insert | Easy to reason about | Serializes all bookings on a flight, even for different seats | Unnecessary serialization; the hot-flight bottleneck grows with load | Flight-level invariants (overbooking limits, fare-bucket caps) |
| Optimistic locking / versioning: `@Version` on a seat or flight row | Good | No | Read version, update `WHERE version = ?`; 0 rows → retry or fail | No lock held while reading; good under low contention | Needs a mutable row to version; retries under contention; a flight-level version makes disjoint seats conflict | Inserts into an index need no version; retries would add latency for nothing | Mutable inventory rows with low contention, for example editing a booking's details |
| SERIALIZABLE isolation | Good | No | Database detects read/write conflicts and aborts one | Protects complex invariants with no explicit locks | Serialization failures must be retried; throughput cost | The invariant is a simple uniqueness rule, so a constraint is cheaper and exact | Cross-row invariants that no single constraint can express |
| Distributed lock (Redis, ZooKeeper) | Bad here | No | Lease per seat or flight outside the database | Coordinates across services or databases | Lease expiry and fencing problems; a second system to run; the database is still the source of truth | One shared database already gives an exact, durable lock | Seat ownership spread across several databases or services |

"Transactions" from the brief are the substrate of the chosen option, not an
alternative: they make the group insert atomic, while the index decides the winner.
Versioning is how optimistic locking is usually implemented, so they share a row.

**Worked example.** Requests A (`1A,1B`) and B (`1B,1C`) arrive together.
1. Both validate; both see `1B` free (stale read is allowed by reply 358-361).
2. Both insert in label order. A inserts `1A`, B inserts `1B`.
3. A's `1B` insert waits on B's uncommitted `1B` index entry.
4. B inserts `1C` and commits → A's `1B` fails with a unique violation → A rolls back
   `1A` too.
5. A receives 409 `SEAT_UNAVAILABLE` naming `1B`; `1A` is still free.

Sorted insert order reduces deadlocks between overlapping groups; it does not make
deadlocks impossible. The order is lexical (`10A` sorts before `2A`); it is
deterministic, which is all deadlock avoidance needs.

**Evidence:** `BookingConcurrencyIT` (service layer: 24 same-seat, reversed and
overlapping pairs, 12 × 72-seat contenders), `CrossInstanceIT` (two contexts over
HTTP), `MixedLoadIT` hot seat (64 HTTP contenders → 1 × 201, 63 × 409). See
[NFR evidence](../../tests/nfr-evidence.md). **Revisit when:** seat holds, payments
during checkout or flight-level limits are added (then per-seat rows with
`FOR UPDATE`, or a flight lock).

## D3. Seat inventory representation

**Requirement:** FR2 (fixed seat map per aircraft), FR6 (Available/Booked map).

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Derived layout + active allocation rows** | **Chosen** | **Yes** | Labels come from `rows × seat_letters`; a `BOOKED` row means booked; no row means available | No per-flight seat rows; aircraft layout defined once (`SeatLayout`) | Availability is computed per read | Smallest model that satisfies FR2/FR6 with D2 | Fixed layouts, no holds |
| Per-instance seat inventory rows | Good | No | One row per (instance, seat) with status | Natural for holds, pricing, lockable rows | ~180 rows × every instance; must be created with the instance | Needs materialization (D1) and maintenance | Holds, seat-level pricing, layout changes per flight |
| Bitmap / JSON column per instance | Good | No | One field encodes all seats | Compact, single-row read | Every booking updates one hot row; needs optimistic/pessimistic control | Concentrates contention on one row | Small aircraft, very read-heavy, single writer |

**Revisit when:** seat holds or per-seat pricing are required.

## D4. Cancellation lifecycle

**Requirement:** FR9 (brief 176-183): change status, release seats, make them
bookable. User policy: repeat or departed cancellation returns 409.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Conditional update + bulk release, one transaction** | **Chosen** | **Yes** | `UPDATE booking SET status='CANCELLED' WHERE status='CONFIRMED'` (row count arbitrates), then `BOOKED→RELEASED` for all seats, checking the release count | Exactly one concurrent canceller wins; status and seats roll back together; history kept | Bulk JPQL updates bypass the persistence context, so the result is built from the read model, not from a stale entity | Atomic and race-safe with no extra lock or version column | Current all-or-nothing cancellation |
| Load, mutate, save (no version) | Bad | No | Read entity, set status, save | Simple | Two cancellers both "succeed" | Lost-update race | Never without a version or predicate |
| `@Version` on booking | Good | No | Optimistic check on save | Standard JPA | Loser gets an exception to translate; no gain over the predicate | The predicate is the same compare-and-set with less code | Bookings edited in several fields by several actors |
| Delete seat rows on cancel | Good | No | Remove allocations | Smaller table | Loses history and audit | History is cheap; the partial index already ignores RELEASED rows | Strict data-retention policies |
| Idempotent repeat cancel → 200 | Good | No | Second cancel returns the same result | Retry-friendly | Contradicts the user-selected 409 policy | User decision | A public API with automatic client retries |

**Evidence:** `CancellationIT` (24 cancellers → one winner; injected release failure
rolls back both changes; cancel/rebook race). **Revisit when:** partial cancellation
or refunds enter scope.

## D5. Availability and read model

**Requirement:** FR5 available seats, FR6 real-time seat map; reply 358-361 allows
stale search if booking reports conflicts.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Live count of active allocations** | **Chosen** | **Yes** | Search: capacity − `count(BOOKED)` in one aggregate query; seat map: list active labels | Always committed truth; no invalidation | Cost grows with active rows per query (bounded by an index) | Measured well under 1 s at 600,000 allocations | Current scale |
| Counter column on instance | Good | No | `available_seats` updated per booking/cancel | O(1) read | Hot row on every booking; drift needs reconciliation; still needs D2 for seats | Adds write contention to fix a read that is already fast | Count query proven dominant under real load |
| Cache (Redis/Caffeine) | Good | No | Cache search results with TTL or invalidation | Offloads DB | Staleness policy, invalidation across replicas | No measured read bottleneck; reply allows staleness but nothing requires it | Sustained read saturation of the database |
| Read replica | Good | No | Route reads to a replica | Scales reads | Replication lag; more infrastructure | Not required by reply 346-348 | Read volume beyond one primary |

Search does not hide flights that already departed earlier today: the window check is
date-based, and booking such a flight returns 409 `FLIGHT_DEPARTED`. This is accepted
staleness under reply 358-361 (user decision, 2026-10-21).
**Revisit when:** measured read load saturates the primary.

## D6. Transaction boundaries

**Requirement:** NFR 191 transaction handling; atomic booking; safe 409 enrichment.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Sequential units with a `NEVER` coordinator** | **Chosen** | **Yes** | Coordinator (no transaction) → short instance upsert transaction → booking transaction in a separate bean → after rollback, a fresh read names the conflicting seats | Rollback finishes before the conflict read; one connection at a time; ambient transactions rejected | An empty instance can survive a failed booking | Correct proxy and rollback semantics with no nested connections | Current hybrid design |
| One encompassing transaction | Bad | No | Upsert + insert + conflict read in one transaction | Fewer boundaries | After a unique violation PostgreSQL aborts the transaction, so the conflict read fails | Breaks 409 enrichment | Only without post-failure reads |
| `REQUIRES_NEW` inner transaction | Good | No | Nested independent commit | Isolated inner work | Two connections per request at once → pool starvation under load | Doubles connection demand | Truly independent audit writes with a capacity budget |
| `ON CONFLICT DO UPDATE … RETURNING` | Good | No | One statement returns the row | One round trip | Writes and locks the existing instance on every booking | Unneeded write on the hot path | When the row really must be updated |
| SERIALIZABLE everywhere | Good | No | Highest isolation | Fewer anomalies | Retry handling everywhere | D2 constraint already exact; READ COMMITTED suffices | Complex invariants (see D2) |

The upsert uses `DO NOTHING` then a separate `SELECT` because under READ COMMITTED the
new statement sees the competing committed row. **Evidence:** `BookingIT`,
`InstanceMaterializationIT`. **Revisit when:** a workflow must span several
aggregates atomically.

## D7. Read protection and connection pools

**Requirement:** reply 363-365: search under 1 s; booking stability over speed.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Two pools on one primary** | **Chosen** | **Yes** | Read pool max 5 (acquire 250 ms, SQL 500 ms, lock 250 ms) routed by `LazyConnectionDataSourceProxy` for read-only transactions; write pool max 10 (acquire 20 s) | Read overload returns fast 503 + `Retry-After: 1` and never consumes booking connections; bookings wait patiently | Custom datasource configuration; 15 connections per replica to budget (45 for three) | Proven isolation in both directions | Current single-primary design |
| One shared pool | Good | No | Default Boot Hikari | Zero custom code | Search spikes can starve bookings | Violates "booking stability over speed" under load | Low, uniform traffic |
| Bulkhead/rate limiter (Resilience4j) | Good | No | Limit concurrent searches in the app | Fine-grained control | New dependency; still shares connections | Pools already bound DB work | Several downstream dependencies to protect |
| Read replica pool | Good | No | Reads to a replica | Removes read load from primary | Lag; infra | Not needed at measured load | Read volume beyond one primary |

**Evidence:** `ReadIsolationIT` (each pool exhausted separately; lock and statement
timeouts produce 503 within 1 s and recover). **Revisit when:** replica count × 15
approaches `max_connections`, or a read replica is introduced.
Limits bound database waits only, not HTTP queueing, network time or initial connection
establishment. Overload is a 503, never a successful empty search.

## D8. Deployment topology

**Requirement:** reply 346-348: design for multiple instances; reviewer runs one.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Stateless modular monolith + PostgreSQL** | **Chosen** | **Yes** | One Spring Boot artifact; all state in the database | Few moving parts; local transactions | All features scale together | Meets the clarified scale with the least complexity | Current scope |
| Microservices (search, booking, schedule) | Good | No | Separate deployables and data | Independent scaling and release | Distributed transactions/sagas, network failures, duplicated contracts | No independent teams or data lifecycles; brief excludes it implicitly by scope | Large organisations, independently evolving domains |
| Event-driven (Kafka) booking pipeline | Good | No | Booking requests queued, processed asynchronously | Absorbs spikes | Asynchronous UX, more infrastructure | Brief expects a synchronous booking response | Very high write bursts, async confirmation acceptable |

**Revisit when:** separate teams own search and booking, or write bursts exceed one primary.

## D9. On-demand scaling

**Requirement:** reply 346-348; user accepted stateless scaling without a fixed peak RPS.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Identical replicas behind a readiness-aware balancer** | **Chosen** | **Yes** | `docker compose --scale app=N`; readiness includes DB, liveness does not | No sticky state; any replica serves any request | Database remains the shared limit; pools × replicas must fit `max_connections` | Simplest correct horizontal model | Current design |
| Sticky sessions / JVM-owned inventory | Bad | No | Route a flight to one JVM | Local locking | Failover loses ownership | Breaks multi-instance correctness | Never here |
| Autoscaler manifests (K8s HPA, cloud) | Good | No | Automatic replica count | Elastic | Infrastructure outside the brief; does not fix DB contention | Out of scope; deployment concern | A real deployment with SLOs |

**Evidence:** `CrossInstanceIT`; historical 3-JVM Compose run (see evidence S11).

## D10. Persistence and schema ownership

**Requirement:** deliverable 4 (schema, constraints, indexes); user chose JPA.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Spring Data JPA + Flyway, Hibernate `validate`** | **Chosen** | **Yes** | Reviewed SQL migrations own DDL; JPA maps; one native upsert | Standard Spring stack; executable schema | ORM SQL must be inspected | User choice; partial index needs hand-written DDL anyway | Current |
| JDBC / jOOQ only | Good | No | Explicit SQL everywhere | Full SQL control | More mapping code | No query too complex for JPQL + one native query | SQL-heavy reporting workloads |
| Hibernate `ddl-auto=update` | Bad | No | ORM generates schema | Fast prototype | Cannot express the partial index; unreviewed changes | Schema is a deliverable | Throwaway prototypes |

## D11. Code organization

**Requirement:** NFR 188-189; user-selected package-by-layer.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Package-by-layer, flat Spring Data repositories** | **Chosen** | **Yes** | `controller`, `service`, `service.impl`, `repository`, `entity`, `model`, `dto` | Familiar; ArchUnit-enforced | Service implementations know JPA | User decision | Small to medium apps |
| Package-by-feature | Good | No | `booking/`, `search/` packages | Cohesive features | Reorganization only | Not requested | Many features with separate owners |
| Hexagonal ports/adapters | Good | No | Domain core with repository ports and JPA adapters | Persistence independence | Extra interfaces and mappings (an earlier draft had them; removed) | No second adapter exists | Several storage or transport adapters |

## D12. Service contracts

**Requirement:** NFR 189 separation, 195 testability.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Narrow use-case interfaces + immutable records** | **Chosen** | **Yes** | e.g. `BookingService.create(CreateBookingCommand)`; controllers map DTOs, implementations map entities | No entity or JSON leakage; mockable | Some mapping code | Clear boundaries | Current |
| Generic CRUD services / return entities | Bad | No | `save`, `findAll` exposing entities | Fewer types | Leaks persistence; lazy-loading errors in controllers | Breaks separation | Admin CRUD tools |
| Interface for every helper | Good | No | Abstract everything | Flexible | Indirection without alternatives | No second implementation | Plugin systems |

## D13. ORM fetching and writes

**Requirement:** NFR 196 reasonable performance.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Projection for search; targeted entity graphs; OSIV off** | **Chosen** | **Yes** | Search returns `FlightAvailability` records (1 SQL, 0 entities); lookups fetch aircraft explicitly | No N+1; small payloads | A few explicit queries | Measured 1 statement per search | Current |
| Lazy loading with OSIV | Bad | No | Load as needed in view | Little code | N+1 queries; connections held through rendering | Performance and pool risk | Never for APIs |
| Global eager fetching | Bad | No | `FetchType.EAGER` | Simple | Over-fetching | Unbounded joins | Never |
| Sequence IDs + JDBC batching | Good | No | `SEQUENCE` with allocation size; Hibernate batch inserts | Fewer round trips for large groups | Migration; changes insert behavior | Groups are small; IDENTITY prevents batching, so seat inserts are individual | Measured write round-trip bottleneck |

`reWriteBatchedInserts=true` in the JDBC URL has no effect while IDs use IDENTITY; it
is kept only so a future sequence migration can batch.

## D14. Time and booking horizon

**Requirement:** FR4 "365 days from the current date"; assumption: no time-zone conversion.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **UTC + injected `Clock`, `[today, today+365)`** | **Chosen** | **Yes** | `FlightDatePolicy` checks the window per request; departure checked at validation and at the start of the write unit | Exactly 365 dates; deterministic tests; leap-year safe | Departure is not re-checked at commit; already-departed flights still appear in search | Simple and testable | Current |
| Host-local time / `plusYears(1)` | Bad | No | System default zone | No code | Zone-dependent; 366 dates in leap years | Inconsistent across hosts | Never |
| Airport-local times | Good | No | Convert per airport zone | Realistic | Out of scope per brief assumptions | Not required | Real multi-zone airline |
| Hard commit-time departure cutoff | Good | No | Check inside commit / DB constraint | Exact | More complexity for a seconds-wide window | Not required | Strict regulatory cutoffs |

## D15. Public identity

**Requirement:** FR7 "Booking Reference (PNR or UUID)".

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **UUID reference; flight = number + date** | **Chosen** | **Yes** | `UUID.randomUUID()`; one immutable schedule per flight number | Collision-free, no retry code | Not human-friendly | Brief allows UUID | Current |
| 6-character PNR | Good | No | Random alphanumeric with uniqueness retry | Human-readable | Collision handling and retry | Not required | Call-centre or legacy GDS integration |
| Versioned schedule identity | Good | No | Number + version | Supports schedule changes | More model and API | Schedules are immutable | Schedule edits in scope |

## D16. API scope and retries

**Requirement:** scope (brief 32-37): only the listed workflows.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Five required operations** | **Chosen** | **Yes** | Create schedule, search, seat map, book, cancel | Exactly the brief | A lost response leaves the client without its reference | Scope discipline | Current |
| `Idempotency-Key` + stored result | Good | No | Replay the same response for the same key | Safe client retries | New contract, storage and key races | Not requested | Public API with unreliable networks |
| Extra CRUD, auth or payment stubs | Bad | No | Invented endpoints | – | Misrepresents scope | Out of scope | When actually required |

## D17. HTTP negotiation and strict clocks

**Requirement:** NFR 190 REST, 193 validation.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Negotiate before invocation; strict `HH:mm`** | **Chosen** | **Yes** | `produces = JSON` at mapping; `@JsonFormat(pattern = "HH:mm", lenient = false)` | No write followed by 406; `24:00` rejected | Slightly stricter clients | Truthful responses | Current |
| Negotiate after the handler | Bad | No | Default fallback | – | Commits then returns 406 | Lies about state | Never for writes |
| Lenient time parsing | Bad | No | `24:00` → `00:00` | Forgiving | Silently changes data | Data integrity | Never |

Cancellation still accepts an empty body without a `Content-Type`.

## D18. Errors and logging

**Requirement:** NFR 192 errors, 194 logging.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **RFC 9457 + stable `errorCode`; named-constraint mapping; correlation ID in MDC; no payload logging** | **Chosen** | **Yes** | Only `uq_booking_seat_active` → 409; other integrity errors → 500; DB unavailable → 503 + `Retry-After` | Precise, safe, traceable | Less raw detail in logs | Privacy and correctness | Current |
| Every integrity error → 409 | Bad | No | Catch-all mapping | Simple | Hides schema bugs | Misleads clients | Never |
| Log raw SQL / payloads | Bad | No | Verbose logs | Easy debugging | Leaks names and values | Privacy | Local debugging only |
| Distributed tracing (OpenTelemetry) | Good | No | Trace IDs across services | Cross-service view | Infra; one service only | Correlation ID suffices | Several services |

Spring's SQL translator also classifies Hibernate-wrapped PostgreSQL shutdown errors as
503; unclassified ORM failures stay 500. Writes whose outcome is unknown are not blindly
retryable (see D16).

## D19. Verification strategy

**Requirement:** NFR 195 testability; evaluation "tests".

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **JUnit + ArchUnit + Testcontainers PostgreSQL** | **Chosen** | **Yes** | `*Test` without Docker; `*IT` against real PostgreSQL with barriers | Proves the real partial index, upsert and rollback | Docker required; one intermittent load test (see evidence §8) | Only the real database proves D2 | Current |
| H2 / mocks for concurrency | Bad | No | In-memory DB | Fast | Different locking and no partial-index semantics | Would not prove D2 | Pure logic tests |
| Postman/Newman only | Good | Complement | Public HTTP flows | Demo-friendly | No fault injection or plans | Used as a complement | Interview demo |

## D20. Latency acceptance

**Requirement:** reply 363-365 search under 1 s; no peak RPS agreed.

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **Slowest response < 1 s and all 200 in defined baselines** | **Chosen** | **Yes** | Read-only 100 + 200 searches; 16-client mixed phase; 64/192 stress counts 503 separately | Tails and failures cannot hide | Local, short, warm, closed loop | Strict without inventing an SLA | Current |
| p95 only | Bad | No | Percentile gate | Tolerant of noise | Hides tail and errors | Misleading | Never alone |
| Sustained open-loop load test | Good | No | Fixed arrival rate (Gatling/k6) for minutes | Real saturation curve | Needs agreed workload and environment | No target RPS agreed | Capacity planning with SLOs |

This gate is an engineering choice under the user's delegated judgment, not an
interviewer percentile. In 64/192-client stress phases a 503 is an explicit failure to
serve, successful latency is reported separately, and a phase where every search is shed
fails. Booking correctness is mandatory in every phase.

## D21. Libraries and documentation

**Requirement:** NFR 188 clean architecture; deliverables (README, HLD, LLD).

| Option | Verdict | Used? | How it works | Strengths | Weaknesses | Why chosen / why not now | Better when |
|---|---|---|---|---|---|---|---|
| **JDK + Spring + Lombok; one decision log; one evidence ledger** | **Chosen** | **Yes** | Records, `UUID`, `java.time`; this file for rationale; `tests/nfr-evidence.md` for numbers | No custom plumbing; numbers in one place | Discipline to keep links current | Prevents drift between documents | Current |
| Repeat numbers in every document | Bad | No (was the case before 2026-10-21) | Copy results into README/HLD/LLD | Self-contained pages | Drifts across runs | Caused stale numbers | Never |
| New generic frameworks or utilities | Bad | No | Abstract imaginary variants | – | Indirection | No repeated variants | Real repeated variants |

Process rule kept from the original log: audit after every five completed todos and fix
findings before continuing.
