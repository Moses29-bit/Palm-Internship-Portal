# Student List and Registration Security

## Registration eligibility

The Admin → Student List is the authoritative source for student self-registration:

- A student email must be on the approved list before an account can be created.
- An empty list closes public student registration.
- An email missing from the list cannot create a student account; it can submit an access request for administrator review.
- Approving a request adds the email to the approved list. The student must then complete normal signup and email verification.
- Replacing the approved list does not delete existing student accounts.

## Access requests

Public request responses do not reveal whether an email is already eligible or registered. Duplicate pending requests are not created. Requests are rate-limited, persisted in the configured SQL database, and recorded in the administrator audit log. Approval and rejection require an administrator session and CSRF token.

## Student List imports

The Admin → Student List page supports pasted lists and CSV/TXT/TSV files with one email per line. An optional name can follow the email, separated by a comma, semicolon, or tab.

- Invalid email addresses and duplicate addresses within an import are identified in the preview.
- Imports use **Merge** by default and are limited to 5,000 entries.
- Replacing the list requires a separate confirmation; it removes eligibility for omitted email addresses, not existing accounts.
- List changes and access decisions are audit logged.

## Email verification

When SMTP is configured, new students must verify a six-digit email code that expires after 30 minutes. Verification and resend attempts are throttled. In production, signup is blocked if email verification is unavailable.

## Database migration

For existing PostgreSQL databases, run [`static/migrations/20261025_student_access_requests.sql`](static/migrations/20261025_student_access_requests.sql) before deploying the updated server. New databases can use [`static/schema-postgres.sql`](static/schema-postgres.sql). No table data is deleted by this migration.
