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
4. [Build](#build)
5. [Run](#run)
6. [Database initialisation](#database-initialisation)
7. [Testing](#testing)
8. [API overview](#api-overview)
9. [Design decisions](#design-decisions)
10. [Search algorithm](#search-algorithm)
11. [Booking algorithm](#booking-algorithm)
12. [Cancellation algorithm](#cancellation-algorithm)
13. [Assumptions](#assumptions)
14. [Out of scope](#out-of-scope)

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

Tear down, including the database volume:

```bash
docker compose down -v
```

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

*To be completed. The full decision log, with consequences, lives in*
[`hld/architecture.md`](hld/architecture.md)*.*

---

## Search algorithm

*To be completed: how flights are identified, how schedules are matched to a travel
date, how flight instances are generated, and how seat availability is computed.*

---

## Booking algorithm

*To be completed: seat validation, seat locking strategy, transaction flow, booking
creation, and concurrency handling.*

---

## Cancellation algorithm

*To be completed: booking lookup, seat release, transaction flow, and status updates.*

---

## Assumptions

*To be completed.*

---

## Out of scope

Treated as black boxes, per the brief: payment and refund processing, notifications,
authentication and authorisation, airport and aircraft CRUD, multi-airline support,
connecting itineraries, fares and pricing, time-zone conversion for display.
