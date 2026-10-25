# Phase 3/4 service test checklist

Run against a non-production Neon branch with test records only. These checks are not proof of live portal integration; `Server.java` does not call the new service yet.

## Database foundation

- [ ] Confirm Phase 2 tables remain present in `palm_portal_v2`.
- [ ] Run `static/migrations/20261025_phase3_phase4_foundation.sql`.
- [ ] Confirm `palm_portal_v2.email_verification_challenges` exists.
- [ ] Confirm the legacy `public` tables remain unchanged.
- [ ] Confirm roles, an open internship cycle, an active programme, and matching test eligibility data exist.

## Registration and verification

- [ ] Register with matching student number, institutional email, full name, programme code, and an open cycle; verify the account starts `PENDING`.
- [ ] Confirm unlisted or mismatched student details fail without disclosing eligibility membership.
- [ ] Confirm an empty eligibility list does not permit registration.
- [ ] Verify access requests create neither an account nor eligibility before approval.
- [ ] Confirm only an active authorized coordinator can review an access request.
- [ ] Confirm approval adds eligibility, but does not create an account.
- [ ] Confirm incorrect verification attempts increment and persist the attempt counter, and the sixth attempt cannot verify.
- [ ] Confirm valid codes activate once; expired and reused codes fail.
- [ ] Confirm unverified accounts cannot authenticate and five failed logins trigger a temporary lock.
- [ ] Add HTTP-layer throttling and email delivery checks before exposing service methods.

## Companies, opportunities, and applications

- [ ] Confirm new companies start `PENDING` and cannot publish opportunities.
- [ ] Confirm unauthorized users cannot verify companies.
- [ ] Confirm company users can manage only their own company's opportunities and applications.
- [ ] Confirm closed cycles, closed opportunities, and passed deadlines reject submissions.
- [ ] Confirm a student cannot submit duplicate applications or more than three active applications per cycle, including simultaneous requests.
- [ ] Confirm invalid application status transitions leave application and history unchanged.
- [ ] Confirm every valid status change is recorded in `application_status_history`.
- [ ] Confirm only an authorized coordinator can create a placement from an accepted application.
- [ ] Confirm the active-placement constraint prevents duplicate active placements for a student/cycle.
- [ ] Confirm privileged mutations create audit-log rows.

## Runtime boundary

- [ ] Keep `Server.java` and existing `public` persistence unchanged during service testing.
- [ ] Do not connect production HTTP routes until server-side identity/session and CSRF handling are implemented and tested.
- [ ] Never log connection strings, passwords, verification codes, or raw database exceptions.

