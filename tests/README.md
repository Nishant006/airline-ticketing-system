# Manual / exploratory tests

Automated tests live in `src/main`'s sibling `src/test/java`, following the Maven
Standard Directory Layout. Moving them to this top-level `tests/` directory would
break the build tool for no benefit, so this directory holds the artefacts that
genuinely do not belong on the classpath:

| Path | Purpose |
|---|---|
| `api.http` | Executable request collection (IntelliJ HTTP Client / VS Code REST Client) |
| `smoke.sh` | curl-based end-to-end walkthrough: seed → search → seat map → book → cancel |

## Automated test layout (`src/test/java`)

| Suffix | Runner | Docker required | Contents |
|---|---|---|---|
| `*Test` | Surefire (`./mvnw test`) | No | Domain unit tests, mocked application-service tests, `@WebMvcTest` slices, ArchUnit rules |
| `*IT` | Failsafe (`./mvnw verify`) | **Yes** | Testcontainers repository slices, end-to-end flows, and the concurrency suite |

This split is deliberate: `./mvnw test` stays fast and runnable without a Docker
daemon, while `./mvnw verify` runs everything including the concurrency evidence.
