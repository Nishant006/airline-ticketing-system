# Airline Ticketing System

Backend for a single-airline reservation system: flight schedule management, flight
search, seat inventory, ticket booking, and cancellation.

> **Status:** scaffolding. Design documents and implementation are in progress.
> See [`hld/architecture.md`](hld/architecture.md) and [`lld/design.md`](lld/design.md).

---

## Table of contents

1. [Tech stack](#tech-stack)
2. [Repository layout](#repository-layout)
3. [Setup instructions](#setup-instructions)
4. [Dependencies](#dependencies)
5. [Build](#build)
6. [Run](#run)
7. [Database initialisation](#database-initialisation)
8. [Testing](#testing)
9. [API overview](#api-overview)
10. [Design decisions](#design-decisions)
11. [Search algorithm](#search-algorithm)
12. [Booking algorithm](#booking-algorithm)
13. [Cancellation algorithm](#cancellation-algorithm)
14. [Assumptions](#assumptions)
15. [Out of scope](#out-of-scope)

---

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 | Records, sealed types, pattern matching, virtual threads |
| Framework | Spring Boot 4.1.1 | Current GA; Spring Framework 7 |
| Database | PostgreSQL 16 | **Partial unique indexes** — the mechanism that makes double-booking physically impossible. Not expressible in H2. |
| Migrations | Flyway | Schema is versioned and reproducible; Hibernate runs in `validate` mode |
| API docs | springdoc-openapi 3.1.1 | OpenAPI 3.1 + Swagger UI |
| Testing | JUnit 5, Testcontainers, ArchUnit | Tests run against real PostgreSQL, not an embedded substitute |
| Formatting | Spotless + google-java-format | Mechanically enforced, zero style debate |
| Retry | Spring Framework 7 core `@Retryable` | No `spring-retry` dependency — retry is built into the framework |

---

## Repository layout

```
.
├── README.md                 ← you are here
├── hld/
│   ├── architecture.md       ← high-level design
│   └── diagrams/             ← Mermaid sources
├── lld/
│   └── design.md             ← low-level design
├── src/
│   ├── main/java             ← application code
│   └── test/java             ← automated tests
├── db/
│   ├── migrations/           ← Flyway migrations (source of truth)
│   ├── schema.sql            ← generated schema snapshot
│   └── er-diagram.md
├── tests/                    ← manual request collection + smoke script
├── Dockerfile
└── docker-compose.yml
```

> **Note on test location.** Automated tests live in `src/test/java`, per the Maven
> Standard Directory Layout, rather than in the top-level `tests/` directory shown in
> the brief. Relocating them would break the build tool for no benefit. The `tests/`
> directory holds the artefacts that genuinely do not belong on the classpath — the
> HTTP request collection and the curl smoke script. See [`tests/README.md`](tests/README.md).

---

## Setup instructions

### Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21+ | Building and running |
| Docker | 20.10+ | `docker compose` run mode, and the integration/concurrency test suite |
| Maven | *not required* | The repository ships the Maven Wrapper (`./mvnw`) |

Verify:

```bash
java -version      # expect 21 or newer
docker --version
docker compose version
```

---

## Dependencies

*To be completed: the runtime and build dependencies, each with the reason it is
present. See also [Tech stack](#tech-stack) and [Prerequisites](#prerequisites).*

> This heading exists because the brief lists **Dependencies** as a mandated README
> item in its own right. The information also appears under *Tech stack* and
> *Prerequisites*, but a reviewer checking the brief's list scans for the word.

---

## Build

```bash
./mvnw clean package
```

Produces `target/airline-reservation-0.0.1-SNAPSHOT.jar`.

To skip the Docker-dependent integration tests:

```bash
./mvnw clean package -DskipITs
```

---

## Run

### Option 1 — Docker Compose (recommended)

Brings up PostgreSQL and the application together. Flyway applies the schema and
reference data on startup, so the API is usable immediately with no manual database
steps.

```bash
docker compose up --build
```

| URL | What |
|---|---|
| <http://localhost:8080> | API base |
| <http://localhost:8080/swagger-ui.html> | Swagger UI |
| <http://localhost:8080/v3/api-docs> | OpenAPI 3.1 document |
| <http://localhost:8080/actuator/health> | Health check |
| <http://localhost:8080/actuator/health/readiness> | Readiness — *should this instance receive traffic?* |
| <http://localhost:8080/actuator/health/liveness> | Liveness — *should this process be restarted?* |

Tear down, including the database volume:

```bash
docker compose down -v
```

### Option 1b — Multiple instances

The design assumes `N ≥ 2` stateless instances behind a load balancer (HLD §6.1, rules
D1–D10). One instance is the default because that is what the agreed NFR envelope asks
for — but the distributed claims are only *checkable* if more than one can actually be
started:

```bash
docker compose up --build --scale app=3
```

Each replica takes the next free host port: <http://localhost:8080>,
<http://localhost:8081>, <http://localhost:8082>. They share one PostgreSQL instance and
hold no state of their own, so any request may be sent to any of them.

This is the configuration that makes the central claim falsifiable. Booking the same
seat concurrently **through two different ports** exercises the invariant across two
JVMs rather than across two threads in one — and because enforcement lives in a database
constraint rather than in application memory, the result is identical: exactly one
`201`, the rest `409`. A design that passed the single-JVM tests and failed this one
would have been relying on process-local state.

There is deliberately no load balancer in the compose file; adding one would make the
one-command path heavier to serve a case the envelope does not ask for. The instances
are interchangeable, so addressing one directly is a faithful test.

### Option 2 — Local JVM against a containerised database

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

### Option 3 — Zero infrastructure (Testcontainers-backed)

Starts a throwaway PostgreSQL container automatically. Nothing to install or clean up.

```bash
./mvnw spring-boot:test-run
```

---

## Database initialisation

No manual step is required: Flyway runs automatically at application startup and
applies every migration in `db/migrations`.

| Migration | Contents |
|---|---|
| *to be documented* | |

Reference data (airports, aircraft, sample schedules) is seeded by a migration, so a
freshly started system is immediately searchable and bookable.

See [`db/README.md`](db/README.md) for how the brief-mandated `db/migrations/`
directory is wired to Flyway's classpath convention.

---

## Testing

```bash
./mvnw test      # unit + slice tests — fast, no Docker required
./mvnw verify    # everything, including Testcontainers and the concurrency suite
```

| Suffix | Runner | Docker | Contents |
|---|---|---|---|
| `*Test` | Surefire | No | Domain unit tests, mocked service tests, `@WebMvcTest` slices, ArchUnit rules |
| `*IT` | Failsafe | **Yes** | Repository slices, end-to-end flows, concurrency suite |

Coverage report after `./mvnw verify`: `target/site/jacoco/index.html`.

---

## API overview

*To be completed.*

---

## Design decisions

*To be completed: the headline choice per area, each linking to its full entry.*

**Source of truth:** [`features/ATS-1/decision-log.md`](features/ATS-1/decision-log.md)
holds the complete reasoning, including every rejected alternative.
[`hld/architecture.md` §8](hld/architecture.md) summarises the major ones, and this
section carries the headline per area. One source, two derivations — anything here that
contradicts the decision log is a bug in this file.

---

## Search algorithm

> The four sub-headings below are the brief's own, verbatim and in its order, so a
> reviewer scanning for a phrase finds that phrase rather than a paraphrase.
> Source: spec §8.1.

### Flights are identified

*To be completed: a bookable flight is identified by `(flightNumber, flightDate)` and
deliberately not by a surrogate id — under the hybrid strategy the row does not exist
until somebody books.*

### Schedules are matched

*To be completed: weekday derivation from the travel date, the route predicate, and the
365-day window check.*

### Flight instances are generated

*To be completed: the hybrid strategy — projected on read, materialised lazily on first
booking — and why the rolling window rules out a scheduled generator.*

### Seat availability is computed

*To be completed: the set difference `seats(aircraft) − booked(instance)`, computed in
one aggregate query with no N+1 and no cache.*

---

## Booking algorithm

> Five sub-headings, verbatim from the brief. Source: spec §8.2.

### Seat validation

*To be completed: label well-formedness, existence against the aircraft configuration,
duplicate labels within one request, and why none of these is the availability check.*

### Seat locking strategy

*To be completed: the partial unique index as the single enforcement point; no
`synchronized`, no `SELECT … FOR UPDATE`, no `@Version`, no distributed lock.*

### Transaction flow

*To be completed: materialise (commits) → book (commits), two sequential transactions
orchestrated by a non-transactional coordinator.*

### Booking creation

*To be completed: PNR generation and collision retry, `booking` and `booking_seat`
inserts, and the response contract.*

### Concurrency handling

*To be completed: what happens when two requests contend, why the loser receives `409`
immediately rather than retrying, and why the guarantee is independent of how many
application instances are running.*

---

## Cancellation algorithm

> Four sub-headings, verbatim from the brief. Source: spec §8.3.

### Booking lookup

*To be completed: PNR resolution and why lookup is separate from the conditional
update.*

### Seat release

*To be completed: status change only — no row is ever deleted, and the partial index
predicate is what makes the seat immediately rebookable.*

### Transaction flow

*To be completed: one transaction, conditional update first, seat release second, in a
deterministic order.*

### Status updates

*To be completed: the affected-row count as the decision, and the resulting `404` vs
`409` split.*

---

## Assumptions

*To be completed: the brief's own assumptions, restated, plus every assumption this
design added. Source: spec §10 and §11.*

---

## Out of scope

Treated as black boxes, per the brief: payment and refund processing, notifications,
authentication and authorisation, airport and aircraft CRUD, multi-airline support,
connecting itineraries, fares and pricing, time-zone conversion for display.
