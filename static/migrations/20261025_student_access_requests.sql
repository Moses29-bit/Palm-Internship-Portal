-- Add durable student eligibility access requests to an existing PostgreSQL database.
-- Safe to run before deploying the portal update; it preserves all existing data.
CREATE TABLE IF NOT EXISTS access_requests (
  id VARCHAR(20) PRIMARY KEY,
  name VARCHAR(60) NOT NULL,
  program VARCHAR(60) NOT NULL,
  email VARCHAR(120) NOT NULL,
  reason VARCHAR(300) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_access_requests_email
  ON access_requests (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_access_requests_status
  ON access_requests (status);
