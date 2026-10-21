# Non-functional requirement evidence

This is the single place for measured test evidence. README, HLD, LLD and spec link
here instead of repeating numbers. Each result is tied to a **run** (when, which
revision, which command) and to a **scenario** (what was stimulated, under which
conditions, and what counts as a pass).

How to read it:

1. [Requirement map](#1-requirement-to-evidence-map): which scenario proves which requirement.
2. [Table A](#4-table-a-scenarios-conditions-and-acceptance-criteria): conditions and pass criteria per scenario.
3. [Run register](#5-run-register): every recorded run, including failures.
4. [Table B](#6-table-b-results-per-scenario-and-run): outcome per scenario per run.
5. [Latency tables](#7-latency-detail): percentiles with their populations.
6. [Known limitation](#8-known-limitation-intermittent-mixedloadit-connection-reset) and [what is not proven](#9-what-this-evidence-does-not-prove).

Percentiles are diagnostics. The acceptance gates are the **slowest response** and
the **status codes**, not p95. A missing value is written `not recorded`; nothing is
inferred.

## 1. Requirement to evidence map

| Source (brief line) | Requirement | Scenarios | Layer exercised |
|---|---|---|---|
| NFR, 188 | Clean architecture | S1 | Static (ArchUnit layer-dependency rules) |
| NFR, 189 | Separation of concerns | S1 | Static (ArchUnit DTO/model/entity boundary rules, source rules) |
| NFR, 190 | RESTful API design | S4, S12 | MockMvc; Postman over HTTP |
| NFR, 191 | Transaction handling | S2, S3, S5, S6, S7 | Service + real PostgreSQL |
| NFR, 192 | Error handling | S1, S4, S7, S8 | Unit + MockMvc / HTTP |
| NFR, 193 | Validation | S1, S2, S4 | Unit + MockMvc |
| NFR, 194 | Logging | S1, S4 | Unit + captured output |
| NFR, 195 | Testability | All (injected clock, Testcontainers, barriers) | – |
| NFR, 196 | Reasonable performance | S9, S10 | Real HTTP |
| Reply 346-348 | Designed for multiple instances, reviewer runs one | S8, S11 | Two Spring contexts; three Compose JVMs (historical) |
| Reply 350-353 | ~10k DAU, ~500 schedules | S9, S10 (500-schedule fixture) | Real HTTP |
| Reply 355-356, 367-368 | 25:1 search:booking | S10 | Real HTTP |
| Reply 358-361 | Stale search allowed, booking must report conflict | S5, S10 hot seat | Service + HTTP |
| Reply 363-365 | Search under 1 s; booking stability over speed | S7, S9, S10 | MockMvc + HTTP |
| Reply 370-372 | Zero double booking | S5, S8, S10 | Service + HTTP + two contexts |

## 2. Common environment

| Dimension | Value | Limitation |
|---|---|---|
| Host | Apple M3 Max, 16 logical CPUs, 48 GiB RAM, macOS (aarch64) | Shared developer laptop, not a dedicated load host |
| Runtime | Java 21.0.5, Spring Boot 4.1.1, Hibernate 7.4.5.Final, Tomcat 11.0.24 | – |
| Database | PostgreSQL 16 (`postgres:16-alpine`, observed 16.15) via Testcontainers 2.0.5, Docker 28.3.3 | Floating minor tag |
| Placement | HTTP client and application in the same JVM; database in local Docker | No network hop between hosts |
| Instrumentation | JaCoCo agent on; Hibernate statistics on in `SearchPerformanceIT` | Adds overhead to every timing |
| Clock | Fixed business clock 2027-03-01 08:00 UTC; "tomorrow" = 2027-03-02 | Not wall-clock time |
| Default pools | Read: max 5, min idle 1, acquire 250 ms, SQL 500 ms, lock 250 ms. Write: max 10, acquire 20 s. READ COMMITTED, same primary | Database wait budgets, not end-to-end HTTP deadlines |
| Cache state | Warm: explicit warm-up requests and `ANALYZE` after seeding | No cold-cache result |

## 3. Shared performance fixture (`PerformanceDataset`)

| Item | Volume |
|---|---|
| Daily schedules | 500 (100 DXB→LHR match the search; 400 BOM→SIN do not) |
| Operating-day rows | 3,500 (every weekday) |
| Dates per schedule | 30 |
| Flight instances | 15,000 |
| Bookings | 30,000 (15,000 CONFIRMED, 15,000 CANCELLED) |
| Seat allocations | 600,000 (300,000 BOOKED, 300,000 RELEASED; 20 + 20 per instance) |
| Search response | 100 flights, 4 fields each; 160 available seats per flight |

The volume is a declared synthetic assumption. 10k DAU is not converted into a
request rate.

## 4. Table A: scenarios, conditions and acceptance criteria

| ID | Test (count) | Conditions / stimulus | Acceptance criterion |
|---|---|---|---|
| S1 | Surefire `*Test` (33): ArchUnit 12, exception handler 8, controller slice, DTO/clock/aircraft/source rules | No Docker; static and unit | All pass; a service-contract violation returns 400 `VALIDATION_FAILED` without echoing the rejected value (from R5) |
| S2 | `AirlineReservationApplicationIT` (1), `DatabaseSchemaIT` (7), `ScheduleIT` (8) | Full context start; real migrations and seeds; 12 concurrent schedule creators; test-only trigger rejects whichever weekday row is inserted **second**, so an earlier sibling row is guaranteed to exist before the fault | Context starts with both migrations applied and 4 seeded airports; constraints and seeds present; exactly 1 schedule winner; failed create leaves 0 parent and 0 weekday rows; the schedule service called without HTTP rejects invalid commands with no row written (from R5) |
| S3 | `InstanceMaterializationIT` (3) | 24 concurrent first bookers; uncommitted competing insert observed via `pg_stat_activity` | One instance row per (schedule, date); existing-instance path is one statement; waiting insert sees the committed winner |
| S4 | `FlightSearchIT` (5), `SeatMapIT` (4), `BookingIT` (10), `HttpContractsIT` (6) | MockMvc through the full app and real database; injected constraint failures; fixed clock for the departure boundary | Required fields and statuses; atomic rollback; 406 before any write; strict `HH:mm`; no names in logs; today's search omits flights at or past departure (from R4); unknown or non-operating flight → 404 `FLIGHT_NOT_FOUND` with no rows written (from R4); seat map outside the window → 422 and a departed flight's seat map stays readable; field paths in the `errors` list and search/UUID `errorCode`s asserted; the booking service called without HTTP rejects invalid commands before materializing; a booking returns seats in request order (from R5) |
| S5 | `BookingConcurrencyIT` (5) | **Service layer, not HTTP.** Start barrier; 24 same-seat; 24 reversed pairs (`1A,1B` vs `1B,1A`); 16 overlapping pairs; 24 disjoint; 12 × whole 72-seat aircraft | Exactly one winner per contested set; no partial loser; all disjoint succeed; 0 duplicate active seats |
| S6 | `CancellationIT` (7) | 24 concurrent cancels of one booking; cancel/rebook race; injected release failure | One cancel winner (others 409); status and seats roll back together; released seats rebookable; four statements and zero entity loads per cancellation, seats listed in natural row order (from R5) |
| S7 | `ReadIsolationIT` (4) | Borrow **every** read or write connection; `LOCK TABLE … ACCESS EXCLUSIVE`; `pg_sleep(2)` against 500 ms SQL limit | Read exhaustion → 503 + `Retry-After: 1` in < 1 s while booking still succeeds; write exhaustion does not block search/seat map; SQLSTATE 57014 in < 1 s; same backend reused |
| S8 | `CrossInstanceIT` (1) | Two independent Spring web contexts in one JVM, separate pools and ports, one PostgreSQL. **Overrides:** write pool max 5, acquire 500 ms. Then the database is stopped | Same-seat race → one 201 + one 409; cancel via the other context, rebook → 201; DB down → liveness 200, readiness 503, booking 503 |
| S9a | `SearchPerformanceIT` sequential | Fixture §3; 10 warm-ups; 100 sequential real HTTP searches; connect 5 s, request 10 s | Every response 200 with 100 flights and < 1 s; 1 SQL statement and 0 entity loads per request |
| S9b | `SearchPerformanceIT` concurrent | Same; 8 workers × 25 = 200 searches | Same as S9a |
| S10 | `MixedLoadIT` (1) | Fixture §3; 20 warm-ups; closed loop, no think time; per client 3 cycles of 25 searches + 1 disjoint single-seat booking; phases 16, 64, 192 clients; then 64 HTTP contenders for one seat. Connect 5 s, request 30 s, future 60 s | **16-client baseline:** every search 200 and < 1 s. **64/192 stress:** search 200 or 503 only, at least one 200. **Every phase:** all disjoint bookings 201. **Hot seat:** 1 × 201 + 63 × 409. **End:** 0 duplicate active seats; every booking owns its intended seat |
| S11 | Manual Compose acceptance (historical) | `docker compose up --scale app=3`, three separate JVMs on one PostgreSQL | Same seat via three ports → one 201 + two 409; cancel/rebook through other replicas |
| S12 | Postman/Newman collection (H4, R6) | 8 ordered folders, 127 definitions, 24 concurrent subrequests, real UTC departure cutoff | 0 failed assertions; every observed search < 1 s |
| S13 | `spotless:check` | 87 Java files | 0 files need formatting |

## 5. Run register

All times are IST (+05:30). "Unknown" means the source record does not hold that value.

| Run | Finished | Revision | Command | Verdict |
|---|---|---|---|---|
| H0 | Before 2026-10-20 (date not recorded) | Unknown | Post-review remediation runs of `SearchPerformanceIT` and `MixedLoadIT` | Pass. These are the numbers formerly quoted in README/HLD/LLD |
| H1 | 2026-10-20 18:27 | Not recorded | `./mvnw clean verify` | Pass: 32 unit + 57 IT |
| H2 | 2026-10-20 19:21 | HEAD `55ee30e` per execution log (Audit 12); working tree not fingerprinted | `./mvnw clean verify` | **Fail**: `MixedLoadIT` connection reset in the 192-client phase |
| H3 | 2026-10-20 19:23 | Same as H2 | `MixedLoadIT` only, same source | Pass |
| H4 | 2026-10-21 18:41 | Unknown | Newman full collection and published demo | Pass: 214 requests / 737 assertions, 0 failures; demo 16 / 66, 0 failures |
| F1 | 2026-10-21 19:48 | `814a891` | `./mvnw -B -ntp clean verify` | **Fail**: 32 unit pass; 57 IT = 56 pass + `MixedLoadIT` error (connection reset by peer) |
| F2 | 2026-10-21 19:50 | `814a891` | `./mvnw -B -ntp -Dit.test=MixedLoadIT -Dtest=SourceConventionsTest verify` | **Fail**: same reset; phase data not persisted |
| F3 | 2026-10-21 19:51 | `814a891` | `./mvnw spotless:check` | Pass |
| R1 | 2026-10-21 20:21 | `814a891` (isolated copy, no `target/`) | `./mvnw -B -q verify` | Pass: 32 unit + 57 IT, 0 failures/errors/skips |
| R2 | 2026-10-21 21:15 | `814a891` + then-uncommitted `ScheduleIT` fault and docs, later committed as `bca3c84` (the only `src` change between the two). No source fingerprint was recorded, so identity with `bca3c84` is likely but not proven. Isolated copy, no `target/`; reports not archived | `./mvnw -B -ntp verify` (includes `spotless:check`) | Pass: 32 unit + 57 IT, 0 failures/errors/skips; 87 files clean; 55 s |
| R3 | 2026-10-22 00:10 | `bca3c84`. The 92 build-input files (`src/`, `pom.xml`, Maven wrapper) matched a checkout of that commit byte for byte, and so did the isolated copy (SHA-256 manifest `d17fe711…`). The working tree's other changes were documentation only. Isolated copy, no `target/`; raw reports kept outside the repository | `./mvnw -B -ntp verify` (includes `spotless:check`) | Pass: 32 unit + 57 IT, 0 failures/errors/skips; 87 files clean; Maven total time 01:02 min |
| R4 | 2026-10-22 01:48 | `6a076f3` + uncommitted `src` changes: search drops departed flights (`FlightDatePolicy.hasDeparted`), search counts `seatLabel` instead of `id`, three new or extended tests. 92 build-input files; isolated copy identical to the working tree (SHA-256 manifest `18e017e3…`). No `target/`; raw reports kept outside the repository | `./mvnw -B -ntp verify` (includes `spotless:check`) | Pass: 32 unit + 59 IT, 0 failures/errors/skips; 87 files clean; Maven total time 57.5 s |
| R5 | 2026-10-22 02:43 | `6a076f3` + uncommitted `src` changes: R4, plus named unique-constraint constants matched by `ConstraintKind.UNIQUE`, booking seats returned in request order, the cancellation lookup as one `BookingDetails` projection with seats in natural row order, and five new tests (one unit, four IT) with extended assertions. 92 build-input files; isolated copy identical to the working tree (SHA-256 manifest `32711d1f…`). No `target/`; raw reports kept outside the repository | `./mvnw -B -ntp verify` (includes `spotless:check`) | Pass: 33 unit + 63 IT, 0 failures/errors/skips; 87 files clean; Maven total time 53.1 s |
| R6 | 2026-10-22 02:50 | Same build inputs as R5 (SHA-256 manifest `32711d1f…`); Compose built the image from the working tree | Isolated Compose project with a fresh volume (app on port 18980), then pinned Newman 6.2.3: `newman run postman/postman.json -e postman/env.json --env-var baseUrl=http://localhost:18980 --bail --timeout-request 10000 --timeout-script 30000`; stack and volume removed afterwards | Pass: 8 folders, 214 executed items, 238 HTTP requests (including the 24 concurrent sub-requests), 833 assertions, 0 failures; 71 s |

H2 and F1/F2 left only stack traces. `MixedLoadIT` writes its report only after
every phase passes (see §8).

## 6. Table B: results per scenario and run

| Scenario | H1 | H3 | F1 | F2 | R1 | R2 | R3 | R4 | R5 |
|---|---|---|---|---|---|---|---|---|---|
| S1 | Pass | – | Pass (32/32) | Pass (1/1: `SourceConventionsTest` only) | Pass (32/32) | Pass (32/32) | Pass (32/32) | Pass (32/32) | Pass (33/33) |
| S2 | Pass | – | Pass (15/15) | – | Pass (15/15) | Pass (15/15) | Pass (15/15) | Pass (15/15) | Pass (16/16) |
| S3 | Pass | – | Pass (3/3) | – | Pass (3/3) | Pass (3/3) | Pass (3/3) | Pass (3/3) | Pass (3/3) |
| S4 | Pass | – | Pass (21/21) | – | Pass (21/21) | Pass (21/21) | Pass (21/21) | Pass (23/23) | Pass (25/25) |
| S5 | Pass | – | Pass (5/5) | – | Pass (5/5) | Pass (5/5) | Pass (5/5) | Pass (5/5) | Pass (5/5) |
| S6 | Pass | – | Pass (6/6) | – | Pass (6/6) | Pass (6/6) | Pass (6/6) | Pass (6/6) | Pass (7/7) |
| S7 | Pass | – | Pass (4/4) | – | Pass (4/4) | Pass (4/4) | Pass (4/4) | Pass (4/4) | Pass (4/4) |
| S8 | Pass | – | Pass (1/1) | – | Pass (1/1) | Pass (1/1) | Pass (1/1) | Pass (1/1) | Pass (1/1) |
| S9a/b | Pass | – | Pass | – | Pass | Pass | Pass | Pass | Pass |
| S10 | Pass | Pass | **Error: reset** | **Error: reset** | Pass | Pass | Pass | Pass | Pass |

S2 in H1/F1/R1 used the earlier Wednesday-constraint fault; R2 is the first full run
with the second-insert trigger. S11 (historical Compose) passed in its manual run and
S12 (Newman) passed in H4 and R6; Maven runs neither. S13 passed in F3, R2, R3, R4 and
R5. Before R4, S4 had `FlightSearchIT` (4) and `BookingIT` (8), so 21 tests. R5 added
one test to each of `GlobalExceptionHandlerTest` (S1), `ScheduleIT` (S2), `BookingIT`
and `SeatMapIT` (S4) and `CancellationIT` (S6). The Postman collection was edited after
H4: folder 05 request 06; folder 08 request 08, now asserting that search omits the
departed flight; and the shared booking check, which now expects seats in request order
for a confirmed booking and in natural row order after cancellation. R6 re-ran S12 after
these edits.

## 7. Latency detail

### 7.1 Read-only search (S9), milliseconds, all responses 200

| Run | Sequential n=100 p50 / p95 / max | 8 workers n=200 p50 / p95 / max | SQL execution (EXPLAIN ANALYZE) | Over 1 s |
|---|---|---|---|---|
| H0 | 2.267 / 2.702 / 3.215 | 4.324 / 7.332 / 10.835 | 1.106 | 0 |
| Audit 12, 2026-10-20 (execution log) | max 5.298 | max 14.784 | not recorded | 0 |
| F1 | 2.401 / 2.827 / 3.478 | 4.602 / 9.417 / 14.302 | 1.077 (planning 0.566) | 0 |
| R1 | 2.360 / 2.859 / 2.999 | 4.102 / 7.657 / 10.605 | 1.187 (planning 0.747) | 0 |
| R2 | 2.237 / 2.589 / 2.816 | 4.559 / 6.858 / 9.609 | 1.252 (planning 0.689) | 0 |
| R3 | 2.255 / 2.746 / 2.811 | 4.307 / 6.490 / 10.255 | 1.035 (planning 0.581) | 0 |
| R4 | 2.260 / 2.627 / 2.959 | 4.700 / 8.923 / 13.124 | 1.115 (planning 0.624) | 0 |
| R5 | 2.251 / 2.705 / 2.965 | 4.207 / 6.789 / 10.014 | 1.226 (planning 0.763) | 0 |

Every run used one SQL statement per search and loaded zero entities. The plan used
`ix_schedule_route`, `uq_instance` and `uq_booking_seat_active`. A sequential scan of
the 3,500-row weekday table is expected at this size. From R4 the query counts
`seat_label`, so the active-seat join is an index-only scan on `uq_booking_seat_active`
(2,000 heap fetches in this freshly seeded, unvacuumed fixture). R4 also drops departed
flights in Java; the SQL statement count is unchanged.

### 7.2 Mixed 25:1 load (S10), milliseconds

`searchAll` counts every search response. `search200` excludes fast 503 rejections so
they cannot make successful latency look better.

| Run | Clients | Searches 200 / 503 | Search p50 / p95 / p99 / max | Bookings (all 201) | Booking p95 / max | Phase s | Over 1 s |
|---|---|---|---|---|---|---|---|
| H0 | 16 | 1,200 / 0 | – / 15.049 / – / – | 48 | 166.547 / – | 0.879 | 0 |
| H0 | 64 | 4,800 / 0 | – / 34.228 / – / – | 192 | 9.918 / – | 2.268 | 0 |
| H0 | 192 | 14,399 / 1 | – / 97.745 / – / – (all responses) | 576 | 4.835 / – | 6.120 | 0 |
| H3 | 16 | 1,200 / 0 | 8.724 / 17.102 / 22.679 / 36.581 | 48 | 157.663 / 179.955 | 0.896 | 0 |
| H3 | 64 | 4,800 / 0 | 29.540 / 37.088 / 60.751 / 93.914 | 192 | 9.610 / 10.956 | 2.318 | 0 |
| H3 | 192 | 14,400 / 0 | 79.700 / 101.743 / 160.199 / 242.268 | 576 | 5.053 / 13.118 | 6.086 | 0 |
| R1 | 16 | 1,200 / 0 | 7.985 / 14.624 / 19.235 / 26.553 | 48 | 161.426 / 168.383 | 0.836 | 0 |
| R1 | 64 | 4,800 / 0 | 30.003 / 35.315 / 57.527 / 70.530 | 192 | 8.995 / 10.462 | 2.327 | 0 |
| R1 | 192 | 14,386 / 14 | all: 88.189 / 105.655 / 181.622 / 307.666; 200 only: 88.179 / 105.445 / 180.299 / 251.728 | 576 | 7.084 / 9.197 | 6.756 | 0 |
| R2 | 16 | 1,200 / 0 | 7.742 / 12.515 / 17.154 / 24.916 | 48 | 154.802 / 161.650 | 0.805 | 0 |
| R2 | 64 | 4,800 / 0 | 28.582 / 32.393 / 54.510 / 86.042 | 192 | 7.672 / 9.576 | 2.203 | 0 |
| R2 | 192 | 14,398 / 2 | all: 82.831 / 97.293 / 165.875 / 307.096; 200 only: 82.831 / 97.290 / 165.831 / 251.316 | 576 | 6.126 / 8.943 | 6.322 | 0 |
| R3 | 16 | 1,200 / 0 | 7.643 / 13.456 / 15.776 / 23.351 | 48 | 159.417 / 163.940 | 0.802 | 0 |
| R3 | 64 | 4,800 / 0 | 28.524 / 36.142 / 54.292 / 80.166 | 192 | 11.520 / 25.095 | 2.233 | 0 |
| R3 | 192 | 14,399 / 1 | all: 81.814 / 90.164 / 164.669 / 294.545; 200 only: 81.814 / 90.164 / 164.669 / 246.110 | 576 | 5.344 / 9.028 | 6.151 | 0 |
| R4 | 16 | 1,200 / 0 | 7.812 / 13.474 / 17.476 / 24.340 | 48 | 158.527 / 161.452 | 0.810 | 0 |
| R4 | 64 | 4,800 / 0 | 28.840 / 32.763 / 55.417 / 88.693 | 192 | 8.558 / 9.951 | 2.222 | 0 |
| R4 | 192 | 14,399 / 1 | all: 81.651 / 95.720 / 166.322 / 307.487; 200 only: 81.651 / 95.720 / 166.322 / 244.329 | 576 | 5.736 / 7.654 | 6.274 | 0 |
| R5 | 16 | 1,200 / 0 | 7.704 / 13.104 / 15.478 / 31.154 | 48 | 155.372 / 157.405 | 0.805 | 0 |
| R5 | 64 | 4,800 / 0 | 28.753 / 32.868 / 54.943 / 86.452 | 192 | 7.449 / 8.574 | 2.215 | 0 |
| R5 | 192 | 14,396 / 4 | all: 83.560 / 98.283 / 172.437 / 306.324; 200 only: 83.559 / 98.268 / 172.249 / 253.449 | 576 | 6.511 / 17.300 | 6.423 | 0 |
| F1, F2 | – | not recorded | not recorded | not recorded | – | – | – |

| Run | Hot seat: 64 HTTP contenders for one seat | Final ownership check |
|---|---|---|
| H0 | 1 × 201, 63 × 409 | 816 disjoint bookings own their seats; 0 duplicates |
| H3 | 1 × 201, 63 × 409; all-response p95 / max 88.634 / 89.041 | Same |
| R1 | 1 × 201, 63 × 409; p50 / p95 / p99 / max 32.439 / 36.426 / 36.883 / 36.883 | Same |
| R2 | 1 × 201, 63 × 409; p50 / p95 / p99 / max 31.796 / 36.687 / 37.960 / 37.960 | Same |
| R3 | 1 × 201, 63 × 409; p50 / p95 / p99 / max 28.810 / 32.704 / 34.009 / 34.009 | Same |
| R4 | 1 × 201, 63 × 409; p50 / p95 / p99 / max 25.729 / 30.951 / 31.656 / 31.656 | Same |
| R5 | 1 × 201, 63 × 409; p50 / p95 / p99 / max 29.150 / 33.183 / 33.809 / 33.809 | Same |

Warm-ups are searches only, so the 16-client phase is the first to exercise the
booking path; its booking p95 (~160 ms) is higher than later phases. The cause
(likely JIT/connection warm-up) was not investigated. All targeted flight instances
are pre-seeded.

### 7.3 Manual SQL probe: active-seat count column

One `EXPLAIN (ANALYZE, BUFFERS)` per variant, 2026-10-22 01:07 IST, on a throwaway
`postgres:16-alpine` container (minor version not recorded) seeded by SQL with the §3
volumes, then `ANALYZE`; searched date = container date + 1; enum literal inline, as
Hibernate renders it. A single diagnostic execution, not a benchmark; all buffers were
cache hits.

| Search counts | Active-seat access | Shared buffers | Execution ms |
|---|---|---|---|
| `count(bs.id)` (up to R3) | Index scan on `uq_booking_seat_active` + heap | 2,817 | 1.966 |
| `count(bs.seatLabel)` (from R4) | Index-only scan on `uq_booking_seat_active` | 807 | 0.835 |

Both return the same count because `seat_label` is `NOT NULL`. In the S9 fixture (R4)
the index-only scan still made 2,000 heap fetches, so the gain there is smaller (§7.1).

## 8. Known limitation: intermittent `MixedLoadIT` connection reset

| Observation | Detail |
|---|---|
| Pattern | 3 failures (H2, F1, F2) and 7 passes (H1, H3, R1, R2, R3, R4, R5) from 2026-10-20 to 2026-10-22 on the same laptop |
| Symptom | `ExecutionException` → `java.net.ConnectException: Connection reset by peer` (root `SocketException`) from the JDK `HttpClient`, surfaced through a client worker in `measurePhase`: the reset happened while **opening** a connection, not after a response. The H2 record attributes it to a search in the 192-client phase; F1/F2 do not record the phase or request kind |
| Not observed | Any HTTP 500, wrong booking, partial booking or duplicate seat in any run |
| Cause | **Unknown.** It is a transport error, not an HTTP status, observed while 192 closed-loop clients share one JVM with the server. No cause was demonstrated and no hypothesis is adopted here |
| Deliberately not done | No retries, no relaxed assertion, no tuning without a demonstrated cause |
| Reporting gap | `target/mixed-load.txt` is written only after every phase and the final assertions succeed. A failed run leaves no phase metrics, and an older report from a previous run may still be present |

The 16-client baseline gate is unaffected by this limitation in every run that recorded phases.

## 9. What this evidence does not prove

- Sustained, open-loop or production capacity, or a peak-RPS SLA (none was agreed).
- Cold-cache latency or behaviour after long-term table bloat.
- Latency over a real network or across hosts. S8 uses one JVM with two contexts;
  S11 used three local containers.
- That `BookingConcurrencyIT` exercises HTTP. It calls the service layer; HTTP races
  are covered by S8, S10 hot seat and S12.
- A departure deadline at commit time. Departure is checked when the request is
  validated and again at the start of the write unit, not at commit.

## 10. Reproduce

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; any Java 21 works
./mvnw -B verify                                    # everything, Docker required
./mvnw -B verify -Dit.test=SearchPerformanceIT -Dtest=SourceConventionsTest
./mvnw -B verify -Dit.test=MixedLoadIT -Dtest=SourceConventionsTest
```

Reports: `target/search-performance.txt` (timings, SQL, full plan),
`target/mixed-load.txt` (phase table, successful runs only),
`target/failsafe-reports/` and `target/surefire-reports/`. `target/` keeps files
from earlier invocations: use `failsafe-summary.xml` and the Maven log to identify
which run produced them.
