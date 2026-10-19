---
title: "ATS-1 — Decision Log"
status: "DRAFT"
source-of-truth: ".notes/assignment-brief.md (verbatim)"
audience: "feeds HLD section 8 (Major design decisions) and README section Design Decisions"
---

# Decision Log — Airline Ticketing System

> **Why this file exists.** The brief grades *"Design decisions"* (Evaluation Criteria
> #8) and *"Major design decisions"* (HLD deliverable). A decision is only gradeable if
> the **rejected** alternatives are visible — a design that shows only what was chosen
> is indistinguishable from a design that never considered anything else.
>
> **Format.** Every entry states the forcing question, tabulates the real options with a
> verdict, names the choice, and — most importantly — records **what would have to change
> for the answer to flip**. Verdict key: ❌ rejected · 🟡 workable but not chosen ·
> ✅ chosen.
>
> **Reconstruction notice.** This file was rebuilt on 2026-10-12 after the original was
> destroyed by a truncating write. It is reconstructed from `spec.md`, which cites every
> entry here, and is therefore consistent with the spec by construction. See DEC-22.

## Index

| ID | Decision | Primary driver |
|---|---|---|
| DEC-1 | Flight instance generation — hybrid | FR4 (mandated: *"must be documented"*) |
| DEC-2 | Concurrency — partial unique index | FR8 (mandated: *"document their concurrency strategy"*) |
| DEC-3 | Seats are derived, only bookings are stored | FR2.4, FR6 |
| DEC-4 | Seat map is read-through, never cached | FR6 (*"real-time"*) |
| DEC-5 | PNR format and collision handling | FR7 |
| DEC-6 | Days of operation as a child table | FR3.3 |
| DEC-7 | Public identity is `flightNumber + date` | FR5, FR7 |
| DEC-8 | No airport time zone column | FR1 vs Assumptions |
| DEC-9 | Booking window is 365 days, externalised | Scope vs FR4 contradiction |
| DEC-10 | Package-by-feature, enforced by ArchUnit | NFR1, NFR2 |
| DEC-11 | RFC 9457 `application/problem+json` | NFR5 |
| DEC-12 | PostgreSQL in tests, not H2 | NFR8 |
| DEC-13 | `open-in-view=false`, DTOs across boundaries | NFR1, NFR4 |
| DEC-14 | Black boxes read narrowly | Scope |
| DEC-15 | Cancellation is `POST /cancel`, not `DELETE` | FR9, NFR3 |
| DEC-16 | Containerised, smoke-tested before code | Deliverable 3, 5 |
| DEC-17 | Mermaid sources plus rendered PDF | Deliverable 1 |
| DEC-18 | Where decisions live | Deliverable 1, 5 |
| DEC-19 | Deployment topology is `N` instances | Evaluation Criteria #5 |
| DEC-20 | NFR envelope from interviewer clarifications | Clarification round |
| DEC-21 | `409` enumerates the seats that were lost | Clarification round |
| DEC-22 | Decision log is version-controlled | Incident |
| DEC-23 | One deployable, two namespaces — not two services | Scope (two actors) + DEC-19 |
| DEC-24 | Booking retries made safe by an optional idempotency key | FR7, FR8 + D4 |
| DEC-25 | Migrations backward-compatible for one release (expand/contract) | DEC-19 + D5 |
| DEC-26 | The 1-second search target is verified, not asserted | Clarification round |
| DEC-27 | Search and booking get different timeout policies | Clarification round |
| DEC-28 | The specification is frozen; effort goes to functionality | Clarification round |
| DEC-29 | Virtual threads are turned off | NFR *"performance optimizations not required"* |
| DEC-30 | The index set, sized to the stated row counts | Deliverable 4 (*"Indexes"*) |

---

## DEC-1 — Flight instance generation

**Forcing question.** Schedules are templates. FR4 requires bookable instances for 365
days. Where do those instances live?

The brief explicitly offers three approaches and then adds the only "must" of its kind:
*"The design decision **must** be documented."* That sentence makes this the single
highest-signal decision in the assignment — it is the one place the brief tells you it is
watching.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | **Materialise all** — on schedule creation, insert every instance for 365 days | ❌ | The window is **rolling**. 365 days from *today* moves every midnight, so a materialised set is correct for exactly one day and then decays. Keeping it correct requires a daily job — and under DEC-19 a daily job on `N` instances fires `N` times, so it drags in leader election or ShedLock. A correctness problem is created by the storage choice, not by the domain |
| B | **Fully dynamic** — never store an instance; project it at query time | 🟡 | Correct, and the rolling window becomes free: *today* is an input to a query rather than a property of stored data. But a booking needs something to point at. Without a row there is no foreign key target, so the seat uniqueness invariant (DEC-2) would have to key on `(flightNumber, date, seatLabel)` strings — losing referential integrity exactly where the system's one hard guarantee lives |
| C | **Hybrid** — project on read, materialise lazily on first booking | ✅ | Reads need no rows, so the rolling window stays a predicate. Writes get a real row to reference, so the unique index has a proper FK. The row is created inside the booking transaction by the first booker and reused by everyone after |

**Chosen: C.** An instance row is created at the moment it first acquires state worth
storing, and not before. A flight nobody has booked has no row and needs none.

**The consequence that must be handled, not hidden.** Read paths must tolerate a missing
row. The naive seat-map implementation looks up the instance, finds nothing, returns
`404` — so **every flight is unviewable until someone books it, and nobody can book it
because nobody can see it**. A missing row means *"fully available"*, never *"not
found"*. This is recorded in spec section 4.5 and section 6 (BR), and is the reason BR-5
distinguishes *"does not operate on this date"* (`404`) from *"operates and is empty"*
(`200`).

**What would flip this.** If the brief had required listing all future flights without a
route filter, or if schedules were mutable with history (see DEC-8 note on versioning),
materialisation would start to pay for itself. At 500 schedules (DEC-20) neither applies.

---

## DEC-2 — Concurrency strategy

**Forcing question.** FR8 requires that two customers cannot book the same seat, and
*"Candidates should document their concurrency strategy."* The brief then **names five
mechanisms**: database locking, optimistic locking, pessimistic locking, transactions,
versioning. All five are evaluated here, because quoting a list and evaluating a subset
of it is the failure mode this requirement is designed to catch.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | **Optimistic locking** (`@Version` on a seat/inventory row) | ❌ | Requires a row **per seat** to carry the version — which is precisely what DEC-3 refuses to create. Versioning an aggregate instead (the flight) serialises *all* bookings on that flight, turning 180 independent seats into one contended row |
| B | **Pessimistic locking** (`SELECT … FOR UPDATE`) | 🟡 | Correct, and it is the textbook answer. But you can only lock a row that exists, and under DEC-3 a free seat has no row. You would therefore lock the parent flight — same serialisation cost as A — or create seat rows just to lock them, reintroducing 14M rows |
| C | **`SERIALIZABLE` isolation** | 🟡 | Correct and elegant: PostgreSQL's SSI would abort the losing transaction with `40001`. Rejected because it demands a retry loop on every write path in the system to solve a conflict that one index solves locally, and it raises the isolation level globally to fix one table |
| D | **PostgreSQL advisory locks** | ❌ | A lock exists only while someone holds it. It defeats the **concurrent** hazard and not the **sequential** one: two bookings a minute apart never overlap, so no advisory lock is ever contended, and the second still needs the unique index underneath to be rejected. If the index must exist anyway, the advisory lock is redundant |
| E | **Partial unique index** — `(flight_instance_id, seat_label) WHERE status='BOOKED'` | ✅ | The invariant becomes a property of the schema rather than of the code |

**The brief names five mechanisms; the table above has five rows, and they are not the
same five.** That mismatch is the trap, so the mapping is made explicit rather than left
to be inferred:

| Brief's term | Where it is addressed | Verdict |
|---|---|---|
| **Database locking** | Row E *is* database locking — the index takes a lock on the conflicting key. Rows B and D are its other two forms (row-level, advisory) | ✅ chosen, in its index form |
| **Optimistic locking** | Row A | ❌ needs a per-seat row (DEC-3 refuses) |
| **Pessimistic locking** | Row B | 🟡 correct but lockable rows do not exist |
| **Versioning** | Row A — `@Version` **is** the versioning mechanism in JPA; they are not two separate options, and listing them twice would pad the table rather than widen it. Versioning as *history* (keeping every state transition) is separately rejected in spec §8.2.2 as disproportionate scope for one invariant | ❌ same reason as A |
| **Transactions** | **Not an alternative to the others — the substrate under all of them.** Every option here runs inside one; row C is the isolation-level dial on that substrate, and spec §7.5 and §7.11 define the boundaries. A design that answered *"we use transactions"* would not have answered the question, because transactions alone do not prevent two concurrent inserts of the same seat at `READ COMMITTED` | ✅ used by all, decisive on its own for none |

Two entries therefore collapse (versioning **is** optimistic locking) and one is a
category error if treated as an option (transactions). Saying so is part of the answer:
the list is not five parallel choices.

**Chosen: E.**

```sql
CREATE UNIQUE INDEX uq_booking_seat_active
    ON booking_seat (flight_instance_id, seat_label)
    WHERE status = 'BOOKED';
```

Two concurrent transactions inserting the same `(instance, seat)` cannot both commit: the
second **blocks on the index** until the first resolves, then either succeeds (if the
first rolled back) or fails with SQLSTATE `23505`.

**Why this is the strongest available answer.** It holds *regardless of application code,
thread count, instance count, or retry logic*, because it is enforced below the
application entirely. Every other option on this list is a promise the application makes;
this one is a guarantee the database keeps. Under DEC-19 that distinction is the whole
argument — options A through D all require every instance to cooperate, and E does not
care how many instances there are.

**Why the `WHERE status = 'BOOKED'` predicate is load-bearing.** A full unique constraint
would force a choice between losing booking history and blocking seat reuse. The
predicate lets a cancelled row stay in the table — preserving who sat where — while
leaving **no entry in the index**, so the seat is immediately rebookable without deleting
anything. FR9's *"release reserved seats / make seats available"* is therefore satisfied
by a status change, with no row deletion anywhere in the system.

**No version column and no retry for seat conflicts.** A seat that is taken is taken;
retrying cannot change the answer, so the correct response is `409`, immediately. Retry
exists in exactly two narrow places: PNR collision (DEC-5, ≤ 3 attempts) and deadlock
`40P01` (section 7.6), which is possible only on paths outside the ordering invariant.

**Confirmed by clarification (DEC-20).** The interviewer stated booking latency *"can
change based on load and concurrent attempts"* and that *"ultimate stability is key"*.
That is an explicit licence for the blocking wait above: under contention the second
writer waits rather than failing fast, and correctness outranks latency on this path.

---

## DEC-3 — Seats are derived, only bookings are stored

**Forcing question.** FR2 gives an aircraft a fixed seat configuration (rows × letters).
FR6 requires a seat map with per-seat state. Does a seat exist as a row?

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | A `seat` row per aircraft seat | 🟡 | ~180 rows per aircraft. Harmless on its own, but it tempts option B |
| B | A `seat` row per flight instance per seat | ❌ | 500 schedules × ~156 operating days × 180 seats ≈ **14 million rows** carrying no information — every one of them says "nobody has booked this". And they must be generated, which reintroduces exactly the job DEC-1 removed |
| C | **Seats derived from aircraft configuration; only booked seats stored** | ✅ | The seat map is `generate(rows × letters)` minus `select booked`. Storage is proportional to **bookings**, not to capacity × time |

**Chosen: C.** FR2.4 states the seat map *"remains fixed for an aircraft"* — fixed data
is configuration, not inventory. Storing it per flight would be storing a constant
365 times over.

**Consequences.**
- Availability is computed: `capacity − count(booked)`, where capacity is
  `rows_count × length(seat_letters)`.
- `booking_seat` is the only seat table, and a row in it means *"this seat was taken by
  this booking"* — its mere existence is the fact.
- At DEC-20's scale, booked seat rows accrue at roughly 2,000 bookings/day × ~1.5 seats
  ≈ 1.1M rows/year — versus 14M rows of nothing under option B.

---

## DEC-4 — The seat map is read-through, never cached

**Forcing question.** FR6: *"Seat map should reflect **real-time** booking state."*

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Cache the seat map with short TTL | ❌ | The brief says *real-time*. A TTL is a documented window of wrongness. Under DEC-19 it is worse: `N` instances hold `N` independently stale copies, so two customers see two different seat maps |
| B | Cache with invalidation on booking | ❌ | Correct only if invalidation is broadcast to all instances, which requires the message bus the design deliberately does not have |
| C | **Read-through from `booking_seat` on every request** | ✅ | One indexed query against a table whose size is bounded by bookings, not capacity |

**Chosen: C.** At DEC-20's measured load (~6 QPS peak search) a cache would add staleness
and an invalidation protocol in exchange for no measurable latency benefit. This is now
an arithmetic argument, not a stylistic one.

---

## DEC-5 — PNR format and collision handling

**Forcing question.** FR7 requires *"Booking Reference (PNR or UUID)"*. The brief offers
both.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | UUID | 🟡 | Zero collision risk and trivially implemented. But a PNR is read aloud, typed at a kiosk and printed on a boarding pass; a 36-character UUID is hostile in every one of those contexts. The brief lists PNR **first** |
| B | Sequential reference | ❌ | Guessable. If the PNR is the credential for cancellation (it is, absent an auth system — DEC-14), a guessable reference means anyone can cancel anyone's booking by counting |
| C | **6-character random, visually unambiguous alphabet** | ✅ | Airline-realistic, human-speakable, and unguessable enough that enumeration is not a practical attack |

**Chosen: C.** Alphabet `A-HJ-NP-Z` plus digits — **`I` and `O` are excluded** because
they are visually indistinguishable from `1` and `0` when read back over a phone or
printed in a condensed font.

**The rule is enforced in the schema**, not only in the generator:

```sql
CONSTRAINT ck_booking_pnr CHECK (pnr ~ '^[A-HJ-NP-Z0-9]{6}$')
```

so the alphabet survives any future code path that invents a PNR another way.

**Collision handling.** The PNR is generated **inside** the booking transaction, so each
attempt gets a fresh value and a rolled-back attempt leaks nothing. A `23505` on
`uq_booking_pnr` triggers regeneration, up to 3 attempts, then `503`. This is why
conflicts are discriminated by **constraint name** (section 7.8) and never caught
generically: a generic catch would report `409 SEAT_UNAVAILABLE` for a perfectly free
seat whenever a PNR happened to collide, and would consume the exception so the
regeneration never ran.

---

## DEC-6 — Days of operation as a child table

**Forcing question.** FR3 gives a schedule *"Days of Operation"*. How is a set of
weekdays stored?

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | CSV string `"MON,WED,FRI"` | ❌ | Not indexable, not joinable, and requires parsing in SQL to answer the one question search asks |
| B | 7-bit bitmask | 🟡 | Compact and fast, but `day_mask & 4` is unreadable in a query, undebuggable in `psql`, and pushes a translation layer into every caller |
| C | Seven boolean columns | ❌ | Unqueryable without a seven-way `CASE` on the requested weekday |
| D | **Child table `flight_schedule_day(schedule_id, day_of_week)`** | ✅ | Search becomes a plain join on an indexed integer |

**Chosen: D**, with `day_of_week` as ISO-8601 `SMALLINT` (1 = Monday … 7 = Sunday),
matching `java.time.DayOfWeek.getValue()` exactly — so **no translation layer exists to
get wrong**. A `CHECK (day_of_week BETWEEN 1 AND 7)` keeps the domain closed.

---

## DEC-7 — Public identity is `flightNumber + date`

**Forcing question.** What does the API expose as a flight's identity — an internal id,
or the business key?

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Surrogate `flight_instance.id` | ❌ | Under DEC-1 the row **does not exist** until someone books. An API keyed on it could not address a flight that nobody has booked yet, which is every flight a customer is about to search for |
| B | **`flightNumber` + `date`** | ✅ | Always addressable, matches the brief's own examples (*"XY101, 7 July"*), and is what a customer actually has |

**Chosen: B.** This is DEC-1 showing through the API surface: because instances are
projected rather than enumerated, the identity has to be the projection's input.

**The invariant this creates.** `flightNumber + date` is only a valid key if a flight
number maps to one schedule per weekday. The brief never states that flight numbers are
unique — so this is an invariant **the design creates**, and it is therefore enforced
rather than assumed: `uq_flight_number_day UNIQUE (flight_number, day_of_week)`. Recorded
as **DI-1** (design-introduced) rather than as a requirement, so it is never mistaken for
something the brief asked for.

---

## DEC-8 — No airport time zone column

**Forcing question.** FR1 says an airport has *"at least"* code, name, city, country —
an **open** list. A nullable `timezone` column holding an IANA zone would be a legal
addition and would **completely dissolve** the two largest ambiguities in the design
(AMB-1, AMB-2): a schedule's local `departure_time` and a `flight_date` would compose
into an unambiguous `ZonedDateTime`, making *"has this flight departed?"* exact.

**It is deliberately not added.**

The Assumptions section of the brief states *"Time zone conversion is out of scope"* —
unconditionally. Adding the column would require conversion logic to be of any use, so
taking the permission in FR1 would mean violating the exclusion in Assumptions. **Where
two clauses of the brief point in opposite directions, the explicit exclusion wins over
the permissive "at least".**

**The cost is carried openly, not hidden.** BR-7 and BR-14 compare against a
UTC-referenced instant and are therefore wrong by at most one airport offset (≤ 14h): a
same-day flight may be judged departed slightly early or late. The consequence is
bounded and stated.

**This is the single highest-value extension point in the schema** — one nullable column
plus one `ZoneId` lookup converts every approximation in this design into an exact
answer, with **no table, API or index changing shape**.

---

## DEC-9 — Booking window is 365 days, externalised

**Forcing question.** The brief contradicts itself:

| Location | Wording |
|---|---|
| Scope | *"bookings for up to **one year** from the current date"* |
| FR4 | *"bookable flight instances for **365 days** from the current date"* |
| Assumptions | *"Booking window extends up to **one year**"* |

Across a leap year these differ — from 2027-03-01, one calendar year is **366** days.

**Chosen: 365 days**, externalised as `airline.booking.window-days`. FR4 is the only
place the brief states a precise, testable number, and a fixed day count gives
deterministic boundary tests. The alternative reading is then a **one-line property
change**, not a code change — which is the real point: the contradiction is resolved in
configuration, so being wrong is cheap.

**The boundary is half-open:** `[today, today + 365)`, i.e. `today + 364` is the last
bookable date. Stated explicitly because an off-by-one on a window boundary is the
classic untested defect.

---

## DEC-10 — Package-by-feature, enforced by ArchUnit

**Forcing question.** NFR1/NFR2 require *"clean architecture"* and *"separation of
concerns"*. How is that demonstrated rather than claimed?

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Package-by-layer (`controller/`, `service/`, `repository/`) | ❌ | Every feature is smeared across every package; the structure tells you nothing about what the system does |
| B | **Package-by-feature**, layered inside each feature | ✅ | The top-level packages name the domain: `schedule`, `search`, `booking`, `seatmap` |
| C | Full hexagonal/ports-and-adapters | 🟡 | Defensible, but for a system with one datastore and no external integrations it adds a port/adapter pair per use case to decouple from a dependency that will not change. Over-engineering against the brief's *"not over-engineered"* spirit |

**Chosen: B, with the rules enforced by ArchUnit tests** — layering violations fail the
build rather than surviving review. *"Clean architecture"* asserted in a README is worth
nothing; the same claim as a failing test is worth something.

---

## DEC-11 — RFC 9457 `application/problem+json`

**Forcing question.** NFR5 requires error handling. What shape is an error?

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Ad-hoc JSON per endpoint | ❌ | Clients must learn a new error shape per route |
| B | Bespoke house format | 🟡 | Consistent, but invents a standard that already exists |
| C | **RFC 9457 Problem Details** | ✅ | An IETF standard, natively supported by Spring (`ProblemDetail`), with `type`/`title`/`status`/`detail` plus extensions for machine-readable error codes |

**Chosen: C**, with a documented status-code matrix (spec section 5.2) and a stable
`errorCode` extension (`SEAT_UNAVAILABLE`, `FLIGHT_NOT_OPERATING`, `ALREADY_CANCELLED`, …)
so clients branch on a code, not on prose.

---

## DEC-12 — PostgreSQL in tests, not H2

**Forcing question.** NFR8 requires testability. What database do tests run against?

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | H2 in-memory | ❌ | **H2 cannot express a partial unique index.** The entire correctness argument of DEC-2 lives in `WHERE status='BOOKED'`. An H2 suite would silently verify a *different, weaker schema* than production — and would pass |
| B | **PostgreSQL via Testcontainers** | ✅ | Tests run against the real engine, the real index, and the real SQLSTATEs |

**Chosen: B.** This is the clearest case in the design of a test choice being a
*correctness* choice: the fast option does not merely run differently, it would report
success for a system that does not have the property being claimed.

---

## DEC-13 — `open-in-view=false`, DTOs across boundaries

**Forcing question.** Spring Boot defaults `spring.jpa.open-in-view` to `true`. Keep it?

**Chosen: `false`,** explicitly set rather than left to the default.

With it `true`, the Hibernate session stays open through view rendering, so a lazy
association touched during serialisation triggers a query **while holding a connection**,
outside any transaction. Under load the bottleneck is then neither CPU nor the database
but **the connection pool** — the pool is held for the duration of response writing
rather than the duration of the transaction.

**Consequence, enforced:** application services return **DTOs only** — no entity and no
Hibernate proxy crosses a `@Transactional` boundary. Search, which needs
`schedule.aircraft.rowsCount` and `seatLetters` to compute capacity, fetches them
explicitly inside the transaction rather than relying on lazy loading later.

---

## DEC-14 — Black boxes read narrowly

**Forcing question.** The brief says *"Any functionality not explicitly mentioned should
be considered out of scope and treated as a black box."* How far does that extend?

**Chosen: "black box" is read narrowly — as an external call that would be made, not as
a feature silently dropped.**

Authentication, payment, refunds, notifications and pricing are black boxes: the design
names **where** they would attach (the trust boundary in front of the admin API, the step
that would sit between seat acquisition and commit) without implementing them. The
opposite reading — *"anything inconvenient is out of scope"* — would let the design
delete requirements it did not want to meet.

**Confirmed by clarification (DEC-20):** the interviewer directed focus toward
functionality, which validates implementing the stated FRs well rather than scaffolding
unstated integrations.

---

## DEC-15 — Cancellation is `POST /cancel`, not `DELETE`

**Forcing question.** FR9 cancels a booking. NFR3 requires RESTful design.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | `DELETE /api/v1/bookings/{pnr}` | ❌ | `DELETE` means the resource is gone. The booking is **not** gone — FR9 says *"change booking status"*, and the record is retained for history (DEC-2's predicate depends on it). A `DELETE` that leaves the resource retrievable is a lie about the verb |
| B | `PATCH` with `{"status":"CANCELLED"}` | 🟡 | Honest, but exposes the status field as client-writable, inviting `PATCH {"status":"CONFIRMED"}` to un-cancel |
| C | **`POST /api/v1/bookings/{pnr}/cancel`** | ✅ | Models a **state transition**, not a field assignment. One direction only |

**Chosen: C.** Cancellation is a business operation with preconditions (must exist, must
be `CONFIRMED`, flight must not have departed), not a property edit.

The implementation is a **conditional update** — `UPDATE … WHERE pnr = ? AND status =
'CONFIRMED'` — which is atomic at `READ COMMITTED`, needs no lock and no version column.
A read-then-write would make double-cancellation unenforceable: on a double-click both
threads read `CONFIRMED`, both update, both return `200`. Lookup first for `404`, then
the conditional write for `409`.

---

## DEC-16 — Containerised, smoke-tested before code

**Forcing question.** Deliverable 3 requires *"executable with clear setup
instructions"*; Deliverable 5 requires Setup/Build/Run/DB-init.

**Chosen: `docker compose up` as the single entry point** — application plus PostgreSQL,
with Flyway migrating on startup — and the compose stack was **verified end to end before
any application code was written**.

The ordering is the decision. *"A working service"* is graded first (Evaluation Criteria
#1, *Correctness of functionality*). A submission that compiles on the author's machine
and fails on the grader's scores zero on the criterion that matters most, and discovering
that at the end leaves no time to fix it.

**Confirmed by clarification (DEC-20):** the interviewer stated they will *"bring up a
single instance while checking"*. The deliverable therefore ships **one app container** —
no load balancer, no replica count — while the *design* remains `N`-instance correct per
DEC-19. These are not in tension: see DEC-19's closing note.

---

## DEC-17 — Mermaid sources plus rendered PDF

**Forcing question.** Deliverable 1 says *"Architecture diagrams are encouraged."* In
what format?

**Chosen: Mermaid source files under `hld/diagrams/`, with `hld/architecture.md` as the
source of truth and `hld/architecture.pdf` as a rendered copy.**

Mermaid diffs as text, renders natively on GitHub, and cannot drift from the repo the way
an exported PNG does. The PDF exists because a reviewer may read the submission outside a
Markdown renderer — it is a **rendered copy, never the source**, and that relationship is
stated in the repo so no one edits the wrong artifact.

---

## DEC-18 — Where decisions live

**Forcing question.** *"Design decisions"* is required in **three** places: the HLD
(*"Major design decisions"*), the README (*"Design Decisions"*), and Evaluation Criteria
#8. Three copies will diverge.

**Chosen: one source, two derivations.**

```
decision-log.md  (full reasoning, all rejected options)
      │
      ├──→ hld/architecture.md section 8   (the major ones, summarised)
      └──→ README.md section Design Decisions (the headline choice per area)
```

This file is the only place an option's rejection rationale is written. The other two
summarise and link. Anything that contradicts this file is a bug in that file.

---

## DEC-19 — Deployment topology is `N` instances

**Forcing question.** The brief is **completely silent** on deployment topology. Across
333 lines the words *pod*, *node*, *replica*, *process* and *deploy* appear **zero**
times; every occurrence of *"instance"* means *flight instance*. The only
deployment-adjacent phrase in the entire document is **"Scalability considerations"**
under Evaluation Criteria.

Silence is not permission to assume the easy case. Every concurrency argument in a design
rests on an answer to this question, so leaving it unstated means the argument rests on an
**unstated assumption** — which is precisely the thing an architecture review exists to
find.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Assume a single process | ❌ | Licenses `synchronized`, `ReentrantLock` and in-memory registries — all of which are *correct in a demo and wrong in production*. A rolling deployment alone forces `N = 2` during every release, so single-instance is not a steady state any real system occupies |
| B | Assume `N` instances, add coordination infrastructure (Redis locks, a broker) | ❌ | A distributed lock **in front of** a unique index is strictly worse than the index alone: it can fail, expire, or be partitioned away, and the index must still exist underneath to be correct. Infrastructure added to solve a problem the database already solved |
| C | **Assume `N ≥ 2` stateless instances behind a load balancer; enforce every invariant in PostgreSQL** | ✅ | The enforcement point was already outside the JVM |
| D | Leave it unstated | ❌ | The status quo being corrected |

**Chosen: C.** The service runs as `N ≥ 2` stateless instances behind a load balancer.

**The key insight: this validates the core design rather than changing it.** The seat
invariant already lives in a partial unique index inside PostgreSQL (DEC-2, DEC-3). `N`
instances all reach the same index, and exactly one wins. Nothing about the central
correctness argument moves.

### Rules D1–D10

> **These ten rules are maintained in `spec.md` §2.0.4 and reproduced here.** Until the
> pass-4 audit the two copies had **diverged**: this file defined `D3` as *no in-memory
> caching* and `D4` as *uniqueness is a database constraint*, while the spec defined `D3`
> as *no session state* and `D4` as *retry may land elsewhere*. Both files were cited as
> authoritative, so every `D3`/`D4` reference in the project was ambiguous.
>
> It was resolved by **usage, not by preference**: all six `D3` citations in the wild meant
> *caching*, and three of four `D4` citations meant *retry*. The two displaced rules were
> real rules that had lost their identifiers, so they were given their own — `D9` and `D10`
> — rather than deleted. Nothing was dropped; two rules that had been sharing numbers now
> have their own.

| ID | Rule | Why | Where |
|---|---|---|---|
| D1 | No correctness may depend on JVM-local state — no `synchronized`, no `ReentrantLock`, no in-memory seat registry | Two pods have two heaps. A lock one pod holds is invisible to the other, so the guarded section runs twice in parallel | spec §7.1 · DEC-2 |
| D2 | No background or scheduled work | A `@Scheduled` job on `N` pods fires `N` times. DEC-1's lazy materialisation has **no scheduled component at all**, so there is no leader election, no `ShedLock`, no split brain to reason about | spec §7.4 · DEC-1 |
| D3 | No in-memory caching of **mutable** booking state | `N` instances would hold `N` divergent views, and two customers would see two different seat maps. Scoped to *mutable* — see §4.2.1 for why immutable reference data is exempt | DEC-4 · spec §4.2.1 |
| D4 | A client retry may land on a **different** pod than the original | Retry-safety cannot come from remembering the first attempt in memory; it must come from the database | spec §7.2 · §7.7 |
| D5 | Startup must be safe when `N` pods boot simultaneously | All `N` run Flyway against one database at the same moment | **spec §4.0.1** |
| D6 | The connection budget is `N × pool`, not `pool` | A pool size that is safe once can exhaust `max_connections` when multiplied | **spec §7.5.1** · DEC-13 |
| D7 | Wall-clock time is per-pod | `today` decides a `422`; two pods must not disagree about which day it is | **spec §6.1** · BR-6 · DEC-9 |
| D8 | A **derived** configuration value must cite its derivation and be asserted | `maximumPoolSize` is derived from `max_connections` and `N`. Nothing in the quality toolchain reads `application.yml`, and the failure it causes is configuration-shaped — so a single instance can never falsify it | **spec §2.0.2.1** |
| D9 | Every uniqueness rule is a database constraint, never an application check | A `SELECT`-then-`INSERT` check is a race on one instance and a guaranteed race on `N` | DEC-2, DEC-5, DEC-7 |
| D10 | The service holds no session or request-spanning state | Any instance must be able to serve any request, including the *second* call of a two-call flow | spec §5 · NFR3 |

**The topology is runnable, not just asserted.** `docker-compose.yml` brings up one
instance by default — DEC-20 asks for exactly that — but declares no `container_name`
and publishes a host port *range*, so `docker compose up --scale app=3` works. Both
properties are one careless edit away from being lost, and losing either makes every
rule above unfalsifiable: a reviewer who cannot start a second instance cannot test a
single claim D1–D10 makes. A pass-4 audit found both had in fact been lost.

**Why single-JVM concurrency tests are still valid.** CT-1…CT-10 run threads inside one
JVM. This proves the property **precisely because enforcement is outside the JVM** —
the threads contend on the same index rows that separate instances would contend on. A
test that spun up two containers would exercise the same index through a longer path and
prove nothing additional.

**And why the interviewer's "single instance while checking" is not a contradiction.**
The graded artifact runs one container; the design is correct for any `N`. Because every
invariant is enforced in PostgreSQL, the instance count is not an input to correctness —
which is exactly the property being claimed. Running one instance does not weaken the
claim; it simply does not exercise it.

---

## DEC-20 — NFR envelope from interviewer clarifications

**Forcing question.** The brief states no load, latency or availability targets. A design
without an envelope cannot justify what it leaves out — *"no cache needed"* is an opinion
until there is a number attached to it.

Clarifications received **2026-10-12**, recorded verbatim:

| Question | Answer |
|---|---|
| Multiple instances or single? | *"Design for multiple instances, but we'll bring up a single instance while checking"* |
| Scale? | *"More focus is to be done on the functionality, you can assume moderate scale (10k DAU, 500 flight schedules)"* |
| Search:booking ratio? | *"25:1"* |
| Can search be stale? | *"If they are late, how does the user get informed if there is a change in the state when he proceeds for a booking — if that is handled, the results can be stale"* |
| Latency targets? | *"Search — 1 second with a provision to serve lazy loading if needed. Booking — ultimate stability is key, the time can change based on load and concurrent attempts"* |
| Consistency split? | *"Yes"* — search may be stale, booking strongly consistent with zero double-booking |

### Derived load

| Quantity | Derivation | Result |
|---|---|---|
| Search throughput | 10k DAU × ~5 searches/day ÷ 86,400 | **~0.6 QPS average** |
| Search peak | ×10 burst factor | **~6 QPS** |
| Booking throughput | search ÷ 25 | **~2,000/day, ~0.25 QPS peak** |
| Flight instances/year | 500 schedules × ~3 days/week × 52 | **~78,000** |
| Seat rows if materialised | 78,000 × 180 | **~14,000,000** (avoided by DEC-3) |
| Booked seat rows/year | 2,000/day × ~1.5 seats × 365 | **~1,100,000** |

### What the envelope closes

| Previously defended by | Now defended by |
|---|---|
| "A cache is not needed for this assignment" | **~6 QPS peak.** A cache adds staleness and an invalidation protocol for no measurable latency benefit (DEC-4) |
| "A read replica would be over-engineering" | **~6 QPS peak**, against a 1-second search budget that an indexed join meets with three orders of magnitude of headroom |
| "Materialising seats would be wasteful" | **14M rows vs 1.1M** (DEC-3) |
| "Booking should not fail fast under contention" | The interviewer's explicit *"ultimate stability is key, the time can change"* — a direct licence for DEC-2's blocking wait |

**Explicitly rejected on these numbers:** Redis, read replicas, CQRS, sharding, caching
layers, async booking via a queue. Each would be defensible at 1000× this load; none is
defensible at 6 QPS.

**On *"lazy loading if needed"*.** This does **not** mean search pagination. At 500
schedules a `(source, destination, date)` query returns **single-digit** rows — there is
nothing to paginate, and the brief lists sorting and filtering as out of scope. The
lazy-load boundary the answer asks for already exists in the API shape: **search returns a
flight list with an available-seat *count*, and the 180-seat map is a separate resource
fetched on demand** (`GET /flights/{number}/seat-map`). Search is never made heavy by seat
data. Recorded here so the reasoning is visible rather than looking like an omission.

**On *"focus on functionality"*.** Read as a direction on effort allocation: correctness
and completeness of the stated FRs outrank performance work. It does not license skipping
the scalability *reasoning*, which Evaluation Criteria #5 grades independently.

---

## DEC-21 — `409` enumerates the seats that were lost

**Forcing question.** The interviewer's permission for stale search is **conditional**:
*"if they are late, how does the user get informed if there is a change in the state when
he proceeds for a booking — **if that is handled**, the results can be stale."*

That condition is a requirement, not a courtesy. Staleness is acceptable *only because*
the booking path is the authority that detects and reports the divergence. If the booking
response cannot tell the customer **what** changed, the grant does not apply.

**The gap.** DEC-2 discriminates conflicts by **constraint name** (section 7.8), which
identifies the *kind* of conflict but not the *subject*. A booking for `{12A, 12B, 12C}`
that loses `12B` produces a `23505` on `uq_booking_seat_active` that does not say `12B`.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Generic `409 SEAT_UNAVAILABLE` | ❌ | Fails the interviewer's condition. The client can only re-fetch the whole seat map and diff it — and cannot tell the customer which of their chosen seats to replace |
| B | Pre-check availability, then insert | ❌ | A `SELECT`-then-`INSERT` is exactly the race DEC-2 exists to remove. It would report seats as free that are taken by commit time, and it is wrong on one instance before `N` is even considered |
| C | **Catch `23505`, then re-query which of the requested seats are now `BOOKED`, and return them** | ✅ | The authoritative answer is read **after** the conflict is known, so it reports a state the database actually held — not a guess made before the write |

**Chosen: C.** On `uq_booking_seat_active` violation, the transaction rolls back, a
**separate read-only query** re-checks the requested labels against `booking_seat`, and
the problem detail carries them:

```json
{
  "type": "https://airline.example/problems/seat-unavailable",
  "title": "Seat unavailable",
  "status": 409,
  "detail": "2 of 3 requested seats are no longer available",
  "errorCode": "SEAT_UNAVAILABLE",
  "flightNumber": "XY101",
  "flightDate": "2026-10-12",
  "unavailableSeats": ["12B", "12C"],
  "requestedSeats": ["12A", "12B", "12C"]
}
```

**The honesty constraint.** The re-query runs after the failed transaction, so by the time
the client reads the response another booking may have changed things again. The field is
therefore specified as **"unavailable as of this response"**, not as a guarantee that the
remaining seats are still free. Promising otherwise would reintroduce the staleness this
entry exists to disclose. The customer's next attempt is authoritative — which is the same
contract the whole system runs on.

**No all-or-nothing change.** A multi-seat booking remains atomic: losing one seat fails
the whole booking (BR-10). This entry changes the **response**, not the transaction
semantics.

---

## DEC-22 — The decision log is version-controlled

**Forcing question.** On 2026-10-11 this file was destroyed by a tool invocation that
opened it for writing (truncating it) and then failed before writing. It held ~690 lines.
It was **not recoverable**: `features/` is listed in `.gitignore`, so git held no copy;
the editor's local history had no snapshot because the file was never edited in the
editor; and no Time Machine backup existed.

**Chosen: `features/` is removed from `.gitignore` and committed.**

Two reasons, in order of weight:

1. **Durability — decisive.** An ungitignored file has a recovery path. This one did not,
   and the risk is now demonstrated rather than theoretical. ~690 lines of reasoning that
   existed in exactly one place, with no second copy, was a single-point-of-failure that
   nothing in the design process would have caught until it fired.
2. **Visibility — supporting, not decisive.** DEC-18 already routes this content to two
   graded destinations (HLD §8, README), so the grader is not *dependent* on this file.
   But a reviewer who wants the rejected alternatives in full has nowhere else to look,
   and Evaluation Criterion #8 rewards exactly that depth. Committing it costs nothing and
   makes the reasoning trail inspectable.

**The process rule that follows:** never write to an existing file with a truncating open.
Write to a temporary file and `os.replace()` over the target, so a failed write leaves the
original intact. The `.gitignore` edit that implements this decision was itself performed
that way.

---

## DEC-23 — One deployable, two namespaces — not two services

**Forcing question.** Scope establishes two actors with different trust levels: a
privileged **back office** that defines schedules, and **anonymous customers** who search
and book. Under §2.0.4 the system is explicitly distributed. So: one service or two?

This question is never forced by the brief, which is exactly why it must be answered — an
unexamined monolith and a deliberate one look identical in the repository and score
differently in review.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | **Two deployables** — `admin-service` and `booking-service` | ❌ | They share one schema. Splitting gives two bad choices: **two services on one database** (the textbook anti-pattern — neither can migrate independently, so the "independence" is fictional), or a synchronous call / event between them, which introduces a distributed-consistency problem where a local transaction currently suffices |
| B | **One deployable, two URL namespaces** (`/api/v1/admin/*` vs `/api/v1/*`) | ✅ | The trust boundary is enforced where trust boundaries belong — at the edge — without buying a network hop to express it |
| C | Modular monolith with separate ports/listeners per actor | 🟡 | Genuine network-level isolation of the admin surface, and the right answer if admin were reachable only from an internal network. Rejected as unjustified here: it complicates the single-command run (DEC-16) to enforce a boundary that an authenticated edge already enforces |

**Chosen: B.**

**Why the usual argument for splitting does not apply.** Services are normally split to
scale, deploy or fail independently. At DEC-20's envelope none of those bite:

| Usual justification | Does it apply here? |
|---|---|
| Independent **scaling** | No. 500 schedules are written once; admin write volume is effectively zero against ~6 QPS of search. There is no load profile to scale apart |
| Independent **deployment** | No. One team, one repository, one release |
| **Blast-radius** isolation | **Partially — and this is the strongest argument for A.** Admin traffic cannot currently degrade booking traffic except through the shared connection pool (§7.5.1). But both actors depend on the same PostgreSQL instance, so a database outage takes both down regardless; splitting the application tier isolates the smaller half of the risk |
| Independent **data ownership** | No. Schedules are written by admin and read by search on every query. The read path crosses the proposed boundary constantly |

**What would flip this.** If the admin surface acquired a write-heavy workload (bulk
schedule imports, fare loading), or had to remain available during booking-tier
incidents, or moved behind a separate corporate network, option A or C becomes correct.
The threshold is **independent load or independent availability requirements** — neither
exists at this scope.

**What is built so the flip stays cheap.** Package-by-feature (DEC-10) keeps the admin
feature in its own package with no inbound dependency from customer-facing features, and
an ArchUnit rule holds that line. Extraction would then be moving a package, not
untangling one. This is the standard sequence — modular first, distributed only when a
measured need appears — and the reasoning belongs in HLD §9 alongside the other
scale-out steps that are deliberately not taken.

---

## DEC-24 — Booking retries are made safe by an optional idempotency key

**Forcing question.** D4 states that a client retry may land on a *different* instance
than the original. §5 answers the resulting ambiguity by pointing at API-6
(`GET /api/v1/bookings/{pnr}`), on the argument that resolution must be *"a read of
committed state keyed on something the client already holds"*.

**A fresh audit found that argument is wrong.** It holds for the case §7.2 was written
against — a commit whose outcome is indeterminate, where the server knows the PNR and
returns `503`. It fails for the strictly more common case: **the booking committed and
the response was lost in transit.** The client then holds no PNR, because the PNR was in
the response that never arrived. API-6 is keyed on the one value the client cannot have.

What happens today, with no key:

| Step | Result |
|---|---|
| Client `POST`s seats `12A, 12B` | Booking commits, PNR `K7R4Q2` generated |
| Response lost (load balancer timeout, instance terminated mid-write, client network drop) | Client sees a failure |
| Client retries — load balancer routes to a different instance (D4) | Seats `12A, 12B` are taken, so `409 SEAT_UNAVAILABLE` |

The customer is told their seats are unavailable. They are — **they are holding them.**
The system cannot distinguish *"the same user retrying"* from *"a different user
competing"*, and answers both with the same `409`. For the competing user that answer is
correct; for the retrying user it is actively misleading, and there is no path from there
to their own booking.

This is not a missing feature. It is the mandated booking flow (FR7) behaving incorrectly
under the conditions FR8 mandates it handle, in the exact scenario the interviewer's
staleness clarification asks about — *"how does the user get informed if there is a change
in the state when he proceeds for a booking"*.

### Options

| # | Option | | Assessment |
|---|---|---|---|
| A | Leave it; document the hole | ❌ | The primary write path has an unrecoverable outcome and the spec contains a claim that is false. Documenting a defect is not the same as deciding about it |
| B | Let the client look up a booking by `(flight, date, seat)` | ❌ | Any customer could then enumerate who holds which seat. A privacy hole opened to fix a retry bug |
| C | Make the client generate the PNR | ❌ | Breaks DEC-5's alphabet and collision handling, and moves identity generation to the least trustworthy participant |
| D | Server-side request de-duplication by payload hash | 🟡 | Works without a client change, but silently collapses two *genuinely distinct* bookings of the same seats by the same name — and makes a hash of unvalidated input load-bearing |
| E | **Optional `Idempotency-Key` request header, unique in the database** | ✅ | The industry-standard answer (Stripe, PayPal, AWS). The client decides what is a retry, which is the only participant that knows |

### Chosen: E

```sql
ALTER TABLE booking ADD COLUMN idempotency_key UUID;
ALTER TABLE booking ADD CONSTRAINT uq_booking_idem UNIQUE (idempotency_key);
```

- **Optional.** Absent header ⇒ `NULL` ⇒ current behaviour exactly. PostgreSQL treats
  `NULL`s as distinct in a `UNIQUE` constraint, so unkeyed bookings never collide with
  each other. The brief's minimal API is unchanged for a client that ignores this.
- **Replay returns the original booking**, `200` rather than `201`, so the client can tell
  a replay from a first write. The seats in that response are the seats they hold.
- **Enforced by the constraint, not by a prior read** — rule D9. The flow is
  `INSERT … ON CONFLICT ON CONSTRAINT uq_booking_idem` → on conflict, `SELECT` the
  existing booking and return it. A `SELECT`-then-`INSERT` check would be a race on one
  instance and a guaranteed race on `N`, which is the whole point of D9.
- **No separate idempotency table, no TTL, no reaper.** The key lives on the row it
  protects, so it is cleaned up by whatever cleans up bookings, and it needs no background
  job — which D2 forbids anyway.

### Cost, stated openly

One nullable column and one constraint. The API gains one optional header. That is a
genuine addition to the brief's surface, and it is justified on the same basis API-6 was:
**it is demanded by this design's own failure handling**, not by convenience. The
difference between the two is that API-6 was added to serve an argument, and this is added
because that argument does not actually close without it.

---

## DEC-25 — Migrations must be backward-compatible for one release (expand/contract)

**Forcing question.** D5 establishes that concurrent startup is safe: `N` instances boot
together, Flyway takes a lock on its history table, exactly one migrates and the rest
wait. That solves **lock contention**. A fresh audit found it does not solve the problem
that actually breaks multi-instance deployments, and nothing else in the design did
either.

**Version skew.** A rolling deployment is not atomic. For its duration — typically
minutes — instances running the *old* code and instances running the *new* code are
serving production traffic **against the same database, at the same time**:

```
t0   N instances on v1, schema v1
t1   one v2 instance starts, runs the migration     <- schema is now v2
t2   v1 instances are STILL SERVING, now on schema v2
t3   rollout completes
```

Everything between `t1` and `t3` is the dangerous window, and the danger runs in the
direction people do not expect: **the new migration must not break the old code.** A
migration that renames `booking.status` to `booking.state` succeeds, and every v1
instance begins throwing on every query until the rollout finishes. The deploy looks
healthy — Flyway reported success — while half the fleet returns `500`.

This is invisible at `N = 1`, because there is never a moment when two versions coexist.
It is also invisible in testing, because tests run one version against one schema.

### The rule

**Every migration must leave the previous release's code working.** Destructive changes
are split across two releases:

| Phase | Release | Action |
|---|---|---|
| **Expand** | `r` | Add the new column/table/constraint. Both versions work: old code ignores it, new code uses it |
| **Migrate** | `r` | Backfill, dual-write if needed |
| **Contract** | `r+1` | Drop the old column — only once no running instance refers to it |

Concretely, what a single migration may **not** do: drop or rename a column still read by
the previous release, add a `NOT NULL` column without a default, narrow a type, or add a
constraint that existing rows or in-flight writes would violate.

**This is why DEC-24's change is written as `ADD COLUMN … NULL`** rather than
`NOT NULL DEFAULT`. The nullable form is expand-phase-safe by construction: v1 instances
that know nothing about `idempotency_key` continue inserting rows without it, and the
`UNIQUE` constraint tolerates every one of them because PostgreSQL treats `NULL`s as
distinct. The decision and the deployment rule reinforce each other, which is the check
that the rule is real rather than decorative.

**Scope.** This assignment ships `V1` and `V2` — a baseline and a seed — so no contract
phase exists yet and nothing here is exercised. It is recorded because the rule must be
established *before* the first destructive migration, not after it, and because stating a
multi-instance topology while retaining the freedom to drop a column mid-rollout is a
contradiction a reviewer is entitled to probe.

---

## DEC-26 — The 1-second search target is verified, not asserted

**Forcing question.** The interviewer gave one numeric, testable performance target:
*"Search — 1 second"*. The design's answer has been an argument — an indexed join over
500 schedules is three orders of magnitude inside the budget. A fresh audit asked what
would happen if that argument were wrong, and found nothing that would notice.

Arguments of this shape fail in one specific way: not because the index is missing, but
because a change elsewhere stops it being used. An `@EntityGraph` removed during a
refactor turns one query into N+1; a `LIKE` added to a filter makes an index unusable; a
`findAll()` creeps into a service. Each is individually plausible, none changes the index
definition, and all of them are invisible to a test suite that asserts only correctness.

### Options

| # | Option | | Assessment |
|---|---|---|---|
| A | Keep the argument | ❌ | The one measurable NFR given is the one thing never measured |
| B | Full load test (k6/Gatling in CI) | ❌ | Measures the runner's hardware as much as the system. Flaky in CI, and at ~6 QPS there is no load worth generating |
| C | Assert a wall-clock bound in an integration test | 🟡 | Timing assertions on shared CI hardware are the classic flaky test. A 1-second bound would pass on a broken query and fail on a healthy one under load |
| D | **Assert the *shape* of the work: statement count and plan** | ✅ | Deterministic, hardware-independent, and fails for the actual reasons latency regresses |

### Chosen: D, with a loose C as a backstop

- **Statement count** — Hibernate statistics assert a multi-row search issues a bounded
  number of statements. This is the N+1 regression test NFR9 already promises; it is
  named here as the primary latency control rather than a tidiness check, because N+1 is
  the realistic way this budget gets blown.
- **Plan assertion** — `EXPLAIN` on the search query asserts an index scan on
  **`idx_booking_seat_instance`** and no sequential scan of `booking_seat`. Scoped to that
  one table deliberately: at **500 rows** `flight_schedule` is correctly sequentially
  scanned, so asserting an index scan there would fail against a *healthy* planner
  (amended by DEC-30). `booking_seat` carries ~1.1M rows a year, which is where an
  unusable index actually costs something.
- **A deliberately loose wall-clock bound** of 1 second on a seeded dataset, as a
  backstop. Loose on purpose: it must only catch a catastrophic regression, never
  arbitrate hardware. A tight timing assertion would be removed the first week it flaked,
  and a removed test protects nothing.

**What this does not claim.** None of it measures production latency, which depends on
data volume and hardware this assignment does not have. It measures that the *reason* the
budget is met — one bounded, index-backed query — still holds. That is the part a code
change can break, and therefore the part worth defending.

---

## DEC-27 — Search and booking get different timeout policies

**Forcing question.** The interviewer gave two latency answers, not one, and they point in
opposite directions:

> *"Search — 1 second with a provision to serve lazy loading if needed, booking —
> **ultimate stability is key, the time can change based on load and concurrent
> attempts**"*

Search has a deadline. Booking explicitly has permission to take longer under load. A
fresh audit asked which part of the system expresses that difference, and the answer was
**nothing**. One Hikari pool of 10 connections, one `connection-timeout` of 10 s, serves
both. A single number cannot encode two opposite policies, and the one we picked encodes
them **backwards**:

| | Interviewer's policy | What the config actually does |
|---|---|---|
| Search | Answer within **1 second** | May queue **10 s** for a connection — ten times the budget |
| Booking | Time **may extend** under load and concurrent attempts | **Sheds** with `503` at 10 s |

The `connection-timeout` comment in §7.5.1 even records the mistake in writing: 10 s was
chosen as *"long enough to ride out a lock convoy at ~0.25 QPS booking"* — tuned for the
booking path, then silently applied to the search path, which has 25× the traffic and
1/10th the time budget.

**The second-order problem: the dominant class can starve the critical one.** At **25:1**
(DEC-20), search is essentially all of the traffic. Virtual threads removed Tomcat's
200-worker admission limit (§7.5.2), so the pool is now the only bound — and nothing
reserves any of its 10 connections for booking. A burst of slow searches holds every
connection; the booking that arrives next waits and then fails. The traffic class the
interviewer called *"ultimate stability is key"* is the one with no protection, and it is
starved by the class that is 25× larger. The decision log noticed the shared pool once
(DEC-23 blast-radius) but only asked whether *admin* traffic could degrade booking, and
dismissed it. It never asked about search, which is the only traffic that matters.

### Options

| # | Option | | Assessment |
|---|---|---|---|
| A | Leave one pool, document the risk | ❌ | The one NFR with a number and the one NFR called *"key"* both go unexpressed. This is the state the audit found |
| B | Two `DataSource` beans — a read-only search pool and a booking pool | 🟡 | True bulkhead; search can never take booking's connections. But it puts a `@Qualifier` and a second `TransactionManager` on every read path, and routing mistakes fail silently (a write on the read-only pool). Real isolation, real complexity, at **6 QPS peak** |
| C | Resilience4j bulkhead / semaphore per class | 🟡 | Isolation without a second pool, but adds a dependency and a second queue in front of the queue that already exists |
| D | **One pool; bound search at the transaction, leave booking unbounded** | ✅ | Expresses both policies, no new infrastructure, and the mechanism that enforces search's deadline is the same one that protects booking |

### Chosen: D

- **Search** runs `@Transactional(readOnly = true, timeout = 2)`. It has a deadline, so it
  is given one; exceeding it is a bug, not a load condition.
- **Booking** keeps no statement timeout. *"The time can change based on load and
  concurrent attempts"* is a direct instruction that a slow booking is acceptable where a
  failed one is not.
- `connection-timeout` rises **10 s → 20 s**, re-tuned for the path it was always meant
  for. Saturation still surfaces as `503` + `Retry-After` (§7.5.2) rather than a hang.

**Why bounding search is what protects booking.** This is the part that makes B and C
unnecessary at this scale. Starvation is not caused by search *volume* — at 6 QPS against
10 connections, volume is irrelevant — it is caused by search *duration*. A search that
cannot exceed 2 seconds returns its connection within 2 seconds, which caps how long
booking can ever wait behind it. Bounding the dominant class is the same act as reserving
headroom for the critical one, and it costs one annotation attribute instead of a second
`DataSource`. The isolation argument for B becomes real when search gets slow enough that
2 s is a normal duration rather than a fault; that is the documented scale-out step, not
today's build.

**Falsifiable.** DEC-26 already asserts search issues a bounded, index-backed query. If
that holds, a 2-second transaction timeout can never fire in normal operation — so the
timeout firing is a real signal, not a tuning knob. The two decisions check each other.

## DEC-28 — The specification is frozen; remaining effort goes to functionality

**Forcing question.** The clarification round contained one instruction about **where the
marks are**, and it is the only clause in the entire exchange that was never audited
against the repository:

> *"**more focus is to be done on the functionality**, you can assume moderate scale"*

Measured against that sentence, the repository says this:

| | Lines | Asked for by the brief? |
|---|---|---|
| `spec.md` + `decision-log.md` | **3,898** | **No** — internal working artefacts |
| `hld/` + `lld/` (outlines) | 441 | Yes — Deliverables 1 and 2 |
| `README.md` | headings, placeholder bodies | Yes — Deliverable 5 |
| Migrations | **0** — `.gitkeep` | Yes — Deliverable 4 |
| Production code | **1 class** — an empty `main` | Yes — Deliverable 3 |
| Feature tests | **0** | Evaluation Criterion #10 |

Four thousand lines have gone into documents nobody requested, and none into the thing the
hiring team singled out. *"Correctness of functionality"* is **first** in the brief's own
evaluation list; it is currently the only criterion scoring zero. Three consecutive audit
passes examined whether the documents were internally consistent and never asked whether
the documents were the work.

**The honest counter-argument, and why it only goes so far.** The spec is not waste: HLD
§8, the LLD, and the README's three mandated algorithm sections are all harvested from it,
and the 27 decisions are the direct evidence for Evaluation Criterion #8. The investment
was sound. What changed is the **marginal** return — a 28th decision now displaces the
first endpoint, and a reviewer cannot run a decision log.

There is also a failure mode specific to this situation: audit passes produce findings, so
they always feel productive. Pass 4 found 11 real defects. But six of them were in config
files that only exist because of decisions, not because of features — the audit had begun
feeding on its own output. A fifth pass over the same documents would find a fifth
pass's worth of findings and still leave Deliverable 3 empty.

### Options

| # | Option | | Assessment |
|---|---|---|---|
| A | Continue deepening the spec | ❌ | Directly contradicts the one instruction about effort the hiring team gave |
| B | Write HLD/LLD/README now, implement last | ❌ | Same inversion in a different order; also, the LLD describes classes that do not exist, so it cannot be verified against anything |
| C | **Freeze the spec; implement; harvest the docs from working code** | ✅ | Deliverable 3 exists, and every claim in the HLD/LLD becomes checkable against an artefact rather than against another document |

### Chosen: C

The specification is **frozen at 41 acceptance criteria and 28 decisions**. From here:

1. **Migrations** — `V1__baseline_schema.sql`, `V2__seed_reference_data.sql`. Fully
   specified in spec §4; writing them is transcription.
2. **Implementation** — the six APIs, vertically: schema → repository → service →
   controller, one feature at a time, each merged working.
3. **Tests** — CT-1…CT-10 and the 41 acceptance criteria.
4. **HLD, LLD, README** — written last, against running code.

**The freeze has one exemption, and it is narrow.** Implementation will contradict the
spec somewhere — it always does, and those contradictions are the most valuable findings
available, because they are the only ones a document-to-document audit structurally cannot
produce. The spec may be amended **when code proves it wrong**. It may not be amended to
add depth, polish an argument, or record a decision nothing forced.

**What would falsify this decision.** If implementation turns out to be transcription —
if no spec contradiction surfaces — then the spec really was complete and the freeze cost
nothing. If contradictions surface quickly, the freeze was overdue.

## DEC-29 — Virtual threads are turned off

**Forcing question.** `spring.threads.virtual.enabled: true` was set early, when the
design was being reasoned about without a stated scale. It is the only performance
setting in the repository, and the brief is explicit:

> *"Performance optimizations are **not required** beyond reasonable implementation."*

With the clarified numbers the setting can finally be evaluated instead of assumed.
Peak search is **~6 QPS** (DEC-20). Tomcat's default pool is **200** platform threads.
Virtual threads solve thread-per-request exhaustion — a problem that begins somewhere
around a thousand concurrent blocked requests. This system's peak is **three orders of
magnitude** below the point where the optimization does anything at all.

**What it cost.** The benefit is zero, but the setting was not free:

| Cost | Detail |
|---|---|
| Removed an admission limit | Tomcat's 200 workers were an accidental but *useful* bound. §7.5.2 exists only to explain that the queue moved to the connection pool, and DEC-27 had to re-reason about starvation in a system with no admission control |
| Carrier pinning on **Java 21** | A `synchronized` block pins the carrier thread. JEP 491 fixes this in Java 24; this project targets **21** (`pom.xml`), so the risk is live, not historical. Hikari and parts of the Hibernate path contain `synchronized` sections |
| Reasoning surface | An entire spec chapter analysing a failure mode introduced by a setting that buys nothing |

The pinning risk deserves emphasis because it inverts the intent: under contention —
exactly when §7.1 is waiting on row locks — a pinned carrier makes throughput *worse*
than the platform-thread model it replaced.

### Options

| # | Option | | Assessment |
|---|---|---|---|
| A | Keep it on | ❌ | Accepts a live Java 21 pinning risk and the loss of admission control, to optimize a load that is 1000× below the threshold where it matters |
| B | Keep it on, upgrade to Java 24 for JEP 491 | ❌ | A JDK upgrade to de-risk an optimization that was never needed. The tail wagging the dog |
| C | **Turn it off** | ✅ | Restores a standard, well-understood threading model and a free backpressure bound; removes the risk; deletes reasoning surface |

### Chosen: C

`spring.threads.virtual.enabled: false`, stated explicitly rather than deleted, so the
next reader knows it was considered and rejected rather than overlooked.

**What this does not change.** Nothing about correctness. D1–D10 are untouched; the
thread model was always an efficiency question, never a consistency one. DEC-27 survives
intact — bounding search duration still caps how long a booking queues — and in fact
strengthens, because Tomcat's 200-worker limit now bounds queue *depth* as well.

**The general rule this is an instance of.** An optimization whose benefit is unmeasured,
whose scale threshold is three orders of magnitude away, and which carries a known
runtime risk on the chosen JDK, is not a neutral default. Enabling it was reasonable
before a scale was stated; keeping it after one was stated would not be.

## DEC-30 — The index set, sized to the stated row counts

**Forcing question.** Deliverable 4 lists **"Indexes"** as a required item of the database
schema deliverable. The schema defines seven tables, every primary key, every foreign key,
two `CHECK` constraints and one partial unique index — and **not a single secondary
index**. Worse, DEC-26 asserts an `EXPLAIN` plan showing *"an index scan on the route
index"*: an index that appears nowhere in the DDL. A required deliverable item was empty,
and a decision was already depending on its contents.

**Why it stayed invisible.** `uq_booking_seat_active` is a unique *index*, so every search
for the word "index" in the spec returned a hit, and the section looked populated. The
check that found it was counting `CREATE INDEX` statements against table count, not
grepping for a word.

### Sizing — the clarification changes the answer

The clarified scale is **500 flight schedules** and ~1.1M booked seat rows per year
(DEC-20). Those two numbers sit on opposite sides of the threshold where an index helps,
which is why a single blanket rule would be wrong:

| Table | Rows | Index | Will the planner use it? |
|---|---|---|---|
| `flight_schedule` | **500** | `idx_schedule_route (source_airport_id, dest_airport_id)` | **Often not — and correctly so.** 500 rows is a handful of pages; a sequential scan is genuinely cheaper. The index is created for growth, not for today |
| `flight_schedule_day` | ~1,500 | `idx_schedule_day_dow (day_of_week)` | Only as part of the join; small table |
| `booking_seat` | **~1.1M/yr** | `idx_booking_seat_instance (flight_instance_id)` | **Yes.** This is the index that matters — both the seat map and the availability count filter on it |
| `booking` | ~180k/yr | `pnr` already `UNIQUE`; `idx_booking_instance (flight_instance_id)` | Yes, for cancellation and instance-level lookups |
| `flight_instance` | ~78k/yr | `uq_instance (flight_schedule_id, flight_date)` **UNIQUE** | Yes — and it is a correctness constraint (DEC-1 lazy materialisation must not create two rows for one date), not merely an access path |

**The correction this forces on DEC-26.** DEC-26 asserted *"an index scan on the route
index and no sequential scan on `flight_schedule`"*. At 500 rows that assertion is
**wrong**, and would fail against a correct planner. Making it pass would require either
seeding a fictitious volume or `SET enable_seqscan = off` — the second of which proves
only that the index is usable, never that it is chosen. DEC-26 is amended: the plan
assertion applies to **`booking_seat`**, where 1.1M rows make an index scan genuinely
obligatory, and `flight_schedule` is covered by the statement-count assertion alone.

**The honest statement about `idx_schedule_route`.** It is created knowing the planner may
ignore it at the stated scale. That is not waste: it costs ~16 kB and one line, it is the
access path the moment the airline's schedule set grows, and omitting it would mean the
schema encodes an assumption about scale that nothing else in the design encodes. But
claiming it makes search fast *today* would be false — at 500 rows, search is fast because
the table is small.

## Traceability

| Brief mandate | Decision(s) |
|---|---|
| FR4 — *"The design decision must be documented"* | DEC-1 |
| FR8 — *"Candidates should document their concurrency strategy"* | DEC-2, DEC-21 |
| FR1/FR2 — entity fields | DEC-3, DEC-6, DEC-8 |
| FR5/FR6 — search and seat map | DEC-1, DEC-3, DEC-4, DEC-7, DEC-20 |
| FR7 — booking reference | DEC-5 |
| FR7/FR8 — retry safety under a multi-instance topology | **DEC-24** |
| FR9 — cancellation | DEC-2 (predicate), DEC-15 |
| NFR1/NFR2 — clean architecture, separation of concerns | DEC-10, DEC-13 |
| NFR3 — RESTful API design | DEC-11, DEC-15 |
| NFR4 — transaction handling | DEC-2, DEC-13, DEC-15 |
| NFR5 — error handling | DEC-11, DEC-21 |
| NFR8 — testability | DEC-12, DEC-19, **DEC-26** |
| Deliverable 1 — HLD | DEC-17, DEC-18, DEC-19 |
| Deliverable 3 — executable implementation | DEC-16 |
| Deliverable 5 — README | DEC-16, DEC-18 |
| Evaluation Criteria #5 — scalability considerations | DEC-19, DEC-20, DEC-23, **DEC-25** |
| Evaluation Criteria #8 — design decisions | this file, DEC-18, DEC-22 |
| Clarification round — latency target | **DEC-26** |
| Clarification round — staleness condition | DEC-20, DEC-21, **DEC-24** |

---
