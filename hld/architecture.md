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

*To be completed.*

### 6.2 Component view

*To be completed.*

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

*To be completed.*

### 7.2 Concurrency strategy

*To be completed.*

### 7.3 Seat availability computation

*To be completed.*

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

*To be completed.*

---

## 10. Black boxes

*To be completed.*
