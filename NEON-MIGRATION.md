# Moving the portal to Neon

Neon is PostgreSQL. The portal can still use MariaDB/MySQL (including XAMPP); it selects the SQL dialect from `DB_URL`.

Build and run the Java server with JDK 25 or newer and Maven 3.9 or newer. The Maven compiler targets Java 25 bytecode.

## 1. Prepare Neon

1. Create a Neon project in the region appropriate for the portal's users and data residency requirements.
2. In the Neon **Connect** panel, copy the full PostgreSQL connection string and set it as `DATABASE_URL`. The portal accepts the Neon-provided `postgresql://user:password@host/database?sslmode=require` format directly; separate `DB_USER` and `DB_PASS` settings are not needed. Prefer the direct (non-pooler) host.
3. In the Neon SQL Editor, run [`static/schema-postgres.sql`](static/schema-postgres.sql).
4. Set `ADMIN_PASSWORD` to an initial admin password, run `npm run build`, then run `mvn compile exec:java` from the project directory. The Java server reads `.env` and `.env.local` at startup (environment variables override values from those files); Maven resolves the PostgreSQL JDBC driver from `pom.xml`.

Optional: append `&reWriteBatchedInserts=true` to the connection string to optimize batched inserts. For a pooled Neon endpoint, append `&prepareThreshold=0` if prepared-statement reuse errors occur.

## 2. Migrate existing MariaDB/MySQL data

The TSV export contains user password hashes. Store it securely, do not commit it, and delete it after migration. Do not export over a `data.tsv` file you need to keep; set `DATA_FILE` to a separate transfer filename, such as `neon-migration.tsv`.

1. With the current MariaDB/MySQL connection settings, set `EXPORT_DATA_FILE=true` and `DATA_FILE=neon-migration.tsv`, then run `mvn compile exec:java`. It exports all database tables and exits.
2. Set `DATABASE_URL` to the Neon connection string. Keep the same `DATA_FILE`, set `IMPORT_DATA_FILE=true`, and run `mvn compile exec:java`. Import is refused unless every destination table is empty.
3. After confirming the import, remove `IMPORT_DATA_FILE` and securely delete the transfer file.
4. If the portal runs on a different computer, copy the `cvs` and `reports` folders separately; PDFs are not stored in the database.

PowerShell example (use a new filename that does not already exist):

```powershell
$env:DATA_FILE = "neon-migration.tsv"
$env:EXPORT_DATA_FILE = "true"
mvn compile exec:java
Remove-Item Env:EXPORT_DATA_FILE

$env:DATABASE_URL = "postgresql://YOUR-NEON-USER:YOUR-NEON-PASSWORD@YOUR-NEON-HOST/neondb?sslmode=require"
$env:IMPORT_DATA_FILE = "true"
mvn compile exec:java
Remove-Item Env:IMPORT_DATA_FILE
```

The import expects the table-tagged TSV produced by `EXPORT_DATA_FILE`; it rejects unknown row/table names. The existing TSV-backed demo mode is not a substitute for this migration. Use the export command against the source SQL database to create the transfer file.

## 3. Important considerations

- Student records and CVs contain personal information. Confirm data-protection approval and check Neon’s data-processing terms and region before using real records.
- Neon may pause inactive databases. The server retries remote connection attempts up to three times with a two-second interval.
- Saves rewrite changed tables in a transaction. This is suitable for modest data volumes; growing audit and notification tables may need append-only persistence if performance becomes an issue.
- Use a restricted database role, not the Neon project owner. The end of `static/schema-postgres.sql` shows example table and sequence grants.
- Back up the database using PostgreSQL tools (such as `pg_dump`) and separately back up `cvs` and `reports`.

## 4. Troubleshooting

| Message or symptom | Likely cause |
|---|---|
| `No suitable driver found` | Maven dependencies were not resolved; run `mvn compile exec:java` with network access and a working Maven installation. |
| SSL or connection reset | Ensure the URL includes `sslmode=require`. |
| Password authentication failed | Recheck the username/password within `DATABASE_URL` in Neon’s Connect panel. |
| `relation "students" does not exist` | Run `static/schema-postgres.sql` in the intended Neon database. |
| Prepared statement already exists | Use Neon’s direct host, or add `prepareThreshold=0` when using a pooled host. |
| `DATABASE UNAVAILABLE` | Check the JDBC URL, credentials, driver dependencies, network access, and schema. `ALLOW_FILE_MODE=true` is for demos only. |

The project has been compile-checked locally, but a real Neon connection and cross-database migration should first be tested with non-production data.
