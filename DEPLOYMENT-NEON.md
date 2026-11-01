# Neon deployment checklist

## New database

1. Create a Neon PostgreSQL project and run [`static/schema-postgres.sql`](static/schema-postgres.sql) in the SQL Editor.
2. Set the pooled PostgreSQL connection string as the server-side `DATABASE_URL`.
3. Set `ADMIN_PASSWORD` and `PRODUCTION=true`. Use a password of at least 12 characters in production.
4. Configure SMTP for production student email verification.
5. Build with `npm run build` and start with `mvn compile exec:java`.

## Existing PostgreSQL database

Before deploying the updated server, run [`static/migrations/20261025_student_access_requests.sql`](static/migrations/20261025_student_access_requests.sql) in the Neon SQL Editor. It adds the access-request table and indexes without replacing existing data. Do not start the updated server until the migration completes: startup loads every configured table.

## Registration go-live

1. Sign in as administrator and open **Student List**.
2. Import the approved students, inspect invalid and duplicate rows, and use **Merge** unless a full-list replacement is intended.
3. Confirm the approved-list count and test signup with an eligible email.
4. Test an unlisted email and confirm it cannot create an account.
5. Test an access request, approve it, and confirm the approval adds eligibility without creating an account.
6. Confirm the student can then sign up and verify their email.

## Production safeguards

- Keep `DATABASE_URL`, `ADMIN_PASSWORD`, and SMTP credentials in server-side environment or secret storage, not in frontend files.
- Serve the Java server behind HTTPS. Browser geolocation used for attendance is unavailable on non-localhost HTTP origins.
- Attendance location is requested only for explicit clock-in/clock-out actions; precise coordinates are available to administrators and purged according to the configured retention period. Companies see only on-site verification status.
- Set `ALLOWED_ORIGINS` when the frontend is hosted on another origin.
- Do not enable `ALLOW_FILE_MODE` for real data.
- Review the Admin Audit log and establish a database backup/retention policy.

See [DEPLOYMENT.md](DEPLOYMENT.md) for the full release checklist and [TEST-PLAN.md](TEST-PLAN.md) for the attendance, onboarding, evaluation privacy, and report-export checks.
