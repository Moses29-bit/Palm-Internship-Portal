-- Palm Internship Portal: Phases 5–8 additive performance/reporting migration
-- Intended for the already-installed Phase 2A PostgreSQL schema in Neon.
-- This script does not drop tables, reset data, or replace existing schema objects.
BEGIN;
SET LOCAL search_path TO palm_portal_v2;

CREATE INDEX IF NOT EXISTS ix_attendance_review_queue
    ON attendance(status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_logbook_review_queue
    ON logbook_entries(status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_reports_review_queue
    ON reports(status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_evaluations_type_submitted
    ON evaluations(evaluation_type, submitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_monitoring_visit_date
    ON monitoring_visits(visit_date DESC);
CREATE INDEX IF NOT EXISTS ix_placements_completion
    ON placements(status, end_date);
CREATE INDEX IF NOT EXISTS ix_notifications_recent
    ON notifications(user_id, created_at DESC);

-- Safe, read-only dashboard views. No user-identifiable data is exposed here.
CREATE OR REPLACE VIEW v_portal_dashboard_counts AS
SELECT 'active_students'::text AS metric, COUNT(*)::bigint AS metric_value
  FROM students WHERE active = TRUE
UNION ALL SELECT 'verified_companies', COUNT(*)::bigint
  FROM companies WHERE status IN ('VERIFIED','ACTIVE')
UNION ALL SELECT 'open_opportunities', COUNT(*)::bigint
  FROM opportunities WHERE status = 'OPEN'
UNION ALL SELECT 'active_placements', COUNT(*)::bigint
  FROM placements WHERE status = 'ACTIVE'
UNION ALL SELECT 'pending_attendance_reviews', COUNT(*)::bigint
  FROM attendance WHERE status = 'PENDING_REVIEW'
UNION ALL SELECT 'pending_logbook_reviews', COUNT(*)::bigint
  FROM logbook_entries WHERE status = 'SUBMITTED'
UNION ALL SELECT 'pending_report_reviews', COUNT(*)::bigint
  FROM reports WHERE status IN ('SUBMITTED','UNDER_REVIEW');

COMMIT;
