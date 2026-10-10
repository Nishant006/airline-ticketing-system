# Database

Flyway owns the schema. Hibernate is configured with `ddl-auto: validate`, so the
application refuses to start if the entity mapping and the migrated schema disagree.

| Path | Role |
|---|---|
| `migrations/` | **Source of truth.** Versioned Flyway migrations, applied automatically on startup. |
| `schema.sql` | Generated, human-readable snapshot of the final schema (`pg_dump --schema-only`). Reference only — never executed by the application. |
| `er-diagram.md` | Entity-relationship diagram. |

## Why `migrations/` and not `src/main/resources/db/migration`

The assignment brief mandates a top-level `db/migrations/` directory, while Flyway
scans `classpath:db/migration` by convention. Rather than keeping two copies of the
same SQL, `pom.xml` declares `db/migrations` as an additional Maven resource
directory mapped to `db/migration` on the classpath:

```xml
<resource>
  <directory>db/migrations</directory>
  <targetPath>db/migration</targetPath>
</resource>
```

One source of truth, the layout the brief asks for, and it still works inside the
Docker image.

## Migration naming

```
V<version>__<snake_case_description>.sql
```

Migrations are **immutable once committed**. A mistake is corrected by a new
migration, never by editing an applied one.
