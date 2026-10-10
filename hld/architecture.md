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
| 8 | [Decision log](#8-decision-log) | HLD → "Major design decisions" |
| 9 | [Scale-out evolution — NOT IMPLEMENTED](#9-scale-out-evolution--not-implemented) | Evaluation → "Scalability considerations" |
| 10 | [Black boxes](#10-black-boxes) | Scope → "treated as a black box" |

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

## 7. Deep dives

The brief explicitly requires two of these to be documented.

### 7.1 Flight instance generation strategy

*To be completed.*

### 7.2 Concurrency strategy

*To be completed.*

### 7.3 Seat availability computation

*To be completed.*

---

## 8. Decision log

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
