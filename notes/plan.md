# Airline Ticketing System — Locked Plan (D1–D10)

> **PRIVATE.** This file is gitignored. It contains interview strategy and
> evaluation commentary that must never reach a reviewer.

---

## 0. How the assignment is actually scored

The brief *looks* like a CRUD exercise. It is not. It is a **concurrency +
data-modelling design test** wrapped in a booking domain.

Estimated weight:

| Area | Weight | Why |
|---|---|---|
| Documentation (HLD + LLD + README) | ~35% | 3 of 5 deliverables are documents |
| Concurrency handling | ~25% | The only genuinely hard problem in the brief |
| Database design | ~15% | ER + constraints + indexes explicitly demanded |
| API design + code organisation | ~15% | |
| Testing approach | ~10% | Named in evaluation criteria |

**Priority order agreed with user: HLD (1) → LLD (2) → Java code (3).**
This matches the brief's own deliverable ordering.

### The three decisions that carry the round
1. **Flight-instance generation strategy** — the brief explicitly says
   "the design decision must be documented".
2. **Seat consistency under concurrency** — the brief explicitly says
   "candidates should document their concurrency strategy".
3. **Proving #2 with a real multi-threaded test** — almost nobody does this.
   It converts a claim into evidence.

### Hidden traps most candidates miss
- Timezone of "operates Monday" — Monday *where*? (Departure airport local time.)
- Overnight flights — arrival 01:30 is the *next* day; naive date maths breaks.
- Cancelled seat rows permanently blocking rebooking (needs a partial index).
- PNR collisions (6-char random space is small; need retry/unique).
- TOCTOU: "check seat is free, then insert" is not a guard, only a UX nicety.

---

## D-X. Extensibility & Scalability Charter

Guiding principle: **build extension points, not extensions.**

| Tier | Meaning | Rule |
|---|---|---|
| **Tier 1** | Build now, ~zero cost | Just do it |
| **Tier 2** | Build the *seam*, thinnest possible feature behind it | Interface + one impl |
| **Tier 3** | Document only | Named in the Decision Log "Future" column |

Scope discipline (user directive): **do not increase scope.** Extensibility
lives in the *class design*, not in extra tables or extra features.

---

## D1. Domain model

Six aggregates. Key decision: **seats are derived, not materialised.**

- **Option A (rejected):** one row per seat per flight (~180 rows × 18,250
  instances ≈ 3.3M rows) — pre-created inventory.
- **Option B (CHOSEN):** store only *booked* seats. Seat map =
  `aircraft layout − booked set`.

Consequence of B: there is no per-seat row to carry `@Version`, so optimistic
locking has to land on `FlightInstance`. That is a real trade-off and is
documented honestly (see D4).

Other D1 decisions:
- `passenger_name` is a column on `booking_seat`. **No passenger table**
  (brief: "passenger information may be minimal: Name").
- Days of operation → **child table** (not a bitmask — see D3 self-correction).
- Overnight flights supported via `arrival_day_offset`.
- Soft seat release (status flag), not physical delete — preserves history.
- Black-box calls (payment, notification) return static stubs.

---

## D2. Flight instance generation — **HYBRID**

| Approach | Verdict |
|---|---|
| Materialise all 365 days up front | Needs a cron, dead rows, schedule edits are painful |
| Fully dynamic | Nothing to attach bookings to |
| **Hybrid (CHOSEN)** | Schedules are truth; search computes in memory; the row is materialised **lazily on first booking** |

**Do not** justify this with "materialising is too expensive" — 50 schedules ×
365 days ≈ 18,250 rows is trivial, and a reviewer will catch the bad argument.
The real arguments are **operational**:
- no scheduled job to own, monitor, or back-fill;
- a schedule created today is bookable *immediately*, with no job latency;
- zero rows for dates nobody ever queries;
- schedule edits don't require reconciling pre-generated rows.

The elegant part is the **read/write asymmetry**: reads never write.

### Search algorithm (3 queries, no N+1)
1. Resolve `source`/`destination` IATA → airport ids.
2. `SELECT` schedules matching route **and** day-of-week (join on
   `flight_schedule_day`), with `valid_from <= date <= valid_to`.
3. One batched `SELECT` of existing `flight_instance` rows for those schedule
   ids on that date → merge. Missing instance ⇒ full aircraft capacity.

### Business key
`flightNumber + date`. **No flight-instance IDs are ever exposed in the API** —
that is precisely what makes lazy materialisation invisible to clients.

### Materialisation gotcha (Postgres)
A constraint violation **aborts the whole transaction**. So
`try { insert } catch { select }` inside one tx is a latent bug.
Use, in a `REQUIRES_NEW` transaction:

```sql
INSERT INTO flight_instance (...) VALUES (...)
ON CONFLICT (schedule_id, flight_date) DO NOTHING;
SELECT ... ;  -- always re-select
```

---

## D3. Database schema — 7 tables

`airport` · `aircraft` · `flight_schedule` · `flight_schedule_day` ·
`flight_instance` · `booking` · `booking_seat`

### Self-correction made during design
Originally planned a `SMALLINT` bitmask for days of operation. Writing the DDL
exposed two problems:
1. `(days & bit) <> 0` is **non-sargable** — can never use an index.
2. The schedule-overlap rule cannot be expressed as a DB constraint.

→ switched to a `flight_schedule_day` child table. **This self-correction is
worth narrating in the Decision Log** — it shows design iteration.

### The three load-bearing constraints
```sql
-- 1. THE authoritative guard against double-booking
CREATE UNIQUE INDEX uq_booking_seat_active
  ON booking_seat (flight_instance_id, seat_label)
  WHERE status = 'BOOKED';

-- 2. One instance per schedule per date (enables ON CONFLICT)
ALTER TABLE flight_instance
  ADD CONSTRAINT uq_instance UNIQUE (schedule_id, flight_date);

-- 3. PNR uniqueness
ALTER TABLE booking ADD CONSTRAINT uq_booking_pnr UNIQUE (pnr);
```

Constraint #1 is **partial**, so `RELEASED` seats are instantly rebookable.
This is the single most important line in the schema.

### Keys
BIGSERIAL internal PK + business key (IATA code, flight number, PNR).
**Pooled sequences** (`allocationSize=50`, DB `INCREMENT BY 50`) because
Hibernate **cannot batch INSERTs with IDENTITY columns** — a 6-seat booking
would otherwise be 6 round trips. Plus `reWriteBatchedInserts=true` in the JDBC URL.

### Index plan (9, four partial)
Route+day composite on schedules; `(schedule_id, flight_date)` unique;
`(flight_instance_id, seat_label) WHERE status='BOOKED'`; `pnr`;
`(flight_date)` on `booking_seat` (future partition key); FK indexes.

### Flyway layout
- `V1__baseline_schema.sql` — tables, PKs, FKs, constraints
- `V2__indexes.sql`
- `V3__seed_reference_data.sql` — 10 airports, 4 aircraft, ~12 schedules

`db/schema.sql` = labelled `pg_dump --schema-only` snapshot (Flyway is truth).

### PNR
6 chars, Crockford-style alphabet with ambiguous characters removed
(no `I`, `O`, `0`, `1`).

---

## D4. Concurrency — **the core block**

### Three-layer guarantee
| Layer | Mechanism | Guarantees |
|---|---|---|
| **L1** | Partial unique index | **Correctness** — the DB physically cannot double-book |
| **L2** | `@Version` on `FlightInstance` | Consistency of the `available_seats` counter |
| **L3** | Bounded retry + jitter | Availability / UX under contention |

> **Key line for the write-up:** delete L2 and L3 and the system is *still
> correct*. They buy consistency and UX, not correctness.

### Four details that differentiate
1. **`@Retryable` and `@Transactional` must be on different beans.** On the same
   method, retries re-run inside the already-doomed transaction. Requires
   `BookingService` (retry, no tx) → `BookingTransactionalService` (tx).
2. **Seats are inserted in sorted order.** T1 inserting `1A,1B` while T2 inserts
   `1B,1A` deadlocks on the unique index. Sorting imposes a global lock
   ordering. This is a genuinely senior detail.
3. **The availability pre-check is advisory, not a guard** (TOCTOU). Say so
   explicitly; it exists for a good error message, not for safety.
4. **Conflicts name the offending seats** in the 409 response.

### Known accepted flaw — document it, don't hide it
`@Version` on `FlightInstance` means two customers booking **different** seats
on the **same** flight still collide (false conflict).

| Option | Description |
|---|---|
| **A (CHOSEN)** | `@Version` — simple, correct, false conflicts under load |
| B | CAS: `UPDATE ... SET available_seats = available_seats - :n WHERE id = :id AND available_seats >= :n` — no false conflicts |
| C | Drop the counter entirely; derive availability from `COUNT(*)` |

Mitigation: **measure the retry count** in the 180-thread test and report it.
That turns a weakness into evidence of engineering judgement.

Retry config: 3 attempts, 25 ms base, ×2 multiplier, jitter.
Cancellation is **idempotent**.

### Five concurrency tests
1. N threads → same single seat ⇒ exactly 1 success.
2. N threads → same seat *set* ⇒ exactly 1 success.
3. Overlapping seat sets ⇒ no seat double-sold, no deadlock (proves sort order).
4. Book → cancel → rebook same seat ⇒ succeeds (proves partial index).
5. Full-flight stampede: 180 threads, 180 seats ⇒ exactly 180 booked, 0 oversold.

---

## D5. API contract — 7 endpoints

| Method | Path |
|---|---|
| POST | `/api/v1/admin/flight-schedules` |
| GET | `/api/v1/admin/flight-schedules` |
| GET | `/api/v1/flights?source=&destination=&date=` |
| GET | `/api/v1/flights/{flightNumber}/seat-map?date=` |
| POST | `/api/v1/bookings` |
| GET | `/api/v1/bookings/{pnr}` |
| POST | `/api/v1/bookings/{pnr}/cancel` |

- **No flight-instance IDs in the API.** `flightNumber + date` everywhere.
- Errors: **RFC 7807 `ProblemDetail`** + a stable `ErrorCode` enum.
- Booking payload pairs passenger↔seat: `passengers: [{ name, seat }]`.
- Seat map is **row-grouped** (mirrors a physical cabin, not a flat list).
- `POST .../cancel` (not `DELETE`) — cancellation is a state transition that
  returns a representation, not a resource deletion.
- springdoc + Swagger UI.

---

## D6. Cross-cutting

- **6 feature packages**: `airport · aircraft · schedule · flight · booking · common`,
  each internally layered `domain → application → api → infrastructure`.
- `domain/` compiles **without Spring and without JPA annotations** —
  **enforced by ArchUnit**. This is the strongest single signal of clean architecture.
- Injected `Clock` everywhere (never `LocalDate.now()`) — makes the 365-day
  window testable.
- `ddl-auto: validate` — Flyway owns the schema, Hibernate verifies it.
- `open-in-view: false` — no lazy loading in the view layer.
- Hibernate JDBC batching on.
- Three-layer validation: Bean Validation (syntax) → application (existence,
  booking window) → domain invariants (always true).
- MDC logging with a correlation id; **never log passenger names** (PII).
- `@ConfigurationProperties` — no magic `365` or `9` in code.
- Actuator: `health`, `info` only.

---

## D7. Testing — ~55 tests, 5 layers

1. **Domain unit tests** — no Spring, microseconds.
2. **Application service tests** — mocked repositories.
3. **Repository slice tests** (`@DataJpaTest` + Testcontainers) — including a
   *direct* test that the partial unique index rejects a duplicate.
4. **Web slice tests** (`@WebMvcTest`) — validation + error mapping.
5. **Concurrency integration tests** — the headline deliverable.

Notes:
- Tests live in `src/test/java` (Maven convention). `tests/` holds the manual
  API collection + smoke script. **This deviation must be stated in the README.**
- Flyway migrations run under test ⇒ migrations are themselves tested.
- A `RetryListener` counts retries → hard numbers for the D4 trade-off.
- Concurrency tests **cannot** use `@Transactional` rollback isolation (they
  need real commits) ⇒ truncating `DatabaseCleaner`.
- JaCoCo: report only, no coverage gate.
- Fluent test-data builders.

---

## D8. Repo & docs

Layout matches the brief exactly: `README.md`, `hld/`, `lld/`, `src/`, `db/`,
`tests/`, `docker-compose.yml`.

- 9 Mermaid diagrams.
- Decision Log: 10 ADR-lite entries **inside `hld/architecture.md`**.
  Emphasis on the **Consequences** column — that is what separates a decision
  log from a feature list.
- Markdown + Mermaid **and** a generated `architecture.pdf`.
- Private GitHub repo under `Nishant006`.
- Logical conventional commits that tell a build story.

### HLD structure (HelloInterview-inspired, 10 sections)
Deliberately split so the distributed-systems material cannot be mistaken for
what was built:

1. Problem understanding
2. Requirements (above / below the line)
3. Assumptions
4. Core entities
5. API
6. **Architecture — AS BUILT** (one request flow per functional requirement)
7. **Deep dives** (incl. the two the brief mandates: generation + concurrency)
8. Decision log
9. **Scale-out evolution — NOT IMPLEMENTED** ← the Ticketmaster-style
   distributed diagram lives *here*, clearly fenced
10. Black boxes

> **Coherence trap:** a 6-microservice diagram pasted over a modular-monolith
> repo reads as *copied*, not *reasoned*. The §6/§9 split is what prevents that.

---

## D9. Build & run

- Three documented run modes; the headline is one-command `docker compose up`.
- Multi-stage `Dockerfile` + Postgres 16 service.
- Spotless (google-java-format) bound to `verify`.
- JaCoCo report-only.
- **Virtual threads enabled** — with the honest note that HikariCP is the real
  ceiling and that virtual threads may *increase* retry counts under contention.
- **Lombok compromise:** `@Getter` + `@NoArgsConstructor(PROTECTED)` +
  `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on entities.
  **Never `@Data`** — four concrete failure modes:
  1. public setters bypass aggregate invariants;
  2. all-field `equals`/`hashCode` breaks `Set` semantics and triggers lazy loads;
  3. all-field `toString` **logs passenger names** (PII leak);
  4. bidirectional associations → `StackOverflowError`.

---

## D10. Scalability narrative

Six bottlenecks, **each with a metric trigger** (not "we could add caching"
but "when p99 search latency exceeds X ms, do Y").

**Structural insight worth leading with:** every invariant in this system is
scoped to a *single flight instance*. Therefore:
- the application is **fully stateless** — scale horizontally with no coordination;
- `flight_instance_id` is a **natural shard key** with zero cross-shard transactions.

That single observation is the most valuable scalability statement in the
whole document.

---

## Deliberately cut (each needs a documented extension path)

Outbox · `booking_event` audit table · idempotency table · caching ·
`airline` table · seat holds (TTL) · multi-schedule-per-flight-number ·
connecting itineraries · fares/pricing · JSONB cabin config.

---

## Open items / blockers

- [ ] Docker Desktop not installed → Testcontainers + the concurrency suite are blocked.
- [ ] `architecture.pdf` tooling undecided (no pandoc, no mermaid-cli).
- [ ] Spring Boot version: Initializr no longer offers 3.x (see D11 below).

---

## D11. Spring Boot 4 vs 3 (raised during scaffolding)

Initializr now offers only **4.x**; Spring Boot 3.x is out of OSS support.
Verified to resolve on Maven Central:

| Artifact | Version |
|---|---|
| `spring-boot-starter-parent` | 4.1.1 |
| `springdoc-openapi-starter-webmvc-ui` | 3.1.1 |
| `archunit-junit5` | 1.5.1 |
| `testcontainers` | 1.21.4 |
| `mapstruct` | 1.6.3 (stable) |
| `lombok` | 1.18.48 |

**Impact on D4:** Spring Framework 7 absorbed `spring-retry` into core.
- `@EnableRetry` → `@EnableResilientMethods`
- `maxAttempts = 3` → `maxRetries = 2` (**excludes** the initial attempt)
- `retryFor` → `includes` / `excludes`
- backoff is now inline: `delay`, `multiplier`, `jitter`

The two-bean split (retry bean ≠ transactional bean) **still applies** — core
retry is still AOP-proxy based, so self-invocation still bypasses the advice.
