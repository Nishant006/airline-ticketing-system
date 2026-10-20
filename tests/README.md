# Manual / exploratory tests

Automated tests live in `src/main`'s sibling `src/test/java`, following the Maven
Standard Directory Layout. Moving them to this top-level `tests/` directory would
break the build tool for no benefit, so this directory holds the artefacts that
genuinely do not belong on the classpath:

| Path | Purpose |
|---|---|
| `api.http` | IntelliJ HTTP Client collection with rolling UTC dates and response variables |
| `smoke.sh` | curl-based end-to-end walkthrough: seed → search → seat map → book → cancel |

## Automated test layout (`src/test/java`)

| Suffix | Runner | Docker required | Contents |
|---|---|---|---|
| `*Test` | Surefire (`./mvnw test`) | No | Domain unit tests, mocked application-service tests, `@WebMvcTest` slices, ArchUnit rules |
| `*IT` | Failsafe (`./mvnw verify`) | **Yes** | Testcontainers repository slices, end-to-end flows, and the concurrency suite |

This split is deliberate: `./mvnw test` stays fast and runnable without a Docker
daemon, while `./mvnw verify` runs everything including the concurrency evidence.

`SearchPerformanceIT` and `MixedLoadIT` share one 500-schedule/600,000-allocation
fixture and write `target/search-performance.txt` and `target/mixed-load.txt`.
`SearchPerformanceIT` requires all 100 sequential and 200 eight-worker measured
searches to return 200 in under one second; p50/p95 remain diagnostics, not substitutes
for the slowest-response check.

`MixedLoadIT` uses exact 25:1 traffic. Its existing 16-client phase is the local
engineering baseline: every search must return 200 in under one second. The 64/192-client
phases are exploratory stress, allowing explicit 503 overload responses but not an
all-shed phase. `searchAll` includes all search responses; `search200` includes only
successful results, so fast overload rejections cannot hide successful-search latency.
All disjoint bookings must return 201 in every phase; the hot-seat race still requires
one winner and no duplicate active allocations.

The user accepted a stateless backend and delegated scaling judgment without a fixed
peak request rate. These short warm-cache, local closed-loop checks are not an agreed
peak-RPS SLA, a sustained production capacity certification or a cold-cache guarantee.
Production autoscaling infrastructure is not supplied by these tests.

`ReadIsolationIT` exhausts the physical read/write pools and exercises PostgreSQL
SQL/lock cancellation, independent booking capacity and connection recovery.

With the application ready, run `bash tests/smoke.sh http://localhost:8080`.
The script needs Bash, curl and Python 3 (standard library only). It creates a fresh
schedule for tomorrow, checks every response and releases its final test booking.
Its schedule and cancelled booking history remain as explicit walkthrough data.
