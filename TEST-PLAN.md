# Test plan

## Automated end-to-end coverage

`tests/e2e_test.py` uses only Python's standard library. It compiles the server and
`tests/FakeDb.java`, launches an isolated local instance unless `PALM_BASE_URL` points
to an already running test instance, and exercises onboarding, attendance, evaluation
privacy, exports, and the landing page. Never point it at production: it creates users,
companies, placements, and attendance records.

The fake JDBC driver rejects identifiers quoted with the wrong dialect. Run both SQL
dialects in the normal server entry point:

```powershell
$env:PALM_DIALECT = 'mariadb'
Remove-Item Env:PALM_MAIN -ErrorAction Ignore
python tests\e2e_test.py

$env:PALM_DIALECT = 'postgres'
python tests\e2e_test.py
```

Run both dialects through the `PALM_MAIN=FakeDb` entry point as well:

```powershell
$env:PALM_DIALECT = 'mariadb'
$env:PALM_MAIN = 'FakeDb'
python tests\e2e_test.py

$env:PALM_DIALECT = 'postgres'
python tests\e2e_test.py
Remove-Item Env:PALM_DIALECT, Env:PALM_MAIN -ErrorAction Ignore
```

The test compiler defaults to Java 25. Set `PALM_JAVA_RELEASE` only when intentionally
testing against a compatible lower local JDK. The fake driver verifies the server's
dialect-specific identifier quoting; it is not a substitute for executing either
schema against a real MariaDB or PostgreSQL instance.

## Manual device and operational checks

1. Use a disposable database; apply the matching schema and start the application.
2. Create a company as admin. Confirm that non-onboarding actions return
   `ONBOARDING_REQUIRED`; change the temporary password, complete the profile, set its
   location, preview, and confirm onboarding.
3. Use a physical phone over HTTPS. Confirm permission-denied, timeout, inaccurate GPS,
   missing workplace, outside-geofence, successful clock-in, and unrestricted
   clock-out messages and states.
4. Confirm the attendance report filters and totals, opens a student's full history,
   and downloads the exact filtered rows with verification and distance columns.
5. Create, activate, submit, and review an evaluation form. Verify that only admins can
   read answers and that submitted forms cannot be edited.
6. Verify report sorting on screen, in CSV, and in the print view. Confirm CSV formula
   escaping and that downloads include a report type and date.
7. Verify mobile layout, keyboard focus, live status announcements, and reduced-motion
   behavior.

Real-device location accuracy and provider-specific permission prompts require manual
testing; synthetic test coordinates do not validate GPS hardware or browser behavior.
