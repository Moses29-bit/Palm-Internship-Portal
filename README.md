# Palm Internship Portal

## Requirements

Build and run the Java server with JDK 25 or newer and Maven 3.9 or newer. The Maven compiler targets Java 25 bytecode.

## Portal experience

The public landing page and sign-in experience are served from [`static/index.html`](static/index.html). Students, university coordinators and industry partners can enter the existing sign-in flow from the landing page. After sign-in, each role sees a tailored Overview dashboard. The admin Overview includes placement health, priority actions, the internship lifecycle, application outcomes, partner capacity, and recent placements. Admins can manage the approved student registration list, import and preview CSV/TXT/TSV files, and review access requests. Student accounts can only be created for approved email addresses; approving an access request adds the email to the list but does not create an account. Authenticated navigation includes a Back action for the previous portal page and accessible controls for the mobile menu and quick actions. Consistent inline SVG icons distinguish actions, status chips and application progress without adding external UI dependencies. Application, placement, attendance, logbook and reporting workflows continue to use the existing Java API.

## Run with MariaDB/MySQL

The default database URL remains `jdbc:mariadb://localhost:3306/palm_portal`. Create the database with [`static/schema.sql`](static/schema.sql), then start the server with `mvn compile exec:java`.

## Run with Neon PostgreSQL (current application)

1. Create a Neon project and run [`static/schema-postgres.sql`](static/schema-postgres.sql) in its SQL Editor. This is the legacy-compatible `public` schema used by the current Java server.
2. Copy the full PostgreSQL connection string from Neon’s **Connect** panel and set it as `DATABASE_URL`. The portal accepts Neon’s `postgresql://user:password@host/database?sslmode=require` string directly; you do not need separate `DB_USER` or `DB_PASS` settings.
3. Set `ADMIN_PASSWORD` (at least 8 characters; 12 or more when `PRODUCTION=true`).
4. Run `npm run build` to compile the Java server, then `mvn compile exec:java` from the project directory to start it.

The Java server loads `.env` and `.env.local` at startup; process environment variables take precedence. Keep both files private. The Maven project includes PostgreSQL and MariaDB drivers. `DATABASE_URL` takes precedence over `DB_URL`; the PostgreSQL path uses double-quoted identifiers and PostgreSQL-compatible parameter binding. Hosted database connections are retried up to three times.

For an existing database, run [`static/migrations/20261025_student_access_requests.sql`](static/migrations/20261025_student_access_requests.sql) before deploying this version; it adds the request table and indexes without removing existing data.

Student registration requires a populated approved list. If no list is loaded, signup is closed; configure the list in Admin → Student List before inviting students. Set `PRODUCTION=true`, use a strong `ADMIN_PASSWORD`, and configure SMTP to require email verification for production signups.

## Architecture foundation (Phase 1)

The root Java source directory includes additive domain types for roles and permissions, application workflow transitions, internship cycles, placements, student eligibility, and access requests. These types are compiled with the project but are not yet wired into the existing API, authorization checks, or database schema. The current portal continues to use its established role strings and persistence model; connect these types only as part of a separately tested server and schema migration.

## Phase 2 Neon database foundation

The reviewed Phase 2 schema is [`static/schema-postgres-phase2.sql`](static/schema-postgres-phase2.sql). It creates the new model under the isolated `palm_portal_v2` schema so its tables do not collide with the current server's legacy-compatible tables in `public`. Follow [`DEPLOYMENT-NEON-PHASE2.md`](DEPLOYMENT-NEON-PHASE2.md) to install and verify it. This foundation is not yet used by `Server.java`; do not remove or replace the current `public` schema until the later application/data migration is implemented and tested.

## Phase 2B Neon connection check

`DatabaseConfig`, `Database`, and `DatabaseHealthCheck` provide a separate, read-only JDBC connectivity check. `StudentRepository` is a read-only example for the Phase 2 `students` and `programmes` tables in `palm_portal_v2`; its query is schema-qualified and uses the Phase 2 UUID and column names. The configuration reuses the server's environment loader, so it reads `NEON_DATABASE_URL` or `DATABASE_URL` from process environment, `.env`, or `.env.local`; existing `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD` variables are also supported. In PowerShell, build with `mvn.cmd -q -DskipTests compile`, then run `mvn.cmd -q exec:java '-Dexec.mainClass=DatabaseHealthCheck'` to verify connectivity. This does not migrate schema or change how `Server.java` persists data.

The Phase 2C ZIP contains an older `Server.java` and a compatibility schema that reuses existing table names in `public`. Do not replace the current server with that older copy or run that compatibility schema in the existing Neon database; the current server already enforces the approved Student List gate, and the compatibility schema is not the isolated Phase 2 schema.

## Phase 3/4 service foundation

[`Phase3Phase4Service.java`](Phase3Phase4Service.java) adds database-backed registration/verification/authentication, access-request review, company/opportunity workflows, application transitions, and placement creation against `palm_portal_v2`. [`StudentRepository.java`](StudentRepository.java) remains a read-only repository example. These classes are not wired into the current HTTP routes or `Server.java`; the existing portal remains on its current `public` persistence. Before using these service methods, install the Phase 2 schema and then the additive migration described in [`DEPLOYMENT-NEON-PHASE2.md`](DEPLOYMENT-NEON-PHASE2.md). Use [`PHASE3-PHASE4-TEST-CHECKLIST.md`](PHASE3-PHASE4-TEST-CHECKLIST.md) on a test branch before connecting any routes.

## Phase 5–8 operations, analytics, and UI foundation

[`InternshipOperationsService.java`](InternshipOperationsService.java) and [`PortalAnalyticsService.java`](PortalAnalyticsService.java) add attendance, logbook, monitoring, evaluation, report, placement-completion, notification, and aggregate dashboard operations for `palm_portal_v2`. Both use the shared [`Database.java`](Database.java) connection helper, including its existing environment loader and schema selection. [`PortalSecurity.java`](PortalSecurity.java) supplies controller-level CSRF, input, and response-header helpers; these are not a replacement for authenticated sessions, role/ownership checks, or route-level CSRF enforcement.

The additive migration [`static/migrations/20261025_phase5_phase8_operational_analytics.sql`](static/migrations/20261025_phase5_phase8_operational_analytics.sql) is explicitly scoped to `palm_portal_v2`. Apply it only after the Phase 2A schema is installed, on a Neon development branch first. It does not change the legacy [`static/schema-postgres.sql`](static/schema-postgres.sql), the live `public` schema, or Neon automatically.

The Phase 8 styles and semantic icon helper are loaded by [`static/index.html`](static/index.html). Existing API routes and [`Server.java`](Server.java) remain unchanged; service methods are not wired to HTTP endpoints. Review [`PHASE5-PHASE8-TEST-CHECKLIST.txt`](PHASE5-PHASE8-TEST-CHECKLIST.txt) and [`DEPLOYMENT-NEON-PHASE2.md`](DEPLOYMENT-NEON-PHASE2.md) before connecting routes or applying the migration.

## Moving existing SQL data

Set `EXPORT_DATA_FILE=true` while connected to the source database to export the tables to a TSV file and exit. Set `IMPORT_DATA_FILE=true` while connected to an empty destination database to import that file. Use `DATA_FILE` to choose a new transfer filename, for example `neon-migration.tsv`; the exporter refuses to overwrite an existing file. The transfer file includes password hashes; delete it securely after migration. CV and report PDFs are files and must be copied separately.

`ALLOW_FILE_MODE=true` enables the existing TSV-backed demo mode. Do not use it for real data.
