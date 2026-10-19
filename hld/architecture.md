# High-Level Design — Airline Ticketing System

> **Status:** outline. Sections are filled in during the design pass.

---

## Table of contents

| § | Section | Maps to brief requirement |
|---|---|---|
| 1 | [Problem understanding](#1-problem-understanding) | — |
| 2 | [Requirements](#2-requirements) | Functional / Non-functional requirements |
| 3 | [Assumptions](#3-assumptions) | HLD → "Assumptions" |
| 4 | [Core entities](#4-core-entities) | Database design |
| 5 | [API surface](#5-api-surface) | API design |
| 6 | [Architecture — as built](#6-architecture--as-built) | HLD → "Overall architecture", "Components", "Service interactions", "Request flow" |
| 7 | [Deep dives](#7-deep-dives) | HLD → "Flight generation strategy", "Concurrency strategy" |
| 8 | [Major design decisions](#8-major-design-decisions) | HLD → "Major design decisions" |
| 9 | [Scale-out evolution — NOT IMPLEMENTED](#9-scale-out-evolution--not-implemented) | Evaluation → "Scalability considerations" |
| 10 | [Black boxes](#10-black-boxes) | Scope → "treated as a black box" |

### Deliverable coverage — the brief's eight mandated HLD sections

The brief lists exactly what an HLD *"should include"*. Each item is mapped to the
section that satisfies it, so coverage can be checked without reading the document
first. Nothing here is implied by a neighbouring section: *"Service interactions"* in
particular is **not** the same question as *"Components"* (what the parts are) or
*"Request flow"* (the path of one request) — it is the contract governing which part may
call which, and under what transactional and failure semantics.

| # | Brief mandate | Section |
|---|---|---|
| 1 | Overall architecture | §6, §6.1 Deployment view |
| 2 | Components | §6.2 Component view |
| 3 | **Service interactions** | **§6.4 Service interactions** |
| 4 | Request flow | §6.3 Request flows |
| 5 | Major design decisions | §8 Major design decisions |
| 6 | Assumptions | §3 Assumptions |
| 7 | Concurrency strategy | §7.2 Concurrency strategy |
| 8 | Flight generation strategy | §7.1 Flight instance generation strategy |
| + | *"Architecture diagrams are encouraged"* | `hld/diagrams/` — C4 context + container, ER, two sequence diagrams |

---

> **Reading note.** §6 describes **what is actually built and running in this
> repository**. §9 describes how the same design would evolve under load that this
> assignment does not require. The two are kept rigorously separate so that nothing
> in §9 can be mistaken for an implemented capability.

---

## 1. Problem understanding

*To be completed.*

---

## 2. Requirements

### 2.1 Functional — in scope

*To be completed.*

### 2.2 Non-functional — in scope

*To be completed.*

### 2.3 Below the line — explicitly out of scope

*To be completed.*

---

## 3. Assumptions

*To be completed.*

---

## 4. Core entities

*To be completed. Entity-relationship diagram and the reasoning behind each table.*

---

## 5. API surface

*To be completed.*

---

## 6. Architecture — as built

### 6.1 Deployment view

*To be completed — must cover:*

- **`N ≥ 2` stateless application instances behind a load balancer, one PostgreSQL**
  (DEC-19). This is the single most consequential statement in the HLD, because every
  concurrency claim in §7.2 is conditional on it. Drawing one box labelled "app" would
  quietly assume the easy case.
- **Why the topology was decided at all**, given the brief never mentions deployment:
  silence is not permission to assume a single process, because a rolling deployment
  alone forces `N = 2` during every release (DEC-19, option A).
- **What the graded artifact actually runs**: one application container plus PostgreSQL
  via `docker compose` (DEC-16), because the interviewer stated they will bring up a
  single instance. The diagram must make the distinction visible — *design for `N`,
  ship `1`* — and state why they are not in tension: **instance count is not an input to
  correctness, because every invariant is enforced in PostgreSQL** (spec §7.1).
- **Rules D1–D7** (DEC-19) as the constraints the rest of the design is built under,
  with the discharge point for each. D5 (concurrent Flyway startup) and D6 (the
  connection budget is `N × pool`) are the two that are invisible at `N = 1` and
  therefore the two a reviewer will probe.
- **What is deliberately absent from the diagram**: no Redis, no message broker, no
  distributed lock manager, no read replica, no service mesh. Each absence is a decision
  with arithmetic behind it (DEC-20, §9), not a gap.

### 6.2 Component view

*To be completed — must cover:*

- **Package-by-feature boundaries** (`schedule`, `search`, `booking`, `seatmap`) with
  layering inside each, enforced by ArchUnit rather than drawn (DEC-10).
- **One deployable, two namespaces** (DEC-23): the admin and customer surfaces are
  separated at `/api/v1/admin/*` with a documented authentication seam, but are not two
  services. The component view must show the seam and state the flip threshold —
  independent load or independent availability requirements — so the boundary reads as
  reversible rather than merely deferred.
- **Stateless by construction** (spec NFR3): no session, no server-side cart, no hold
  state. This is what makes the `N` boxes in §6.1 interchangeable.

### 6.3 Request flows

One flow per functional requirement:

| # | Flow | Status |
|---|---|---|
| 1 | Create flight schedule (back office) | *to be completed* |
| 2 | Search flights | *to be completed* |
| 3 | View seat map | *to be completed* |
| 4 | Create booking | *to be completed* |
| 5 | Cancel booking | *to be completed* |

---

### 6.4 Service interactions

*To be completed — must cover:*

- **Call matrix**: which component may call which, and the directions that are forbidden.
  Enforced by ArchUnit (NFR1/NFR2), not merely drawn.
- **The one rule that shapes everything else**: application services never call one
  another. A use case is one transaction, and nesting them is what produced the
  `REQUIRES_NEW` connection-pool defect recorded in DEC-13.
- **The materialise → book handoff** (DEC-1): two **sequential** transactions
  orchestrated by a *non-transactional* coordinator, not a nested pair. The interaction
  diagram must show the first transaction **committing** before the second opens.
- **Synchronicity**: every interaction is synchronous and in-process. There is no queue,
  no event, no second service — stated explicitly because its absence is a decision
  (see DEC: declined — outbox/events) rather than an omission.
- **External interactions**: exactly one — PostgreSQL. Admin authentication is a black
  box at the edge (§10) and is the only other boundary.
- **Failure semantics per hop**, including the commit-phase case where the outcome is
  indeterminate and the API returns `503` rather than guessing (spec §7.9).

---

## 7. Deep dives

The brief explicitly requires two of these to be documented.

### 7.1 Flight instance generation strategy

*To be completed — must cover:*

- The brief's three options, all three evaluated, and the **hybrid** chosen (DEC-1).
  This is the one decision the brief says **must** be documented, so the rejected
  options must appear, not just the winner.
- **Why the rolling window defeats full materialisation**: 365 days from *today* moves
  every midnight, so a materialised set is correct for one day and then decays. Keeping
  it correct needs a daily job — and **a `@Scheduled` job on `N` instances fires `N`
  times**, dragging in leader election or ShedLock (rule D2). The storage choice would
  have created a distributed-coordination problem the domain does not have.
- **Why the row still exists on the write path**: without it the seat invariant has no
  foreign-key target (DEC-2, DEC-3).
- The **circular-`404` trap** a lazy strategy creates on read paths, and its resolution:
  a missing row means *fully available*, never *not found* (spec §8.1.3, API-4).
- **Determinism across instances**: the projection is computed independently by every
  instance and cannot diverge, because schedules are immutable (spec §A5).

### 7.2 Concurrency strategy

*To be completed — must cover:*

- All **five mechanisms the brief names**, mapped explicitly to how they are addressed —
  including that *versioning* and *optimistic locking* are the same mechanism, and that
  *transactions* are the substrate rather than an alternative (DEC-2).
- The **partial unique index** as the single enforcement point, with the
  `WHERE status = 'BOOKED'` predicate shown and its load-bearing role explained: it is
  what lets a cancelled row persist for history while leaving the seat immediately
  rebookable, so FR9 needs no deletion anywhere.
- **Why this is the strongest available answer under `N` instances**: every alternative
  requires the instances to cooperate; this one does not care how many there are.
- **No version column and no retry for seat conflicts** — a taken seat is taken, so the
  answer is `409`, immediately. The two narrow places retry *does* exist (PNR collision,
  deadlock `40P01`) must be named so the absence elsewhere reads as deliberate.
- **Transaction topology**: materialise and book are two *sequential* transactions, not a
  nested pair, and the three failures that shape avoids (spec §7.5).
- The **third write path** — schedule creation (spec §7.11) — which is where a
  concurrency section is usually incomplete.
- **Why single-JVM concurrency tests prove the distributed property**: the threads
  contend on the same index rows separate instances would contend on, precisely because
  enforcement is outside the JVM.

### 7.3 Seat availability computation

*To be completed — must cover:*

- Availability as a **set difference**, `seats(aircraft) − booked(instance)`, with no
  seat table — and the row-count arithmetic that justifies it (DEC-3).
- The **four load-bearing properties** of the search query, especially
  `bs.status='BOOKED'` in the `ON` rather than the `WHERE` (spec §8.1.4).
- **Recomputed, not maintained**: why a cached availability counter would be both a
  second source of truth and a per-flight hot row that serialises bookings sharing no
  seat (spec §8.1.5).
- **Which staleness was granted and which one exists**: the design has no server-side
  staleness at all; the client-side window is unavoidable, exists at `N = 1`, and is
  closed by the `409` body naming the lost seats, not by a fresher read (DEC-20, DEC-21).

---

## 8. Major design decisions

Lightweight ADR format. The **Consequences** column is the important one: it is what
separates a decision log from a feature list.

| # | Decision | Alternatives considered | Rationale | Consequences |
|---|---|---|---|---|
| | *to be completed* | | | |

---

## 9. Scale-out evolution — NOT IMPLEMENTED

> ⚠️ **Nothing in this section is implemented.** It documents how the design would
> evolve if traffic grew, and — more usefully — *what would have to be measured first*
> to justify each change. It is included because the evaluation criteria list
> "scalability considerations"; it is fenced off because shipping a distributed
> architecture diagram over a modular monolith would misrepresent what was built.

*To be completed — must cover, in this order:*

1. **The measured envelope first** (DEC-20): ~10k DAU, 500 schedules, 25:1 read:write,
   giving **~6 QPS peak search** and **~0.25 QPS peak booking**. Every item below is
   rejected against these numbers, not against taste. Stating the arithmetic before the
   options is what makes the rejections auditable.
2. **What is rejected and why** — caching, read replicas, CQRS, sharding, partitioning.
   Each needs its own line: the reason caching is declined is *not* the reason sharding
   is declined, and collapsing them into "not needed at this scale" wastes the section.
   Caching carries an extra reason that is not about load at all: FR6 says *real-time*,
   and `N` instances would hold `N` divergent seat maps (DEC-4, rule D3).
3. **What is safe to cache first if search ever became hot**: immutable reference data
   (airport, aircraft), which is the only category that cannot diverge across instances
   and the only one needing no invalidation protocol (spec §4.2.1).
4. **The first real ceiling, which is not throughput**: the connection budget is
   `N × maximumPoolSize` against PostgreSQL's `max_connections`. Both numbers are now
   **pinned rather than inherited** — `max_connections=100` in `docker-compose.yml`,
   `maximumPoolSize=10` in `application.yml` — which leaves 87 usable after reserved and
   migration headroom, so the ceiling is `N = 8` and the planned `N = 4` sits at less
   than half of it. Beyond that: `FATAL: sorry, too many clients already`.
   **This failure is configuration-shaped, not load-shaped** — it appears when the
   replica count changes, so no amount of single-instance load testing finds it. It is
   also the reason both values carry their derivation at their definition site (rule D8):
   a pass-4 audit found the pool had drifted to 20, which would have capped the cluster
   at `N = 4` *without changing a single line of application code*.
   PgBouncer is the named next step, deliberately not built (rule D6, spec §7.5.1).
5. **When the monolith should split** (DEC-23): the threshold is independent load or
   independent availability requirements, not code size. Until one of those appears,
   splitting buys a distributed-consistency problem where a local transaction suffices.
6. **What would flip the flight-generation strategy** (DEC-1): a requirement to list all
   future flights without a route filter, or mutable schedules with history.
7. **A runtime ceiling that is not a capacity ceiling**: virtual threads are enabled
   (spec §7.5.2), and on Java 21 a virtual thread blocking inside a `synchronized` block
   pins its carrier. Hikari's checkout path avoids `synchronized`, so the hot path is
   clear today — but this is a property of a dependency's internals, not of this design,
   and the mitigation is a JDK bump rather than a redesign: JEP 491 removes pinning in
   Java 24. Named here because it is the kind of ceiling that is invisible until it is
   sudden.
8. **What scaling does *not* change**: the seat invariant. It is enforced below the
   application, so it survives every item above unmodified — which is the strongest
   scalability statement the design can make, and the reason this section is short.

---

## 10. Black boxes

*To be completed.*
