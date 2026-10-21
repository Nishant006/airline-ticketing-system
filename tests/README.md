# Manual / exploratory tests

Automated tests live in `src/main`'s sibling `src/test/java`, following the Maven
Standard Directory Layout. Moving them to this top-level `tests/` directory would
break the build tool for no benefit, so this directory holds the artefacts that
genuinely do not belong on the classpath:

| Path | Purpose |
|---|---|
| `api.http` | IntelliJ HTTP Client collection with rolling UTC dates and response variables |
| `smoke.sh` | curl-based end-to-end walkthrough: seed → search → seat map → book → cancel |

## Postman: interview and E2E

The only Postman files are [`../postman/postman.json`](../postman/postman.json)
and [`../postman/env.json`](../postman/env.json). They contain no credentials.
The Postman management API key is not backend authentication and must never be
added to these files or backend requests.

1. Start the application: `docker compose up --build -d --wait`.
2. Import both JSON files into Postman and select **ATS Local**. Alternatively,
   use **Airline Ticketing System - Interview and E2E** and **ATS Local** already
   created in the personal **My Workspace**. Use the desktop app/desktop agent
   for localhost access.
3. Keep `baseUrl` as `http://localhost:8080`, or change it to your running
   instance without a trailing slash.
4. Run **01 Interview demo**, one iteration, for the short 16-request walkthrough.
   The first request regenerates compact flight IDs, UTC dates and references.
   Follow schedule creation, search, complete seat map, group booking, conflict,
   rollback proof, cancellation and rebooking. A blank passenger name is
   intentionally accepted, matching the brief. Seat maps have a **Visualize**
   tab: green means available; red means booked.
5. Run the **entire collection from the beginning**, one iteration, for all
   eight ordered folders and 127 request definitions. Other folders depend on
   initialized fixtures and earlier requests; they are not independent runs.
   Enable stop-on-error if your Runner offers it, and inspect failed assertions.

| Folder | Demonstration |
|---|---|
| 01 Interview demo | Atomic two-seat booking, live availability, conflict without partial allocation, cancellation, released-seat rebooking |
| 02 Schedule and seeded reference contracts | Duplicate schedule, structural validation, strict clocks, valid airport references and B738 overnight schedule/layout |
| 03 Search booking and cancellation validation | Empty search, absent flight/booking, malformed inputs, invalid/duplicate seats, repeat cancellation, unchanged ownership |
| 04 HTTP and correlation | 400/404/405/406/409/415/422 cases across the collection, incompatible Accept before writes, correlation echo/generation/rejection |
| 05 Operating weekdays and rolling UTC window | Nonoperating dates and half-open `[today, today+365)` boundaries, including last-day booking/cancellation |
| 06 Sold-out and full-group cancellation | Actual ATR 72 layout, all 72 seats booked atomically, searchable at zero availability, full release |
| 07 Concurrent public HTTP flows | 16 same-seat booking contenders and eight same-reference cancellation contenders, exactly one winner per race |
| 08 Real UTC departure cutoff | Book before departure; reject new booking and cancellation at/after departure; retain original ownership |

The final folder chooses a real server UTC minute 15-75 seconds ahead, then
polls liveness with a bounded delay until the server reaches that time. Run it
with **Runner**, not individual **Send**, so `setNextRequest` can repeat the poll.
The full collection usually takes about one to two minutes. Executed request
counts vary because of polling and include the 24 concurrent subrequests.
Every observed search is asserted to complete in under one second; this is not
a sustained-load or production capacity certification.

Successful runs cancel all future bookings they create. Schedules and cancelled
history remain because there are no deletion APIs. One **departed confirmed
booking** intentionally remains: cancelling it would violate the departure rule.
Interrupted/failed runs can leave future reservations; inspect the saved
collection reference variables and API state before any manual cleanup or retry.
Never blindly retry an unconfirmed write.

### Optional command-line run

Newman is optional for using the Postman app. Node 20+ and npm can run the pinned
CLI without adding setup, dependencies or reports to this repository:

```bash
npm exec --registry=https://registry.npmjs.org --package=newman@6.2.3 -- \
  newman run postman/postman.json -e postman/env.json \
  --bail --timeout-request 10000 --timeout-script 30000

# Short demo only; override the target when needed:
npm exec --registry=https://registry.npmjs.org --package=newman@6.2.3 -- \
  newman run postman/postman.json -e postman/env.json \
  --folder "01 Interview demo" --env-var baseUrl=http://localhost:8080 --bail
```

The collection checks the supported healthy public HTTP flows and representative
negative inputs, not every possible input combination. FR1/FR2 reference data is
observed through valid reference use and complete aircraft layouts, not invented
CRUD endpoints. Database-fault rollback, outage/503 recovery, SQL plans, internal
logging/MDC privacy, deterministic concurrency barriers and multi-JVM guarantees
remain the responsibility of the Java integration tests. Unexpected 500/503
responses are not manufactured or claimed as Postman coverage.

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
one winner and no duplicate active allocations. `target/mixed-load.txt` is written only
after every phase and final assertion succeed, so a failed run leaves no phase metrics
(and may leave an older report in place). The test fails intermittently with a
connect-time connection reset whose cause is not established; it is not retried.

Every recorded run, its conditions, per-scenario results and latency tables are kept in
the [NFR evidence ledger](nfr-evidence.md). Update that file, not the README/HLD/LLD,
when new measurements are taken.

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
