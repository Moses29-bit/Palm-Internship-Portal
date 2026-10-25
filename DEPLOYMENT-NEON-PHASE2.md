# Neon PostgreSQL Phase 2 foundation

## Scope and compatibility

The Phase 2 database model lives in the separate PostgreSQL schema `palm_portal_v2`. The current Java server continues to use its existing tables in `public`; installing this foundation does not move, overwrite, or delete current portal data and does not switch the running application to the new model.

Use [`static/schema-postgres-phase2.sql`](static/schema-postgres-phase2.sql), not the source ZIP's unreviewed SQL. The script creates the schema, enums, tables, indexes, timestamps, role/permission seeds, and registration defaults in one transaction. It does not require a separately installed PostgreSQL extension.

## Install

1. Create a Neon branch for testing the schema changes before applying them to a production branch.
2. In Neon, select the intended project, branch, and database, then open SQL Editor.
3. Review and run the complete [`static/schema-postgres-phase2.sql`](static/schema-postgres-phase2.sql) script. It creates objects inside `palm_portal_v2`, leaving the current `public` schema intact.
4. Run the verification query below in the same database:

```sql
SELECT
  to_regclass('palm_portal_v2.users') AS users_table,
  to_regclass('palm_portal_v2.internship_cycles') AS cycles_table,
  (SELECT COUNT(*) FROM palm_portal_v2.roles) AS role_count,
  (SELECT COUNT(*) FROM palm_portal_v2.permissions) AS permission_count,
  (SELECT COUNT(*) FROM palm_portal_v2.role_permissions) AS role_permission_count,
  (
    SELECT setting_value
    FROM palm_portal_v2.system_settings
    WHERE setting_key = 'registration.allow_domain_fallback'
  ) AS domain_fallback;
```

The table names should resolve, the role, permission, and role-permission counts should be nonzero, and `domain_fallback` should be JSON `false`.

## Important runtime boundary

- Do not change the current server's `DATABASE_URL` or `search_path` to point at `palm_portal_v2` yet.
- Do not drop or replace the existing `public` tables or `static/schema-postgres.sql`.
- `Server.java` still reads and writes the existing table/column model. The new schema is a foundation only; a later phase must implement repositories, data migration, and compatibility tests before switching runtime traffic.
- For migration tools that connect directly, use the Neon direct (unpooled) connection string; keep application traffic on the pooled string where appropriate. Never put either connection string in frontend code or commit it.

## Phase 2B connectivity check

The project includes a separate JDBC health check. It does not read or modify application tables:

```powershell
mvn.cmd -q -DskipTests compile
mvn.cmd -q exec:java '-Dexec.mainClass=DatabaseHealthCheck'
```

It reads `NEON_DATABASE_URL` first, then `DATABASE_URL`, using the same process-environment / `.env` / `.env.local` loader as the existing server. PostgreSQL `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` variables are a fallback. Keep the URL and credentials private. A successful check reports the database and PostgreSQL version without printing the username or connection string.

The check proves connectivity only. It does not switch the portal's live `Server.java` persistence from `public` to `palm_portal_v2`.

## Repository compatibility sample

[`StudentRepository.java`](StudentRepository.java) is an isolated, read-only example against `palm_portal_v2.students` and `palm_portal_v2.programmes`. It uses the Phase 2 schema's UUID student ID, `full_name`, `institutional_email`, `programme_id`, and `active` fields. It will only run successfully after that schema is installed. It is not called by the existing server.

Do not install the Phase 2C compatibility ZIP's `palm_legacy_server_neon_schema.sql` into the current database: it creates legacy table names in `public` and conflicts with the separate Phase 2 schema. Its included `Server.java` is not the current portal source. The current app already requires an approved Student List and has the access-request workflow; keep these live implementations unchanged during this foundation step.

## Phase 3/4 service foundation

[`Phase3Phase4Service.java`](Phase3Phase4Service.java) contains a separate service layer for the normalized Phase 2 schema. It obtains connections through `Database`, whose JDBC `currentSchema` is set to `palm_portal_v2`. It is not called by the current HTTP server.

After the Phase 2 schema is installed on a non-production Neon branch, run [`static/migrations/20261025_phase3_phase4_foundation.sql`](static/migrations/20261025_phase3_phase4_foundation.sql) in that same branch/database. The migration is additive and scoped to `palm_portal_v2`; it adds verification challenges and lookup indexes. Do not run it against a database that has only the legacy `public` schema.

The service handles data operations, not the web security boundary. Before routing traffic to it, add and test authenticated server-side sessions, CSRF protections, request/IP throttling for verification/access requests, safe user-facing errors, and email-provider delivery. Never accept a user UUID or authorization role from an unauthenticated client as proof of identity.

Run the behavioral checks in [`PHASE3-PHASE4-TEST-CHECKLIST.md`](PHASE3-PHASE4-TEST-CHECKLIST.md) against test-only records before integrating these methods into `Server.java`.

## Phase 5–8 operations and analytics foundation

[`InternshipOperationsService.java`](InternshipOperationsService.java) and [`PortalAnalyticsService.java`](PortalAnalyticsService.java) use the shared `Database.getConnection()` helper, which selects only `palm_portal_v2`. The supplied standalone `DatabaseConfig` from the ZIP is intentionally not installed because this project already has a centralized `DatabaseConfig` that reads `.env` and `.env.local`; do not create a second class with the same name.

After confirming the Phase 2A schema is installed, review and run [`static/migrations/20261025_phase5_phase8_operational_analytics.sql`](static/migrations/20261025_phase5_phase8_operational_analytics.sql) on a Neon development branch using a direct, non-pooled connection. The migration sets a transaction-local search path to `palm_portal_v2`, adds operational indexes, and creates the aggregate view `v_portal_dashboard_counts`. It does not touch legacy tables in `public`; it has not been applied to Neon as part of this change.

The service classes are compiled foundations, not HTTP route integrations. Before calling them from `Server.java`, implement and test authenticated server-side identity, role and record-ownership checks, CSRF validation for state-changing requests, rate limits, and safe error responses. Never use actor, student, company, or role identifiers supplied by an unauthenticated browser as authorization evidence. The CSS and icon helper are loaded by `static/index.html`, but the application's current endpoints and data model remain unchanged.

Use [`PHASE5-PHASE8-TEST-CHECKLIST.txt`](PHASE5-PHASE8-TEST-CHECKLIST.txt) for non-production validation. The response-header helper allows the Google Fonts styles/font hosts already used by the public page; validate any Content Security Policy against all runtime assets before enabling it on the live server.
