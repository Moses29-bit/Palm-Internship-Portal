# Deployment and operations

## Requirements

- JDK 25 or newer and Maven 3.9 or newer (the configured build targets Java 25).
- A MariaDB/MySQL or PostgreSQL database initialized with the matching schema:
  [`static/schema.sql`](static/schema.sql) or
  [`static/schema-postgres.sql`](static/schema-postgres.sql).
- A unique `ADMIN_PASSWORD` (at least 12 characters in production).

For an existing database, apply the additive `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
statements in the matching schema before deploying. Back up the database first. The
application expects every configured table and column to exist at startup.

## Configuration and startup

Set `DATABASE_URL` to the database connection string, `ADMIN_PASSWORD` to the
administrator password, `PRODUCTION=true`, and `PORT` to the listening port. Configure
SMTP (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM`, and optionally
`SMTP_SECURITY`) if production email verification or notifications are required. Keep
secrets in deployment environment settings; never put them in static files or source
control.

Build and start from the repository root:

```powershell
npm run build
mvn compile exec:java
```

See [README.md](README.md) and [DEPLOYMENT-NEON.md](DEPLOYMENT-NEON.md) for the Neon
configuration and the current application limitations. The portal uses process-local
sessions and state; do not scale it horizontally without shared session storage and
safe concurrent database writes.

## HTTPS and device location

Browser geolocation is available only in a secure context: serve the portal over HTTPS
for every non-localhost deployment. `http://localhost` is allowed for development.
Configure TLS at the hosting platform or at a trusted reverse proxy, and forward
requests to the Java server over a protected network. Do not expose the unencrypted
backend port directly to the public internet.

Students are asked for a fresh, high-accuracy location only when they explicitly set
their attendance location. Clock-in coordinates are used to verify the configured
workplace geofence; no continuous tracking is performed. Administrators can review
precise attendance data, companies see only the on-site verification result, and
precise coordinates/accuracy are purged according to the attendance retention setting.
The retention setting defaults to 730 days.

## Verification before release

Run the e2e test plan in [TEST-PLAN.md](TEST-PLAN.md), test against a disposable
database for the selected SQL dialect, and verify that the application starts and
loads all tables before switching production traffic. Test geolocation on supported
physical Android and iOS devices over HTTPS; simulated coordinates cannot establish
real-device GPS accuracy.
