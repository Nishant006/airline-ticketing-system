# Low-Level Design — Airline Ticketing System

> **Status:** outline. Sections are filled in during the design pass.

Where the [HLD](../hld/architecture.md) answers *what* the system is and *why* it is
shaped that way, this document answers *how* it is built: the modules, the classes,
the contracts between them, and the exact boundaries of each transaction.

---

## Table of contents

| § | Section | Maps to brief requirement |
|---|---|---|
| 1 | [Module structure](#1-module-structure) | LLD → "Modules" |
| 2 | [Class model](#2-class-model) | LLD → "Classes" |
| 3 | [Services](#3-services) | LLD → "Services" |
| 4 | [API contracts](#4-api-contracts) | LLD → "APIs" |
| 5 | [Validation](#5-validation) | LLD → "Validation" |
| 6 | [Error handling](#6-error-handling) | LLD → "Error handling" |
| 7 | [Transaction boundaries](#7-transaction-boundaries) | LLD → "Transaction boundaries" |
| 8 | [Sequence diagrams](#8-sequence-diagrams) | LLD → "Sequence diagrams (optional)" |
| 9 | [Concurrency implementation](#9-concurrency-implementation) | Brief → "document your concurrency strategy" |
| 10 | [Testing design](#10-testing-design) | Evaluation → "Testing approach" |

---

## 1. Module structure

*To be completed: package-by-feature layout, the internal layering within each
feature, and the dependency rules enforced by ArchUnit.*

---

## 2. Class model

*To be completed: aggregates, entities, value objects, and the invariants each one
owns.*

---

## 3. Services

*To be completed.*

---

## 4. API contracts

*To be completed: request/response shapes, status codes, and idempotency semantics.*

---

## 5. Validation

*To be completed: the three validation layers and which rule lives in which layer.*

---

## 6. Error handling

*To be completed: **RFC 9457** problem details (which obsoletes RFC 7807 — DEC-11), the
stable error-code catalogue, and the mapping from domain exception to HTTP status.*

*Must additionally cover:*

- The **SQLSTATE → HTTP mapping**, which is where this design's error handling is
  unusual: constraint violations are not failures to be logged and rethrown, they are the
  **primary conflict-detection mechanism** (spec §7.8). `23505` on `uq_booking_seat_active`
  → `409 SEAT_UNAVAILABLE`; on `uq_flight_number_day` → `409 DUPLICATE_SCHEDULE`; on the
  PNR index → retry, not a response. **Mapping by SQLSTATE alone is insufficient** — the
  constraint *name* is required to distinguish them, so the handler must read it from
  `ConstraintViolationException` rather than matching on the code.
- The `409 SEAT_UNAVAILABLE` body carrying `requestedSeats` and `unavailableSeats`
  (spec §7.8.1, DEC-21) — the condition on which stale search was granted, so this is a
  contract obligation rather than a nicety.
- The **indeterminate commit** case: a failure during commit leaves the outcome unknown,
  and the API returns `503` rather than guessing `201` or `409` (spec §7.9).

---

## 7. Transaction boundaries

*To be completed: one row per use case — where the transaction opens, what it covers,
what is deliberately outside it, and why.*

*Must cover **all three** write paths, since the usual omission is the third:*

| Use case | Boundary | Notable |
|---|---|---|
| Create schedule | One transaction spanning `flight_schedule` + `flight_schedule_day` | Non-atomic would leave a schedule operating on **no** days — invisible to search and to the duplicate constraint alike (spec §7.11) |
| Materialise instance | Its own transaction, **committed before** the booking transaction opens | Sequential, never nested — `REQUIRES_NEW` would exhaust the pool under CT-5 (spec §7.5) |
| Create booking | One transaction: resolve → validate → PNR → `booking` → `booking_seat` | All domain exceptions extend `RuntimeException` so the default rollback rule applies; a checked exception would let Spring **commit** a partial multi-seat booking |
| Cancel booking | One transaction: conditional `UPDATE` on `booking`, then seat release | Affected-row count is the decision; lookup first for `404`, conditional write for `409` (spec §7.7) |

*Also state the two global facts:* every transaction runs at `READ COMMITTED` and the
isolation level is never overridden (spec §7.3), and `spring.jpa.open-in-view=false` so a
connection is held for the duration of the transaction rather than of response writing
(DEC-13).

---

## 8. Sequence diagrams

*To be completed.*

---

## 9. Concurrency implementation

*To be completed — must cover:*

- The partial unique index as the enforcement point, and the **absence** of
  `synchronized`, `ReentrantLock`, `SELECT … FOR UPDATE`, `@Version` and any distributed
  lock — stated as an enforced rule (D1), because the absence is the design.
- **Lock ordering**: multi-seat inserts are issued in a deterministic seat-label order so
  two overlapping requests cannot deadlock by acquiring the same two index entries in
  opposite orders (spec §7.6). The ordering is a correctness mechanism, not tidiness.
- Where retry exists and where it does not: **PNR collision ≤ 3 attempts** and deadlock
  `40P01`; never for a seat conflict, because retrying cannot change a taken seat.
- `CT-1…CT-10`, and specifically **why single-JVM tests prove a multi-instance property**
  — the threads contend on the same index rows separate instances would (DEC-19).
- **What is instance-count-sensitive and what is not**: no correctness property is; the
  connection budget (`N × pool`, D6) and the Flyway startup lock (D5) are the only two
  behaviours that change with `N`, and neither is a correctness property.

---

## 10. Testing design

*To be completed — must cover:*

- The four suites — unit, slice (`@WebMvcTest`), integration (Testcontainers), concurrency
  — and what each is uniquely able to catch.
- **Why PostgreSQL via Testcontainers and not H2** (DEC-12): H2 cannot express a partial
  unique index, so an H2 suite would silently verify a weaker schema **and pass**. This is
  a correctness choice wearing the clothes of a tooling choice.
- **ArchUnit as the enforcement of NFR1, NFR2, NFR3 and NFR8** — layering, no field
  injection, no `HttpSession`, no no-arg `LocalDate.now()`. Structural claims decay
  silently; encoded as tests they fail the build.
- The tests that exist to catch a **specific named defect** rather than a requirement:
  CT-7 (raw JDBC — proves the index exists in the database, not merely in the application),
  the N+1 statement-count assertion, and the MDC-leak test across two requests on one
  pooled thread.
