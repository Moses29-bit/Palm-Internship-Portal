# Palm Internship Portal - Project Documentation

## 1. Project Overview

The Palm Internship Portal is a web application for coordinating internships between Palm University College students, university coordinators, and industry partners. It brings the placement lifecycle into one workspace: student access and profiles, opportunity discovery, applications, university and company review, placement assignment, internship progress, evaluations, and reporting.

The project is intended to reduce fragmented email and spreadsheet workflows, make application and placement status visible to each participant, and provide coordinators with a central place to manage eligibility, partners, student progress, and outcomes.

This document describes the top-level project in this workspace. The workspace also contains a nested `Palm-Internship-Portal/` tree with a second copy of many project files; make changes in the intended project copy and keep the copies synchronized only when that is deliberate.

## 2. Users and Roles

| Role | Main responsibilities |
| --- | --- |
| Administrator / university coordinator (`ADMIN`) | Maintain student access, students, companies, staff, settings, and internship dates; review applications and access requests; coordinate placements; monitor attendance and logbooks; manage evaluation forms; view reports, audit activity, and notifications. |
| Student (`STUDENT`) | Maintain an account and profile, discover partner companies and opportunities, apply and follow application status, manage a CV, record attendance and logbook entries, receive notifications, and complete internship records. |
| Industry partner (`COMPANY`) | Maintain a company profile and workplace location, publish opportunities or updates, review applications forwarded by the university, manage interns, record evaluations, and provide reports or feedback. |

Role-specific navigation and API checks limit each user to the workflows intended for that role. A company account may be required to complete onboarding before accessing the rest of the portal.

## 3. Features and Workflows

### Authentication and account access

- Sign in, sign out, change password, request password reset, and reset a password.
- Student sign-up is gated by the administrator-managed approved student list. A student access request must be reviewed; approving the request adds an email to the approved list but does not itself create an account.
- Email verification and verification-code resend are supported when SMTP is configured.
- Company onboarding, profile updates, and administrator-managed staff access are supported.
- Public users can verify a placement letter without signing into an account.

### Student access and records

- Administrators manage students and can import or preview approved-list data from CSV, TXT, or TSV files.
- Students can maintain profile information and upload a PDF CV.
- Students can browse companies and opportunities, search portal content, and submit or withdraw applications.
- The portal provides a student-facing view of application, placement, attendance, logbook, notification, and evaluation information.

### Applications and placements

- The active application workflow uses the legacy statuses `SUBMITTED`, `FORWARDED`, `ACCEPTED`, `REJECTED`, `DECLINED`, and `WITHDRAWN`.
- A student submits an application to the university. A coordinator can reject it or forward it to a selected company with an optional note. A company can then accept or decline it.
- Coordinators can assign students to companies, update placement status and dates, and manage the placement lifecycle.
- The portal can generate school and company placement letters and allows their verification.
- Company capacity and placement matching information can assist coordination.

### Internship operations

- Students can clock in and out. The portal can use browser geolocation and configured workplace coordinates/geofence settings to assess attendance location.
- Students submit weekly logbook entries; coordinators or company supervisors can review, approve, or return entries with notes.
- Coordinators can monitor attendance and placement status and keep internship dates current.
- Students, coordinators, and companies receive in-portal notifications. Email notification delivery is available when SMTP is configured.
- Partners and coordinators can publish opportunities and company news/updates.

### Evaluations and reporting

- Administrators can create, duplicate, activate, and archive evaluation forms with configurable questions.
- Company users submit evaluations for interns; administrators can review response summaries and aggregate evaluation information.
- Student or company reports can be uploaded as PDF documents and retrieved by authorized users.
- Administrators can export portal data and review audit activity.
- The administration area also contains operational settings, password resets, and administrative reporting views.

### Optional CV analysis

- The portal includes CV and skills analysis that can use built-in keyword matching or, when configured, an external Anthropic or Google Gemini API.
- External analysis sends CV-related content to the selected provider. It is an optional integration and should only be used with appropriate user notice and consent. API keys must be stored as server-side environment variables, never in browser code or source control.

## 4. Technology Stack and Languages

| Area | Technology |
| --- | --- |
| Backend | Java 25; JDK `com.sun.net.httpserver.HttpServer`; Java standard-library HTTP client, JDBC, crypto, concurrency, and file APIs. |
| Frontend | HTML5, CSS3, and browser-native JavaScript (ES modules are not required by the current page). There is no React, Vue, Angular, or separate frontend build framework. |
| Database definitions | SQL for MariaDB/MySQL and PostgreSQL. The Neon v2 foundation uses PostgreSQL-specific types and schema features. |
| Build and dependency management | Apache Maven for the Java project; Node.js/npm for the small `scripts/build.mjs` helper and Neon configuration dependencies. |
| Automated end-to-end testing | Python 3 standard library (`unittest`, `urllib`, and subprocess/socket utilities), with a Java fake JDBC driver used by the test harness. |
| Containerization | Docker, using a multi-stage Java 25 image. |
| Deployment options | A Java process behind an HTTPS reverse proxy, or the supplied container configuration. Vercel Container Runtime is described in the deployment notes; it is not required for local development. |
| Hosted PostgreSQL option | Neon PostgreSQL. Neon project configuration is in `neon.ts`; the Phase 2 schema is an isolated future database foundation. |

The interface references Poppins and Material Symbols from Google Fonts. The portal's application logic and icons are otherwise implemented locally; there is no separate frontend component library.

## 5. Direct Dependencies

### Maven dependencies

| Dependency | Version | Purpose |
| --- | --- | --- |
| `org.postgresql:postgresql` | `42.7.8` | JDBC connectivity to PostgreSQL, including Neon. |
| `org.mariadb.jdbc:mariadb-java-client` | `3.5.2` | JDBC connectivity to MariaDB and MySQL-compatible deployments. |

The Maven build also configures `maven-compiler-plugin` `3.16.0` and `exec-maven-plugin` `3.5.0`. Java's built-in APIs handle HTTP serving, outbound HTTP calls, password hashing, SMTP communication, and other runtime functions; no additional Java libraries are declared for these tasks.

### npm dependencies

| Dependency | Version range | Purpose |
| --- | --- | --- |
| `@neon/config` | `^1.9.3` | Neon project configuration used by `neon.ts`. |
| `@neon/env` | `^1.6.4` | Declared Neon environment tooling dependency. |

The npm `build` script invokes `javac --release 25` through `scripts/build.mjs`; it does not bundle or compile the browser frontend. `package-lock.json` records the resolved npm dependency tree.

### Other development and runtime tools

- JDK 25 or newer. The Maven compiler release is set to Java 25.
- Maven 3.9 or newer.
- Node.js and npm for `npm run build` and installation of the declared npm dependencies.
- Python 3 for the standard-library end-to-end test suite.
- A supported database server for non-test operation: MariaDB/MySQL or PostgreSQL. Neon is a hosted PostgreSQL option.
- Docker when building or running the supplied container image.
- A modern browser. Geolocation features require browser permission and a secure context in normal deployments.
- Optional SMTP service for verification and email notifications; optional Anthropic or Google Gemini credentials for external CV analysis.

## 6. Application Architecture

The active application is a compact, single-process web application:

1. `Server.java` serves static frontend assets and handles the JSON/form API using the JDK HTTP server.
2. `static/index.html`, `static/app.js`, `static/style.css`, and `static/portal-ui.css` provide the landing, authentication, and role-based portal views.
3. The server authenticates API requests, applies role checks and CSRF validation to authenticated writes, and manages the portal's business workflows.
4. The running server connects to the configured SQL database through JDBC. Its current persistence model loads data into in-process collections and saves portal state back to the legacy-compatible schema.
5. Uploaded CV and report PDFs are written to local filesystem directories (`cvs/` and `reports/`).

This design is straightforward to run as one instance, but it is not currently a horizontally scalable, stateless architecture. Sessions, rate-limit counters, and active workflow state include in-memory components. Local file uploads are not durable storage for deployments where the container filesystem may be replaced.

## 7. Databases and Data Model

### Active database path

The active `Server.java` supports MariaDB/MySQL and PostgreSQL through JDBC. The default local URL is `jdbc:mariadb://localhost:3306/palm_portal`. For PostgreSQL/Neon, configure `DATABASE_URL` and install the legacy-compatible `public` schema from [`static/schema-postgres.sql`](static/schema-postgres.sql). For MariaDB/MySQL, use [`static/schema.sql`](static/schema.sql).

The active schema covers these data areas:

- Users, password credential material, onboarding state, and account verification.
- Students, companies, approved student registrations, and access requests.
- Applications, placements, internship dates, and company capacity/location.
- Attendance, reports, logbook entries, and evaluations.
- Evaluation forms, questions, and responses.
- Company posts, notifications, audit log, and system settings.

The schema files are database setup artifacts and are not automatically applied by the application. Apply the schema that matches the configured database before starting the server.

### Neon v2 foundation (not the active server schema)

[`static/schema-postgres-phase2.sql`](static/schema-postgres-phase2.sql) defines a more normalized model in the isolated `palm_portal_v2` schema. Its table groups include role/permission-based identity, departments and programmes, internship cycles and eligibility, companies and opportunities, applications and status history, placements, attendance, logbooks, monitoring visits, evaluations, reports, documents, notifications, audit logs, and settings.

The additional Java classes `Database`, `DatabaseConfig`, `DatabaseHealthCheck`, `StudentRepository`, and the Phase 3-8 services provide connectivity checks, examples, and service foundations for this v2 model. As documented in the README, these classes are not wired into the current `Server.java` HTTP routes or persistence. Applying a v2 schema does not migrate the active application to it. Keep the v2 schema separate from `public` and follow the phase deployment notes before considering an application migration.

### File-based data and uploads

`data.tsv` is used by supported migration/demo file workflows. `ALLOW_FILE_MODE=true` enables TSV-backed demo operation and is not suitable for real user data. SQL export/import uses `EXPORT_DATA_FILE`, `IMPORT_DATA_FILE`, and optionally `DATA_FILE`; exported files contain password hashes and must be protected and removed securely after transfer. CV and report PDFs are separate files and need a separate migration strategy.

## 8. Integrations and External Services

| Integration | Use | Required configuration |
| --- | --- | --- |
| MariaDB/MySQL or PostgreSQL | Persistent application data. | `DATABASE_URL` or `DB_URL`, with credentials supplied by the URL or database environment settings. |
| Neon | Hosted PostgreSQL option and future v2 schema hosting. | Neon connection URL (`DATABASE_URL` for the active app; `NEON_DATABASE_URL` is also understood by the standalone v2 connectivity configuration). |
| SMTP server | Email verification and email notifications. | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM`, optionally `SMTP_SECURITY`. |
| Anthropic API | Optional external CV analysis. | `ANTHROPIC_API_KEY`; optionally `ANTHROPIC_MODEL`. |
| Google Gemini API | Optional external CV analysis. | `GEMINI_API_KEY`; optionally `GEMINI_MODEL`. `AI_PROVIDER` can select an available provider. |
| Browser geolocation | Attendance and company workplace location workflows. | User permission, supported browser, and secure page context; company location and geofence settings are managed in the portal. |
| Google Fonts | Poppins font and Material Symbols used in the UI. | Browser network access; if unavailable, browser fallback fonts/icons may be used. |

## 9. Configuration Reference

The Java server reads process environment variables and `.env` / `.env.local` files in its working directory. Process environment values take precedence. Keep secret files out of version control and container images.

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Preferred database connection string. Used by the active server for PostgreSQL and can also be used for configured database URLs. |
| `DB_URL` | Alternate database URL, particularly the MariaDB/MySQL configuration. |
| `DB_USER`, `DB_PASS` | Separate database credentials where supported by the configured connection path. |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | PostgreSQL connection components for the separate Neon v2 database configuration. |
| `ADMIN_PASSWORD` | Initial administrator password. At least 8 characters in normal mode and at least 12 characters with `PRODUCTION=true`. |
| `PRODUCTION` | Enables production startup requirements and production response behavior. |
| `BIND`, `PORT` | Bind address and HTTP port. Development defaults to loopback and scans ports 8080-8099 when `PORT` is unset. Containers set `0.0.0.0:8080`. |
| `ALLOWED_ORIGINS`, `TRUST_PROXY` | CORS allow-list and trusted forwarded client-address handling when deployed behind a configured proxy. |
| `SMTP_*` | Outbound email server settings; see the integration table. |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_PROVIDER` | Optional external CV analysis provider settings. |
| `DATA_FILE`, `EXPORT_DATA_FILE`, `IMPORT_DATA_FILE`, `ALLOW_FILE_MODE` | Data transfer and demo file-mode controls. |

See [`README.md`](README.md), [`DEPLOYMENT.md`](DEPLOYMENT.md), and [`DEPLOYMENT-NEON.md`](DEPLOYMENT-NEON.md) for deployment-specific instructions and the Neon phase guide for the isolated v2 schema.

## 10. Local Development

Prerequisites: JDK 25+, Maven 3.9+, and a supported SQL database with the matching schema installed. Node.js/npm is needed for the npm build helper; Python 3 is needed only for the end-to-end tests.

Typical local sequence:

1. Configure the database and initialize it with the matching schema.
2. Set `ADMIN_PASSWORD` and the database connection variables in the process environment or a private `.env.local` file.
3. Optionally install npm dependencies with `npm install` and run `npm run build`.
4. Compile and start the Java server with `mvn compile exec:java`.
5. Open the local URL printed by the server, normally `http://localhost:8080`.

The server requires the matching database JDBC driver, already declared in the Maven project. Do not expose a production instance directly over plain HTTP; production traffic should use HTTPS termination at a properly configured reverse proxy or hosting layer.

## 11. Testing

The automated end-to-end suite is [`tests/e2e_test.py`](tests/e2e_test.py). It uses Python's standard library and launches the Java server against `tests/FakeDb.java` by default, so the suite does not require a live production database. It can also target a running server using `PALM_BASE_URL`.

Run the end-to-end test file with:

```powershell
python tests/e2e_test.py
```

For the documented browser/device, database, and deployment checks, use [`TEST-PLAN.md`](TEST-PLAN.md), [`PHASE3-PHASE4-TEST-CHECKLIST.md`](PHASE3-PHASE4-TEST-CHECKLIST.md), and [`PHASE5-PHASE8-TEST-CHECKLIST.txt`](PHASE5-PHASE8-TEST-CHECKLIST.txt). The phase checklists cover foundation work and do not imply that those service classes are connected to the active server.

## 12. Security, Privacy, and Operational Notes

- Use HTTPS for real users. Set `PRODUCTION=true`, configure a strong administrator password, and use the deployment guidance for proxy and origin settings.
- Never commit database credentials, SMTP passwords, or AI provider keys. Use the hosting platform's secret/environment-variable facility in deployment.
- The application has server-side authentication, role checks, CSRF checks for authenticated writes, request rate limiting, and security response headers. Production readiness still depends on correct environment and proxy configuration.
- CVs, reports, and AI analysis involve sensitive student information. Restrict access, retain only what is required, and disclose when a CV is sent to an external AI provider.
- The current process stores sessions and rate limits in memory and persists state from a single application process. Concurrent instances can lose session continuity or overwrite stale state unless storage is redesigned for shared and concurrency-safe operation.
- Uploaded documents are stored on local disk. Container platforms may use ephemeral filesystems; production use requires durable object storage and a migration plan for existing uploads.
- A deployed container is not, by itself, evidence that the application is ready for horizontal scaling or production use. Test in a non-production environment first.
- Use the isolated `palm_portal_v2` schema only for its documented phase work. Do not replace the active `public` schema with the v2 model without a tested migration of code and data.

## 13. Key Project Files

| Path | Purpose |
| --- | --- |
| `Server.java` | Active HTTP server, routes, authentication, business logic, and active persistence implementation. |
| `static/` | Frontend assets and SQL schema/migration files. |
| `pom.xml` | Java release level, Maven plugins, and JDBC dependencies. |
| `package.json`, `package-lock.json`, `scripts/build.mjs` | npm dependencies and the Java compilation helper. |
| `neon.ts` | Neon project and branch configuration. |
| `Database.java`, `DatabaseConfig.java`, `DatabaseHealthCheck.java`, `StudentRepository.java` | Separate PostgreSQL v2 connectivity/repository examples. |
| `Phase3Phase4Service.java`, `InternshipOperationsService.java`, `PortalAnalyticsService.java` | Database-backed service foundations for later phases; not connected to active HTTP routes. |
| `PortalSecurity.java` | Security helpers for the later service/controller foundation; not a substitute for active route authorization. |
| `tests/` | End-to-end test harness and fake JDBC driver. |
| `Dockerfile`, `vercel.json` | Container and deployment configuration. |
| `DEPLOYMENT*.md`, `NEON-MIGRATION.md` | Deployment, database, and migration instructions. |
| `TEST-PLAN.md`, `PHASE*-TEST-CHECKLIST*` | Testing procedures and phase-specific verification checklists. |

## 14. Related Documentation

- [README.md](README.md): requirements, setup, deployment, and implementation phase status.
- [DEPLOYMENT.md](DEPLOYMENT.md): general deployment guidance.
- [DEPLOYMENT-NEON.md](DEPLOYMENT-NEON.md) and [DEPLOYMENT-NEON-PHASE2.md](DEPLOYMENT-NEON-PHASE2.md): Neon database setup and phase guidance.
- [NEON-MIGRATION.md](NEON-MIGRATION.md): existing data migration notes.
- [TEST-PLAN.md](TEST-PLAN.md): test procedures.