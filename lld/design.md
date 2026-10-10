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

*To be completed: RFC 7807 problem details, the stable error-code catalogue, and the
mapping from domain exception to HTTP status.*

---

## 7. Transaction boundaries

*To be completed: one row per use case — where the transaction opens, what it covers,
what is deliberately outside it, and why.*

---

## 8. Sequence diagrams

*To be completed.*

---

## 9. Concurrency implementation

*To be completed.*

---

## 10. Testing design

*To be completed.*
