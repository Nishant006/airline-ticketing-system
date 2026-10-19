---
Status: DRAFT
Jira: ATS-1
Created: 2026-10-10
Source: Backend Engineering Assignment – Airline Ticketing System
---

# Feature Spec: Airline Ticketing System — Backend

## 1. Summary

Backend for a **single-airline** reservation system. A back office defines flight
**schedules** (templates with days of operation). Customers **search** flights for a
travel date, view a **seat map**, **book** one or more named seats, and **cancel** a
booking to release those seats. The bookable horizon is **365 days from the current
date**.

This spec is the authoritative line-by-line reading of the assignment brief. Every
numbered requirement in the brief is traced below to a design element and a
deliverable. Anything the brief does not explicitly require is **out of scope**. The brief couples
that with a second instruction — such functionality is also *"treated as a black box"* —
and the two clauses are discharged in different places: scope in §2.4 and §10, black
boxes in DEC-14. "Black box" is read **narrowly, as an external call that would be
stubbed**, on the authority of the hiring team's own gloss (*"you can leave those calls
as a static response"*) rather than on this document's preference. The narrow reading is
load-bearing and is recorded as a reading, not smuggled in as the brief's wording: under
a broad reading every excluded item (sorting, filtering, refunds) would demand a stub,
which would manufacture moving parts the brief never asked for.

---

## 2. Requirement Traceability Matrix

Read section by section against the brief. `ID` is referenced from the HLD, LLD and
tests so that every line of the brief is demonstrably satisfied.

### 2.0 Objective- and Scope-level Requirements

The Objective and Scope are **prose, not numbered lists**, so it is easy to trace the
nine Functional Requirements and miss them entirely. Between them they carry seven
binding statements and an evaluation charter, traced here before anything else.

Both are traced in this one block because the Actors (§2.0.3) are defined by Scope while
the capabilities they exercise (§2.0.1) are named by the Objective; splitting them would
put two halves of one statement in two places.

| ID | Brief text (verbatim) | Design element | Verified by |
|---|---|---|---|
| OBJ-1 | *"the backend for a single-airline … system"* | Backend only — no UI, no BFF. **No carrier/airline entity exists**: it would be a single-valued column on every row | §4 (absent by design), §10 |
| OBJ-2 | *"flight schedule management, flight search, **seat inventory management**, ticket booking, and booking cancellation"* | The five named capabilities, mapped in §2.0.1 | §2.0.1 |
| OBJ-3 | *"Any functionality not explicitly mentioned should be considered out of scope and treated as a black box"* | Scope is a **requirement**. Every endpoint and persisted column traces to a brief clause | §2.4, §10 |
| OBJ-4 | *"The objective of this assignment is to evaluate: …"* (8 axes) | §2.0.2 | §2.0.2 |

The Scope section adds four more. SCOPE-1 is the one most easily lost, because it is a
constraint hiding inside a scene-setting sentence.

| ID | Brief text (verbatim) | Design element | Verified by |
|---|---|---|---|
| SCOPE-1 | *"a single airline operating flights **between airports**"* | **Point-to-point only.** A flight has exactly one origin and one destination, so there is no itinerary, segment or leg table and search never composes two flights into one result | §2.4 (multi-leg **OUT**), §4.3 |
| SCOPE-2 | *"an administrative back office responsible for **defining** schedules"* | Admin surface is **create + read only** — *defining*, not *managing* | §2.0.3, API-1/API-2 |
| SCOPE-3 | *"Customers can search for flights, view seat availability, make bookings, and cancel bookings"* | Exactly four customer capabilities; the list is **closed** | §2.0.3, API-3…API-7 |
| SCOPE-4 | *"The implementation should support **bookings** for up to one year from the current date"* | Rolling horizon enforced as BR-6 on **both** search and booking — the brief binds the window to *bookings*, so enforcing it only on search would satisfy the sentence's setting but not its subject. *"From the current date"* names no clock; **§6.1** fixes which one, because at `N ≥ 2` there are `N` | BR-6, AMB-12, **§6.1**, §8.2.1 |

**SCOPE-1 is a scope boundary, not background colour.** *"Flights between airports"*
describes a point-to-point carrier. Connecting itineraries are the single most common
unrequested feature in a reservation-system exercise, and the discipline that excludes
them is noticing that this sentence — not a prohibition elsewhere — is what forecloses
them.

#### 2.0.1 The five named capabilities

Four map cleanly onto numbered FRs. **One does not, and that is the point of this
table.**

| Objective capability | Brief FR | Where it lives |
|---|---|---|
| Flight schedule management | FR3 | API-1, `flight_schedule` |
| Flight search | FR5 | API-3 |
| **Seat inventory management** | *no single FR* | **§4.7 + §7** — see below |
| Ticket booking | FR7 | API-5 |
| Booking cancellation | FR9 | API-7 |

**"Seat inventory management" has no numbered FR.** It is spread across FR5
(*"Available Seats"*), FR6 (seat map), FR7 (seats become booked) and FR9 (seats are
released). Treating it as "just the seat map" would under-build a capability the
Objective names as one of five. Defined explicitly here as the **inventory invariant**:

> For a given flight instance, the set of seats is `aircraft.rows × aircraft.letters`
> (derived, never stored — §4.2); a seat is *unavailable* iff a `booking_seat` row
> exists for it with `status = 'BOOKED'`; and every transition into or out of that
> state is mediated by the partial unique index of §7.1.

Availability is therefore a **computed set difference**, never a stored counter. There
is no `available_seats` column anywhere — a counter would be a second source of truth
that can drift from the booking rows under concurrency, and reconciling it would need
exactly the locking the index already provides.

**This is also the Objective-level reason the design is instance-count-independent.** A
counter is state that must be *maintained*; a set difference is state that is
*recomputed*. Maintained state needs every writer to agree on an ordering, which at
`N ≥ 2` cannot come from the application at all — no `synchronized` block, no
`AtomicInteger`, no per-instance cache can serialise writers in separate JVMs (D1, D3).
Recomputed state needs no agreement, because the authoritative rows are read at the
moment the answer is produced. Choosing derivation over maintenance here is what lets
every later section say *"this holds regardless of instance count"* without qualification. This single invariant is what makes
FR5's count, FR6's map, FR8's constraint and FR9's release *the same mechanism viewed
from four angles* rather than four features.

#### 2.0.2 The eight evaluation axes

Distinct from the ten *Evaluation Criteria* at the end of the brief — both lists exist
and both are traced (see §2.5).

| # | Axis | Primary evidence |
|---|---|---|
| 1 | System design skills | `hld/architecture.md`; **§2.0.4 deployment topology (DEC-19)**; **§2.0.5 NFR envelope (DEC-20)**; DEC-1. *The topology and the envelope are the system-design evidence — §7's topology is transactional, a different thing* |
| 2 | Database design | §4 normative DDL; `db/schema.sql`; ER diagram |
| 3 | Backend architecture | NFR1/NFR2; ArchUnit-enforced layering (DEC-10) |
| 4 | API design | §5, §5.1, §5.2; RFC 9457 (DEC-11) |
| 5 | **Algorithmic thinking** | **§8 Algorithms** — search, booking, cancellation, each with its step sequence and complexity |
| 6 | Concurrency handling | §7 in full; CT-1…CT-10; **D1–D10** — the claim is not "this is thread-safe" but "this is correct for any instance count", which is a strictly stronger statement and the one §2.0.4 commits to |
| 7 | Code quality | NFR8; Spotless, ArchUnit, JaCoCo |
| 8 | Documentation | HLD, LLD, README, `decision-log.md` |

Axis 5 had **no home in an earlier draft of this spec**. The brief names algorithmic
thinking as an evaluation axis and the README deliverable enumerates **13 algorithm
sub-points**; neither was traceable to anything. §8 exists to close that.

#### 2.0.2.1 Configuration is a deliverable, and it drifts

Axis 7 is evidenced by Spotless, ArchUnit and JaCoCo; axis 8 by the four documents.
Note what that set **cannot** see: Spotless reads formatting, ArchUnit reads package
dependencies, JaCoCo reads line coverage, and none of the three opens
`application.yml`. A number in the configuration can contradict the section of this
spec that derives it, and the whole quality toolchain stays green.

That is not hypothetical here — a pass-4 audit found exactly two instances:

| Artefact said | Document said | Consequence |
|---|---|---|
| `maximum-pool-size: 20` | §7.5.1 derives **10** from `max_connections` | At `N = 5` the cluster exceeds the usable budget and the last instance to start fails with `FATAL: sorry, too many clients already`. §7.5.1 *describes this exact failure* while the repository shipped it |
| `# RFC 7807 ProblemDetail` | DEC-11 and NFR5 specify **RFC 9457** | Cosmetic alone, but it is the obsoleted number, and a reviewer who greps for the standard finds the wrong one in the code |

Both are **one-directional** drifts: the document was revised and the artefact was not.
This is the normal direction, because the document is where the thinking happens.

**The rule this establishes (D8).** *A configuration value that is **derived** in this
spec must name its derivation at its definition site and be asserted by a test.* A value
that is merely **chosen** needs neither. The distinction is whether changing it in
isolation can break a property argued somewhere else:

| Value | Kind | Treatment |
|---|---|---|
| `maximumPoolSize: 10` | Derived — §7.5.1, from `max_connections` and `N` | Comment citing §7.5.1 + `ConnectionBudgetTest` (§9 acceptance criteria) |
| `airline.booking.window-days: 365` | Derived — brief *"up to one year"* | Comment + asserted by the search/booking window tests |
| `hibernate.jdbc.batch_size: 50` | Chosen — a throughput knob | No assertion; wrong values are slow, never incorrect |
| `server.port: 8080` | Chosen — the load balancer resolves it | None |

The distinction matters because asserting *every* configuration value produces a change
-detector test, which is a different and worse failure mode: it breaks on every edit and
is therefore disabled. Only values that carry an argument get a test.

#### 2.0.3 Actors

Scope establishes exactly two actors, and the distinction is architectural, not
cosmetic:

| Actor | Brief text | Capabilities | Trust |
|---|---|---|---|
| **Back office** | *"an administrative back office responsible for **defining** schedules"* | Create schedules (FR3) | Privileged — authentication is a **black box** (OBJ-3, DEC-14) |
| **Customer** | *"Customers can search for flights, view seat availability, make bookings, and cancel bookings"* | Search, seat map, book, cancel | Anonymous — the brief defines no customer account (§10) |

This is why admin routes are separated under `/api/v1/admin/*`: the two actors have different
trust levels, so they get different URL namespaces and a documented authentication seam
at the boundary — even though the authentication itself is not built. Collapsing them
into one namespace would make that seam invisible.

**Two namespaces, one deployable — and that is a decision, not a default.** Under §2.0.4
the obvious follow-up is whether two actors at two trust levels should be two services.
They are not, and the reasoning — shared schema, zero independent load, no independent
availability requirement, and what would have to change for the answer to flip — is
recorded as **DEC-23**. The boundary is kept extractable by package-by-feature plus an
ArchUnit rule, so the decision is reversible rather than merely deferred.

The brief says *"defining"*, not *"managing"*, and the Assumptions confirm *"Flight
schedules do not change after creation"* — so the back office gets **create only**. No
update, no delete.

**The customer capability list is closed at four.** `GET /api/v1/bookings/{pnr}` (API-6) is
therefore **not** a capability the brief grants; it is a deliberate addition, and §5
records it as one rather than claiming a requirement that does not exist. Its
justification is internal: §7.2 returns `503` on an indeterminate commit failure and
instructs the client to re-query the booking, which is unimplementable without it. An
addition demanded by this design's own failure handling is defensible; one added for
convenience would not be.

**Under §2.0.4 this stops being a convenience and becomes structural.** With `N`
instances, the client's re-query is load-balanced — it will usually reach a *different*
instance than the one whose commit outcome was indeterminate (D4). There is therefore no
possibility of resolving the ambiguity from instance-local memory, because the instance
that holds that memory is not the one answering. The only thing both instances share is
the database, so the resolution path **must** be a read of committed state keyed on
something the client already holds. That is precisely API-6. At `N = 1` this endpoint
looks like a nicety; at `N ≥ 2` it is the sole mechanism by which an indeterminate
booking can ever be resolved.

**That argument covers less than it appears to, and the gap is DEC-24.** It holds when
the *server* knows the outcome is in doubt and says so — §7.2's `503` carries the PNR,
so the client has a key to re-query with. It does **not** hold when the booking committed
and the **response was lost in transit**: the PNR was inside the response that never
arrived, so the client holds nothing to key on, and API-6 — keyed on `pnr` — is
unreachable by exactly the client that needs it. Retrying then collides with the
customer's *own* seats and returns `409 SEAT_UNAVAILABLE`, which is true but misleading,
and leads nowhere. The system cannot tell *the same user retrying* from *a different user
competing*, because without a key from the client there is nothing in the request that
distinguishes them. **DEC-24 adds that key**, and §7.2.1 specifies the flow.

**What actually produces an indeterminate outcome is deployment, not failure.** The
`503` path reads as an exotic case — a commit that crashes mid-flight — and on one
long-lived instance it very nearly is. Under §2.0.4 it is routine: every rolling
release terminates instances while they hold open transactions, so the dominant
generator of indeterminate outcomes is **a deploy**, which happens on a schedule, rather
than a fault, which does not. A design that treats the `503` path as rare will have
under-tested the path its own release process exercises most.

Two artefact-level consequences, neither of which is visible at `N = 1`:

| Setting | Why |
|---|---|
| `server.shutdown: graceful` + a bounded `spring.lifecycle.timeout-per-shutdown-phase` | On `SIGTERM` the instance stops accepting new requests and lets in-flight ones finish. Without it the container dies mid-transaction; PostgreSQL rolls back — so **no partial booking is ever possible either way**, which is the property that matters — but the client receives a dropped connection rather than a response, and a dropped connection carries no PNR and no `Retry-After`. Graceful shutdown converts *most* indeterminate outcomes into ordinary ones |
| **Readiness separated from liveness** (`management.endpoint.health.probes.enabled`) | These answer different questions and conflating them is actively harmful. *Liveness* asks "should this process be killed?"; *readiness* asks "should traffic be routed here?". If the database is unreachable, the correct response is to leave the load-balancer rotation (readiness fails) and **not** be restarted (liveness still passes) — restarting cannot reach a database that is down, and a cluster that restarts every instance at once turns a recoverable outage into a thundering herd against a database already in trouble |

The second is why `docker-compose.yml`'s healthcheck targets **readiness** specifically:
`depends_on: service_healthy` is asking the routing question, not the restart question.


#### 2.0.4 Deployment topology — the assumption everything else rests on

The brief **never states how many instances of the service run**. Across all 333 lines
the word *"instance"* appears only as *flight instance*; there is no mention of a pod,
node, replica, process or deployment. The single deployment-adjacent word in the whole
document is **"Scalability considerations"** — and it appears in the *Evaluation
Criteria*.

Silence is not permission to assume the convenient thing. The topology has to be chosen
and stated, because **it decides whether a whole class of designs is correct or
broken**, and a reader cannot evaluate the concurrency strategy without knowing it.

**The assumption (DEC-19): the service runs as `N ≥ 2` stateless instances behind a load
balancer, sharing one PostgreSQL primary.** Requests are routed to any instance; no
affinity, no sticky sessions, no coordination between instances. A design that is only
correct when `N = 1` is treated as **incorrect**, not as a simplification.

Three reasons this is the right default rather than gold-plating:

1. **It is the enterprise baseline.** A service that cannot be deployed twice cannot be
   deployed with zero downtime, because a rolling restart *is* `N = 2` for the duration
   of the rollout. `N = 1` is not a simpler production topology; it is an unavailable
   one.
2. **It costs this design almost nothing**, because the seat invariant is already
   enforced by a **partial unique index inside PostgreSQL** (DEC-2, DEC-3). That is the
   one enforcement point whose behaviour is completely independent of how many
   application instances exist. The core was already distributed-correct — what was
   missing was that this spec never *said so*, never *tested* it, and carried several
   peripheral assumptions that were quietly single-process.
3. **It converts a graded criterion from intent into a property.** *"Scalability
   considerations"* is evaluated. Prose claiming the design would scale is weaker than a
   design in which no single-instance assumption exists to remove.

**What the assumption forbids, and where each rule is discharged:**

| # | Rule | Why `N = 1` thinking fails | Discharged in |
|---|---|---|---|
| D1 | No correctness may depend on JVM-local state — no `synchronized`, no `ReentrantLock`, no in-memory seat registry | Two pods have two heaps. A lock one pod holds is invisible to the other, so the guarded section runs twice in parallel | §7.1 · DEC-2 |
| D2 | No background or scheduled work | A `@Scheduled` job on `N` pods fires `N` times. DEC-1's lazy materialisation has **no scheduled component at all**, so there is no leader election, no `ShedLock`, no split brain to reason about | §7.4 · DEC-1 |
| D3 | No in-memory caching of **mutable** booking state | `N` instances would hold `N` divergent views, and two customers would see two different seat maps. Scoped to *mutable* — see §4.2.1 for why immutable reference data is exempt | DEC-4 · §4.2.1 |
| D4 | A client retry may land on a **different** pod than the original | Retry-safety cannot come from remembering the first attempt in memory; it must come from the database | §7.2 · §7.7 |
| D5 | Startup must be safe when `N` pods boot simultaneously | All `N` run Flyway against one database at the same moment | **§4.0.1** |
| D6 | The connection budget is `N × pool`, not `pool` | A pool size that is safe once can exhaust `max_connections` when multiplied | **§7.5.1** |
| D7 | Wall-clock time is per-pod | `today` decides a `422`; two pods must not disagree about which day it is | **§6.1** |
| D8 | A **derived** configuration value must cite its derivation and be asserted | `maximumPoolSize` is derived from `max_connections` and `N`. Nothing in the quality toolchain reads `application.yml`, and the failure it causes is configuration-shaped — so a single instance can never falsify it | **§2.0.2.1** · the connection-budget acceptance criterion |
| D9 | Every uniqueness rule is a database constraint, never an application check | A `SELECT`-then-`INSERT` check is a race on one instance and a guaranteed race on `N` | §7.1 · DEC-2 · DEC-5 |
| D10 | The service holds no session or request-spanning state | Any instance must be able to serve any request, including the *second* call of a two-call flow | §5 · NFR3 · ArchUnit: no `HttpSession`, no `@SessionAttributes` |

**What the assumption does *not* license.** It is a statement about *topology*, not an
invitation to add infrastructure. No distributed cache, no message broker, no
coordination service, no service mesh is introduced — none is needed, because every rule
above is discharged by PostgreSQL or by *not doing something*. Adding Redis to
"handle distribution" would be the failure mode this assumption is meant to prevent, not
its fulfilment. Scale-out machinery that is genuinely absent is argued in **HLD §9**,
which remains explicitly *not implemented*.

Each section below is audited against D1–D10.

---

### 2.0.5 Non-functional envelope *(clarified 2026-10-12 — DEC-20)*

The brief states **no** load, latency or availability targets. That absence is itself a
design hazard: *"a cache is not needed here"* is an opinion until a number is attached to
it, and Evaluation Criterion #5 grades *"Scalability considerations"* — which cannot be
demonstrated against an unknown envelope.

The envelope below was obtained by asking. It is recorded verbatim because the design's
exclusions are now justified **arithmetically**, and an auditor must be able to check the
arithmetic against its inputs.

| Dimension | Stated | Normative consequence |
|---|---|---|
| Topology | *"Design for multiple instances, but we'll bring up a single instance while checking"* | Design satisfies D1–D10; the **deliverable ships one app container** (no LB, no replicas) |
| Scale | *"moderate scale (10k DAU, 500 flight schedules)"* | Drives every row-count estimate below |
| Search : booking | **25 : 1** | Far lower than a public OTA; closes the caching question |
| Search staleness | *"if they are late, how does the user get informed if there is a change in the state when he proceeds for a booking — **if that is handled**, the results can be stale"* | **Conditional grant.** Discharged by API-5's `409` body — see §7.8.1 and DEC-21. **The grant is accepted and then not spent**: §8.1.5 shows search has no server-side staleness at all, because the condition applies to a client-side window that caching never caused |
| Search latency | *"1 second with a provision to serve lazy loading if needed"* | Budget is ~3 orders of magnitude above an indexed join; lazy-load boundary = seat map is a separate resource |
| Booking latency | *"ultimate stability is key, the time can change based on load and concurrent attempts"* | **Explicit licence for the blocking wait** in §7.1 — correctness outranks latency on this path |
| Consistency | Search may be stale; booking strongly consistent, zero double-booking | Confirms the split already designed for |
| Effort | *"more focus is to be done on the functionality"* | Correctness/completeness of FRs outranks performance work |

**Derived load** — the figures every later exclusion cites:

| Quantity | Derivation | Result |
|---|---|---|
| Search throughput | 10k DAU × ~5 searches ÷ 86,400 s | **~0.6 QPS** |
| Search peak | × 10 burst | **~6 QPS** |
| Booking peak | search ÷ 25 | **~0.25 QPS** |
| Flight instances / year | 500 × ~3 days-per-week × 52 | **~78,000** |
| Seat rows **if materialised** | 78,000 × 180 | **~14,000,000** — avoided by DEC-3 |
| Booked seat rows / year | 2,000/day × ~1.5 seats × 365 | **~1,100,000** |

**What the envelope closes.** Redis, read replicas, CQRS, sharding, async booking via a
queue and any caching layer are rejected **on these numbers** — each is defensible at
1000× this load and none at ~6 QPS. The reasoning, not merely the conclusion, belongs in
**HLD §9**, because the graded skill is knowing the threshold at which the answer flips.

**One clarification is deliberately *not* implemented as asked.** *"Provision to serve
lazy loading"* does **not** become search pagination: at 500 schedules a
`(source, destination, date)` query returns **single-digit** rows, and the brief lists
sorting and filtering as out of scope (§10). The lazy-load boundary the answer asks for
already exists in the API shape — **search returns an available-seat *count*; the
180-entry seat map is a separate resource** (API-4). Recorded here so the decision reads
as a reasoned decline rather than an omission.

---

### 2.1 Functional Requirements

**Where the brief puts its mandatory language.** Across the whole Functional
Requirements block the brief uses *must* **four times**, *should* twelve times and *may*
four times. The four are worth naming, because they are the only places the brief
compels rather than suggests:

| # | Brief § | Mandatory statement |
|---|---|---|
| 1 | FR3 | *"The system **must** expose administrative APIs to create flight schedules"* |
| 2 | FR4 | *"The booking engine **must** generate (or logically expose) bookable flight instances for 365 days"* |
| 3 | FR4 | *"The design decision **must** be documented"* |
| 4 | FR5 | *"Customers **must** be able to search flights using…"* |

Two readings follow, and they pull in opposite directions, so both are stated rather
than the convenient one.

**FR6, FR7, FR8 and FR9 contain no *must* at all.** Seat map, booking, booking
constraints and cancellation are all *should*. That emphatically does **not** make them
optional — FR8 is the subject of a named evaluation criterion (*Concurrency handling*),
and a reservation system that cannot book is not a submission. Modal strength here
tracks **how precisely the brief is dictating a mechanism**, not how much it cares: it
mandates *that* an admin API exists and *that* search accepts three parameters, while
leaving *how* seats are stored or bookings are serialised to the candidate. Treating
*should* as "optional" would be the single most expensive misreading available in this
document.

**Half of the mandatory statements are in FR4, and one of them is about documentation.**
*"The design decision must be documented"* is one of only four compelled statements in
the entire functional specification. It is the brief telling the candidate, in its
strongest available voice, that the flight-generation **write-up** is graded alongside
the code. That is why DEC-1 is the longest entry in the decision log and why §7.1 of the
HLD exists as a named section rather than a paragraph.

| ID | Brief § | Requirement (as written) | Design element | Verified by |
|---|---|---|---|---|
| FR1.1 | 1 Airport | Airports already exist; may be seeded | Flyway seed migration | `V2__seed_reference_data.sql` |
| FR1.2 | 1 | Airport CRUD out of scope | No airport controller | §10 |
| FR1.3 | 1 | Each airport has **at least** IATA code, name, city, country | `airport` table, 4 columns | Schema |
| FR2.1 | 2 Aircraft | Aircraft pre-configured | Flyway seed migration | `V2__…` |
| FR2.2 | 2 | Aircraft has ID, type, seat configuration | `aircraft` table | Schema |
| FR2.3 | 2 | Seat config = rows + seat letters (A320: 30 rows, A–F) | `rows_count INT`, `seat_letters VARCHAR` | §4.2 |
| FR2.4 | 2 | **Seat map remains fixed** for an aircraft | Seats **derived**, never stored per-aircraft | §4.2, DEC-3 |
| FR2.5 | 2 | Aircraft CRUD out of scope | No aircraft controller | §10 |
| FR3.1 | 3 Schedule | Expose **administrative API to create** schedules | `POST /api/v1/admin/flight-schedules` | API-1 |
| FR3.2 | 3 | Schedule = flight no., origin, destination, dep time, arr time, aircraft, days of operation | `flight_schedule` + `flight_schedule_day` | §4.3, §4.4 |
| FR3.3 | 3 | Days of operation are a **set of weekdays** | `flight_schedule_day(day_of_week)` | §4.4 |
| **DI-1** | *(derived)* | **Not a brief requirement.** The brief never states flight numbers are unique. `flightNumber + date` is nonetheless the only handle the API exposes (DEC-7), so uniqueness per weekday is an invariant **this design creates** and must enforce | `uq_flight_number_day` | AMB-10, §4.4 |
| FR4.1 | 4 Instances | Schedules are **templates** | Schedule is the source of truth | DEC-1 |
| FR4.2 | 4 | Generate **or logically expose** bookable instances for 365 days | **Hybrid**: computed on read, materialised on first write | DEC-1 |
| FR4.3 | 4 | The design decision **must be documented** | HLD §Flight Generation Strategy; README | HLD, README |
| FR5.1 | 5 Search | Search by origin, destination, travel date | `GET /api/v1/flights?originCode&destinationCode&date` | API-3 |
| FR5.2 | 5 | Return flight number, dep time, arr time, **available seats** | `FlightSearchResultResponse` — exactly 4 fields, seats as a **count** | API-3, AMB-9 |
| FR5.3 | 5 | **Only flights operating on the requested date** | `dayOfWeek(date) ∈ schedule.daysOfOperation` | §6 BR-5 |
| FR5.4 | 5 | Sorting/filtering beyond this out of scope | Fixed deterministic order only | §10 |
| FR6.1 | 6 Seat map | Each flight exposes a seat map | `GET /api/v1/flights/{no}/seat-map?date=` | API-4 |
| FR6.2 | 6 | Each seat is **Available or Booked** — exactly two states | `SeatStatus` enum, 2 constants | §4.7 |
| FR6.3 | 6 | Seat labels follow aircraft configuration (`1A`…`30F`) | `row + letter`, derived | §4.2 |
| FR6.4 | 6 | Seat map reflects **real-time** booking state | Read-through of `booking_seat`; no cache | DEC-4 |
| FR7.1 | 7 Booking | Select flight, view seats, select **one or more** seats, create booking | `POST /api/v1/bookings` with seat list | API-5 |
| FR7.2 | 7 | Response returns **PNR**, flight no., flight date, passenger count, assigned seats, status | `BookingResponse` | API-5 |
| FR7.3 | 7 | Passenger info minimal — **name only** | `booking_seat.passenger_name` — one per seat | §4.7, AMB-8 |
| FR7.4 | 7 | **No passenger validation required** | Name accepted as supplied — no semantic check. Only the structural `NOT NULL` the column needs | §6 BR-9 |
| FR8.1 | 8 Constraints | Prevent **double booking** | Partial unique index (L1) | §7 |
| FR8.2 | 8 | Prevent **two customers booking the same seat** | Same index + tx isolation | §7, CT-1 |
| FR8.3 | 8 | Concurrent requests maintain **seat consistency** | Single DB-enforced guarantee (§7.1) + `READ COMMITTED` + ordered writes | §7, CT-1…CT-10 |
| FR8.4 | 8 | Candidates **must document** concurrency strategy | HLD §Concurrency; README | HLD, README |
| FR9.1 | 9 Cancel | Customer cancels an existing booking | `POST /api/v1/bookings/{pnr}/cancel` | API-7 |
| FR9.2 | 9 | Change **booking status** | `BookingStatus → CANCELLED` | §6 BR-12 |
| FR9.3 | 9 | **Release** reserved seats | `booking_seat.status → RELEASED` | §6 BR-13 |
| FR9.4 | 9 | Released seats **bookable again** | Partial index excludes `RELEASED` | CT-4 |
| FR9.5 | 9 | Refund processing out of scope | No payment module | §10 |

### 2.2 Non-Functional Requirements

The brief's verb is *"The system should **demonstrate**"* — not *have*, not *follow*.
That is an **evidentiary burden**: for each NFR there must be something a reviewer can
run or read that would **fail** if the property were absent. An earlier draft of this
table stated nine intentions, six of which no artifact could contradict — "controllers
hold no logic" is a promise, not a demonstration.

Every row therefore names its **falsifier**: the specific thing that breaks if the NFR
is violated. Where the falsifier is a document rather than a test, that is stated
plainly rather than dressed up as verification.

| ID | Brief requirement | How it is satisfied | **What fails if it is violated** |
|---|---|---|---|
| NFR1 | Clean architecture | `api / application / domain / infrastructure`; dependencies point inward | **ArchUnit** `layeredArchitecture()` rule — build fails on an inward-violating import |
| NFR2 | Separation of concerns | Controllers hold no logic; services hold no HTTP; entities hold invariants | **ArchUnit**: `..api..` must not be accessed by `..domain..`; no `javax.servlet`/`org.springframework.web` type may appear in `..application..` or `..domain..`; no `@Transactional` outside `..application..` |
| NFR3 | RESTful API design | Resource nouns, correct verbs, correct status codes, `/api/v1` prefix (DEC-15 for the cancel action); **stateless — see below** | `@WebMvcTest` slice asserting the status-code matrix of §5.2 — including that an empty search is `200 []` and an unknown PNR is `404`; **ArchUnit**: no handler method parameter or field of type `HttpSession`, and no use of `@SessionAttributes` |
| NFR4 | Transaction handling | Explicit `@Transactional` boundaries at the **application service** layer only | Integration test asserting a forced failure mid-booking leaves **zero** `booking` and **zero** `booking_seat` rows; plus ArchUnit (NFR2) pinning where the annotation may appear |
| NFR5 | Error handling | `@RestControllerAdvice` → **RFC 9457 `application/problem+json`**; typed domain exceptions | Test asserting every error path returns `Content-Type: application/problem+json` with a stable `type`; a bare `500` with an HTML body fails it |
| NFR6 | Validation | Bean Validation at the DTO boundary; **invariants enforced in the domain**, not only at the edge | Unit test constructing the domain object **directly**, bypassing the controller, and asserting it still rejects — this is what proves validation is not merely edge-deep |
| NFR7 | Logging | SLF4J; correlation ID in MDC; structured `key=value`; **passenger names never logged** | Test capturing the log appender during a booking and asserting the passenger name string is **absent**, and that the correlation ID is present on every line |
| NFR8 | Testability | Constructor injection only, no field injection, no static mutable state; unit · slice · integration · concurrency suites | **ArchUnit**: no field annotated `@Autowired`; no non-final static fields. The concurrency suite (CT-1…CT-10) is itself only writable because the design is injectable |
| NFR9 | *"Performance optimizations are not required **beyond reasonable implementation**"* — the qualifier is binding, and *Scalability considerations* is a separate evaluation criterion | **Reasonable is still required**: every query index-backed (§4.8), search bounded by schedules-on-route, no N+1 (`@EntityGraph`), no full scans. **Not** built: caching, read replicas, sharding, partitioning — each rejected on DEC-20's measured envelope (~6 QPS peak search, ~0.25 QPS peak booking) rather than on taste. Caching is additionally rejected on correctness: `N` instances would hold `N` divergent seat maps, and FR6 says *real-time* (DEC-4, D3) | N+1 regression test asserting a bounded statement count for a multi-result search (Hibernate statistics). The *omissions* are deliberate and documented in HLD §Scale-out |

**NFR3's statelessness constraint is the one NFR that §2.0.4 makes load-bearing.**
"RESTful" is usually audited as verbs, nouns and status codes. Its *statelessness*
constraint is normally the uncontroversial part — here it is the part that carries
weight, because a stateless API is exactly what lets a load balancer route any request to
any instance with no session affinity. Three consequences, each a thing the design
therefore cannot do:

| Consequence | What it rules out |
|---|---|
| No `HttpSession`, no server-side cart, no sticky sessions | A seat selection held in session on pod 1 is invisible to pod 2, so a customer whose booking request is routed elsewhere loses their selection. The failure is intermittent and routes-dependent — the worst kind to reproduce |
| No multi-step *hold → confirm* flow | A hold is server-side state between two requests. FR7 asks for *"create a booking"*, one step, and §8.2 implements it as one transaction. **This is why a hold model was never on the table**, independently of it being out of scope |
| Every request carries everything it needs | `(flightNumber, date, seatLabels, passengerNames)` on `POST`, `pnr` on cancel. Nothing is remembered between calls, so `N` instances are interchangeable and a rolling deployment drops no in-flight state |

This is D1 seen from the API side rather than the concurrency side: D1 forbids JVM-local
state because two heaps disagree; NFR3 forbids it because REST says so. **They forbid the
same thing for different reasons, which is why neither is doing the other's work** — a
design could be stateless and still use a `ReentrantLock`, or use sessions and no locks,
and both would be wrong here.

**NFR7 names a correlation ID, so the mechanism is specified rather than implied.**
The row above promises a test asserting the ID is present on every line, which is not
something an unspecified mechanism can satisfy:

| Question | Answer |
|---|---|
| Where does it come from? | The inbound `X-Correlation-Id` header if present, otherwise a server-generated UUID. Accepting a caller's value is what makes a log searchable across a client and this service; generating one guarantees it is never absent. |
| Where is it set? | A servlet `Filter` ordered **outermost**, so it is established before Spring MVC, before `@RestControllerAdvice`, and therefore before any error is logged. An interceptor is too late — it never sees requests rejected by the dispatcher. |
| Is it returned? | Yes, echoed as a response header, including on error responses. An ID that only exists in the server's logs cannot be quoted by the person reporting the problem, which is most of its value. |
| Which instance served it? | The MDC also carries an **instance identifier** (`HOSTNAME`, which a container runtime sets to the pod name). Under §2.0.4 a correlation ID alone is not enough: a customer's search and their booking may be served by different pods, so aggregated logs for one correlation ID interleave lines from several instances. Without the instance tag the sequence is unreadable — and worse, a bug present on one pod and not another is invisible. |
| How is it cleaned up? | `MDC.remove(...)` in a `finally` block. **This is the one that bites**: MDC is thread-local and Tomcat pools threads, so a missing cleanup does not lose the ID — it leaks the *previous* request's ID into an unrelated request's log lines. The result is worse than no correlation ID, because the logs look correct and are wrong. |

**NFR7 and the PII rule interact with FR7.** Booking requests carry passenger names, so
the natural "log the request body" reflex would put personal data in every log line.
The rule is therefore explicit — **log the PNR, the flight key and the seat labels;
never the name** — and it is asserted, because a logging convention that is merely
written down is the one that erodes first.

**Where ArchUnit is load-bearing.** NFR1, NFR2 and NFR8 are all structural claims, and
structural claims decay silently as a codebase grows — nothing at runtime notices a
controller that acquired a transaction. Encoding them as tests converts three of the
brief's eight NFRs from prose into build failures. This is the single highest-leverage
testing decision in the project and is why ArchUnit appears before any business test.

### 2.3 Deliverables

| Brief deliverable | Artifact | Status |
|---|---|---|
| High-Level Design | `hld/architecture.md` → rendered to **`hld/architecture.pdf`** (structure names a PDF explicitly) | Outline committed |
| HLD diagrams | `hld/diagrams/` | Pending |
| Low-Level Design | `lld/design.md` | Outline committed |
| Backend implementation | `src/` | Pending |
| ER diagram | `db/er-diagram.md` | Pending |
| SQL schema / migrations | `db/schema.sql`, `db/migrations/` | Pending |
| README (setup, design decisions, 3 algorithms, assumptions) | `README.md` — must cover all **13 named sub-points** the brief lists under the three algorithms | Outline committed |
| Tests | `src/test/java` (documented deviation from `tests/`) | Pending |
| docker-compose (optional) | `docker-compose.yml` | Committed |

**This document is not a deliverable.** `features/` and `.notes/` are gitignored; the
submission is `README.md`, `hld/`, `lld/`, `db/` and `src/`. The spec and the decision
log are the **working surface** where the brief is interrogated — analysis that stays
here is invisible to a reviewer. Every section therefore has a declared destination, so
nothing is stranded:

| Spec section | Distils into |
|---|---|
| §2.0–§2.2 Traceability, NFR falsifiers | HLD §2 Requirements · **LLD §1 Module structure** (the ArchUnit dependency rules *are* the module definition) · LLD §10 Testing design |
| §2.4 Scope matrix | HLD §2.3 Below the line · README §Assumptions |
| §3 Resolved ambiguities (AMB-1…AMB-12) | README §Assumptions · HLD §3 Assumptions — **the ambiguity *and* its resolution**, since an unstated reading looks like an oversight |
| §4 Domain model (normative DDL) | `db/schema.sql` · `db/migrations/` · `db/er-diagram.md` · LLD §2 Class model |
| §4.8 Indexes | `db/schema.sql` · HLD §7.3 · README §Search Algorithm |
| §5.1 API contracts · §5.2 Status codes | LLD §4 API contracts · README §API reference |
| §6 Business rules | LLD §5 Validation · LLD §6 Error handling |
| §7 Concurrency (§7.1–§7.11, CT-1…CT-10) | HLD §7.2 Concurrency strategy · **LLD §7 Transaction boundaries** · LLD §9 Concurrency implementation · README §Booking Algorithm |
| §8 Algorithms | **LLD §3 Services** · LLD §8 Sequence diagrams · README §Search / §Booking / §Cancellation Algorithm — the brief's **13 named sub-points** |
| §9 Acceptance criteria | `tests/` · LLD §10 Testing design · README §Testing — the only bridge between this spec and the brief's mandated `tests/` directory, and the evidence for evaluation criterion 10 *"Testing approach"* |
| §10 Out of scope / black boxes | HLD §10 Black boxes · README §Assumptions |
| §11 Assumptions | **HLD §3 Assumptions · README §Assumptions** — the brief mandates a section named *Assumptions* in **both** deliverables, so this section has two obligatory destinations, not one |
| §12 Lifecycle impact · §13 Approval | **Nothing — deliberately.** These are workflow records, not design content. They are named here so that "nothing is stranded" stays a checkable claim rather than an unexamined one. |
| Decision log DEC-1…DEC-22 | HLD §8 Major design decisions · README §Design Decisions |

The table is read in **both** directions. Forwards it answers *“where does this spec
section go?”*; backwards it answers *“what feeds this deliverable section?”* The second
reading is the one that catches a mandated deliverable heading nobody is scheduled to
write. Checking it that way is what added **LLD §1**, **LLD §3** and **LLD §7
Transaction boundaries** above — the last being both a named LLD mandate *and* NFR4, with
rich source material already in §7 and §8 that no row had routed anywhere.

Four of those rows were added by the second audit pass. The table previously stopped at
§8 while the sentence above it claimed **every** section had a destination — a claim the
table did not support. The omission mattered most for **§11**, because *Assumptions* is
one of the few things the brief requires **twice**, in two different deliverables; a
section with two obligatory destinations and no recorded destination at all is the
easiest kind to under-write.

The rule is one-directional: **the spec is the source, the deliverables are the
rendering.** When the two disagree, the spec is corrected first and the deliverable
re-derived — otherwise the reasoning and the artifact drift apart, which is precisely
the failure this audit keeps finding.

#### 2.3.1 The README is the only deliverable specified with *"must"*

The brief's modal verbs across the five deliverables are not uniform, and the difference
is worth acting on:

| Deliverable | Brief's wording |
|---|---|
| 1. HLD | *"The HLD **should** include:"* |
| 2. LLD | *"**Describe:**"* |
| 3. Backend Implementation | *"**should be executable** with clear setup instructions"* |
| 4. Database Schema | *"**Provide:**"* |
| 5. README | *"The README **must** include:"* |

Only the README gets **must**, and it is then given the most granular structure in the
whole document — six headings, of which three are decomposed into **thirteen** named
sub-points. That combination makes it the deliverable where omissions are **mechanically
detectable**: a reviewer does not need to form a judgement, only to scan for a phrase and
fail to find it.

The README therefore uses the brief's own words as headings, verbatim, in the brief's
order:

| # | README heading (brief's words) | Sub-points | Source |
|---|---|---|---|
| 1 | **Setup Instructions** | Dependencies · Build · Run · Database initialization | DEC-16, `docker-compose.yml` |
| 2 | **Design Decisions** — *"Explain major architectural choices"* | — | `decision-log.md` (see below) |
| 3 | **Search Algorithm** — *"Describe how:"* | 4 | §8.1 |
| 4 | **Booking Algorithm** — *"Describe:"* | 5 | §8.2 |
| 5 | **Cancellation Algorithm** — *"Describe:"* | 4 | §8.3 |
| 6 | **Assumptions** — *"List any assumptions made"* | — | §3 (AMB-1…AMB-12) · §11 |

**17 named items total** — 4 under Setup, 13 under the three algorithms. Each is a
checkbox a reviewer can tick, so the cost of missing one is deterministic rather than
a matter of taste.

Two readings that change what gets written:

- **"Database initialization" is listed separately from "Run".** With Compose and Flyway
  the schema is created automatically *during* Run, so the honest-looking answer is
  "nothing to do". That answers the question by dismissing it. The reviewer is asking
  what creates the schema, what seeds airports and aircraft, how to reset it, and how to
  tell it worked — all real questions even when the answer is one command.
- **"Transaction flow" appears twice** — once under Booking, once under Cancellation.
  The DRY instinct is to write it once and cross-reference. Here that instinct is wrong:
  they are genuinely different transactions with different boundaries, different write
  orders and different failure modes (§8.2.3 vs §8.3.3). The brief lists it twice because
  there are two of them.

### 2.3.2 Repository structure — compliance and deviations

The brief's *Expected Repository Structure* is drawn **language-agnostically** — the same
tree is offered to candidates choosing Java, Go, Python, Node.js or Rust. Parts of it
therefore collide with the conventions of any specific toolchain. The rule applied here:

> **Comply literally wherever compliance is cheap. Deviate only where compliance would
> break the build tool, and then say so in the repository itself**, at the path where a
> reviewer looks for the missing thing.

| Brief path | Status | Detail |
|---|---|---|
| `README.md` | ✅ literal | — |
| `hld/` | ✅ literal | — |
| `hld/architecture.pdf` | 🟡 **deviation** | `hld/architecture.md` is the source of truth; PDF is a rendered copy (DEC-17) |
| `hld/diagrams/` | ✅ literal | Mermaid sources (DEC-17) |
| `lld/design.md` | ✅ literal | — |
| `src/` | ✅ literal | Maven Standard Layout nests beneath it — `src/main/java`, `src/main/resources` |
| `db/schema.sql` | ✅ literal | Consolidated snapshot, generated from the migrations |
| `db/migrations/` | ✅ **literal — and wired** | See below; this one was worth engineering for |
| `tests/` | 🟡 **deviation** | Automated tests live in `src/test/java`; `tests/` holds a `README.md` explaining why |
| `docker-compose.yml` | ✅ literal | Marked *(optional)* in the brief; provided |

#### `db/migrations/` — complied with literally rather than waved away

This is the one place where literal compliance required real work instead of a note, and
it was worth doing because `db/migrations/` is a **named deliverable path** (Deliverable 4
asks for *"SQL schema or migration scripts"*), not incidental scaffolding.

Flyway's default location is `classpath:db/migration`, which in a Maven project means
`src/main/resources/db/migration` — **four levels away from where the brief says to look.**
The three obvious resolutions are all bad:

| Option | Why rejected |
|---|---|
| ❌ Point Flyway at `filesystem:db/migrations` | Works from a checkout, **fails inside the jar and inside the Docker image** — the path is not on the filesystem there. Breaks the one binding requirement of Deliverable 3 |
| ❌ Symlink `db/migrations` → `src/main/resources/db/migration` | Fragile across platforms and confusing in `git diff`; a reviewer on Windows sees a broken file |
| ❌ Keep migrations in `src/`, leave `db/migrations/` empty with a note | Two places to look, and the brief's named path contains nothing |

✅ **Chosen: Maven copies the brief's directory onto Flyway's classpath location.**

```xml
<resource>
  <directory>db/migrations</directory>
  <targetPath>db/migration</targetPath>
</resource>
```

The migrations physically live at `db/migrations/` exactly as the brief draws them; Maven
places them at `db/migration` inside `target/classes`, where Flyway's unmodified default
finds them. This works identically in a checkout, in the jar, in the Docker image and
under Testcontainers, because all four resolve the **classpath**, not the filesystem.
**Verified**, not assumed: a probe migration placed in `db/migrations/` was confirmed at
`target/classes/db/migration/` after `mvn process-resources`.

One guard comes with it. `src/main/resources` is also a resource root, so if a migration
were ever dropped into `src/main/resources/db/migration` **both roots would write to the
same classpath path** and Maven's copy order — not any declared intent — would decide
which set survives. Silent, and invisible in `git diff`. The first root therefore
**excludes** `db/migration/**`, making "one source of migrations" a property the build
enforces rather than a convention someone must remember. The empty
`src/main/resources/db/migration` scaffold directory was deleted for the same reason.

#### `tests/` — the one deviation that is not worth engineering around

Compliance here is technically possible (`<testSourceDirectory>`), and it was rejected.
Moving tests out of `src/test/java` fights Surefire, Failsafe, JaCoCo, every IDE's default
project import and every Java reviewer's expectation — all to satisfy a directory name in
a tree that was drawn for five languages at once. Unlike `db/migrations/`, `tests/` is
**not** a named deliverable path: no deliverable mentions it, and Deliverable 3 asks only
that the implementation be executable.

The deviation is instead made **discoverable at the point of confusion**: `tests/README.md`
states where the automated tests are and why, so a reviewer who opens `tests/` expecting
tests is answered immediately rather than concluding there are none.

### 2.4 Explicit Scope Matrix

The brief states: *"This assignment intentionally limits its scope. Any functionality
not explicitly mentioned should be considered out of scope and treated as a black box."*
Scope is therefore a **requirement**, not a preference. Below, every in-scope capability
is paired with what must be handled to call it done.

| Capability | Scope | What must be handled |
|---|---|---|
| Airport data | **IN** (seeded only) | 4 mandated fields; seeded via migration; IATA code as the identifier used by search |
| Airport CRUD | **OUT** | No controller, no service, no DTO |
| Aircraft data | **IN** (seeded only) | ID, type, rows, seat letters; seat map immutable |
| Aircraft CRUD | **OUT** | No controller |
| Schedule creation | **IN** | All 7 fields validated; days of operation non-empty; airports differ; refs exist; flight-number/weekday uniqueness |
| Schedule update / delete | **OUT** | Brief: *"Flight schedules do not change after creation"* |
| Flight instance generation | **IN** | Rolling 365-day horizon; approach documented (DEC-1) |
| Flight search | **IN** | All 3 params mandatory; weekday match; horizon validation; 4 response fields exactly |
| Search sort / filter / paginate | **OUT** | Brief: *"Sorting and filtering beyond this are out of scope"* |
| Seat map | **IN** | Every label from aircraft config; exactly 2 states; real-time |
| Booking | **IN** | Multi-seat; PNR; passenger name per seat; 6 response fields exactly |
| Seat holds / temporary reservation | **OUT** | FR6 defines **two** seat states only |
| Booking constraints | **IN** | Double-booking prevented; concurrency documented and **tested** |
| Cancellation | **IN** | Status change + seat release + rebookable |
| Partial cancellation | **OUT** | FR9 cancels *a booking*, not individual seats |
| Refunds / payments | **OUT** | Brief: *"Refund processing is out of scope"* |
| Multi-leg / round-trip | **OUT** | Brief: *"One booking is for a single flight instance"* |
| Multiple airlines | **OUT** | Brief: *"Single airline only"* |
| Fares, classes, baggage, check-in | **OUT** | Never mentioned |
| Customer accounts / auth | **OUT** (black box) | No user model; admin auth documented, not built |
| Time-zone conversion | **OUT** | Brief states it explicitly |

---

### 2.5 The ten Evaluation Criteria

The brief contains **two different lists of what is being assessed**, 250 lines apart,
and they are not the same list restated:

- the **Objective**'s eight *evaluation axes* (§2.0.2), and
- the closing **Evaluation Criteria** — *"Candidates will be evaluated on:"* — ten items.

Only **three** items appear verbatim in both: *API design*, *Database design*,
*Concurrency handling*. A candidate who reads the Objective and assumes the closing list
mirrors it misses **four criteria that are graded and never mentioned up front**.

| # | Criterion (brief's words) | In the Objective? | Where it is earned |
|---|---|---|---|
| 1 | **Correctness of functionality** | ❌ **Not an axis** | §9 Acceptance Criteria · integration tests · a `docker compose up` that works |
| 2 | API design | ✅ identical | §5.1 contracts · §5.2 status rule · DEC-11, DEC-15 |
| 3 | Database design | ✅ identical | §4 normative DDL · §4.8 indexes · `db/er-diagram.md` |
| 4 | Code organization | 🟡 *"Backend architecture"* | NFR1/NFR2 · package-by-feature · ArchUnit (DEC-10) |
| 5 | **Scalability considerations** | ❌ **Not an axis** | HLD §9 Scale-out — see below |
| 6 | Concurrency handling | ✅ identical | §7 in full · §8.2 · CT-1…CT-10 · DEC-2 |
| 7 | Documentation quality | 🟡 *"Documentation"* | HLD · LLD · README's 17 mandated items (§2.3.1) |
| 8 | **Design decisions** | 🟡 implicit only | `decision-log.md` → HLD §8 → README (DEC-18) |
| 9 | **Readability and maintainability** | 🟡 half of *"Code quality"* | Naming · small methods · Spotless · no clever code |
| 10 | **Testing approach** | ❌ **Not an axis** | §9 · CT-1…CT-10 · LLD §10 test strategy |

Three readings follow from the differences, and each changes what gets built.

**1. *Correctness of functionality* is listed first, and is absent from the Objective.**

The Objective is framed entirely around demonstrating *skill*; the grading list opens
with whether the thing works. That ordering is a corrective worth taking literally: an
elegant design that does not run fails the **first** criterion, and no amount of
documentation compensates. It is also the direct justification for having smoke-tested
`docker compose up` before writing application code (DEC-16) rather than after.

This sits in tension with the working assumption that HLD and LLD outrank the
implementation. Both can hold — the *submission* is graded on correctness while the
*discussion* centres on design — but the tension is real and is recorded rather than
resolved by preference: **a non-running implementation fails criterion 1 regardless of
how good the first two deliverables are.**

**2. *Scalability considerations* is graded, yet NFR9 says performance optimization is
not required. The reconciling word is "considerations".**

| | |
|---|---|
| What is graded | **Reasoning** about scale — where this design breaks, what the next bottleneck is, what one would change and in what order |
| What is **not** graded | Implemented scaling machinery — sharding, replicas, caches, queues |

Building that machinery would violate the scope discipline the Objective demands
(*"Any functionality not explicitly mentioned should be considered out of scope"*).
Omitting the **discussion** forfeits a named criterion. The two are reconciled by putting
scale-out entirely in **HLD §9** as analysis, with an explicit statement of what was
deliberately not built and why. This is the one place where thinking about the system as
a distributed one is not scope creep — it is a graded requirement, discharged in prose.

Concretely, HLD §9 must name the real limits of *this* design: a single
`uq_booking_seat_active` index is the serialisation point for a flight, so contention is
per-flight rather than global; `flight_instance` grows with bookings, not with the
calendar; search is one indexed query with no fan-out; and the first thing to break under
load is neither of those but the connection pool (which is exactly what DEC-13's
transaction topology protects).

**3. *Testing approach* is graded and is not an Objective axis — and it is not NFR8.**

| | |
|---|---|
| NFR8 **Testability** | A property of the *code* — can it be tested? Constructor injection, no static state, no `new` on collaborators. Asserted by ArchUnit |
| Criterion 10 **Testing approach** | A property of the *submission* — what was tested, at which level, and why those choices |

Coverage percentage answers neither. The approach to document is the shape of the
pyramid: pure unit tests for label derivation and PNR generation; slice tests for
controller contracts and status mapping; Testcontainers integration tests for anything
touching a constraint, because an H2 run cannot execute a partial unique index; and
CT-1…CT-10 as genuine concurrency tests rather than repeated single-threaded calls.

**4. *Algorithmic thinking* runs the other way** — it is an Objective axis with **no
corresponding criterion**. It is graded through Deliverable 5 instead, whose three
algorithm headings carry 13 mandated sub-points. §8 only earns those marks once it is
rendered into the README; left in this document it is invisible (§2.3).

## 3. Resolved Ambiguities

**Twelve** ambiguities were found in the brief — including **two places where the brief
contradicts itself** (AMB-1, AMB-12) and **two where it relies on a guarantee it never
states** (AMB-8, AMB-10). Each resolution below is a **binding decision** for the
implementation. Alternatives considered and rejected are recorded in `decision-log.md`.

### AMB-1 — Time semantics *(contradiction inside the brief)*

The brief's own example is self-contradicting:

> *XY101 · Dubai → London · Departs **09:30** · Arrives **13:45***

Dubai→London is a ≈7h45m flight, but 09:30→13:45 is 4h15m of wall clock. The
difference is exactly the 3h30m Dubai/London offset. No other reading accounts for
that arithmetic, so **the example is only consistent with these being
local times at each airport**, yet the brief's assumptions state *"All timestamps
should be stored in UTC"* and *"Time zone conversion is out of scope"*.

**Resolution.** `departure_time` and `arrival_time` are **times of day** (`TIME`), not
timestamps, so *"store timestamps in UTC"* applies to audit **instants**
(`created_at`, `updated_at`) — which are `TIMESTAMPTZ` in UTC — and not to schedule
times. **No timezone column is added** and **no conversion is ever performed**, which
honours *"time zone conversion is out of scope"*.

Consequently **"operates Monday" means Monday at the departure airport**.

### AMB-2 — Derived conflict: there is no instant to compare against

AMB-1 (no timezone data) makes it **impossible to compute a flight's true departure
instant**. Yet AMB-4 and AMB-5 below both require comparing "now" to a departure.

**Resolution.** Define one explicit seam:

```
departureInstant(flightDate) = flightDate.atTime(departureTime).toInstant(SYSTEM_REFERENCE_ZONE)
```

where `SYSTEM_REFERENCE_ZONE = UTC`. This is **deliberately approximate** — it can be
wrong by at most the airport's UTC offset — and that limitation is stated in the
README under Assumptions rather than hidden.

It is isolated in a **single domain method**, so adding real per-airport time zones
later changes exactly one method body plus one column. This is the extension seam
that keeps the design honest without importing scope the brief excluded.

### AMB-3 — Schedule validity window

The brief lists exactly **seven** schedule fields; a validity window is not among
them, and the assumptions state *"Flight schedules do not change after creation"*.

**Resolution.** **No `valid_from` / `valid_to`.** Schedules are open-ended within the
365-day booking horizon. *(This reverses an earlier draft decision — it was scope the
brief never asked for.)*

### AMB-4 — Same-day flights that have already departed

The booking window is defined only at date granularity.

**Resolution.** A flight instance is bookable iff
`now < departureInstant(flightDate)` **and** `flightDate < today + 365 days`.
A flight departing earlier today is **not** bookable (`409`), using the AMB-2 seam.

### AMB-5 — Cancelling a booking for a departed flight

FR9 is silent.

**Resolution.** **Rejected** with `409 FLIGHT_ALREADY_DEPARTED`, using the AMB-2 seam.
A seat on a flown flight has no resale value, so releasing it would be meaningless.

### AMB-6 — Booking reference format

The brief permits *"PNR or UUID"*.

**Resolution.** **6-character PNR** over the alphabet
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789` — ambiguous glyphs `I`, `O`, `0`, `1` excluded so
it is safe to read aloud or print on a boarding pass. Generated from
`SecureRandom`, uniqueness guaranteed by a `UNIQUE` constraint with bounded retry.
A UUID would be correct but is not a realistic airline artifact.

### AMB-7 — Listing flight schedules

FR3 mandates only **create**.

**Resolution.** `GET /api/v1/admin/flight-schedules` is **included**. It is a trivial read
that makes the system demonstrable end-to-end without database access, and it is
required for a reviewer to exercise search meaningfully. Explicitly noted as a
deliberate, minimal addition beyond the letter of the brief.

One further detail is recorded but **deliberately not leaned on**: the brief's noun is
plural — *"administrative **APIs** to create flight schedules"* — while it names exactly
one operation. That is at best weak evidence, since the plural is equally explicable as
loose phrasing, and reading a second endpoint out of a noun's inflection would be the
kind of over-reading this specification otherwise refuses. The justification above
stands on demonstrability alone; the plural merely means the addition does not sit
*against* the text.

### AMB-8 — Passenger cardinality *(gap: brief implies, never states)*

FR7 says *"Select **one or more seats**"*, returns *"**Passenger Count**"* and
*"**Assigned Seats**"*, and states *"Passenger information may be minimal: **Name**"*.
Taken together, **a booking with N seats has N passengers, each with a name** — but
the brief never says so explicitly, and `Passenger Count` is a mandatory response
field with no stated source.

**Resolution.** `passenger_name` lives on **`booking_seat`**: one row = one passenger
occupying one seat. `passengerCount` is therefore **derived** as the number of
`booking_seat` rows for the booking, never stored. Passenger count and seat count are
the same number by construction and cannot drift apart.

A separate `passenger` table was rejected: with a name as the only attribute it would
be a 1:1 satellite of `booking_seat` adding a join for no information.

### AMB-9 — "Available Seats" — count or list?

FR5 lists *"Available Seats"* as a search result field without specifying a shape.

**Resolution.** An **integer count**. FR6 already exposes the full seat map on its own
endpoint; returning labels in search would duplicate that payload for every result
row. Search answers *"is there room?"*; the seat map answers *"which seat?"*.

### AMB-10 — Flight number uniqueness *(gap: brief is silent, and it matters)*

The brief never constrains flight numbers. This is not cosmetic: FR5 specifies that
search returns **Flight Number** — not an identifier — so `flightNumber + date` is the
**only** handle a customer ever holds. If two schedules shared a number on the same
weekday, `GET /api/v1/flights/XY101/seat-map?date=…` would have two valid answers and the
booking API would be ambiguous.

**Resolution.** `UNIQUE (flight_number, day_of_week)` on `flight_schedule_day`. A
number may serve different routes on different weekdays (as real airlines do), but
never twice on the same day — which is exactly the constraint that makes
`flightNumber + date` a sound business key. **The brief's own API contract requires
this constraint to exist; it simply never says so.**

### AMB-11 — Booking status values

FR7 requires *"Booking Status"* in the response but never enumerates the values.

**Resolution.** `CONFIRMED | CANCELLED` — the minimum the brief's own lifecycle needs
(FR7 creates, FR9 cancels). No `PENDING` state, because there is no payment step; no
`EXPIRED`, because seat holds are out of scope.

### AMB-12 — "365 days" vs "one year" *(the brief contradicts itself)*

Three statements, two different numbers:

| Location | Wording |
|---|---|
| Scope | *"bookings for up to **one year** from the current date"* |
| FR4 | *"bookable flight instances for **365 days** from the current date"* |
| Assumptions | *"Booking window extends up to **one year** from the current date"* |

Across a leap year these differ: from 2027-03-01, one calendar year is **366** days.

**Resolution.** **365 days**, externalised as `airline.booking.window-days`. FR4 is the
only place the brief states a precise, testable number, and a fixed day count gives
deterministic boundary tests. The alternative reading is a **one-line property change**.
See DEC-9.

**The boundary is half-open**, and this is the second decision hiding inside the first:

```
today ≤ flightDate < today + 365 days          →  exactly 365 bookable dates
```

An earlier draft wrote the interval as `[today, today + 365 days]` **inclusive**, which
contains **366** dates — it fails to implement the very number it had just finished
arguing for. Stating the window as half-open removes the class of error rather than
fixing one instance of it: the bound and the count are then the same literal, so they
cannot drift. *"365 days from the current date"* is read as a 365-day **duration**
beginning today, not as the single point `today + 365`.

Boundary tests assert all three points explicitly: `today` → accepted,
`today + 364` → accepted (last bookable date), `today + 365` → rejected `422`.

---

## 4. Domain Model

Seven tables. Nothing is stored that can be correctly derived.

**Which columns are allowed to exist at all.** FR2 and FR3 both say a thing
*"contains"* its fields, with no *"at least"* (§4.2), so their field lists are read as
closed. Yet `flight_schedule` carries `id` and `created_at`, which the brief never
names. The distinction being applied is between a **domain field** and an
**infrastructure column**:

| | Domain field | Infrastructure column |
|---|---|---|
| Examples | `flight_number`, `departure_time`, `seat_letters` | `id`, `created_at` |
| Carries business meaning? | Yes | No |
| Appears in an API response? | Yes | **Never** |
| Governed by the closed list? | **Yes** | No |

A hypothetical `aircraft.manufacturer` would be domain data the brief did not ask for,
and is therefore excluded. A surrogate key and a row-creation timestamp assert nothing
about the airline's business; they are how a row is addressed and when it was written.
The test applied throughout §4 is the third line of that table: **no column absent from
the brief's field list is ever surfaced through the API.** `createdAt` appears in no
response body in §5.1 — which is what keeps this a modelling convenience rather than a
quiet extension of the brief's data model.

The DDL below is **normative** — it is the contract `db/schema.sql` and the Flyway
migration must satisfy. Every constraint named here is referenced by an error-handling
rule in §5.2 or a concurrency guarantee in §7, so constraint **names are a stable API**,
not incidental.

### 4.0 Migration set

Deliverable 4 asks for *"SQL schema or migration scripts"* and names `db/migrations/`.
The DDL in this section is the **specification**; these files are its only
implementation, and the set is fixed so that "which file does this table live in?" has
one answer.

| File | Contents | Why separate |
|---|---|---|
| `V1__baseline_schema.sql` | All seven tables of §4.1–§4.7, every `CHECK`, every FK, and all six indexes of §4.8 — including `uq_booking_seat_active` | Structure. One baseline rather than a table-per-file set: nothing here predates the first release, so a seven-file "history" of a schema that never evolved would be fiction |
| `V2__seed_reference_data.sql` | The four airports (§4.1) and the aircraft (§4.2) | Reference **data**, not structure. Separate because it is the only file a reviewer may legitimately want to edit — to demo a different route — and editing it must not mean editing the schema |

**`db/schema.sql` is generated from these, never hand-maintained.** It exists because
Deliverable 4 asks for a schema file *and* migrations, and two hand-written copies of the
same DDL diverge the moment one is edited. The direction is one-way: migrations are the
source, `schema.sql` is a snapshot for reading, and it carries a header saying so.

**Flyway numbering starts at `V1`, and there is no `V0`.** Stated because this spec
previously named only `V2__seed_reference_data.sql`, which silently implied a `V1` that
nothing defined — an implementer would have had to invent the schema file's name, and the
one artefact every other artefact depends on would have been the one left unspecified.

#### 4.0.1 Concurrent startup across `N` instances — discharges D5

Under §2.0.4 the service runs as `N ≥ 2` instances, and a rolling deployment starts them
**simultaneously**. Every one of them runs Flyway against the *same* database at the same
moment. The naive expectation — that `V1` is applied twice, or that two instances race to
create `uq_booking_seat_active` — would make startup non-deterministic.

**It does not happen, and the reason is stated rather than assumed.** Flyway acquires a
lock before migrating: on PostgreSQL it takes a transactional `SELECT … FOR UPDATE` on
`flyway_schema_history` (falling back to a session-level advisory lock for the initial
table creation). Exactly one instance migrates; the rest **block** until it commits, then
observe the schema as already current and proceed to serve traffic.

Three consequences follow, and all three are design constraints rather than trivia:

| Consequence | Requirement |
|---|---|
| Startup time is bounded by the **slowest** migration, for every instance | Migrations must stay fast; no data backfill in `V1`/`V2`. Both files here are DDL plus four seed rows |
| A failed migration fails **every** instance, not one | Correct behaviour — a half-migrated schema must not serve traffic. No instance may be configured to continue past a Flyway error |
| The readiness probe must not pass while an instance is still waiting on the lock | `baseline-on-migrate` stays off and migration runs **before** the context reports ready, so the load balancer cannot route to an instance whose schema is not yet current |

This is the one place in the design where instances genuinely coordinate — and the
coordination is **supplied by PostgreSQL**, not built. Consistent with D2, no application
code elects a leader.

### 4.1 `airport` — FR1.3

```sql
CREATE TABLE airport (
    iata_code CHAR(3)      PRIMARY KEY,
    name      VARCHAR(120) NOT NULL,
    city      VARCHAR(80)  NOT NULL,
    country   VARCHAR(80)  NOT NULL,
    CONSTRAINT ck_airport_code CHECK (iata_code ~ '^[A-Z]{3}$')
);
```

IATA code is the natural primary key: it is stable, externally meaningful, and it is
what the search API accepts, so a surrogate key would buy an extra join and nothing
else. Codes are **stored and compared upper-case**; the API upper-cases on input.
Seeded via Flyway (`V2__seed_reference_data.sql`); CRUD is out of scope.

**Seed set** — fixed and committed, because the README's worked example, the integration
tests and the demo all depend on the same identifiers being present:

| Code | Name | City | Country |
|---|---|---|---|
| `DXB` | Dubai International | Dubai | United Arab Emirates |
| `LHR` | London Heathrow | London | United Kingdom |
| `BOM` | Chhatrapati Shivaji Maharaj Intl | Mumbai | India |
| `SIN` | Singapore Changi | Singapore | Singapore |

`DXB` and `LHR` are not arbitrary — they are the pair in the brief's own FR3 example, so
the documentation can be demonstrated against the brief's scenario verbatim. `BOM` and
`SIN` exist so that search can be shown returning **empty** for a valid route with no
schedule, which is a distinct case from an unknown airport (`404` vs `200 []`, §5.2).

**The `timezone` column that is not here.**

FR1 says each airport must have *"**at least**"* those four fields — "at least" is
permissive, so additional columns are explicitly allowed. An `airport.timezone` column
holding an IANA zone (`Asia/Dubai`, `Europe/London`) would be a legal addition, and it
would **completely dissolve AMB-1 and AMB-2**: with a zone per airport, a schedule's
local `departure_time` and a `flight_date` compose into an unambiguous
`ZonedDateTime`, so the "has this flight departed?" test (BR-7, BR-14) becomes exact
instead of approximate, and the brief's instruction to store timestamps in UTC becomes
literally satisfiable rather than reinterpreted.

**It is deliberately not added.** The Assumptions state *"Time zone conversion is out of
scope"* — unconditionally, and that is the more specific instruction. Adding the column
would require conversion logic to be of any use, so taking the permission in FR1 would
mean violating the exclusion in Assumptions. Where two clauses of the brief point in
opposite directions, the explicit exclusion wins over the permissive "at least".

The cost is carried openly: BR-7 and BR-14 compare against a UTC-referenced instant and
are therefore wrong by at most one airport offset (≤14h). The consequence is bounded and
stated in AMB-2 — a same-day flight may be judged departed slightly early or late. This
is the single highest-value extension point in the schema: **one nullable column plus
one `ZoneId` lookup** converts every approximation in this design into an exact answer,
with no table, API or index changing shape. Recorded as **DEC-8**.

### 4.2 `aircraft` — FR2.3, FR2.4

```sql
CREATE TABLE aircraft (
    id           VARCHAR(16) PRIMARY KEY,   -- e.g. 'A320-001'
    type         VARCHAR(40) NOT NULL,      -- e.g. 'Airbus A320'
    rows_count   SMALLINT    NOT NULL,
    seat_letters VARCHAR(10) NOT NULL,      -- e.g. 'ABCDEF'
    CONSTRAINT ck_aircraft_rows    CHECK (rows_count BETWEEN 1 AND 99),
    CONSTRAINT ck_aircraft_letters CHECK (seat_letters ~ '^[A-Z]{1,10}$')
);
```

**Seats are derived, never stored.** A `seat` table would hold 180 immutable rows per
aircraft fully determined by two columns, and FR2.4 fixes the seat map. Labels are
generated as `row || letter`, rows ascending then letters in the order given by
`seat_letters`.

`rows_count ≤ 99` is not arbitrary: it is the exact bound that keeps every generated
label inside `booking_seat.ck_seat_label` (§4.6). The two constraints are deliberately
co-designed, so an aircraft that could generate an unstorable label cannot be seeded.

**Not enforceable in DDL:** `seat_letters` must contain no duplicates. A regex cannot
express this. Enforced in the `Aircraft` domain object's constructor and asserted by a
seed-validation test — recorded here rather than left implicit.

**Seed set** — the first row is the brief's own example, verbatim:

| ID | Type | Rows | Letters | Seats |
|---|---|---|---|---|
| `A320-001` | Airbus A320 | 30 | `ABCDEF` | 180 |
| `B738-001` | Boeing 737-800 | 32 | `ABCDEF` | 192 |
| `AT72-001` | ATR 72 | 18 | `ABCD` | 72 |

`A320-001` reproduces *"Aircraft A320 / Rows: 30 / Seats: A B C D E F"* exactly, so the
worked example in the README is the brief's example and nothing has to be translated.
`AT72-001` has **four** letters rather than six, which is what makes the seat-label
derivation demonstrably driven by configuration instead of hard-coded to `A–F` — a
six-letter-only seed set would let an `A..F` constant pass every test.

**FR1 and FR2 do not grant the same licence, and the difference is one word.** FR1 says
each airport *"should have **at least**"* its four fields — an open list, which is what
made the `timezone` discussion of §4.1 a real option to weigh. FR2 says each aircraft
*"**contains**"* its three. There is no "at least", so the aircraft field list is read as
**closed**: a `manufacturer`, `cabin_class` or `registration` column would be an
unlicensed addition, however natural it looks on an aircraft table.

That distinction is what the next decision has to answer to. This design stores **four**
columns for FR2's **three** fields, and the defence is that `rows_count` + `seat_letters`
is a *decomposition* of the single named field *"Seat Configuration"* — faithful to the
brief's own example, which itself gives that field as two numbers (*"Rows: 30"*,
*"Seats: A B C D E F"*) — and not a fourth field smuggled in. Every column traces to a
brief-named field; none is additive.

**Why "Seat Configuration" became two columns.** FR2 lists three fields, and the third
— *"Seat Configuration"* — is a single named field that this design stores as
`rows_count` + `seat_letters`. That is a modelling decision, so it is recorded rather
than assumed:

| Option | Verdict | Why |
|---|---|---|
| One opaque string (`"30xABCDEF"`) | ❌ | Preserves the brief's field count and nothing else. Needs parsing on every read, is unqueryable, and pushes validation into a regex. |
| JSON / JSONB seat-map document | 🟡 | Genuinely more extensible — it would carry non-rectangular cabins, exit rows and classes. **Rejected as scope creep**: FR2's model is explicitly rectangular (*rows × letters*), and nothing in the brief needs a per-seat attribute. Building for cabins the brief never mentions would violate the Objective's scope clause. |
| **`rows_count` + `seat_letters`** | ✅ | Two typed, checkable, queryable columns that express exactly the brief's model — *"Rows: 30"* and *"Seats: A B C D E F"* — with no parsing. Capacity is `rows_count × length(seat_letters)`, computable in SQL. |

**What this model deliberately cannot represent**, because the brief does not ask for it:
non-rectangular cabins, missing seats in exit rows, seat classes, per-seat attributes.
The extension seam is clean: such layouts would arrive as a child `aircraft_seat` table
keyed by `(aircraft_id, label)`, and **nothing else in the design would move** — seat
labels are already produced by a single derivation point (§2.0.1), so replacing the
derivation with a lookup is a one-class change. Recorded as declined scope, not as an
oversight.

**Seat label ordering is row-major**, rows ascending and letters in the order given by
`seat_letters` — which is what FR6's example `1A, 1B, 1C … 30F` specifies. The order is
therefore part of the contract, not an incidental property of iteration.

#### 4.2.1 Immutability is what makes derivation safe at `N ≥ 2`

FR2 says the seat map *"remains fixed for an aircraft"* and puts aircraft CRUD out of
scope. Read as a convenience, that is a simplification. Read against §2.0.4, it is a
**correctness property**:

Every instance derives seat labels independently, from its own heap, with no coordination
of any kind. That is only safe because the inputs — `rows_count` and `seat_letters` — can
never change. Two instances computing `1A…30F` at the same moment cannot disagree,
because there is no write that could make them disagree. Had aircraft been mutable, the
same derivation would be a **per-instance read of racing state**: instance A could render
a 30-row seat map while instance B renders 32 rows, and a seat label valid on one would be
rejected by the other.

**This is also the one data category that D3 does *not* forbid caching.** D3 bars caching
of *booking* state because `N` instances would hold `N` divergent views. Airport and
aircraft rows are immutable within a deployment, so a per-instance cache of them could
never diverge — caching them would be legitimate.

**It is still not done.** At DEC-20's ~6 QPS, the aircraft row arrives on the same join
that search already performs (§7.9), so a cache would remove a cost that is not being
paid. Recorded because the distinction matters: *the rule against caching is about
mutability, not about caching*, and applying it to immutable reference data would be
cargo-culting it. If search later became hot enough to matter, reference data is the
**first and safest** thing to cache, and the only thing cacheable without an invalidation
protocol.

### 4.3 `flight_schedule` — FR3.2, FR3.3

```sql
CREATE TABLE flight_schedule (
    id               BIGSERIAL   PRIMARY KEY,
    flight_number    VARCHAR(8)  NOT NULL,
    origin_code      CHAR(3)     NOT NULL REFERENCES airport (iata_code) ON DELETE RESTRICT,
    destination_code CHAR(3)     NOT NULL REFERENCES airport (iata_code) ON DELETE RESTRICT,
    departure_time   TIME        NOT NULL,
    arrival_time     TIME        NOT NULL,
    aircraft_id      VARCHAR(16) NOT NULL REFERENCES aircraft (id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_schedule_route  CHECK (origin_code <> destination_code),
    CONSTRAINT ck_schedule_number CHECK (flight_number ~ '^[A-Z0-9]{2,8}$'),
    CONSTRAINT uq_schedule_id_number UNIQUE (id, flight_number)
);
```

`departure_time`/`arrival_time` are `TIME` — **times of day, not instants** (AMB-1).
There is deliberately **no** `arrival_time > departure_time` check: an overnight flight
is legitimate (BR-4).

`uq_schedule_id_number` is redundant as a uniqueness claim (`id` is already the PK). It
exists **solely** to be a valid target for the composite foreign key in §4.4 —
PostgreSQL requires a FK to reference a unique constraint covering exactly the
referenced columns. This is the mechanism that makes the flight-number invariant
enforceable at all.

### 4.4 `flight_schedule_day` — FR3.3, DI-1

```sql
CREATE TABLE flight_schedule_day (
    schedule_id   BIGINT     NOT NULL,
    flight_number VARCHAR(8) NOT NULL,
    day_of_week   SMALLINT   NOT NULL,
    PRIMARY KEY (schedule_id, day_of_week),
    CONSTRAINT fk_day_schedule FOREIGN KEY (schedule_id, flight_number)
        REFERENCES flight_schedule (id, flight_number) ON DELETE CASCADE,
    CONSTRAINT ck_day_range        CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT uq_flight_number_day UNIQUE (flight_number, day_of_week)
);
```

Days of operation are a child table rather than a CSV, a bitmask or seven booleans, so
they are indexable, joinable and readable in SQL (DEC-6). `day_of_week` uses ISO-8601
numbering (1 = Monday … 7 = Sunday), matching `java.time.DayOfWeek.getValue()`, so no
translation layer exists to get wrong.

**`flight_number` is denormalised into this table on purpose.** `uq_flight_number_day`
is the constraint that makes `flightNumber + date` a valid business key for the entire
API (DI-1, DEC-7), and a unique index cannot span two tables. Denormalisation is the
only way to express it declaratively — and the **composite** FK on
`(schedule_id, flight_number)` makes drift from the parent physically impossible, so
the copy cannot become stale.

> **Prior defect, recorded deliberately.** An earlier draft placed
> `UNIQUE (flight_number, day_of_week)` directly on this table while its columns were
> only `(schedule_id, day_of_week)`. That constraint is **not creatable**. Without it,
> two schedules may share `XY101` on a Monday, each materialising its *own*
> `flight_instance`, so the §7 index — scoped to `flight_instance_id` — would permit
> seat `12A` to be sold twice on one physical departure. The composite-FK form above is
> the fix. Kept visible because the failure mode is the interesting part.

### 4.5 `flight_instance` — DEC-1

```sql
CREATE TABLE flight_instance (
    id          BIGSERIAL   PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES flight_schedule (id) ON DELETE RESTRICT,
    flight_date DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_instance UNIQUE (schedule_id, flight_date)
);
```

Materialised **lazily, on first booking only** (DEC-1). It exists to be the row a seat
booking references — not to enumerate the calendar. Search never reads this table.

**There is no `version` column.** An earlier draft carried `@Version` here as a
"consistency layer"; §7 explains why that was removed rather than kept as decoration.

### 4.6 `booking` — FR7.2

```sql
CREATE TABLE booking (
    id                 BIGSERIAL   PRIMARY KEY,
    pnr                CHAR(6)     NOT NULL,
    flight_instance_id BIGINT      NOT NULL REFERENCES flight_instance (id) ON DELETE RESTRICT,
    status             VARCHAR(9)  NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at       TIMESTAMPTZ,
    idempotency_key    UUID,
    CONSTRAINT uq_booking_pnr   UNIQUE (pnr),
    CONSTRAINT uq_booking_idem  UNIQUE (idempotency_key),
    CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED','CANCELLED')),
    CONSTRAINT ck_booking_pnr    CHECK (pnr ~ '^[A-HJ-NP-Z2-9]{6}$'),
    CONSTRAINT ck_booking_cancel CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT uq_booking_id_instance UNIQUE (id, flight_instance_id)
);
```

`ck_booking_pnr` encodes the DEC-5 alphabet in the schema: `A-HJ-NP-Z` excludes `I` and
`O`, `2-9` excludes `0` and `1`. The ambiguity rule is therefore enforced by the
database, not only by the generator.

`ck_booking_cancel` makes status and timestamp **mutually consistent by construction** —
a `CANCELLED` row without a timestamp, or a `CONFIRMED` row with one, cannot exist.

`uq_booking_id_instance` exists only to anchor the composite FK in §4.7.

`idempotency_key` is **nullable, and that is load-bearing twice over** (DEC-24). First,
PostgreSQL treats `NULL`s as *distinct* in a `UNIQUE` constraint, so every booking made
without the optional header coexists happily — the brief's minimal API is unchanged for a
client that ignores this. Second, `ADD COLUMN … NULL` is expand-phase-safe under DEC-25:
during a rolling deployment, instances running the previous release insert rows with no
knowledge of this column and the constraint tolerates all of them. A
`NOT NULL DEFAULT gen_random_uuid()` would have been tidier and would have broken the
rollout.

**No passenger name here.** Passengers live on `booking_seat` (AMB-8), so
`passengerCount` is derived and can never disagree with the seat count.

### 4.7 `booking_seat` — FR6.2, FR6.4, FR7.3

```sql
CREATE TABLE booking_seat (
    id                 BIGSERIAL    PRIMARY KEY,
    booking_id         BIGINT       NOT NULL,
    flight_instance_id BIGINT       NOT NULL,
    seat_label         VARCHAR(3)   NOT NULL,
    passenger_name     VARCHAR(100) NOT NULL,
    status             VARCHAR(8)   NOT NULL,
    CONSTRAINT fk_seat_booking FOREIGN KEY (booking_id, flight_instance_id)
        REFERENCES booking (id, flight_instance_id) ON DELETE RESTRICT,
    CONSTRAINT ck_seat_status CHECK (status IN ('BOOKED','RELEASED')),
    CONSTRAINT ck_seat_label  CHECK (seat_label ~ '^[1-9][0-9]?[A-Z]$')
);

CREATE UNIQUE INDEX uq_booking_seat_active
    ON booking_seat (flight_instance_id, seat_label)
    WHERE status = 'BOOKED';
```

**One row = one passenger in one seat.**

**Only booked seats are stored.** Availability is `aircraft seat map − booked seats`.
Pre-materialising every seat of every instance for 365 days would be millions of rows
describing the *absence* of information.

Rows are **never deleted**; cancellation flips `status` to `RELEASED`, preserving who
sat where while the partial index makes the seat instantly rebookable (§7).

Three constraints here each close a hole that is invisible without them:

- **`ck_seat_status`** is not redundant with the Java enum. If `status` were mapped
  `@Enumerated` with no argument, JPA's default is `ORDINAL` and Hibernate would write
  the strings `"0"` and `"1"` into this `VARCHAR`. `ddl-auto: validate` would pass
  (varchar is varchar), no error would ever be logged — and the partial index predicate
  `WHERE status = 'BOOKED'` would **never be true for any row**, silently disabling the
  only mechanism preventing double booking. The CHECK turns that into a loud failure on
  the first insert. The mapping is therefore mandated as
  `@Enumerated(EnumType.STRING)` and this constraint is its backstop.

- **`ck_seat_label`** pins the canonical label form. PostgreSQL's default collation is
  case- and format-sensitive, so `'12A'`, `'12a'` and `'012A'` are three distinct index
  keys — one passenger could book a seat another passenger already holds, **with no
  concurrency involved at all**. Labels are canonicalised in the domain before
  persisting (`Integer.parseInt(row) || letter.toUpperCase(ROOT)`, stripping leading
  zeros) and this constraint rejects anything that escapes it. A case-insensitive
  collation would *not* be an acceptable substitute: the index must key on one
  canonical value, not treat several as equal.

- **The composite FK** stops `booking_seat.flight_instance_id` from diverging from
  `booking.flight_instance_id`. The same fact is stored twice — the column is required
  because the partial index needs it, and the index is the correctness guarantee. If a
  retry path or a mis-wired mapper ever wrote seats under instance `B` for a booking on
  instance `A`, the index would enforce uniqueness **in the wrong scope**: silent,
  permanent corruption with no constraint firing. Referencing both columns together
  makes the divergence unrepresentable.

### 4.8 Indexes

| Index | Columns | Serves |
|---|---|---|
| `uq_booking_seat_active` | `(flight_instance_id, seat_label) WHERE status='BOOKED'` | **Correctness** (§7) + seat-map and availability reads |
| `ix_schedule_route` | `flight_schedule (origin_code, destination_code)` | Search — the only selective predicate (API-3) |
| `uq_flight_number_day` | `flight_schedule_day (flight_number, day_of_week)` | Business-key resolution for seat map and booking (API-4, API-5) |
| `uq_instance` | `flight_instance (schedule_id, flight_date)` | Instance lookup and the materialisation upsert |
| `uq_booking_pnr` | `booking (pnr)` | PNR lookup (API-6, API-7) |
| `ix_seat_booking` | `booking_seat (booking_id)` | Loading a booking's seats; cancellation |

Primary keys supply the remaining access paths. No index is added speculatively — each
row above names the query it exists for.

**Two properties of this set were verified rather than assumed**, because both are
places a schema silently loses an index.

**Every foreign key is covered by a leading index column.** PostgreSQL, unlike MySQL,
**does not create an index on a foreign key automatically** — an FK constrains, it does
not index. The check therefore has to be done by hand:

| Foreign key | Covered by | Leading column? |
|---|---|---|
| `flight_schedule_day.schedule_id` | `PRIMARY KEY (schedule_id, day_of_week)` | ✅ — which is also why the search join needs no separate index |
| `flight_instance.schedule_id` | `uq_instance (schedule_id, flight_date)` | ✅ |
| `booking_seat.flight_instance_id` | `uq_booking_seat_active` | ✅ — with the caveat below |
| `booking_seat.booking_id` | `ix_seat_booking` | ✅ |
| `flight_schedule.origin_code`, `.destination_code` | `ix_schedule_route` | ✅ |
| `flight_schedule.aircraft_id` | **none, deliberately** | The join resolves `aircraft.id` (a primary key) from the schedule row already in hand; nothing ever scans `flight_schedule` *by* aircraft. Aircraft are never deleted (A5), so the usual second reason for an FK index — cascading parent deletes — does not apply |

**A partial index only serves queries whose predicate it implies.** `uq_booking_seat_active`
is `WHERE status = 'BOOKED'`, so it is usable as a read index **only because every read
path filters on exactly that status** — search (§8.1.4), the seat map (API-4) and
availability all do. This is a real coupling and it is one-directional: a future query
wanting *all* seats for an instance, cancelled ones included — a passenger manifest, an
audit view — would **not** be served by this index and would seq-scan. The correct
response then is a second, non-partial index, never widening this one: dropping the
predicate would destroy the correctness property it exists for (§7.1).

---

### 4.9 Secondary indexes — discharges DEC-30

Deliverable 4 lists **"Indexes"** as a required item. The constraints above create
indexes as a side effect (every `PRIMARY KEY` and `UNIQUE`, plus the partial
`uq_booking_seat_active`), but those exist to enforce correctness. The access paths below
exist to serve queries, and are sized against the clarified row counts rather than assumed
volume.

```sql
-- Search: route lookup. 500 schedules today (see note below).
CREATE INDEX idx_schedule_route
    ON flight_schedule (source_airport_id, dest_airport_id);

-- Search: day-of-week match for the requested travel date.
CREATE INDEX idx_schedule_day_dow
    ON flight_schedule_day (day_of_week);

-- Seat map and availability count. ~1.1M rows/yr -- the index that matters most.
CREATE INDEX idx_booking_seat_instance
    ON booking_seat (flight_instance_id);

-- Cancellation and instance-level booking lookups.
CREATE INDEX idx_booking_instance
    ON booking (flight_instance_id);

-- DEC-1 lazy materialisation must never create two rows for one (schedule, date).
-- A correctness constraint that happens to also be the lookup path.
CREATE UNIQUE INDEX uq_instance
    ON flight_instance (flight_schedule_id, flight_date);
```

**`idx_schedule_route` will often be ignored, and that is correct.** At **500 rows**
`flight_schedule` occupies a few pages, and PostgreSQL will sequentially scan it because
doing so is genuinely cheaper than descending a B-tree and fetching heap rows. The index
is created for growth, not for today; search is fast at this scale because the table is
small, not because the index is used. Stating this is the difference between a schema that
documents its reasoning and one that implies a performance claim it cannot support.

This is also why DEC-26's plan assertion targets `booking_seat` and not this table: an
`EXPLAIN` test demanding an index scan here would fail against a *healthy* planner, and
the only ways to force it to pass — seeding fictitious volume, or `SET enable_seqscan =
off` — test the planner's obedience rather than the query's shape.

**`uq_instance` is load-bearing.** Under DEC-1, two instances materialising the same
flight date concurrently both attempt an insert; this index is what makes the loser's
`ON CONFLICT` fire (D9 — every uniqueness rule is a database constraint, never a prior
read). It is listed here because it is an index, but it is a concurrency control first.


## 5. API Surface

Seven endpoints, `/api/v1` prefix. **Flight instance IDs never appear in the API** —
`flightNumber + date` is the business key a customer actually holds.

| ID | Method | Path | Purpose | Trace |
|---|---|---|---|---|
| API-1 | `POST` | `/admin/flight-schedules` | Create a schedule | FR3.1 |
| API-2 | `GET` | `/admin/flight-schedules` | List schedules | AMB-7 |
| API-3 | `GET` | `/flights?originCode&destinationCode&date` | Search | FR5.1 |
| API-4 | `GET` | `/flights/{flightNumber}/seat-map?date=` | Seat map | FR6.1 |
| API-5 | `POST` | `/bookings` | Create booking | FR7.1 |
| API-6 | `GET` | `/bookings/{pnr}` | Retrieve booking | FR7.2 |
| API-7 | `POST` | `/bookings/{pnr}/cancel` | Cancel booking | FR9.1 |

### 5.1 Input / output contracts

For FR5 and FR7 the brief enumerates the fields a response must carry. Those are read
as **mandatory minimums**, not closed schemas — the brief says *"The system should
return"* and *"A successful booking returns"*, neither of which forbids additional
fields. This design nonetheless adds nothing beyond what a field is needed for, and
where it does add one (the seat map's `flightNumber`/`flightDate` echo) the reason is
stated. The distinction matters: an earlier draft claimed the brief mandated *"exactly
those fields — no more"*, then immediately violated its own rule.

All times are `HH:mm` (24-hour). All dates are ISO-8601 `yyyy-MM-dd`. Weekdays are the
English `DayOfWeek` names the brief itself uses — `MONDAY`…`SUNDAY` — not integers;
the ISO numbering of §4.4 is a storage detail and never appears in the API.

**API-1 — Create schedule.** *FR3: "The system must expose administrative APIs to
create flight schedules. A schedule contains: Flight Number / Source Airport /
Destination Airport / Departure Time / Arrival Time / Aircraft / Days of Operation."*
All seven are **mandatory**; the request carries exactly those seven and nothing else.

```
POST /api/v1/admin/flight-schedules
{ "flightNumber":     "XY101",                        ← FR3
  "originCode":       "DXB",                          ← FR3 "Source Airport"
  "destinationCode":  "LHR",                          ← FR3 "Destination Airport"
  "departureTime":    "09:30",                        ← FR3
  "arrivalTime":      "13:45",                        ← FR3
  "aircraftId":       "A320-001",                     ← FR3 "Aircraft"
  "daysOfOperation":  ["MONDAY","WEDNESDAY","FRIDAY"] ← FR3
}

→ 201 Location: /api/v1/admin/flight-schedules/42
  { "scheduleId": 42, "flightNumber": "XY101",
    "originCode": "DXB", "destinationCode": "LHR",
    "departureTime": "09:30", "arrivalTime": "13:45",
    "aircraftId": "A320-001",
    "daysOfOperation": ["MONDAY","WEDNESDAY","FRIDAY"] }
```

This is the brief's FR3 example transcribed field for field, which is why the seed data
of §4.1 and §4.2 contains `DXB`, `LHR` and `A320-001`: the documented request is
executable against a freshly migrated database with no edits.

> **The flight date used in every example below is `2026-10-12`, and it was chosen, not
> picked.** A worked example here has to satisfy **two** independent rules or it does not
> return what the document claims:
>
> | Rule | Requirement | `2026-10-12` |
> |---|---|---|
> | **BR-5** | The date's weekday must be in `daysOfOperation` | Monday ✅ |
> | **BR-6** | The date must lie in `[today, today + 365)` | In window at the time of writing ✅ |
>
> This is worth stating because of a trap this design creates for its own documentation.
> DEC-1 chose the hybrid approach precisely because *"today is an input to a query rather
> than a property of stored data"* — but that cuts both ways: the window **rolls**, so any
> absolute date written into a document eventually falls out of it and the example starts
> returning `422 DATE_OUT_OF_WINDOW` through no fault of the code. **A reader running these
> examples after the window has moved must substitute any future Monday, Wednesday or
> Friday within the next 365 days.** The README repeats this instruction rather than
> relying on the reader to infer it.

`scheduleId` is returned even though FR3 does not list it — a `201` that does not
identify the resource it created is not a usable REST response, and `Location`
requires an addressable identifier. It is **not** accepted anywhere as input; customer
APIs continue to use `flightNumber + date` (DEC-7).

**Validation order is fixed**, because when a request violates several rules at once the
response must be deterministic — otherwise the error a client sees depends on field
iteration order, and tests become flaky:

| # | Check | Failure |
|---|---|---|
| 1 | Syntactic — required fields present, `HH:mm` parses, weekday names valid, `flightNumber` matches `^[A-Z0-9]{2,8}$` | `400 VALIDATION_FAILED`, all field errors reported **together** |
| 2 | `daysOfOperation` non-empty and free of duplicates | `400 VALIDATION_FAILED` |
| 3 | `originCode ≠ destinationCode` | `400 SAME_ORIGIN_DESTINATION` |
| 4 | Referenced airports and aircraft exist | `422 UNKNOWN_REFERENCE` — the request is well-formed but references absent data |
| 5 | `uq_flight_number_day` (DI-1) | `409 DUPLICATE_SCHEDULE`, naming the conflicting weekday |

Step 1 reports **all** syntactic errors at once; steps 2–5 are short-circuit, reporting
the first failure only. Reporting every structural problem in one response avoids the
fix-one-resubmit loop, while sequential semantic checks avoid asserting things about
data that may not exist.

Step 4 returns `422`, not `404`. A `404` would claim the *schedule* endpoint does not
exist, when in fact the endpoint is correct and its referenced data is not — the
distinction a client needs in order to retry sensibly.

There is deliberately **no** check that `arrivalTime > departureTime`: an overnight
flight is legitimate (BR-4), and rejecting it would be a bug, not a validation.

**API-2 — List schedules.** Returns an array of the API-1 response shape. Recorded as a
deliberate addition (AMB-7), not an FR: without it the only way to confirm a schedule
exists is to book against it. FR3's wording — *"administrative **APIs**"*, plural —
is weak support and is **not** relied on as justification.

**API-3 — Search.** *FR5: "search flights using: Source Airport, Destination Airport,
Travel Date"* — all three are **mandatory** query parameters.

```
GET /api/v1/flights?originCode=DXB&destinationCode=LHR&date=2026-10-12
→ 200 [ { "flightNumber": "XY101",        ← FR5
          "departureTime": "09:30",        ← FR5
          "arrivalTime":   "13:45",        ← FR5
          "availableSeats": 174 } ]        ← FR5 (count — AMB-9)
```

**Fully-booked flights are still returned**, with `availableSeats: 0`. This looks like
an oversight and is the opposite — FR5 closes with *"Sorting and filtering beyond this
are out of scope."* Dropping sold-out flights **is a filter**, so suppressing them would
add behaviour the brief explicitly excludes. A customer also has a legitimate reason to
see that the flight exists and is full, rather than receiving an empty result that is
indistinguishable from *"no such route"*. Asserted as an acceptance criterion so the
behaviour cannot be silently "optimised" later.

**Result ordering is `departure_time` ascending, then `flight_number`** — deterministic,
but deliberately **not** advertised as a feature. FR5 places sorting out of scope, so no
`sort` parameter exists and the order is not part of the contract. It is fixed anyway
because an unordered result set makes assertions in tests non-reproducible: SQL without
`ORDER BY` guarantees **no** ordering, and a test that passes on one execution plan can
fail on another. This is a testability measure (NFR8), not a search capability.

**An empty result is `200 []`, never `404`.** The route-and-date combination is a valid
query that matched nothing; `404` would assert the *endpoint* does not exist.

**API-4 — Seat map.** *FR6: "Each seat should have one of: Available / Booked"* —
exactly two states, labels `1A … 30F`.

```
GET /api/v1/flights/XY101/seat-map?date=2026-10-12
→ 200 { "flightNumber": "XY101", "flightDate": "2026-10-12",
        "seats": [ { "label": "1A", "status": "AVAILABLE" },
                   { "label": "1B", "status": "BOOKED" }, … ] }
```

**The seat map does not require a `flight_instance` row to exist**, and this is the
single most important consequence of the hybrid strategy (DEC-1) leaking into a read
path. A flight nobody has booked yet has **no row**. The naive implementation looks up
the instance, finds nothing, and returns `404` — so **every flight is unviewable until
someone books it**, and the first booking is impossible because the customer cannot see
the seats. The failure is circular and total, yet it only appears on a freshly seeded
database.

The resolution follows directly from §2.0.1: availability is a **set difference**, and
the subtrahend of an empty set is the full set.

**On the word *"real-time"*.** FR6 requires the map to reflect real-time booking state,
and it does: the response is computed from `booking_seat` inside the request, with no
cache and no replica (DEC-4, D3). What it cannot be is real-time *after* the response is
written — a seat shown `AVAILABLE` can be taken before the customer clicks it. That
window is the client-side staleness of §8.1.5, it exists identically at `N = 1`, and it is
closed not by making this endpoint fresher but by §7.8.1's `409`. The distinction matters
because *"real-time"* is sometimes read as a promise that the seat is held, which no read
endpoint can make.

```
seats(flight) = derive(aircraft)                    ← always non-empty
booked(flight) = { booking_seat WHERE status='BOOKED' }   ← empty when no instance row
```

So the lookup is resolved as *schedule + date*, exactly like search, and the booked set
is an **outer** join that legitimately yields nothing. `GET .../seat-map` on a flight
that has never been booked returns every seat `AVAILABLE`. Reading it must never
materialise a row — materialisation is a **write-path** concern (FR4: *"the booking
engine must generate"*), and a `GET` that inserts would make an unbounded crawl of seat
maps populate the table for all 365 days.

**Two smaller properties of this response are fixed deliberately.**

*Ordering.* `seats` is returned **row ascending, then letter in `seat_letters` order** —
`1A, 1B, … 1F, 2A, …`. The same argument made for search's explicit `ORDER BY` applies
here and is easier to get wrong: the labels are *derived*, so their order is whatever
the generating loop happens to produce, and a map rendered in arbitrary order is both
unusable as a cabin diagram and unassertable in a test. Ordering is **testability
(NFR8)**, not a feature.

*What the response deliberately omits.* FR6 says each seat has *"one of: Available /
Booked"* — a **status and nothing else**. `booking_seat` does carry `passenger_name`
(FR7.3), and it would take only an accidental entity serialisation to emit it here,
which would publish every passenger's name on an **unauthenticated** endpoint to anyone
who can guess a flight number and date. The seat map is therefore built from a
projection carrying exactly `label` and `status`, never from the entity. The brief does
not raise this, but FR6's two-state wording already excludes it, so this is the brief's
requirement enforced rather than a privacy feature added.

`404` is reserved for the case that actually means absent: **no schedule operates
`flightNumber` on that date's weekday**. That is a genuinely non-existent flight, as
opposed to an existing flight with no bookings. The two are easy to conflate and
produce opposite correct answers.

**API-5 — Create booking.** *FR7: "Select one or more seats"*, *"Passenger information
may be minimal: Name"*. The response carries the six fields FR7 names, in that order.

```
POST /api/v1/bookings
{ "flightNumber": "XY101", "flightDate": "2026-10-12",
  "passengers": [ { "name": "A Sharma", "seat": "12A" },
                  { "name": "B Sharma", "seat": "12B" } ] }

→ 201 { "bookingReference": "KQ7TMX",      ← FR7 "Booking Reference (PNR or UUID)"
        "flightNumber":     "XY101",        ← FR7
        "flightDate":       "2026-10-12",   ← FR7
        "passengerCount":   2,              ← FR7 (derived — AMB-8)
        "assignedSeats":    ["12A","12B"],  ← FR7
        "bookingStatus":    "CONFIRMED" }   ← FR7 (AMB-11)
```

Pairing each passenger with a seat in one array is what makes `passengerCount` and
`assignedSeats` **structurally incapable of disagreeing** (BR-9a). Two parallel arrays
would permit a length mismatch that only runtime validation could catch.

**FR7's four user actions are a journey, not four endpoints.** *"Select a flight"* and
*"View available seats"* are satisfied by API-3 and API-4; *"Select one or more seats"*
is the request body below; *"Create a booking"* is the call itself. No server-side
selection state exists between them — adding one would mean seat holds, which FR6's
two-state model forbids (DEC: declined).

**API-7 — Cancel.** Returns the same `BookingResponse` shape with
`bookingStatus: "CANCELLED"`, so a client needs one representation, not two.

### 5.2 Status codes

The split between `400` and `422` is a **stated rule**, not a per-endpoint judgement,
because inconsistency here is exactly what makes an API annoying to integrate against:

- **`400`** — the request is **malformed**: a field is missing, unparseable, or violates
  a syntactic constraint. Answerable without touching the database.
- **`422`** — the request is **well-formed but cannot be satisfied**: it references data
  that does not exist, or asks for something outside a business boundary. Requires a
  lookup to detect.
- **`409`** — the request is well-formed *and* satisfiable in principle, but **conflicts
  with current state**. The distinguishing property is that a `409` can become a `201`
  later (or could have been one a moment earlier) with no change to the request.

| Code | When | Example `type` |
|---|---|---|
| `200` | Success | — |
| `201` | Resource created — API-1 and API-5 only, with `Location` | — |
| `400` | Missing/unparseable field; bad date or time format; invalid weekday name; `flightNumber` fails `^[A-Z0-9]{2,8}$`; seat label fails `^[1-9][0-9]?[A-Z]$`; **empty** `passengers` array (FR7: *"one or more"*); **duplicate seat label within one request** | `VALIDATION_FAILED` |
| `400` | `originCode == destinationCode` | `SAME_ORIGIN_DESTINATION` |
| `404` | Unknown PNR; flight number that matches no schedule; flight that does not operate on that date | `BOOKING_NOT_FOUND`, `FLIGHT_NOT_FOUND` |
| `409` | Seat already booked by someone else | `SEAT_UNAVAILABLE` |
| `409` | Booking already cancelled (BR-12) | `ALREADY_CANCELLED` |
| `409` | Flight already departed | `FLIGHT_DEPARTED` |
| `409` | Schedule already exists for that flight number and weekday (DI-1) | `DUPLICATE_SCHEDULE` |
| `422` | Travel date outside the 365-day window (BR-6, AMB-12) | `DATE_OUT_OF_WINDOW` |
| `422` | Referenced airport or aircraft does not exist (API-1) | `UNKNOWN_REFERENCE` |
| `422` | Seat label well-formed but **not on this aircraft** — e.g. `45A` on a 30-row A320 (BR-8) | `SEAT_NOT_ON_AIRCRAFT` |
| `503` | Commit-phase failure; outcome indeterminate (§7.9) | `BOOKING_STATUS_UNKNOWN` |

`45A` is deliberately a `422` rather than a `400`: the label is syntactically perfect and
only an aircraft lookup can reject it. Classifying it as `400` would mean the same string
is valid or invalid depending on which flight it is sent to — which is the definition of
a semantic, not syntactic, failure.

**Removed from an earlier draft:** a `400` for *"name/seat count mismatch"*. It is
**unreachable** — the paired-array request shape makes the two counts the same number by
construction. A documented error that cannot occur is worse than no documentation: it
implies a validation that does not exist, and no test can ever cover it.

All error bodies use RFC 9457 `application/problem+json` (DEC-11).

---

## 6. Business Rules

| ID | Rule | Trace |
|---|---|---|
| BR-1 | Origin and destination must differ | FR3.2 |
| BR-2 | Both airports and the aircraft must exist | FR3.2 |
| BR-3 | Days of operation must be a non-empty set of distinct weekdays | FR3.3 |
| BR-4 | Arrival time **may be ≤ departure time** (legitimate overnight flight) — never validated as an error | AMB-1 |
| BR-5 | A flight operates on a date iff `date.dayOfWeek ∈ daysOfOperation` | FR5.3 |
| BR-6 | Travel date must satisfy `today ≤ flightDate < today + 365 days` (half-open — exactly 365 bookable dates; AMB-12) | FR4.2 |
| BR-7 | A flight instance is bookable only while `now < departureInstant` | AMB-4 |
| BR-8 | Requested seat labels must be valid for the aircraft's seat map | FR6.3 |
| BR-9 | Passenger names are accepted **as supplied**. FR7 says *"No passenger validation is required"*, so there is no charset, format, realness or non-blank rule — the sole bound is the declared storage limit `length ≤ 100`, enforced at the edge as `400` rather than allowed to surface as a `500` (§8.2.1). The only constraint is structural: the request must supply a `name` element per seat, because `passengerCount` is derived from it (AMB-8). A `null` name is a **malformed request** (`400`), not a rejected passenger | FR7.4 |
| BR-9a | Exactly one passenger name per requested seat — counts must match | AMB-8 |
| BR-9b | A flight number may not operate twice on the same weekday | AMB-10 |
| BR-10 | At least one seat; seat labels must be unique within one request | FR7.1 |
| BR-11 | A seat already `BOOKED` on that instance cannot be booked again | FR8.1 |
| BR-12 | Only a `CONFIRMED` booking may be cancelled. A second cancellation returns `409`. **Cancellation is therefore not idempotent**, and is not described as such: an idempotent operation would return `200` on repeat. `409` is chosen so a double-submit is reported rather than silently absorbed. Enforced by affected-row count, not a prior read (§7.7) | FR9.2 |
| BR-13 | Cancellation releases **all** seats of the booking atomically | FR9.3 |
| BR-14 | Cancelling a booking on a departed flight is rejected | AMB-5 |

### 6.1 Where "today" is evaluated — discharges D7

BR-5, BR-6, BR-7 and BR-14 all turn on *today* or *now*. The brief writes *"from the
current date"* three times and never says **whose** current date — which is harmless at
`N = 1` and not harmless at `N ≥ 2`, because then there are `N` wall clocks deciding an
API contract. Two instances disagreeing by one second across midnight UTC return
**different HTTP status codes for the identical request**: `201` from one, `422
DATE_OUT_OF_WINDOW` from the other. A client retry landing on a different instance (D4)
would see the booking window open and close with no state having changed.

| # | Option | Verdict | Reasoning |
|---|---|---|---|
| A | Ambient `LocalDate.now()` wherever needed | ❌ | `N` clocks, no single answer, and **untestable** — a boundary test cannot pin "today" without changing the system clock |
| B | Read the date from the database (`SELECT CURRENT_DATE`) | 🟡 | Genuinely one clock, and the honest answer to "how do you get a single source of time". Rejected: it adds a round trip to **every** search and booking to remove a skew that NTP already bounds to milliseconds, and it makes a pure validation rule require a database connection |
| C | **A single injected `java.time.Clock` bean, `Clock.systemUTC()` in production** | ✅ | One source per instance, overridable in tests |

**Chosen: C**, with the residual skew stated rather than hidden.

- **The exposure is bounded and quantified.** Instances are NTP-synchronised to within
  single-digit milliseconds. The only requests that can diverge are those arriving in
  that window **and** landing exactly on the window boundary — a request for precisely
  `today + 365` within milliseconds of midnight UTC. The consequence is a `422` the
  client can retry, never a double-booked seat: **no correctness invariant depends on
  the clock**, only a window predicate does. That asymmetry is why option B's round trip
  is not worth buying.
- **`Clock` is injected, never ambient.** `LocalDate.now()` and `Instant.now()` are
  forbidden in production code — an ArchUnit rule asserts no call to the no-arg forms —
  so the rule cannot be re-broken by a later change.
- **This is what makes the boundary testable at all.** BR-6's `today + 364` succeeds /
  `today + 365` fails pair is only assertable because the test supplies
  `Clock.fixed(...)`. Under option A the same test would have to mutate the host clock,
  which is why that acceptance criterion exists in a form that can actually be run.
- **Evaluation is per request, not per startup.** A `Clock` captured once into a field at
  construction would freeze "today" for the lifetime of the instance — correct for the
  first day and wrong every day after, on a long-running process that is never restarted.
  The clock is read at the point of validation.

---

## 7. Concurrency Requirements

FR8 asks the system to **prevent double booking** and to **document the strategy**. It
asks for a strategy that works, not a count of mechanisms.

### 7.1 One enforcement point

**All seat-conflict correctness rests on a single partial unique index:**

```sql
CREATE UNIQUE INDEX uq_booking_seat_active
    ON booking_seat (flight_instance_id, seat_label)
    WHERE status = 'BOOKED';
```

Two concurrent transactions inserting the same `(instance, seat)` cannot both commit:
the second blocks on the index until the first resolves, then either succeeds (if the
first rolled back) or fails with SQLSTATE `23505`. This holds regardless of application
code, thread count, instance count or retry logic, because it is enforced below the
application entirely.

The `WHERE status = 'BOOKED'` predicate is what lets cancellation keep history. A
released seat remains in the table — preserving who sat where — while leaving no entry
in the index, so the seat is **immediately** rebookable without deleting anything. A
full unique constraint would force a choice between losing history and blocking reuse.

*(This predicate is also why PostgreSQL is used in tests, not H2 — H2 cannot express a
partial unique index, so an H2 test suite would silently verify a different, weaker
schema than production. DEC-12.)*

### 7.2 Why there is no version column and no retry

An earlier draft specified three layers: the index, `@Version` on `flight_instance`,
and a bounded retry on optimistic-lock failure. **Two of the three could never have
executed**, and the analysis is worth stating because it is the kind of defect that
survives code review and passes its own tests.

A booking **inserts `booking_seat` rows**. It reads `flight_instance` but does not
modify it. Hibernate increments a `@Version` only when (a) the versioned entity's own
non-version state is dirty, or (b) an **owned** (non-`mappedBy`) collection on it
changes. Seats are derived, so `flight_instance` owns no seat collection — the
`@ManyToOne` lives on the child. Hibernate therefore emits **no `UPDATE
flight_instance`**, the version never moves, `OptimisticLockException` is never thrown,
and the retry that exists to absorb it never fires.

The consequence is worse than "a layer does nothing": the planned test CT-2 was to
*measure the retry count as evidence the design works*. It would have reported **zero**
— and zero is exactly what a perfectly working optimistic-lock layer also reports under
no contention. **The test could not distinguish a working mechanism from an absent
one**, so it would have been presented as evidence for a mechanism that did not exist.

Making it real was considered and rejected:
`em.lock(instance, OPTIMISTIC_FORCE_INCREMENT)` would issue
`UPDATE flight_instance SET version = version + 1 WHERE id = ? AND version = ?`,
which **serialises every booking on a flight through one row**. Two customers booking
different seats would then conflict by construction, and CT-2 with N threads becomes a
retry storm that fails at any bounded attempt count. That is strictly worse than the
index, which allows unlimited concurrency on distinct seats.

A retry is also not kept for seat conflicts. `23505` on `uq_booking_seat_active` means
the seat is *taken* — a deterministic business outcome. Retrying it can only produce
the same failure more slowly, and converts a correct `409` into an
`ExhaustedRetryException` → `500`.

**`CannotAcquireLockException` (deadlock, `40P01`) is the one genuinely transient
failure and is retried** — at most twice, with jittered backoff, around a transaction
boundary that has already rolled back. Exceptions raised *during commit* are never
retried: their outcome is indeterminate, and re-running the booking would hit the index
and report `409 SEAT_UNAVAILABLE` for a booking that actually **succeeded**, stranding a
PNR that was never returned to anyone. Those surface as `503` with a documented
instruction to re-query `GET /api/v1/bookings/{pnr}` (API-6).

> Fewer moving parts, one guarantee, and a clear account of what was removed and why —
> rather than three layers of which two are decoration.

#### 7.2.1 Replaying a booking — discharges DEC-24

`POST /api/v1/bookings` accepts an **optional** `Idempotency-Key` header carrying a
client-generated UUID. Absent, nothing changes. Present, the booking becomes replayable:

```sql
INSERT INTO booking (pnr, flight_instance_id, status, idempotency_key)
VALUES (:pnr, :instanceId, 'CONFIRMED', :key)
ON CONFLICT ON CONSTRAINT uq_booking_idem
DO NOTHING
RETURNING id;
```

Zero rows returned means this key has been used. The service then reads the existing
booking by key and returns it with **`200 OK`** rather than `201 Created`, so the client
can distinguish a replay from a first write, and the seats in that body are the seats they
actually hold.

**Why `DO NOTHING` here, when §7.4 insisted on `DO UPDATE`.** The two sites are solving
different problems and the asymmetry is deliberate. §7.4 races to *create* a shared row
and every racer needs its id, so losing the race must still yield a value — hence
`DO UPDATE`, which waits out the speculative insertion. Here the loser does not need the
winner's row *within the same statement*: it needs the booking that the key already
identifies, which it fetches in a second statement. Were two retries of the same key to
arrive truly simultaneously, one would see zero rows and read the other's committed row;
if that read were to arrive before the other transaction commits, the request fails and
the client retries again with **the same key** — which is the entire point of the key, and
terminates. A booking is not a prerequisite for anything else in the request, so the extra
round trip is free.

**What the key does not do.** It does not reserve seats, create a hold, or survive beyond
the row it sits on. It gives a retry a way to find out what its first attempt did. That is
the whole mechanism, and keeping it that small is why it needs no TTL, no reaper, and no
second table — all three of which D2 would have made awkward.

| Scenario | Without a key | With a key |
|---|---|---|
| First request succeeds, response delivered | `201` | `201` |
| Response lost, client retries | `409 SEAT_UNAVAILABLE` — on the customer's own seats, unrecoverable | **`200`** with the original booking |
| Two different customers, same seats | `201` / `409` | `201` / `409` — unchanged, the keys differ |
| Same customer deliberately books two bookings for the same seats | `409` (correct — seats are taken) | `409` — unchanged, a new intent means a new key |

### 7.3 Isolation level

**Every transaction runs at PostgreSQL's default `READ COMMITTED`. No `isolation`
attribute is set anywhere in the codebase.**

This is a binding constraint, not a default accepted by omission. The materialisation
read in §7.4 is correct **only** at `READ COMMITTED`: under `REPEATABLE READ` the
snapshot is fixed at the transaction's first statement, so a row committed by another
transaction afterwards is invisible, and the first-booking path would fail
*permanently* rather than intermittently. Raising the isolation level "for safety"
would break it. An integration test asserts `SHOW transaction_isolation` = `read
committed` so the invariant cannot be silently changed.

### 7.4 Flight-instance materialisation

```sql
INSERT INTO flight_instance (schedule_id, flight_date)
VALUES (:scheduleId, :flightDate)
ON CONFLICT ON CONSTRAINT uq_instance
DO UPDATE SET schedule_id = EXCLUDED.schedule_id
RETURNING id;
```

`DO UPDATE` rather than `DO NOTHING`, and this is not stylistic.

With `DO NOTHING`, two concurrent upserts for the same `(schedule, date)` — **exactly
scenario CT-5** — can both come away empty-handed. PostgreSQL inserts speculatively;
when the older transaction finds a conflicting *speculative* tuple inserted by a newer
one, it deliberately **does not wait** (this is deadlock avoidance) and reports the
conflict immediately. `DO NOTHING` yields zero rows, and the follow-up `SELECT` takes a
fresh `READ COMMITTED` snapshot in which the other transaction has still not committed
— so **the `SELECT` returns nothing either**. The result is an intermittent `500` on a
perfectly valid booking, under precisely the load CT-5 applies.

`DO UPDATE` takes a tuple lock on the conflicting row and therefore **does** wait out
the speculative insertion, so `RETURNING id` always yields exactly one row. The cost is
one dead tuple on the first booking of each flight — paid once per instance, ever.

### 7.5 Transaction topology

Materialisation runs in **its own transaction, which commits before the booking
transaction opens**. The two are sequential, never nested:

```
BookingApplicationService          (no @Transactional — orchestration + retry only)
  └─ FlightInstanceMaterialiser    @Transactional(REQUIRED)   → commits, returns id
  └─ BookingTransactionService     @Transactional(REQUIRED)   → the booking unit of work
```

Three distinct failures are avoided by this shape, all of which the obvious design has:

1. **Self-invocation.** Spring's `@Transactional` is proxy-based and applies to
   *external* calls only. A `REQUIRES_NEW` method called as `this.materialise(...)`
   from another method of the same bean is **silently ignored** — the annotation has no
   effect and no warning is produced. Separate beans make the proxy boundary real.

2. **Connection-pool exhaustion.** `REQUIRES_NEW` *suspends* the outer transaction but
   the outer transaction **keeps its JDBC connection**, and the inner one acquires a
   second. With Hikari's default pool of 10 and CT-5 running ≥ 10 concurrent
   first-bookings, all 10 connections are held by outer transactions while all 10 inner
   transactions wait for a connection that can never be released. Every thread fails
   after the 30 s connection timeout. **The spec's own concurrency test would be the
   trigger**, and it would present as a database problem. Sequential transactions hold
   one connection at a time, so the pool size and the test's thread count are
   decoupled.

3. **Lost work on rollback.** If materialisation shared the booking transaction, a seat
   conflict would roll back the instance row too, and the next booking would re-create
   it — turning a one-off write into a per-conflict write.

The booking transaction itself is a single unit of work: resolve instance → validate
labels → generate PNR → insert `booking` → insert `booking_seat` rows → commit. All
domain exceptions extend `RuntimeException`, so Spring's default rollback rule applies
— a checked exception would let Spring **commit** a partially written multi-seat
booking, leaving seats 1 and 3 persisted when seat 2 failed. FR7 demands all-or-nothing
and CT-3 asserts it.

#### 7.5.1 The connection budget is `N × pool` — discharges D6

Point 2 above reasons from *"Hikari's default pool of 10"*. That number is the budget of
**one instance**. Under §2.0.4 there are `N`, and the database sees their sum:

```
total connections = N × maximumPoolSize     (+ any migration/admin sessions)
```

PostgreSQL's `max_connections` defaults to **100**, and each backend reserves
`superuser_reserved_connections` (3) on top. At Hikari's default of 10 the design is safe
to `N = 9` and then begins refusing connections with `FATAL: sorry, too many clients
already` — an error that surfaces as a mass `500`, at deploy time, on whichever instance
started last. The failure is **configuration-shaped, not load-shaped**: it appears when the
replica count changes, not when traffic grows, so load testing a single instance will
never reveal it.

**Sizing is therefore derived from the database, not from the instance:**

| Input | Value | Source |
|---|---|---|
| `max_connections` | 100 | PostgreSQL default; pinned in `docker-compose.yml` so it is not environment-dependent |
| Reserved | 3 | `superuser_reserved_connections` |
| Headroom for migration and admin sessions | 10 | §4.0.1 — every instance holds a connection while waiting on the Flyway lock |
| Usable | 87 | — |
| `N` planned ceiling | 4 | Stated so the arithmetic has a bound |
| **`maximumPoolSize`** | **10** | `4 × 10 = 40`, comfortably inside 87 — and still ≥ CT-5's thread count |

**Why not simply raise `max_connections`.** Each PostgreSQL backend is a process with its
own memory; raising the limit trades a hard failure for a soft one (swapping, context
-switch overhead) and is the standard wrong answer. The correct lever at higher `N` is a
**connection pooler** (PgBouncer in transaction mode), which multiplexes many client
connections onto few backends. It is **not** introduced here — at DEC-20's ~6 QPS the
budget above has two orders of magnitude of headroom — but it is named as the scale-out
step, and belongs in HLD §9 rather than in this design.

**The constraint that keeps this true.** §7.5's sequential-transaction shape means a
request holds **one** connection at a time. Were materialisation nested in the booking
transaction, each in-flight booking would hold two, halving `N` at a stroke. The topology
decision and the connection budget are the same decision viewed from two directions.

#### 7.5.2 Virtual threads move the queue, they do not remove it — discharges D8

`application.yml` sets `spring.threads.virtual.enabled: true`. That is a **concurrency
decision sitting in a configuration file**, and until the pass-4 audit this chapter — the
one the brief grades under *Concurrency handling* — did not mention it.

**What it changes.** On a platform-thread Tomcat, the worker pool (200 by default) is an
**admission limit**: request 201 waits at the HTTP layer and the system has backpressure
whether or not anyone designed it. A virtual-thread executor is effectively unbounded, so
that accidental limit disappears. It does not disappear from the system — it **moves to
the next scarce resource**, which here is the Hikari pool of 10 (§7.5.1).

**Why that relocation is the right one.** This design blocks deliberately: §7.1 waits on
row locks rather than spinning, and DEC-20's *"booking — ultimate stability is key, the
time can change based on load and concurrent attempts"* licenses exactly that. Blocking a
platform thread wastes a megabyte of stack; blocking a virtual thread unmounts it and
costs almost nothing. A pessimistic design is the case virtual threads are *for*. And a
bound at the connection pool is a bound on the thing that is actually finite — the
database — rather than on an arbitrary count of HTTP workers.

**What it breaks if left unstated.** The bound is now `connectionTimeout`, whose default
is **30 seconds**, after which Hikari throws `SQLTransientConnectionException`. Unhandled,
that is a 30-second wait followed by a `500`. *"Ultimate stability"* does license a longer
wait — but it does not license a slow, untyped failure, and a `500` tells a client the
request was malformed on our side when the truth is that the system was saturated. Two
consequences follow, and both are now specified rather than inherited:

| Setting | Value | Reason |
|---|---|---|
| `connection-timeout` | **20s** | Declared, not defaulted. Tuned for the **booking** path only — see §7.5.3, which is why this is 20s and not the 10s a search deadline would imply. Long enough to ride out a lock convoy at ~0.25 QPS booking; a saturated instance still sheds rather than queueing past the load balancer's own timeout |
| `SQLTransientConnectionException` | → **`503` + `Retry-After`** | Saturation is retryable and the client should be told so. It joins §7.9's `503` family, which already means *"indeterminate or temporary — retry"*, rather than the `500` family, which means *"we are broken"* |

**Carrier-thread pinning.** On Java 21 a virtual thread that blocks inside a
`synchronized` block pins its carrier, which can starve the scheduler. Hikari's checkout
path uses `java.util.concurrent` structures rather than `synchronized`, so the hot path
does not pin; this is noted because it is a real class of failure and because the
mitigation is a version bump, not a redesign — JDK 24's JEP 491 removes the pinning
entirely. It is listed in HLD §9 as a scale-out consideration, not fixed here.

**What it does not change: D1.** Virtual threads are still JVM-local. A `synchronized`
block would be no more visible to a second instance than before, and no less wrong. The
thread model is an efficiency decision; the correctness argument of §7.1 is untouched by
it, which is precisely why the two can be reasoned about separately.


#### 7.5.3 Two traffic classes, two timeout policies — discharges DEC-27

The hiring team gave search and booking **different** latency policies: search has a
1-second deadline, booking *"ultimate stability is key, the time can change based on load
and concurrent attempts"*. One connection pool with one `connection-timeout` cannot
express both, and expressing only one of them inverts the other.

| Class | Share of traffic (DEC-20) | Policy | Mechanism |
|---|---|---|---|
| Search | **25/26** | Deadline — 1 s | `@Transactional(readOnly = true, timeout = 2)`. Exceeding it is a defect, not load |
| Booking | 1/26 | Stability — may take longer | No statement timeout. Bounded only by `connection-timeout` (20 s), then `503` + `Retry-After` |

**Why the read path is the one that gets a deadline.** It is counter-intuitive to bound
the class that is *not* critical and leave the critical one open, so the reasoning is
worth stating. §7.5.2 removed Tomcat's worker pool as an admission limit, which makes the
10 connections the only bound in the system. Nothing reserves any of them for booking. At
25:1 the entire pool is, in practice, search's — and a booking arriving behind ten slow
searches waits for them regardless of how generous its own timeout is.

Starvation here is not caused by search **volume** — 6 QPS against 10 connections is
nothing — it is caused by search **duration**. Capping that duration caps the longest a
booking can ever queue behind it. Bounding the dominant class *is* the act of reserving
headroom for the critical one, and it costs an annotation attribute rather than a second
`DataSource` and a second `TransactionManager` on every read path.

**Interaction with DEC-26.** Search is asserted to be one bounded, index-backed query, so
a 2-second transaction timeout cannot fire under normal operation. If it fires, something
DEC-26's plan assertions were supposed to catch has escaped them — the timeout is a
signal, not a tuning knob. Neither decision is load-bearing alone; together they are.

**Scale-out step, not built.** When a search legitimately takes seconds — a far larger
schedule set, or a join this scope does not have — the 2-second cap stops being a fault
detector and a true bulkhead (separate read-only pool, or a Resilience4j semaphore)
becomes the correct answer. That boundary is documented so the next engineer knows which
observation should trigger the change.

### 7.6 Lock ordering

**Every statement that touches more than one `booking_seat` row orders by
`seat_label` ascending**, using the canonical label form (§4.7), in **both** the
booking and the cancellation path.

The invariant is global, not booking-local. Ordering only the inserts leaves the cancel
path free to release seats in whatever order PostgreSQL returned them — and without an
explicit `ORDER BY`, PostgreSQL guarantees no order at all (sequential scan, bitmap
scan and `synchronize_seqscans` can each differ run to run). Two concurrent cancels
could then lock `5A → 5B` on one thread and `5B → 5A` on the other: a textbook ABBA
deadlock. Implemented with `@OrderBy("seatLabel ASC")` on the collection and
`hibernate.order_updates = true`, backed by a `List`, never a `HashSet`.

Stated precisely: **no deadlock cycle can form among the ordered paths.** Deadlock is
not claimed to be impossible in general — `40P01` remains possible via paths outside
this invariant, which is why it is the one retried exception (§7.2).

**Release and re-book of the same `(instance, seat)` must never occur in one
transaction.** Hibernate's `ActionQueue` executes insertions *before* updates, so an
insert of a new `BOOKED` row would hit the index while the old `BOOKED` row is still
unreleased → `23505` on correct application logic. CT-4 therefore executes two separate
transactions, which is also what the real flow (two HTTP calls) does.

### 7.7 Cancellation

Cancellation is a **conditional write whose affected-row count is the decision**:

```sql
UPDATE booking SET status = 'CANCELLED', cancelled_at = now()
WHERE id = :id AND status = 'CONFIRMED';
```

Zero rows updated ⇒ the booking was already cancelled ⇒ `409`.

That inference carries a **precondition**, stated here because this section is the one
an implementer reads when writing the concurrency code: the booking's *existence* must
already have been established by the PNR lookup of §8.3.1. `WHERE id = :id AND status =
'CONFIRMED'` returns zero rows for an unknown booking **and** for an already-cancelled
one, so collapsing the lookup into this statement would answer `409 ALREADY_CANCELLED`
for a PNR that never existed — leaking that the two cases are indistinguishable and
returning the wrong code for the more common one. Lookup first (`404`), then the
conditional write (`409`).

A read-then-write (`SELECT`, check status, `UPDATE`) makes BR-12 unenforceable: on a
double-click both requests read `CONFIRMED`, both update, both return `200`. The
conditional form is atomic at `READ COMMITTED`, needs no lock and no version column,
and makes the rule testable under concurrency rather than only single-threaded. Seat
release follows in the same transaction, ordered per §7.6.

Two properties of this are worth stating explicitly under §2.0.4:

**The double-click may arrive at two instances, and nothing changes.** The two requests
are not two threads in one JVM in the general case — they are two requests the load
balancer may have split across pods. The argument above never depended on them sharing a
heap: the arbitration is `UPDATE … WHERE status = 'CONFIRMED'` inside PostgreSQL, so the
loser sees zero affected rows whether it is one hop away or one thread away. **An
application-level guard would have been correct only in the single-JVM reading of
"both threads"** — which is precisely the trap D1 exists to prevent.

**Cancelling a seat while someone else is booking it resolves to a `409`, not to
corruption.** If request A cancels `12A` while request B books `12A`, and B's insert
reaches the index before A commits, B blocks on the still-`BOOKED` row and then fails
`23505` → `409 SEAT_UNAVAILABLE`. B is told the seat is taken at a moment when it is
microseconds from being free. That is **correct and deliberately not retried** (§7.2):
the response describes the state at the time it was computed, which is the same honesty
constraint §7.8.1 and §8.1.5 impose everywhere else. CT-4 covers the sequential case;
this is its concurrent sibling, and the outcome is a clean `409` with no partial state on
either side.

### 7.8 Mapping conflicts to responses

`uq_booking_seat_active` and `uq_booking_pnr` both surface as Spring's
`DataIntegrityViolationException`. Catching it generically would report
**`409 SEAT_UNAVAILABLE` for a free seat** whenever a PNR collided, and would consume
the collision so the PNR regeneration (DEC-5) never ran.

Violations are therefore discriminated by **constraint name** — which is why §4 fixes
those names as a contract:

| Constraint | Meaning | Response |
|---|---|---|
| `uq_booking_seat_active` | Seat taken | `409 SEAT_UNAVAILABLE` |
| `uq_booking_pnr` | PNR collision | Regenerate PNR, retry (≤ 3), then `503` |
| `uq_flight_number_day` | Duplicate flight number on a weekday | `409 DUPLICATE_SCHEDULE` |
| anything else | Unexpected | rethrow → `500` |

The PNR is generated **inside** the booking transaction, so each attempt gets a fresh
value and a rolled-back attempt leaks nothing.

#### 7.8.1 The `409` must name the seats that were lost — DEC-21

Discriminating by constraint name identifies the **kind** of conflict but not its
**subject**. A booking for `{12A, 12B, 12C}` that loses `12B` raises a `23505` on
`uq_booking_seat_active` that does not say `12B`.

That is not merely unhelpful — it **fails the condition under which stale search was
granted** (§2.0.5). The interviewer's permission reads *"if they are late, how does the
user get informed if there is a change in the state when he proceeds for a booking —
**if that is handled**, the results can be stale."* Staleness is acceptable *because*
booking is the authority that detects and reports divergence. A `409` that cannot say
what changed voids the grant.

| # | Option | Verdict |
|---|---|---|
| A | Generic `409 SEAT_UNAVAILABLE` | ❌ Client can only re-fetch the whole seat map and diff it; cannot tell the customer which chosen seat to replace |
| B | Pre-check availability, then insert | ❌ A `SELECT`-then-`INSERT` is exactly the race §7.1 exists to remove — wrong on one instance before `N` is even considered (D1, D9) |
| C | **Catch `23505`, then re-query the requested labels and report them** | ✅ The authoritative answer is read *after* the conflict is known |

**Chosen: C.** The transaction rolls back; a **separate read-only query** re-checks the
requested labels against `booking_seat`; the RFC 9457 body carries them as extensions:

```json
{ "type": "https://airline.example/problems/seat-unavailable",
  "title": "Seat unavailable", "status": 409,
  "detail": "2 of 3 requested seats are no longer available",
  "errorCode": "SEAT_UNAVAILABLE",
  "flightNumber": "XY101", "flightDate": "2026-10-12",
  "requestedSeats":   ["12A", "12B", "12C"],
  "unavailableSeats": ["12B", "12C"] }
```

**The honesty constraint.** The re-query runs *after* the failed transaction, so another
booking may change things again before the client reads the response. `unavailableSeats`
is therefore specified as **"unavailable as of this response"** — never as a guarantee
that the remaining seats are still free. Promising otherwise would reintroduce the very
staleness this subsection exists to disclose; the customer's next attempt is
authoritative, which is the contract the whole system already runs on.

**Transaction semantics are unchanged.** A multi-seat booking stays atomic — losing one
seat fails the whole booking (BR-10). DEC-21 changes the **response body only**, and
adds no lock, no pre-check and no retry.

### 7.9 Entity boundaries

`open-in-view` is `false` (DEC-13), so no Hibernate session exists during response
rendering. **Application services return DTOs only — no entity or Hibernate proxy
crosses a `@Transactional` boundary.** Search additionally needs
`schedule.aircraft.rowsCount` and `seatLetters` to compute capacity, so it fetches via
`@EntityGraph(attributePaths = {"aircraft", "origin", "destination"})`. Without both
rules, every search returns `500` with a `LazyInitializationException`. An ArchUnit rule
forbids `@Entity` types in `api` package signatures.

### 7.10 Required concurrency tests

| ID | Scenario | Expected | Proves |
|---|---|---|---|
| CT-1 | N threads book the **same** seat | Exactly 1 × `201`, N−1 × `409 SEAT_UNAVAILABLE` | §7.1 |
| CT-2 | N threads book **different** seats, same flight | All `201`, zero retries | No false contention (§7.2) |
| CT-3 | Overlapping multi-seat requests | All-or-nothing per booking; no partial bookings | §7.5 rollback |
| CT-4 | Cancel, then rebook the same seat (separate transactions) | Rebooking succeeds | Partial index (§7.1) |
| CT-5 | N ≥ 12 concurrent first-bookings on an unmaterialised instance | Exactly one `flight_instance` row; no `500`, no pool timeout | §7.4 + §7.5 |
| CT-6 | N threads cancel the same PNR | Exactly 1 × `200`, N−1 × `409` | §7.7 |
| CT-7 | **Raw JDBC**: insert `('BOOKED', instance, '12A')` twice | Second fails with SQLSTATE `23505` | That the index **exists** |
| CT-8 | Two threads book overlapping seat sets `{12A,12B,12C}` and `{12B,12C}` | Loser's `409` body lists **exactly** the lost labels in `unavailableSeats`, and `requestedSeats` is echoed unchanged | §7.8.1 · DEC-21 — the condition under which stale search was granted |
| CT-9 | Two instances `POST` the **same** `flightNumber` + overlapping `daysOfOperation` concurrently | Exactly 1 × `201`; the other `409 DUPLICATE_SCHEDULE`; **zero** half-written schedules — no `flight_schedule` row without its `flight_schedule_day` rows | §7.11 — the admin write path |

| CT-10 | Two threads replay the **same** `Idempotency-Key` concurrently | Exactly one `201`, one `200` carrying the *same* PNR; exactly one `booking` row | §7.2.1 — DEC-24 under contention, not merely in sequence |

CT-7 is not redundant with CT-1. CT-1 passes trivially if the index is silently absent
(the application's own read-then-write check masks it under low contention); CT-7 is
the only test that fails when the enum-mapping trap of §4.7 strikes. CT-5's thread
count deliberately exceeds the default pool size, because a pool-coupled design passes
below it.

---

### 7.11 Schedule creation — the third write path

§7.1–§7.10 document booking, cancellation and materialisation. **Schedule creation is
the third write path in the system, and until this section it had no concurrency
treatment at all** — an omission that only becomes visible when §7 is checked against
§3's write paths in *both* directions rather than read forwards.

It is a write path like the others, so under §2.0.4 it needs the same three answers.

**It spans two tables, so it must be one transaction.** `POST /api/v1/admin/flight-schedules`
inserts one `flight_schedule` row and *k* `flight_schedule_day` rows. `@Transactional`
on the service method is therefore load-bearing, not decorative: without it a crash
between the two inserts leaves a schedule that operates on **no** days — a row that
search can never match and that `uq_flight_number_day` will never flag as a duplicate,
because it owns no day rows to collide with. That is a silent, permanent data defect,
and it is the only way this system can produce one.

**The conflict is detected by `uq_flight_number_day`, not by a read.** Two admins — or,
more likely, one admin whose first response was lost and who retried onto a different
instance — submit `XY101 / {MON, WED, FRI}` at the same time. Both pass validation, both
begin inserting. The second blocks on the first conflicting `(flight_number, day_of_week)`
row, and on the winner's commit fails with `23505` → `409 DUPLICATE_SCHEDULE`
(§7.8), naming the conflicting weekday. Both inserts roll back together, so the loser
leaves nothing behind. **This is §7.1's principle applied to a second constraint**: the
database is the enforcement point, the check-then-insert version would be the race.

**Partial weekday overlap is a conflict, and that is correct.** `XY101 / {MON, TUE}`
against an existing `XY101 / {MON, WED, FRI}` collides on `MON` alone and is rejected
*in full*. The alternative — inserting `TUE` and reporting partial success — would mean
a `201` for a request the caller did not make. The constraint is per-weekday, but the
**request** is atomic.

**What this deliberately does not do.** There is no idempotency key on the admin path.
Under D4 the correct retry behaviour for a lost response is to retry and receive
`409 DUPLICATE_SCHEDULE` — which is not an error to the admin, it is the confirmation
that the first attempt succeeded. API-2 (list schedules) makes that confirmable. A
key-based idempotency table would buy a `201`-on-replay instead of a `409`-on-replay for
a single human operator at ~0 QPS; it is recorded as declined, not overlooked.

| Property | Schedule creation | Booking (§7.5) |
|---|---|---|
| Enforcing constraint | `uq_flight_number_day` | `uq_booked_seat` (partial) |
| Tables written | 2 | 2 (3 on first booking) |
| Conflict response | `409 DUPLICATE_SCHEDULE` | `409 SEAT_UNAVAILABLE` |
| Retry on conflict | No — the conflict *is* the answer | No (§7.2) |
| Instance-count sensitive | No | No |

---

## 8. Algorithms

Deliverable 5 is the only deliverable whose internal structure the brief states with
**"must include"** — every other deliverable says *should include*, *Provide:* or
*Describe:*. Under the three algorithm headings it then names **thirteen** specific
sub-points. They are reproduced below as headings **verbatim and in the brief's own
order**, so that a reviewer scanning for *"Seat locking strategy"* finds that exact
phrase rather than a paraphrase. This section is the source; the README renders it.

Each algorithm is stated as: inputs → steps → complexity → failure modes.

---

### 8.1 Search Algorithm

> Brief: *"Describe how: Flights are identified · Schedules are matched · Flight
> instances are generated · Seat availability is computed"*

#### 8.1.1 Flights are identified

The brief asks how a flight is *identified*, not how it is searched — a different and
prior question: **what is the identity of a bookable flight in this system?**

A bookable flight is identified by the pair **`(flight_number, flight_date)`**. It is
deliberately **not** `flight_instance.id`. Under DEC-1 the `flight_instance` row does
not exist until somebody books, so a surrogate key cannot be the identity of something
that is addressable before it is stored. `flight_instance.id` is an artefact of
materialisation, never an identifier in the API.

Consequences that follow from this and nothing else:

| Consequence | Why |
|---|---|
| URLs address flights by `{flightNumber}/{date}` | The identity must be derivable by a client that has only search results |
| A seat map is addressable before any booking exists | There is no id to hand out yet (§5.1 API-4) |
| The identifier is stable across materialisation | Booking does not change how the flight is addressed |

Resolution of that pair to a schedule is a **single index lookup**, not a scan:
`flight_date → ISO weekday` in the application, then
`uq_flight_number_day (flight_number, day_of_week)`. The index is unique, so the pair
resolves to **at most one** schedule — which is what makes it a legitimate identity. If
two schedules could share a flight number on the same weekday, `(flight_number, date)`
would be ambiguous and the whole addressing scheme would collapse; that index is
therefore a **correctness** constraint for identity, not a performance one.

#### 8.1.2 Schedules are matched

Inputs: `origin`, `destination`, `travelDate`.

1. Guard the booking window: `travelDate ∈ [today, today + 364]` — half-open, AMB-12.
   Outside it, `422`. This runs **before** any query: an out-of-window date cannot
   match anything, so touching the database would be wasted work.
2. Compute the ISO weekday **once, in the application**: `travelDate.getDayOfWeek()
   .getValue()` → `1..7`. It is deliberately *not* computed in SQL as
   `EXTRACT(DOW FROM ...)`, for two reasons: PostgreSQL's `DOW` is `0..6` with Sunday
   as `0` while `java.time` is `1..7` with Monday as `1` — an off-by-one that silently
   returns **the wrong day's flights** rather than failing — and a computed predicate
   is not index-eligible.
3. Match with one query: `flight_schedule` filtered on the route, joined to
   `flight_schedule_day` on the weekday.

`ix_schedule_route (origin_code, destination_code)` serves step 3; the route is the
only selective predicate. The join to `flight_schedule_day` needs no additional index
because that table's primary key is `(schedule_id, day_of_week)` and **leads with
`schedule_id`**, so the join column is already the leading column of an existing index.

#### 8.1.3 Flight instances are generated

**They are not generated on this path at all** — and that is the answer, not an evasion.
The brief licenses it explicitly: *"generate (**or logically expose**)"*, and assigns
generation to the write side — *"the **booking engine** must generate"*.

Search **projects** instances rather than reading them: one matched schedule + the
requested date **is** a flight instance, computed in the `SELECT` list. The
`flight_instance` table is not read by search at all.

What that avoids, concretely. A schedule operating three days a week expands to roughly
**156 instances per year**; fifty such schedules is ~7,800 rows that must be generated
ahead of time, kept correct as the window rolls forward daily, and backfilled whenever
a schedule is added. Under DEC-1 the stored row count equals **the number of flights
that have actually been booked** — typically a small fraction — and the rolling window
needs no job at all, because it is a predicate (`today + 364`) rather than a dataset.

Materialisation happens **only** on the booking write path (§7.4). A read path must
never create a row: if `GET` materialised, crawling seat maps would populate the table
for all 365 days per flight, reintroducing exactly the dataset this design exists to
avoid.

#### 8.1.4 Seat availability is computed

The brief's verb is **computed**, which concedes that availability is derived state.
It is a **set difference**:

```
available(instance) = seats(aircraft) − booked(instance)
```

`seats(aircraft)` is `rows_count × length(seat_letters)` — two columns, no seat table
(§4.2). `booked(instance)` is the count of `booking_seat` rows with `status = 'BOOKED'`.
For search only the **cardinality** is needed, so no labels are materialised (AMB-9).

```sql
SELECT s.flight_number, s.departure_time, s.arrival_time,
       (a.rows_count * length(a.seat_letters)) - COUNT(bs.id) AS available_seats
FROM        flight_schedule     s
JOIN        aircraft            a  ON a.id = s.aircraft_id
JOIN        flight_schedule_day d  ON d.schedule_id = s.id
                                  AND d.day_of_week = :dow
LEFT JOIN   flight_instance     fi ON fi.schedule_id = s.id
                                  AND fi.flight_date = :travelDate
LEFT JOIN   booking_seat        bs ON bs.flight_instance_id = fi.id
                                  AND bs.status = 'BOOKED'
WHERE  s.origin_code = :origin
  AND  s.destination_code = :destination
GROUP BY s.id, a.rows_count, a.seat_letters
ORDER BY s.departure_time, s.flight_number;
```

Four properties of this query are load-bearing:

| Property | What breaks without it |
|---|---|
| **One query, not N+1** | The natural shape — match schedules, then count seats per schedule — is `1 + K` round trips. It passes every functional test and degrades linearly with route popularity. Availability must be an **aggregate**, not a loop. |
| **`LEFT JOIN`, not `JOIN`** | Most instances have no row (§8.1.3). An inner join returns **only flights that have already been booked** — so a brand-new flight is invisible, and the first booking is impossible. |
| **`bs.status='BOOKED'` in the `ON`, not the `WHERE`** | In the `WHERE` clause this silently converts the outer join back to an inner one, because `NULL = 'BOOKED'` is not true. Identical symptom to the row above, with no visible `JOIN` to blame. The single most likely way to reintroduce the §5.1 seat-map defect. |
| **Explicit `ORDER BY`** | SQL guarantees no ordering without it; results become plan-dependent and assertions flake. This is **testability (NFR8)**, not a feature — FR5 puts sorting out of scope, and a *fixed* order adds no customer-visible choice. Under §2.0.4 it has a second effect: plan-dependent ordering can differ **between instances**, so an unordered query would let a customer's results reshuffle on refresh purely because the load balancer picked a different pod. |

Fully-booked flights are returned with `availableSeats: 0`, **not omitted** — dropping
them is a filter, and FR5 places filtering out of scope. An empty result is `200 []`,
never `404`: the route exists, it simply has no flight that day.

**The same rule is applied a second time, and it is the less obvious half.** Notice what
the query does *not* contain: there is no `departure_time > now` predicate. A flight
searched for **today** whose departure time has already passed is still returned, even
though AMB-4 makes it unbookable and booking it yields `409 FLIGHT_DEPARTED`. That is
deliberate, and it is recorded here because it looks exactly like a missing filter:

| Option | Verdict | Why |
|---|---|---|
| Filter departed flights out of search | 🟡 Defensible | Nicer UX — a customer is never shown something they cannot buy. |
| **Return them; enforce bookability at booking** | ✅ **Chosen** | Three reasons. (1) FR5 names *one* filter — *"only flights **operating on the requested date**"* — and then explicitly places *"filtering beyond this"* out of scope; a departed flight **did** operate on the requested date. (2) It is the identical principle that keeps fully-booked flights in the result set, and applying it to one and not the other would be incoherent. (3) **Testability (NFR8)**: adding the predicate makes search results depend on wall-clock time *within* a day, so a test asserting "search returns `XY101` on date D" would pass at 08:00 and fail at 10:00. Search stays a pure projection of the schedule; **bookability is a booking-time invariant (BR-7), enforced once, where it belongs.** |

The seam is the `409`, not a silently shorter list.

**Complexity.** One index seek on the route plus `K` grouped rows, where `K` is the
number of schedules on that route operating that weekday — in practice single digits.
Cost is `O(log N + K)` with one round trip, independent of the 365-day window, because
the window is never enumerated.

#### 8.1.5 The staleness that was granted is not the staleness this query has

DEC-20 granted stale search results on a condition. It is worth being precise about
**which** staleness, because the grant and the query are about two different things and
conflating them produces the wrong design:

| | Server-side staleness | Client-side staleness |
|---|---|---|
| Cause | A cache or read replica serving an older view | Time passing between the search **response** and the booking **request** |
| Present here? | **No.** There is no cache (D3), no replica, no materialised availability. §8.1.4 aggregates `booking_seat` live, inside the request | **Yes, always.** Another customer can book `12A` in the seconds the first spends choosing it |
| Depends on `N`? | Would, if it existed — `N` caches diverge | **No.** It exists identically at `N = 1`. It is a property of humans, not of topology |
| Removable? | Yes, by not caching — which is what is done | **No.** Only a hold/lock model removes it, and holds are not in FR7 |

So the honest statement is: **the grant was accepted, and then not spent.** At DEC-20's
~6 QPS peak a live aggregate costs one indexed round trip, so buying staleness would
trade a correctness property for performance that is not needed. The cache is the first
thing to add if search ever became hot — §4.2.1 names what is safe to cache — but adding
it now would be optimising against a load that does not exist.

**The condition still had to be discharged**, and this is the part that is easy to get
backwards. The interviewer's condition — *"how does the user get informed if there is a
change in the state when he proceeds for a booking"* — applies to the **second** column,
which no amount of cache-avoidance removes. §7.8.1's `409` body is therefore not a
concession made in exchange for caching; it is the answer to a question that was already
live before the grant was offered.

**What this costs.** Availability is recomputed on every search rather than maintained.
That is deliberate and recorded under the same reasoning as §4.5's derived-seat argument:
a maintained counter is a second source of truth that can drift from `booking_seat`, and
keeping it correct under `N` instances means it must be updated in the booking
transaction — which turns every booking into a write against one hot row per flight, and
serialises bookings that have no seat in common. **A recomputed aggregate has no such
contention point.** Trading a cheap read for a contended write is the wrong direction for
a system whose stated priority is *"booking — ultimate stability"*.

---

### 8.2 Booking Algorithm

> Brief: *"Describe: Seat validation · Seat locking strategy · Transaction flow ·
> Booking creation · Concurrency handling"*

#### 8.2.1 Seat validation

Four checks, in a **fixed order**, chosen so that each one is answerable with strictly
more context than the last and nothing hits the database earlier than it must:

| # | Check | Needs | Failure |
|---|---|---|---|
| 1 | Request well-formed: non-empty seats, names present, seat labels match `^[1-9][0-9]?[A-Z]$` | Nothing | `400` |
| 2 | No seat repeated **within the request** | The request only | `400` |
| 3 | `(flightNumber, date)` resolves to a schedule operating that weekday, date in window | One index lookup | `404` / `422` |
| 4 | Every label exists **on this aircraft**: `row ≤ rows_count` and `letter ∈ seat_letters` | The aircraft | `422` |

Check 2 is not pedantry: `["12A","12A"]` in one request would otherwise violate the
unique index and surface as a `409 SEAT_UNAVAILABLE`, telling the caller somebody else
holds a seat that nobody holds. It is a malformed request and must say so.

Check 4 is `422`, not `400` — `45A` is perfectly well-formed and is a real seat on a
50-row aircraft. Its validity is a property of **this flight**, not of the string, so
it cannot be decided without a lookup (§5.2).

Labels are **canonicalised before persisting** — `parseInt(row)` strips leading zeros,
letter uppercased with `Locale.ROOT` — because `'12A'`, `'012A'` and `'12a'` are three
distinct keys to a unique index, and a seat booked under one form would not conflict
with the same seat booked under another. `ck_seat_label` is the backstop (§4.7).

What is deliberately **not** validated: passenger names. FR7.4 states *"No passenger
validation is required"*, so there is **no** format, charset, realness or non-blank
check — `""`, `"x"` and `"!!!"` are all accepted passengers.

One boundary does exist and is stated precisely, because the obvious reading of FR7
leaves a `500` waiting to happen. `passenger_name` is `VARCHAR(100)`, so a 101-character
name raises PostgreSQL `22001 string_data_right_truncation` **inside the booking
transaction** — surfacing as a `500` on a request that is the client's fault, which the
Error-handling NFR forbids:

| Option | Verdict | Why |
|---|---|---|
| Column `TEXT`, no bound at all | 🟡 Most literal | FR7 asks for no validation, so impose none. Rejected only because *some* bound must exist somewhere — otherwise it is the HTTP request-size limit, which fails with an even less meaningful error. |
| `VARCHAR(100)` and let the driver throw | ❌ | Turns a client error into a `500`. The constraint exists either way; leaving it unhandled just picks the worst status code. |
| **`VARCHAR(100)`, enforced at the edge as `400`** | ✅ **Chosen** | By §5.2's own rule a length limit is **syntactic** — answerable without touching the database — so it is a `400`, consistent with every other malformed-request case. |

The distinction being drawn: *"no validation required"* relieves this design of judging
whether a name is **plausible**; it does not oblige it to accept **unbounded** input.
`length ≤ 100` is a declared property of the storage contract, not an opinion about
names — which is why it is the one rule BR-9 permits.

#### 8.2.2 Seat locking strategy

**There is no application-level lock, and no row is locked to reserve a seat.**
Correctness rests on one partial unique index (§7.1):

```sql
CREATE UNIQUE INDEX uq_booking_seat_active
    ON booking_seat (flight_instance_id, seat_label)
    WHERE status = 'BOOKED';
```

The index **is** the locking strategy. Two transactions inserting the same
`(instance, seat)` cannot both commit: the second blocks on the index entry until the
first resolves, then either succeeds — if the first rolled back — or fails with
SQLSTATE `23505`. PostgreSQL's unique-index insertion provides the mutual exclusion; no
`SELECT … FOR UPDATE`, no `@Version`, no distributed lock participates.

Why this rather than the alternatives the brief names (full treatment in DEC-2):

| Strategy | Why not chosen here |
|---|---|
| Pessimistic row lock on the flight | Serialises **the whole flight**, including passengers choosing different seats, and the lock holds for the transaction's duration |
| Optimistic `@Version` on the flight | Same scope problem, plus it converts a non-conflict into a retry; and it is **unenforceable** against anything that does not go through the ORM |
| Versioning / event sourcing | Enormous scope for one invariant |
| Application mutex | Correct on one instance; **wrong the moment a second replica starts** — the failure appears only in production |

The decisive property is that this guarantee lives **below the application**. It holds
for any number of application instances, for raw JDBC, for a psql session, and for a
future service that never loads this codebase. CT-7 asserts it at exactly that level —
two raw inserts, no application code — because CT-1 can pass with the index absent.

The `WHERE status = 'BOOKED'` predicate is what allows cancellation to retain history
while releasing the seat: a `RELEASED` row stays in the table but leaves no index entry,
so the seat is immediately rebookable (§8.3.2).

#### 8.2.3 Transaction flow

**Two sequential transactions, never nested** (§7.5). Both at `READ COMMITTED`.

```
T1  materialise                      T2  book
──────────────────────────           ──────────────────────────────────
INSERT flight_instance               INSERT booking   (status CONFIRMED)
  ON CONFLICT DO UPDATE              INSERT booking_seat × N  ← index arbitrates
  RETURNING id                       COMMIT
COMMIT  ───────────────────────────▶
```

T1 uses `ON CONFLICT … DO UPDATE`, not `DO NOTHING`. Under concurrency `DO NOTHING`
returns **zero rows**, and the follow-up `SELECT` takes a fresh `READ COMMITTED`
snapshot in which the competing transaction has still not committed — so it returns
nothing either, producing an intermittent `500` on a valid booking under exactly the
load CT-5 applies. `DO UPDATE` takes a tuple lock and waits out the speculative insert,
so `RETURNING id` always yields a row. Cost: one dead tuple, once per flight, ever
(§7.4).

T1 commits **before** T2 opens, so a failed booking leaves a materialised instance
behind. That is harmless — an empty instance is indistinguishable from one that was
never booked, and the next attempt reuses it. The alternative, `REQUIRES_NEW` nesting,
holds T1's connection open for T2's lifetime and **deadlocks the pool** once concurrent
bookings reach the pool size — which CT-5's `N ≥ 12` is specifically sized to expose
(DEC-13).

Boundaries are declared at the **service** method. Controllers never open transactions;
repositories never widen them.

#### 8.2.4 Booking creation

Inside T2, in this order:

1. Generate a PNR: 6 characters from `A-HJ-NP-Z2-9` — `I`/`O`/`0`/`1` excluded as
   visually ambiguous (DEC-5), enforced by `ck_booking_pnr` so the rule survives any
   future generator.
2. Insert `booking` with `status = 'CONFIRMED'`.
3. Insert one `booking_seat` per passenger — **one row = one passenger in one seat** —
   carrying `booking_id`, `flight_instance_id`, canonical label, name, `status='BOOKED'`.
4. Commit.
5. Assemble the response: PNR, flight number, flight date, passenger count, assigned
   seats, status — the six fields FR7.4 mandates.

`passengerCount` is **derived from the seat rows**, never stored. A stored count is a
second copy of a fact that can drift; a derived one cannot disagree with the seats
(AMB-8).

PNR collision is handled by the unique constraint, not by a pre-check: a
`SELECT`-then-`INSERT` is itself a race. On `23505` against `uq_booking_pnr`
specifically, regenerate and retry — bounded. This is the **one** place a retry is
correct, because a new PNR makes the next attempt genuinely different. A retry on
`uq_booking_seat_active` would be wrong: the seat is taken, and retrying changes nothing
(§7.2).

#### 8.2.5 Concurrency handling

FR8 names **two distinct hazards**, not one phrased twice:

| Hazard | Nature | Caught by |
|---|---|---|
| *"Double booking"* | **Sequential** — retry, double-submit, duplicate delivery. No lock engages, because nothing overlaps in time | The index — stored state has no notion of timing |
| *"Multiple customers booking the same seat"* | **Concurrent** — genuinely overlapping transactions | The index — second insert blocks, then `23505` |

A lock addresses only the second. A constraint on stored state addresses both, which is
the actual argument for this design — not simplicity.

Conflict mapping (§7.8): `23505` on `uq_booking_seat_active` → **`409 SEAT_UNAVAILABLE`**,
naming the offending seats. No retry, no partial success: the whole booking fails, since
a party booked together should not be split silently.

No deadlock is possible on the seat path: there is no multi-row lock acquisition to
order. The only waits are on individual index entries, released at commit or rollback.

---

### 8.3 Cancellation Algorithm

> Brief: *"Describe: Booking lookup · Seat release · Transaction flow · Status updates"*

#### 8.3.1 Booking lookup

By PNR, via `uq_booking_pnr` — one unique-index seek. Unknown PNR → `404`.

The PNR is the **only** handle a customer has (FR7.4 returns it and nothing else
addressable), so it is the lookup key for both cancel and retrieve. Numeric `booking.id`
is never exposed: it is sequential, so exposing it would let anyone enumerate every
booking in the system by counting.

#### 8.3.2 Seat release

`booking_seat` rows are **never deleted**. Cancellation flips `status` to `RELEASED`:

```sql
UPDATE booking_seat SET status = 'RELEASED'
WHERE booking_id = :id AND status = 'BOOKED';
```

Because `uq_booking_seat_active` is partial on `WHERE status = 'BOOKED'`, flipping the
status **removes the index entry**, so the seat becomes immediately rebookable — by
anyone, including a different customer, with no further action. FR9.4's *"make seats
available for future bookings"* is satisfied by the index predicate itself; there is no
release job, no cache to invalidate, and no window in which the seat is neither held nor
free.

Deleting the rows instead would also free the seat, but would destroy the record of who
sat where — unrecoverable, and the first thing anyone investigating a dispute asks for.
Retaining them costs one bounded row per cancelled passenger and keeps
`ix_seat_booking (booking_id)` serving the lookup.

#### 8.3.3 Transaction flow

**One transaction** — both writes commit together or neither does:

```
BEGIN
  UPDATE booking      SET status='CANCELLED', cancelled_at=now()
    WHERE id=:id AND status='CONFIRMED'        ← rowcount is the decision
  if rowcount = 0 → rollback, 409
  UPDATE booking_seat SET status='RELEASED'
    WHERE booking_id=:id AND status='BOOKED'
COMMIT
```

Splitting these is not an option. A booking marked `CANCELLED` whose seats are still
`BOOKED` is **permanently unbookable inventory** that no API can reach — the seat is
held by a booking that no longer exists as far as every query is concerned. That is
silent, uncorrectable revenue loss, and it is why the transaction boundary is here and
not around each statement.

Write order is `booking` then `booking_seat`, matching the booking path's parent-then-
child order (§7.6). Consistent ordering across both paths is what makes lock-ordering
deadlock impossible.

#### 8.3.4 Status updates

`CONFIRMED → CANCELLED` is the **only** legal transition. There is no un-cancel: the
seats are released the instant the transaction commits and may already belong to someone
else, so the prior state is not restorable (BR-12).

The guard is the `AND status = 'CONFIRMED'` predicate, **not** a read-then-write:

| Approach | Behaviour on a double-click |
|---|---|
| `SELECT`, check, `UPDATE` | Both threads read `CONFIRMED`, both update, **both return `200`** |
| Conditional `UPDATE`, rowcount decides | One updates one row → `200`; the other updates zero → `409` |

The conditional form is atomic at `READ COMMITTED`, needs no lock and no version column,
and makes BR-12 testable under concurrency rather than only single-threaded — which is
precisely what CT-6 asserts.

`ck_booking_cancel` makes `status` and `cancelled_at` consistent **by construction**: a
`CANCELLED` row with no timestamp, or a `CONFIRMED` row with one, cannot be stored at
all. The pairing is a schema invariant rather than a convention someone must remember.

---

### 8.4 Complexity summary

| Operation | Round trips | Cost | Scales with |
|---|---|---|---|
| Search | 1 | `O(log N + K)` | Schedules on the route, **not** the 365-day window |
| Seat map | 1 | `O(log N + B)` | Seats **booked** on that flight, not total seats |
| Booking | 2 | `O(log N + P)` | Passengers in the request |
| Cancellation | 1 | `O(log N + P)` | Passengers in the booking |

No operation is `O(days)`, `O(instances)` or `O(total seats)`. That is the direct
consequence of DEC-1 (instances are projected, not enumerated) and §4.2/§4.7 (seats are
derived and only *booked* seats are stored) — the two decisions that keep the whole
system's cost proportional to **what has actually happened**, not to the size of the
bookable calendar.


## 9. Acceptance Criteria

- [ ] A schedule can be created and listed; invalid input returns `400` with `problem+json`
- [ ] Search returns only flights whose weekday matches, with correct available-seat counts
- [ ] Search accepts `today` and `today + 364`, rejects `today + 365` with `422` (half-open window — AMB-12)
- [ ] Search returns a fully-booked flight with `availableSeats: 0` rather than omitting it (FR5 excludes filtering)
- [ ] Search for **today** still returns a flight whose departure time has already passed; booking that flight then returns `409 FLIGHT_DEPARTED` — search is a projection, bookability is enforced at booking (AMB-4, BR-7)
- [ ] Search on a valid route with no operating flight returns `200 []`, not `404`
- [ ] Seat map returns every label from the aircraft config, each `AVAILABLE` or `BOOKED`
- [ ] Seat map reflects a booking immediately after it is made
- [ ] Seat map on a never-booked flight returns all seats `AVAILABLE` and creates **no** `flight_instance` row
- [ ] Seat map for a date the flight does not operate returns `404`
- [ ] A request supplying `X-Correlation-Id` has that exact value on every log line **and** echoed in the response header; two sequential requests on the **same pooled thread** never share an ID (MDC cleanup — NFR7)
- [ ] Every log line carries an **instance identifier** alongside the correlation ID, so aggregated logs for one correlation ID remain readable when requests are served by different pods (NFR7, §2.0.4)
- [ ] **ArchUnit**: no `HttpSession` parameter or field and no `@SessionAttributes` anywhere — the statelessness constraint of NFR3 is enforced, not asserted
- [ ] Cancelling an **unknown** PNR returns `404`, not `409` — the lookup precedes the conditional write (§7.7, §8.3.1)
- [ ] A 101-character passenger name returns `400`, not `500` (BR-9 storage bound enforced at the edge); `""` is accepted as a valid passenger (FR7.4 — no non-blank rule)
- [ ] Seat map `seats` array is ordered row ascending then `seat_letters` order, and contains **only** `label` and `status` — no `passengerName` on any seat (FR6 defines two states and nothing else)
- [ ] Booking returns PNR, flight number, flight date, passenger count, seats, status
- [ ] Passenger count always equals seat count; mismatched name/seat counts return `400`
- [ ] Creating a schedule that reuses a flight number on an already-used weekday returns `409`
- [ ] Booking an already-booked seat returns `409 SEAT_UNAVAILABLE`
- [ ] The `409 SEAT_UNAVAILABLE` body lists **exactly** the lost labels in `unavailableSeats` and echoes `requestedSeats` — the condition on which stale search was granted (§7.8.1, DEC-21, CT-8)
- [ ] Booking a departed same-day flight returns `409`
- [ ] **Booking** `today + 365` returns `422 DATE_OUT_OF_WINDOW`, and `today + 364` succeeds — the window is tested on the booking path and not only on search, because Scope binds it to *"bookings"* (SCOPE-4)
- [ ] Cancellation sets status `CANCELLED` and releases all seats
- [ ] A released seat is immediately rebookable
- [ ] Cancelling an already-cancelled booking returns `409`
- [ ] Cancelling a booking on a departed flight returns `409`
- [ ] All ten concurrency tests (CT-1…CT-10) pass — including CT-6 (concurrent cancellation of one PNR) and CT-7 (raw-JDBC proof that the partial unique index exists in the database, not merely in the application)
- [ ] ArchUnit layer-dependency rules pass
- [ ] ArchUnit rule passes: no production call to no-arg `LocalDate.now()` / `Instant.now()` — time enters only through the injected `Clock` (§6.1, D7)
- [ ] A `Clock.fixed(...)` test proves the BR-6 boundary pair without mutating the host clock (§6.1)
- [ ] **Connection budget**: a test asserts `maximumPoolSize` equals the value §7.5.1 derives from `max_connections`, and that `N_planned × maximumPoolSize` stays inside the usable budget. Rule D8 — the quality toolchain never opens `application.yml`, so without this the pool can drift back to a number that silently caps the cluster
- [ ] **Configuration agreement**: a test asserts `connection-timeout` and `airline.booking.window-days` match the values this spec derives (§7.5.2, §6.1). Only *derived* values are asserted — asserting chosen ones produces a change-detector test that gets disabled
- [ ] **Saturation is typed**: `SQLTransientConnectionException` surfaces as `503` with `Retry-After`, never `500` (§7.5.2)
- [ ] **Readiness is separable from liveness**: `/actuator/health/readiness` reports `DOWN` when the database is unreachable while `/actuator/health/liveness` stays `UP` (§5) — a container must leave the rotation without being restarted
- [ ] **The topology is startable**: `docker compose up --scale app=3` brings up three instances; booking the same seat concurrently through two different host ports yields exactly one `201` and one `409`, proving the invariant holds across JVMs and not merely across threads
- [ ] **Booking replay**: the same `Idempotency-Key` replayed returns `200` with the *original* booking and creates no second row; a different key for the same seats still returns `409` (DEC-24). CT-10
- [ ] **Unkeyed bookings are unaffected**: many bookings with no `Idempotency-Key` header coexist — `NULL`s do not collide in `uq_booking_idem` (DEC-24)
- [ ] **Search does bounded work**: Hibernate statistics assert a bounded statement count for a multi-result search, and `EXPLAIN` shows an index scan with no sequential scan on `flight_schedule` or `booking_seat` (DEC-26) — the shape of the work is asserted, because that is what regresses
- [ ] **Search backstop**: a deliberately loose 1-second wall-clock bound on a seeded dataset (DEC-26). Loose on purpose — it must catch a catastrophic regression without arbitrating CI hardware
- [ ] **Search is bounded**: the search service runs `@Transactional(readOnly = true, timeout = 2)`; booking carries no statement timeout (§7.5.3, DEC-27)
- [ ] **Timeouts match the clarified policy**: `connection-timeout` is 20s, derived for the booking path, and asserted (D8, §7.5.3)
- [ ] **Index set exists**: all five secondary indexes of §4.9 are created by `V1__baseline_schema.sql` (Deliverable 4 *"Indexes"*, DEC-30)
- [ ] **Virtual threads are off**: `spring.threads.virtual.enabled` is `false` and asserted (DEC-29) — a stated rejection, not an omission
- [ ] `./mvnw verify` is green from a clean clone

---

## 10. Out of Scope / Black Boxes

**Out of scope** — not built, by explicit instruction of the brief:
airport CRUD · aircraft CRUD · schedule update/delete · payments and refunds ·
seat holds / temporary reservations · search sorting, filtering, pagination ·
multi-leg, connecting or round-trip itineraries · multiple airlines · fares and
pricing · check-in, boarding, baggage · overbooking and waitlists · seat classes ·
customer accounts · time-zone conversion · notifications.

**Black boxes** — the brief says *"Any functionality not explicitly mentioned should be
considered out of scope and treated as a black box"*, and the hiring team added
*"you can leave those calls as a static response"*.

**Audit result: no in-scope flow calls an external system.** Search, seat map, booking
and cancellation are satisfied entirely by this service and its database. Refunds are
explicitly out of scope (FR9), so there is no payment integration to stub. The only
black box is **admin authentication**, documented in the HLD as a deployment-topology
concern rather than faked with a no-op stub that would add a moving part without
adding meaning. See DEC-14.

### 10.1 The supplementary instruction, read closely

> *"A **working Service**, **Backend only** along with the docs preferable in a github
> repo. No need to code for the black boxes explained in the assignment, you can leave
> those calls as a static response."*

This came from the hiring team **outside the document**, which makes it the most recent
and most direct statement of expectation. Four obligations are carried in two sentences.

| Phrase | Obligation | How it is discharged |
|---|---|---|
| *"A **working** Service"* | **Working** is the first word. It independently confirms Evaluation Criterion 1, *Correctness of functionality*, which is also listed first (§2.5) | `docker compose up` verified end to end before any application code was written (DEC-16) |
| *"**Backend only**"* | No UI, no server-rendered views, no BFF | No `static/` or `templates/` resource directories exist — they were removed rather than left as empty scaffolding. The only interface is JSON over HTTP |
| *"along with the **docs**"* | Documentation ships *alongside* the service, not instead of it | `README.md`, `hld/`, `lld/`, `db/` (§2.3) |
| *"preferable in a **github repo**"* | Delivery is a repository the reviewer can **open** | ⚠️ The repository is currently **private** — see below |

**Repository accessibility is a submission-blocking item, not a formality.** A private
repository is indistinguishable from a missing submission: the reviewer sees a 404 and
has no way to tell whether the work exists. Before submission the repository must either
be made public or have the reviewers added as collaborators. This is tracked as an
explicit obligation rather than left to be remembered on the day.

### 10.2 Why no stub was written, despite being invited to

*"You can leave those calls as a static response"* is a **licence, not an instruction** —
and the licence presupposes that calls exist. In this design they do not: every mandated
flow (search, seat map, booking, cancellation) is satisfied by this service and its own
database.

The temptation is to write a `PaymentGatewayStub` returning a fixed `APPROVED` to
demonstrate that the seam was understood. That would be a mistake, and naming why is the
point:

- FR7 defines booking as *select flight → view seats → select seats → create booking*.
  **There is no payment step.** Adding a stubbed call inserts a step the brief does not
  have, which is scope creep wearing the costume of compliance.
- FR9 says *"Refund processing is out of scope"*. A refund presupposes a payment, so the
  brief is signalling that the **entire payment dimension** is excluded — not that a
  placeholder for it is wanted.

What **is** acknowledged structurally, because it costs nothing and the brief's own Scope
section names it, is the **administrative versus customer trust boundary**. The brief
distinguishes an *"administrative back office"* from *"customers"*, so the API surface
distinguishes them too:

| Audience | Path prefix |
|---|---|
| Back office | `/api/v1/admin/…` |
| Customer | `/api/v1/…` |

That is a boundary, not a stub. It makes the trust distinction visible in the URL — so
the point at which authentication *would* attach is obvious to a reviewer — without
implementing an authentication mechanism the brief never asked for.

---

## 11. Assumptions

### 11.0 The brief's nine assumptions, and what each one buys

The brief's framing sentence is doing more work than it appears to:

> *"**Unless explicitly implemented otherwise**, the following assumptions **may** be
> made:"*

That is a **grant of permission with a disclosure condition**, not a list of
requirements. Two consequences:

- An assumption we **adopt** needs no defence — the brief already licensed it.
- An assumption we **deviate from** must be *explicitly implemented* and said out loud.
  A silent deviation is the worst case: it reads as an oversight rather than a choice.

Every one of the nine is adopted. None is deviated from. What differs between them is
how much each one is actually **load-bearing** — three of them are doing structural work
that the design would not survive without, and those are called out below.

| # | Brief's assumption | Adopted | What it buys / what depends on it |
|---|---|---|---|
| 1 | Single airline only | ✅ | **No carrier entity anywhere.** It would be a single-valued column on every row (OBJ-1) |
| 2 | One seat belongs to only one booking | ✅ *as refined* | `uq_booking_seat_active` — see **A2** below, the wording is literally false as given |
| 3 | One booking is for a single flight instance | ✅ | `booking.flight_instance_id` is a single `NOT NULL` FK — no itinerary, no legs, no round-trip, no partial cancellation (§11 item 17) |
| 4 | Seat maps are fixed per aircraft | ✅ | **Seats are derived, never stored** — two columns replace ~180 rows per aircraft (§4.2) |
| 5 | Flight schedules do not change after creation | ✅ | No update/delete endpoints — and more than that, see **A5** below |
| 6 | Airports and aircraft are preloaded | ✅ | No CRUD (FR1.2, FR2.5); seeded by Flyway migration, which is what *"Database initialization"* points at in the README |
| 7 | Booking window up to one year from current date | ✅ | `[today, today + 364]`, **rolling**, evaluated per request — a predicate, not a dataset, so no job maintains it (AMB-12, DEC-1). *Which* `today`, given `N` instances hold `N` clocks, is settled in **§6.1** |
| 8 | All timestamps stored in UTC | ✅ *as scoped* | `TIMESTAMPTZ` for instants — see **A8** below for why `departure_time` is exempt |
| 9 | Time zone conversion out of scope | ✅ | No `timezone` column on `airport`; no duration field; **no true departure instant exists** (AMB-1, AMB-2) |

### A2 — "One seat belongs to only one booking" is false as literally written

FR9 mandates cancellation. After a cancel-and-rebook, seat `12A` has belonged to booking
`B1` (now `CANCELLED`) **and** to booking `B2` (now `CONFIRMED`). The literal statement
cannot hold in any system that supports cancellation — and cancellation is a functional
requirement, not an optional extra. The brief contradicts itself here in the same way it
does at AMB-1 and AMB-12.

The only reading consistent with FR9 is:

> **At most one *active* booking per `(flight instance, seat)`.**

Two scoping points are implied and neither is stated by the brief:

- **Scope is per flight instance, not per physical seat.** Seat `12A` on A320-001 exists
  on every flight that aircraft operates; uniqueness is `(flight_instance_id, seat_label)`,
  never `seat_label` alone.
- **"Belongs" means currently held, not historically occupied.** History is retained —
  `booking_seat` rows are never deleted (§4.7).

This refined reading is exactly what `uq_booking_seat_active` encodes: unique on
`(flight_instance_id, seat_label)` **`WHERE status = 'BOOKED'`**. The word *active* in the
assumption and the word `BOOKED` in the index predicate are the same word. Had the literal
reading been implemented — a total unique index — **cancellation would permanently destroy
inventory**: the released seat's row would block any future booking of that seat forever.

### A5 — Schedule immutability is load-bearing, not just a simplification

The obvious consequence is that there are no `PUT`/`PATCH`/`DELETE` endpoints for
schedules (API-1 creates, API-2 lists, and that is the whole admin surface).

The non-obvious consequences are the ones worth stating, because the design **depends** on
them rather than merely benefiting:

| Guarantee relied on | What breaks without it |
|---|---|
| `flight_number` is **denormalised** onto `flight_schedule_day` to serve `uq_flight_number_day` | If a flight number could be edited, the copy would need cascading updates, and a stale copy makes `(flightNumber, date)` resolve to the wrong schedule — or to nothing |
| `aircraft_id` never changes | An equipment swap from a 6-abreast A320 to a 4-abreast ATR72 would leave **already-booked seats that no longer exist** (`30F` on a 4-letter aircraft). There is no reconciliation path, and none is in scope |
| `departure_time` never changes | A materialised `flight_instance` carries no time of its own; it derives time from the schedule. If the schedule moved, every existing booking would silently shift |
| The **projection is deterministic across instances** | This is the read-side twin of §4.2.1. Under DEC-1 the bookable instance list is computed, not stored, so at `N ≥ 2` every instance computes it independently from its own heap. Two instances cannot disagree about whether `XY101` operates on a given date **only because no write can move the input beneath them**. Were schedules mutable, the same projection would become a per-instance read of racing state, and search results would differ by which instance the load balancer picked |

If schedule mutation were ever added, **none of these is a small change** — each is a data
migration plus a conflict-resolution policy. Recording that here is the point: the
extension seam is identified and deliberately not built, rather than discovered later.

### A8 — "All timestamps stored in UTC" does not apply to schedule times

Taken at face value this assumption appears to be violated by `flight_schedule`, whose
`departure_time` and `arrival_time` are `TIME`, carry no zone, and are **not** UTC. They
are not violations, because they are not timestamps:

| Column | Type | Is it an instant? | Stored as |
|---|---|---|---|
| `created_at`, `cancelled_at` | `TIMESTAMPTZ` | ✅ a point on the timeline | **UTC** — PostgreSQL `TIMESTAMPTZ` stores a UTC instant and converts only on output |
| `departure_time`, `arrival_time` | `TIME` | ❌ a **time of day** | Exactly as the back office entered it (AMB-1) |
| `flight_date` | `DATE` | ❌ a calendar date | As given |

A flight departs *09:30 local*, which is a wall-clock fact about the origin airport.
Converting it to UTC would require the airport's offset — and assumption 9 puts timezone
conversion out of scope, so that offset is not available anywhere in the model. Storing
a local time **as if** it were UTC would be worse than either: a silently wrong instant
that every downstream calculation inherits.

Assumptions 8 and 9 are therefore consistent only under this reading: **"timestamp" means
instant.** Dates and times-of-day are stored as given, and the system never computes a
true departure instant at all (AMB-2). That is also why `arrival_time < departure_time` is
legal and deliberately unvalidated — an overnight flight is a normal flight, and without
offsets there is no arithmetic that could tell the difference (BR-4).

### 11.1 Assumptions this design adds

The nine above are the brief's. The following are **ours** — decisions taken where the
brief is silent. They are listed separately because the distinction matters to a reviewer:
the first list needs no justification, this one does.

1. Single airline; no carrier dimension anywhere in the model.
2. One seat belongs to at most one **active** booking.
3. One booking covers exactly one flight instance.
4. Seat maps are fixed per aircraft and never change.
5. Schedules are immutable after creation.
6. Airports and aircraft are preloaded via Flyway seed migrations.
7. Booking horizon is `[today, today + 364]` — **365 distinct dates**, half-open at
   the far end (AMB-12).
8. Audit instants are stored in UTC (`TIMESTAMPTZ`); schedule times are local times of day (AMB-1).
9. Departure-instant comparison uses UTC as the system reference zone and may be off by the airport's offset (AMB-2).
10. Every flight operates aircraft of a single, fixed configuration — no equipment swaps.
11. The caller supplies explicit seat labels; the system never auto-assigns seats.
12. No authentication; admin endpoints are protected by deployment topology (black box).
13. The 365-day horizon is **rolling** — it advances daily. Lazy materialisation
    (DEC-1) satisfies this with no scheduled job; full pre-materialisation would need
    one. *(FR4 says "from the current date", not "from schedule creation".)*
14. Search is by **IATA airport code**, not city. FR3's "Dubai → London" example names
    cities, but FR5 says "Source Airport" and FR1 mandates an IATA code.
15. Search returns arrival **time** only, with no next-day indicator, exactly as FR5
    specifies — even though BR-4 permits overnight flights.
16. Booking status is `CONFIRMED | CANCELLED` (AMB-11); the brief never enumerates them.
17. Partial cancellation of individual seats is not supported — FR9 cancels a *booking*.

---

## 12. Lifecycle Impact

- **Cross-cutting**: No — **one deployable artefact**, no inter-service flow. Note this says *one artefact*, not *one process*: that artefact runs as `N ≥ 2` instances per §2.0.4. "Not cross-cutting" is a statement about service boundaries, not about replica count.
- **Reason**: One Spring Boot application and one database. Component interaction is
  internal and belongs in the HLD/LLD rather than a cross-service lifecycle document.

---

## 13. Approval

| Gate | Token | Status |
|---|---|---|
| Spec approved | `APPROVE` / `REVISE: [feedback]` | **Awaiting** |
