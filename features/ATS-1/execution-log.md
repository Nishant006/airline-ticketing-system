# Execution Log — ATS-1

> Entries before 2026-10-19 are historical, not evidence of current functionality or
> approval. Current user choices and corrections are recorded in `spec.md` and
> `decision-log.md`. Implementation progress and five-todo self-audits are appended here.

## Current implementation

| Date | Completed todos | Self-audit and correction |
|---|---|---|
| 2026-10-19 | 01-05: scope, UTC/window, API policies, draft corrections, environment | Audit 1: original brief SHA-256 unchanged; default and custom-port Compose configurations valid; Java 21 selected locally; Docker daemon reachable; whitespace clean. Removed contradictory SQL/time/locking/timeout claims. Ignoring `notes/` does not untrack its existing files; submission cleanup remains open. |
| 2026-10-19 | 06-10: HLD/LLD baseline, schema, seeds, database proof | Audit 2: 8 PostgreSQL integration tests passed, including FR1/FR2 seeds, all seven tables, real partial-index rejection/reuse, independent instances and composite FK. Fixed residual README claims about Hibernate, lifecycle commands and sample schedules. Spotless and Compose configurations pass. |
| 2026-10-19 | 11-15: domain/clock, schedule creation/proof, search/proof | Audit 3: 10 unit/web tests and 9 PostgreSQL tests passed. Concurrent schedule creation has one winner; an injected weekday constraint failure rolls back its parent and earlier days. Search uses one SQL statement, never materializes, handles zero availability and exact UTC/leap-year boundaries. Moved PostgreSQL exception classification out of the application service into its adapter. Brief unchanged; Spotless passes. |
| 2026-10-19 | 16-20: seat map/proof, lazy instance creation, atomic booking, explicit conflicts | Audit 4: user-directed Spring Data JPA and root-level package-by-layer refactor completed; request/response DTOs are separate and no `var` remains. Clean suite: 17 unit/architecture plus 31 PostgreSQL cases passed; subsequent targeted checks cover booking visibility in search/seat map and one additional configuration guard. Search prepares one statement with no entity hydration; seat map prepares two with no lazy entity fetches. Concurrent first-instance creation has one ID; a blocked upsert sees the winner after commit. Sorted actual seat writes, full rollback and constraint-specific 409 are proven. Removed request-to-response DTO coupling, suppressed payload-bearing ORM error logs while retaining application errors, and fixed configuration that could shorten the agreed 365-date window. Brief unchanged. Cancellation API visibility remains part of the FR9 gate, not claimed by this gate. |
| 2026-10-19 | Quality 01-05: imports, focused methods, persistence boundaries, measured queries, regression | Audit 5: 19 unit/architecture/source-convention and 32 PostgreSQL/HTTP tests pass. Replaced wildcard imports, guarded explicit local types/imports, split long orchestration methods, protected schedule-day collection. Search audit: 500 schedules, 600k allocation rows; HTTP p95 5.945 ms sequential / 7.555 ms at eight workers; actual SQL execution 1.150 ms, one query and no entity loads. Existing indexes suffice; no speculative tuning introduced. LLD records dataset, limits and conditional optimizations. |
| 2026-10-20 | 21-25: booking contention, cancellation/races, HTTP contracts, safe logging | Audit 6: 19 unit/architecture plus 15 PostgreSQL/HTTP cases pass; two additional handler cases cover 503 indeterminate writes and sanitized 500 errors. Same-seat/set, reversed/overlapping/disjoint sets, full-flight and first-instance contention preserve atomic ownership. Cancellation is four statements, releases all seats, rolls back on injected failure, arbitrates concurrent cancels and allows rebooking. Specific protocol error codes, bounded correlation IDs, MDC cleanup and non-disclosure of passenger names are verified. Fixed catch-all conflict status mapping to an exhaustive enum switch. |
| 2026-10-20 | 26-30: runtime, cross-instance proof, latency evidence, walkthrough, HLD | Audit 7: built the actual image and started an isolated healthy Compose project; public-HTTP walkthrough passed. Three separate container JVMs produced one 201/two 409s for the same seat and supported cross-JVM cancellation/rebooking. Default/custom port configs validated. Removed fixed database container name, added executable walkthrough, completed HLD/PDF/SVG with benchmark limits. A feature audit additionally added a passing booking-window/exact-departure regression. |
| 2026-10-20 | 31-35: LLD, schema/ER, README, submission boundary, final acceptance | Audit 8: all 44 todos complete. Fresh working-tree snapshot excluded `.git`, `target` and ignored files; `clean verify` passed 21 unit/architecture plus 49 PostgreSQL/HTTP cases, zero failures/errors/skips. Persisted files matched the passing snapshot. This is not a claim that uncommitted changes exist in a clean Git clone. Restored schema contains seven tables and the partial active-seat index; real OpenAPI exposes exactly five operations. Public walkthrough verifies all four search/six booking fields and full A320 labels. Final 500-schedule/600k-allocation run: HTTP p95 5.604 ms sequential / 6.862 ms at eight workers; one SQL, no entity hydration, SQL execution 1.146 ms. Clause-level FR1-FR9/nonfunctional/deliverable/clarification mapping is in `spec.md`. Only disposable acceptance containers/volume/image were removed. |

The source brief and historical plan were moved from `notes/` to this directory during
implementation. The authoritative requirement body is unchanged; an IDE-adjusted wrapper
link changes the full-file checksum. The original indexed brief SHA-256 was
`3f198d5b6df4fa535aab06b2bea9d73e73eb1d441028cc520d1359d96e11ef4a`.
The user explicitly declined untracking these two local sources. They remain tracked;
no history rewriting, publication, repository visibility change or commit is implied.

## Review remediation

The independent review reproduced four issues: writes committed before an incompatible
response returned 406; 24:00 clocks normalized to midnight; an ambient booking
transaction broke rollback-before-conflict-read; and holding the shared connection pool
delayed a real search to 1278.468 ms. These are implementation findings, not new brief
requirements. The user approved the fixes and dedicated short-budget read capacity.

| Gate | Changes and evidence |
|---|---|
| Audit 9: remediation 01-05 | Added JSON preflight negotiation, strict clocks, narrow service interfaces, immutable application commands/results and an explicit no-ambient-booking contract. The initial strict repository-port variant passed 26 unit/architecture plus 52 integration cases. The user then selected conventional flat Spring Data repositories instead of JPA adapters; a clean follow-up passed 27 unit/architecture plus 52 integration cases. Public service contracts remain entity/transport-independent; implementations intentionally know JPA. No repository constructs API response DTOs. |
| Audit 10: remediation 06-10 | Implemented approved read/write isolation with configurable 250 ms acquisition/500 ms SQL/250 ms lock read limits and unchanged write waits. Full verification passed 29 unit/architecture plus 57 PostgreSQL/HTTP cases, zero failures/errors/skips. Focused follow-ups proved the same PostgreSQL backend is reusable after timeout rollback and guarded final flat-repository contracts. Fixed Hibernate-wrapped shutdown 57P01 returning 500 using Spring SQL translation; unknown ORM errors remain 500. Mixed 25:1 load: all 816 disjoint bookings succeeded, zero duplicate active seats, 64 hot-seat contenders produced one 201/63x409. At 192 clients, search p95 was 97.745 ms with one explicitly counted overload 503 among 14,400 searches; short local samples are not production capacity certification. A fresh isolated image/Compose run passed readiness, exactly five OpenAPI operations and the HTTP lifecycle walkthrough. Reconciled architecture docs, regenerated PDF/SVG, checked links/formatting/source conventions and unchanged brief checksum. Removed only this gate's disposable containers/volume/image and empty adapter directories. No commit or publication performed. |

## Scoped audit corrections

The user authorized only source-convention/formatting corrections and safe failure
diagnostics. Performance-acceptance changes were deferred for that gate. Read-only inspection
confirmed that committed `main` (`fec8828`) has flat Spring Data repositories; the
11 obsolete staged additions were left untouched, as requested.

| Gate | Changes and evidence |
|---|---|
| Audit 11: approved source and diagnostic fixes | Replaced three wildcard imports and applied the existing Google formatter to all 87 Java files without changing its configuration. Failure logs now retain exception/root types, validated SQLSTATE/constraint identifiers and application locations without raw messages, SQL or request values. Seven handler tests cover sanitization, metadata validation, unchanged 500/503 behavior and cyclic causes; an existing PostgreSQL booking test additionally proves diagnostic usefulness and passenger privacy through HTTP. Final Java 21 `spotless:apply clean verify` passed 32 unit/architecture plus 57 integration cases, with zero failures/errors/skips. Java syntax-tree comparison against HEAD confirmed that all 82 files outside the five intentionally edited Java files are formatting-only, including both performance tests. The brief checksum, staging fingerprint and HEAD remained unchanged. No staging, commit or push performed. |

An initial full run could not load the nested diagnostic helper. Generated classes
and both packaged JAR variants contained it. Isolated HTTP verification and the
subsequent clean full run passed without changing the helper code. The initial
class-loading failure's cause remains unconfirmed; the failed and passing logs are
retained in the session artifacts.

## Accepted stateless scaling clarification

On 2026-10-20 the user accepted a stateless backend, delegated engineering judgment
and said a fixed peak request-rate requirement is unnecessary for the assignment.
This supersedes the earlier performance-acceptance deferral, not the restrictions on
Git staging/history. Keep one horizontally replicable application and shared PostgreSQL;
production load-balancer/autoscaler provisioning is not claimed to be implemented.

| Gate | Changes and evidence |
|---|---|
| Audit 12: five clarified scaling tasks | Recorded the accepted deployment scope, replaced p95-only search gates with slowest-response checks, required all 16-client baseline searches to return 200 under one second, separated successful/stress response evidence and documented readiness-aware scaling with a database connection budget including rollout surge. All selected cases passed across targeted/isolated Java 21 lifecycle runs: one source-convention and seven integration cases covering search latency, mixed traffic, independent contexts and read/write isolation. Spotless passes. Measured warm read-only maxima were 5.298 ms sequential/14.784 ms at eight workers; mixed-baseline maximum was 36.581 ms. The successful mixed run returned 200 for all 20,400 searches, committed all 816 disjoint bookings and retained one winner/63 conflicts in the hot-seat race, with no duplicate ownership. No application, schema, dependency or container configuration changed. The authoritative brief, HEAD `55ee30e` and staging fingerprint remained unchanged; no commit or push. |

The first combined run failed on a search TCP connection reset in the 192-client
stress phase; unchanged mixed-load verification subsequently passed in isolation.
No automatic retry or relaxed assertion was added. The reset's cause is unconfirmed
and the failed log is retained. These short local results are not a sustained capacity
certificate or a guarantee that on-demand replicas remove database/network limits.

## Postman interview and E2E delivery

The user authorized publishing only the generated collection and environment into
their Postman account. Both were created in **My Workspace**, whose visibility was
verified as **personal**. The management credential was sent only to the HTTPS
Postman API, never included in repository files, backend requests or reports.

| Gate | Changes and evidence |
|---|---|
| Audit 13: five Postman delivery tasks | Reviewed current API/seed contracts, installed pinned Newman 6.2.3 outside the repository, created only `postman/postman.json` and `postman/env.json`, documented use in existing README/test instructions, and executed the collection against a fresh isolated PostgreSQL/application Compose project. Eight ordered folders contain 127 request definitions, including a 16-request interview demo, all three seeded aircraft layouts, strict validation/HTTP errors, pre-write 406 checks, rolling UTC boundaries, sold-out/full-group cancellation, 16 competing bookings/eight competing cancellations, and a bounded real-departure wait. Full Newman run: 214 HTTP requests and 737 assertions, zero failures, 51.915 seconds. Postman API read-back verified all executable request/script content after normalizing server-omitted no-auth/empty-description defaults; the downloaded cloud collection/environment then passed a fresh 16-request/66-assertion demo on the same database. Database inspection found zero future confirmed bookings, zero duplicate active-seat groups, nine cancelled bookings and the one intentionally retained departed confirmed booking. The final audit verifies two JSON-only deliverables, empty initial fixture variables, credential absence, matching local/cloud IDs, unchanged brief/HEAD and unchanged 11 pre-existing staged source entries. Newman setup and CLI/JSON/JUnit evidence remain outside the repository. No backend, schema or repository dependency-manifest changes were made; no manual Git staging/history operations were performed. |

The published collection is **Airline Ticketing System - Interview and E2E**
(`e83609ef-d127-45f4-9f1e-5e120d61857b`); the environment is **ATS Local**
(`156f60d3-d736-497a-b356-dcfa83d6d808`), targeting `http://localhost:8080`.
Polling makes full-run request counts/duration variable. Healthy public HTTP checks
do not claim internal fault-injection, outage/503, deterministic multi-JVM or
production-capacity coverage; the Java integration suite remains complementary.

Both new JSON files appeared staged during generation, before their cloud IDs were
aligned; only those IDs differ between the staged and current working copies.
The index was left as found rather than restaged without approval. Only the
disposable `ats-postman-0effb298` containers, network, volume and app image were
removed after verification; pre-existing services/data were not touched.

## Three-way requirements audit

On 2026-10-21 three independent read-only audits against the brief, clarifications and
user decisions were merged and deduplicated. The user approved: per-decision option
tables, one NFR evidence ledger, the `ScheduleIT` rollback fix, documentation drift fixes
and two policy notes. They declined `MixedLoadIT` instrumentation and diagnostic re-runs,
and accepted that already-departed flights may still appear in search.

| Gate | Changes and evidence |
|---|---|
| Audit 14: merged audit corrections | `ScheduleIT`'s rollback test previously relied on the Wednesday CHECK constraint, which could fire before any earlier weekday row was written because `Set.copyOf` gives no insertion order. A test-only trigger now rejects whichever weekday row is inserted second, and is dropped in `finally`; 0 parent and 0 weekday rows remain. Rewrote `decision-log.md` as 21 decisions with option tables (verdict, used, strengths, weaknesses, why/why not now, better when) and a table mapping every strategy named in FR4/FR7/FR8 to its decision. Added `tests/nfr-evidence.md`: requirement map, environment, fixture, 13 scenarios with conditions and acceptance, a dated run register, per-run results and latency tables, the intermittent `MixedLoadIT` connect-time reset (3 of 7 recorded runs, cause unknown, not retried) and what the evidence does not prove. README/HLD/LLD now give a dated summary and link to the ledger instead of repeating undated numbers; regenerated the HLD PDF with the same `cupsfilter` method and verified its extracted text equals the Markdown. Corrected `Airport.code`, the sanitized (not length-bounded) instance identifier and off-by-one clarification line references; documented the departed-flight search staleness, validation/write-start departure checks, lexical seat ordering and the inert `reWriteBatchedInserts`. Final isolated Java 21 `./mvnw -B -ntp verify` (R2, 2026-10-21 21:15 IST) passed 32 unit plus 57 integration cases with zero failures/errors/skips, and Spotless kept all 87 Java files clean. No production code, migration, dependency or container configuration changed. The brief, HEAD `814a891` and the 11 pre-existing staged entries remain unchanged; no commit or push. `tests/nfr-evidence.md` appeared staged by the IDE on creation and was left as found. |

## Historical execution

| Timestamp | Skill | Action | Key Decision | Outcome |
|---|---|---|---|---|
| 2026-10-10 23:13 | spec-writer | Created `features/ATS-1/` | Greenfield — `code-explorer` skipped (no code to explore) | Steps 0,2,3,5 done |
| 2026-10-10 23:13 | spec-writer | Marked `Cross-cutting: No` | Single deployable; `architecture-diagrammer` deferred to HLD phase | Phase 2 skipped |
| 2026-10-10 23:20 | spec-writer | Line-by-line brief read | 7 ambiguities found (AMB-1…AMB-7) | Traceability matrix built |
| 2026-10-10 23:20 | spec-writer | Clarifying questions answered | localNoTz · drop validity window · departure-time cutoff · reject past cancel · include GET schedules | Decisions locked |
| 2026-10-10 23:25 | spec-writer | Derived conflict AMB-2 surfaced | No tz data ⇒ no true departure instant; resolved via single UTC reference-zone seam | Documented as limitation |
| 2026-10-10 23:25 | spec-writer | Reversed earlier plan decision | Dropped `valid_from`/`valid_to` — was self-inflicted scope creep | Schema reduced |
| 2026-10-10 23:25 | spec-writer | Wrote `spec.md` | 7 tables, 7 endpoints, 14 business rules, 5 concurrency tests | Status: DRAFT — awaiting APPROVE |
| 2026-10-10 23:40 | spec-writer | **Second line-by-line audit** of spec vs brief | Found 2 self-inflicted defects + 6 unsurfaced brief gaps | Spec revised |
| 2026-10-10 23:40 | spec-writer | **D1 fixed** — passenger modelling | `booking.passenger_name` (single) was unable to represent N passengers; moved to `booking_seat.passenger_name`, count derived | AMB-8 |
| 2026-10-10 23:40 | spec-writer | **D2 fixed** — flight number uniqueness | API business key `flightNumber+date` was unguaranteed; added `UNIQUE (flight_number, day_of_week)` | AMB-10 |
| 2026-10-10 23:40 | spec-writer | Closed gaps G1/G2/G5/G6 | Available seats = count; status enum = CONFIRMED\|CANCELLED; rolling window favours lazy; IATA not city | AMB-9, AMB-11, A13–A17 |
| 2026-10-10 23:50 | spec-writer | Saved verbatim brief as source of truth | `.notes/assignment-brief.md` — gitignored; repo must not republish the company's assignment text | Audit anchor established |
| 2026-10-10 23:50 | spec-writer | **Third audit** — scope, I/O, NFR, deliverables | Found AMB-12: brief says both "365 days" (FR4) and "one year" (Scope + Assumptions) — differ by 1 day on leap years | Chose 365, config-driven |
| 2026-10-10 23:50 | spec-writer | Added §2.4 Scope Matrix | 20 capabilities marked IN/OUT with the clause that decides each | Scope made testable |
| 2026-10-10 23:50 | spec-writer | Added §5.1/§5.2 I/O contracts + status codes | Response bodies carry **exactly** the fields the brief names; passengers as `{name, seat}` pairs so count cannot drift | FR5, FR6, FR7 |
| 2026-10-10 23:50 | spec-writer | Created `decision-log.md` | 14 decisions, Bad/Workable/Chosen with rejected alternatives + 7 declined-on-scope entries | Feeds HLD §Major Design Decisions |
| 2026-10-10 23:50 | spec-writer | DEC-14 black-box audit | **No in-scope flow calls an external system**; only admin auth is a black box — a mock payment gateway would misread FR9 | Stub surface minimised |
| 2026-10-11 00:05 | spec-writer | **Section-by-section brief audit begun** | One brief section at a time; audit *both* `spec.md` and `decision-log.md` against it before advancing. Tracked in a `brief_audit` table so no section could be silently skipped | 21 sections enumerated |
| 2026-10-11 00:10 | spec-writer | §Objective / §Scope / FR1–FR9 audited | Objective names **8 assessment axes**; §2.0.2 added. NFR table given a "what fails if violated" falsifier column so each NFR is testable rather than aspirational | ~40 findings |
| 2026-10-11 00:25 | spec-writer | Environment verified, not assumed | Apple M5 Pro arm64, 18 logical cores, 24 GB; Docker 29.8.2, Compose v5.5.1. The 18-core figure is what makes CT-5's `N ≥ 12` genuinely concurrent rather than interleaved | Concurrency tests justified |
| 2026-10-11 00:30 | spec-writer | **Docker smoke test executed end to end** | Verified the *chain*, not just a 200 from `/actuator/health`: both images `linux/arm64` (no QEMU), Hikari opened a real `PgConnection`, **Flyway connected and created `flyway_schema_history`** — proving the migration pipeline, PG 16.15 aarch64 | DEC-16 "unverified" → evidence table |
| 2026-10-11 00:30 | implement-and-test | **Defect found by running it** — hardcoded host ports | 8080/5432 are the two most commonly occupied developer ports; a collision broke the reviewer's *first* command. Parameterised with defaults so the documented command is unchanged | `3691fc7` |
| 2026-10-11 00:35 | spec-writer | §Deliverable 5 (README) audited | Grepped modal verbs across all 5 deliverables: README is the **only** one specified with *"must include"*, and is the most granular — 6 headings, **17 named items**. Omissions there are mechanically detectable, so headings use the brief's words verbatim | §2.3.1 + DEC-18 |
| 2026-10-11 00:35 | spec-writer | **§8 Algorithms written** (~380 lines) | Dangling `§8 Algorithms` reference was real; §8–§12 renumbered to §9–§13 and the section authored. All **13 brief sub-points verbatim and in order** — verified programmatically, not by eye | Obligation `spec-8` discharged |
| 2026-10-11 00:40 | spec-writer | §Evaluation Criteria audited | The brief carries **two different assessment lists 250 lines apart**; only 3 items appear verbatim in both. **4 graded criteria are never mentioned up front** | §2.5 written |
| 2026-10-11 00:40 | spec-writer | Scalability tension resolved | Criterion 5 grades "Scalability **considerations**" while NFR9 waives optimisation. The reconciling word is *considerations*: **reasoning** is graded, **machinery** is not — so distributed thinking is a requirement, not scope creep. Discharged as prose in HLD §9 | Obligation `hld-9-scaleout` |
| 2026-10-11 00:45 | spec-writer | §Assumptions audited | Framing is a **grant of permission with a disclosure condition**, not a requirement list. All 9 adopted, 0 deviations; brief-9 → our-17 traceability map added | §11.0 + §11.1 |
| 2026-10-11 00:45 | spec-writer | **A2 shown to be literally false** | "One seat belongs to only one booking" cannot survive FR9 cancellation. Only consistent reading: at most one **active** booking per `(instance, seat)`. The literal reading — a total unique index — **would make cancellation permanently destroy inventory** | Partial index justified |
| 2026-10-11 00:45 | spec-writer | A8 apparent violation resolved | "All timestamps UTC" vs `TIME` columns: **timestamp means *instant***. `TIMESTAMPTZ` for instants; `TIME`/`DATE` as given. Storing local time *as if* UTC would be worse than either — and is why `arrival < departure` is legal and unvalidated | BR-4 confirmed |
| 2026-10-11 00:50 | spec-writer | §Expected Repository Structure audited | Tree is drawn language-agnostically, so parts collide with any toolchain. Rule: comply literally where cheap, deviate only where compliance breaks the build, say so at the path a reviewer opens | §2.3.2 |
| 2026-10-11 00:50 | implement-and-test | `db/migrations` compliance **proven with a probe** | Named deliverable path, so wired via Maven `targetPath` to Flyway's unmodified classpath default — works in checkout, jar, image and Testcontainers alike. Verified through `process-resources`, not assumed | — |
| 2026-10-11 00:50 | implement-and-test | **Latent dual-resource-root defect fixed** | Two roots could write the same classpath path, letting Maven copy order silently pick the winner — no warning; would surface as a missing table at runtime | `25fed6f` |
| 2026-10-11 00:55 | spec-writer | §Supplementary instruction audited | *"Working"* is its first word, independently confirming Criterion 1. **Declined to write a payment stub**: the licence *presupposes such calls exist* and here none do — a stub would insert a step FR7 does not have | §10.1 / §10.2 |
| 2026-10-11 00:55 | spec-writer | **API path inconsistency fixed** | Traceability matrix said `POST /bookings`, §5.1 said `POST /api/v1/bookings`. 9 references normalised — it matters because the matrix is what maps requirement → endpoint | Residual check clean |
| 2026-10-11 00:56 | spec-writer | **Audit complete — 21/21 sections, 66 findings** | Consistency sweep green: 0 dangling refs across 18 DEC · 12 AMB · 14 BR · 7 CT. `mvn clean package` green after the pom change | 3 obligations open |
| 2026-10-11 00:57 | spec-writer | Frontmatter parse failure fixed | `` ` `` is a YAML **reserved indicator**; a plain scalar may not begin with one. Markdown code-span convention collides with YAML. Value quoted; all frontmatter now parses | Same defect class: reads fine, breaks a parser |
| 2026-10-11 00:58 | pr-preparation | 3 commits pushed to `origin/main` | Split by concern rather than squashed — `git log` should let each change justify itself | `c599f30` |
| 2026-10-11 02:30 | spec-writer | decision-log rebuilt from spec citations | Prior file was truncated to 0 bytes by a non-atomic write. Reconstructed DEC-1…DEC-18 from the 51 spec citations rather than memory, so it is consistent with the spec **by construction** | 23 entries, index matches headings |
| 2026-10-11 02:30 | spec-writer | DEC-19…DEC-23 added | Topology (D1–D7) · NFR envelope from the interviewer · 409 enumerates lost seats · decision log version-controlled · **one deployable, two namespaces** | DEC-22 un-ignored `features/` |
| 2026-10-11 02:30 | spec-writer | D5/D6/D7 found to be promissory notes | The §2.0.4 table named three discharge sections; **all three were empty**. Verified by word count, not heading existence — the only check that catches this | §4.0.1 · §7.5.1 · §6.1 written |
| 2026-10-11 02:30 | spec-writer | **Pass 3 — distributed re-audit, 21/21 sections, 27 findings** | Lens: *what breaks or changes at `N` instances under the DEC-20 envelope?* One section at a time, brief read line by line first | Consistency sweep green |
| 2026-10-11 02:30 | spec-writer | §7.11 — the missing third write path | §7 documented booking, cancellation and materialisation and was **silent on schedule creation**. Found by checking §7 against §3's write paths in **both** directions | CT-9 added; `CT-1…CT-8` fixed in 8 places |
| 2026-10-11 02:30 | spec-writer | §8.1.5 — two kinds of staleness | The grant was tracked but server-side (cache) and client-side (human) staleness were conflated. The grant was **accepted and then not spent**; the condition still binds because it applies to a window caching never caused | DEC-20, DEC-21 |
| 2026-10-11 02:30 | spec-writer | NFR3 statelessness made load-bearing | *RESTful* was audited as verbs and status codes only. Statelessness is what permits any-instance routing — and is the real reason a hold→confirm flow was never on the table | ArchUnit + 2 criteria |
| 2026-10-11 02:30 | spec-writer | DEC-2's own claim audited against itself | It claims *all five mechanisms* are evaluated, then shows five rows that are **not the brief's five**. Added the mapping: versioning **is** optimistic locking; transactions are the substrate, not an option | The failure the paragraph itself names |
| 2026-10-11 02:30 | spec-writer | HLD §6.1/§6.2/§7.1–7.3/§9 and LLD §6/§7/§9/§10 specified | §6.4 had a detailed must-cover brief; the topology-sensitive sections had none. Writing them later would have silently dropped the graded reasoning | Outline → specification |
| 2026-10-11 02:30 | spec-writer | README's 13 mandated sub-points pinned verbatim | The only *must include* deliverable had them as prose. Also added the missing **Dependencies** heading and repointed *Design decisions* at the decision log per DEC-18 | Verified programmatically |
| 2026-10-12 13:55 | spec-writer | **Pass 4 — artefact-vs-document audit, 21/21 sections, 11 findings** | New lens: pass 3 compared documents to documents and was structurally blind to a document that disagrees with the code. Every claim re-checked against the artefact that implements it | 6 artefact defects found |
| 2026-10-12 13:55 | spec-writer | `maximum-pool-size` 20 → 10 | §7.5.1 *derives* 10 from `max_connections`; the repository shipped 20 — the exact configuration-shaped failure that section describes. Also revalidates CT-5, which needs threads > pool (12 > 10 holds; it would not have at 20) | Rule **D8** created |
| 2026-10-12 13:55 | spec-writer | **`docker compose --scale app=3` did not work** | `container_name` + a fixed `8080:8080` binding capped the deployment at one instance. The only runnable artefact could not run the topology the whole design rests on, making every D1–D10 claim unfalsifiable by a reviewer | Verified: `app-1/2/3` on 8080-8082 |
| 2026-10-12 13:55 | spec-writer | `max_connections` actually pinned | §7.5.1's sizing table cited it as *"pinned in `docker-compose.yml`"*. It was the image default — the spec cited an artefact as evidence for arithmetic it then relied on | `command: postgres -c max_connections=100` |
| 2026-10-12 13:55 | spec-writer | **D-tables reconciled — spec and decision-log disagreed** | Both authoritative; `D3` and `D4` named *different rules* in each. Every `D3`/`D4` citation in the project was ambiguous. Resolved by **usage, not preference** (6/6 `D3` meant caching, 3/4 `D4` meant retry); displaced rules given `D9`/`D10` rather than deleted | 10 rules, byte-identical in both files |
| 2026-10-12 13:55 | spec-writer | §7.5.2 — virtual threads | `spring.threads.virtual.enabled: true` was live in config and the concurrency chapter never mentioned it. Virtual threads delete Tomcat's worker pool as an *accidental* admission limit; the queue moves to the connection pool, so the bound becomes `connectionTimeout` — 30 s then a `500`. DEC-20 licenses a longer **wait**, not a slow **untyped failure** | 10 s + `503`/`Retry-After` |
| 2026-10-12 13:55 | spec-writer | Rolling deploy named as the cause of `503` | §7.2 framed indeterminate commit outcomes as an exotic fault. Under `N` instances the dominant generator is a **deploy** — scheduled, not accidental — so the design had under-positioned the path its own release process exercises most | `server.shutdown: graceful` |
| 2026-10-12 13:55 | spec-writer | Readiness split from liveness | They answer different questions. A database outage must fail *readiness* and pass *liveness*: restarting cannot reach a database that is down, and restarting every instance at once turns a recoverable outage into a thundering herd | Compose healthcheck → `/readiness` |
| 2026-10-12 13:55 | spec-writer | README Option 1b | Three documented run options, all single-instance — a reviewer had no path to verify any distributed claim. Added `--scale app=3` and the falsification test: same seat, two ports, exactly one `201` | 5 new acceptance criteria (37) |
| 2026-10-12 17:10 | spec-writer | Clarifications pinned into `.notes/assignment-brief.md` | Seven interviewer replies appended verbatim as a marked section, following the precedent already set by the supplementary instruction. They now carry the same authority as the brief, and where a reply and an inference disagree, **the reply wins** | 374 lines |
| 2026-10-12 17:10 | spec-writer | **Fresh audit of all 26 decisions** | Asked not *are these right* but *which decisions should exist and do not*. Three did not | DEC-24, DEC-25, DEC-26 |
| 2026-10-12 17:10 | spec-writer | **DEC-24 — §5 contained a false claim** | §5 argued API-6 resolves an indeterminate booking because it is *"keyed on something the client already holds"*. If the **response** was lost, the client never received the PNR — so the stated recovery path is unreachable by exactly the client that needs it. Retrying collides with the customer's **own** seats: `409 SEAT_UNAVAILABLE`, unrecoverable | Optional `Idempotency-Key`; nullable column + `UNIQUE` |
| 2026-10-12 17:10 | spec-writer | DEC-25 — expand/contract migrations | D5 made concurrent **startup** safe but said nothing about **version skew**: during a rolling deploy, v1 and v2 serve the same database simultaneously, so a new migration must not break the **old** code. Invisible at `N = 1` and invisible in tests, because neither ever runs two versions at once | DEC-24's `ADD COLUMN … NULL` is expand-safe by construction |
| 2026-10-12 17:10 | spec-writer | DEC-26 — the 1-second target was argued, never measured | The one numeric NFR given was defended by reasoning. Latency does not regress because an index disappears; it regresses because something stops **using** it. Asserting the *shape* of the work — statement count, `EXPLAIN` plan — is deterministic where a wall-clock bound is flaky | Loose 1 s backstop only |
| 2026-10-12 17:10 | spec-writer | CT-10 added; `CT-1…CT-9` fixed in 2 live files | **Fourth** occurrence of the range-ID defect class. Predicted, swept for, found | 41 acceptance criteria |
| 2026-10-12 17:49 | spec-writer | **Pass 5 — brief re-read section by section under the clarified scale** | Previous passes audited the *clarifications*; this one re-read the **whole brief** asking which decisions were sized for a bigger system than the one actually specified | 3 findings, all over-scope |
| 2026-10-12 17:49 | spec-writer | DEC-29 — virtual threads turned off | The only performance setting in the repo, against a brief that says *"performance optimizations are not required"*. Peak is **6 QPS** vs Tomcat's 200 workers — 3 orders of magnitude below the threshold — while **Java 21** still pins carriers on `synchronized` (JEP 491 is Java 24). It also deleted an admission limit that DEC-27 then had to reason around | Setting flipped; §7.5.2 complexity now justified by its absence |
| 2026-10-12 17:49 | spec-writer | **DEC-30 — the index set did not exist** | Deliverable 4 lists **"Indexes"** as mandatory. Seven tables, zero secondary indexes. Hidden because `uq_booking_seat_active` is a unique *index*, so every search for the word returned hits — found by counting `CREATE INDEX` against table count | §4.9: 5 indexes, sized per table |
| 2026-10-12 17:49 | spec-writer | DEC-26 amended — plan assertion was wrong at the stated scale | It asserted an index scan on `flight_schedule` and *"no sequential scan"*. At **500 rows** PostgreSQL correctly seq-scans; the assertion would fail against a **healthy** planner. Forcing it needs fictitious volume or `enable_seqscan=off`, which tests obedience not shape | Retargeted to `booking_seat` (~1.1M rows/yr) |
