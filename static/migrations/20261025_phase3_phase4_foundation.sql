-- Phase 3 + Phase 4 additive hardening for the Phase 2A normalized schema.
-- Safe to run against the Phase 2A database. No existing tables are dropped.
-- This script does not replace the Phase 2A schema and is not for the Phase 2C legacy schema.
BEGIN;
SET LOCAL search_path TO palm_portal_v2;

-- Verification challenges store only a hash of the one-time code. Sending the code is handled by the app's mail provider.
CREATE TABLE IF NOT EXISTS email_verification_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_email_challenges_user_expiry
    ON email_verification_challenges(user_id, expires_at DESC);

-- Application lookups and operational constraints.
CREATE INDEX IF NOT EXISTS idx_eligibility_cycle_email
    ON student_eligibility(cycle_id, LOWER(institutional_email));
CREATE INDEX IF NOT EXISTS idx_opportunities_cycle_status_deadline
    ON opportunities(cycle_id, status, application_deadline);
CREATE INDEX IF NOT EXISTS idx_applications_student_cycle_status
    ON applications(student_id, cycle_id, status);
CREATE INDEX IF NOT EXISTS idx_applications_company_status
    ON applications(company_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_application_history_application_time
    ON application_status_history(application_id, created_at DESC);

COMMIT;
