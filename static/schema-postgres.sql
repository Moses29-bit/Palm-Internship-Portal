-- Palm Internship Portal: PostgreSQL schema for Neon and other PostgreSQL hosts.
-- Run in the Neon console: SQL Editor > paste this file > Run.
-- Safe to run again: it only creates what is missing.

CREATE TABLE IF NOT EXISTS users (
  username  VARCHAR(120) PRIMARY KEY,
  role      VARCHAR(10) NOT NULL,
  ref       VARCHAR(10) NOT NULL,
  salt      BYTEA NOT NULL,
  pass_hash BYTEA NOT NULL,
  must_onboard SMALLINT NOT NULL DEFAULT 0,
  password_changed SMALLINT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS students (
  id VARCHAR(10) PRIMARY KEY, name VARCHAR(60) NOT NULL, program VARCHAR(60) NOT NULL,
  email VARCHAR(120) NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS companies (
  id VARCHAR(10) PRIMARY KEY, name VARCHAR(60) NOT NULL, industry VARCHAR(40), focus VARCHAR(120), capacity INT NOT NULL,
  email VARCHAR(120) NOT NULL DEFAULT '', website VARCHAR(200) NOT NULL DEFAULT '', about VARCHAR(600) NOT NULL DEFAULT '',
  location_lat DOUBLE PRECISION NULL, location_lng DOUBLE PRECISION NULL, location_address VARCHAR(300) NOT NULL DEFAULT '',
  maps_link VARCHAR(500) NOT NULL DEFAULT '', location_set_on TIMESTAMP NULL, location_set_by VARCHAR(120) NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS unverified (username VARCHAR(120) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS applications (
  id           VARCHAR(10) PRIMARY KEY,
  student_id   VARCHAR(10) NOT NULL,
  company_id   VARCHAR(10) NOT NULL DEFAULT '',
  pref         VARCHAR(60),
  statement    VARCHAR(400) NOT NULL,
  status       VARCHAR(12) NOT NULL,
  updated_on   DATE NOT NULL,
  school_note  VARCHAR(300) NOT NULL DEFAULT '',
  company_note VARCHAR(300) NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS placements (
  id SERIAL PRIMARY KEY,
  student_id VARCHAR(10) NOT NULL, company_id VARCHAR(10) NOT NULL,
  status VARCHAR(12) NOT NULL, placed_on DATE NOT NULL,
  start_date DATE NULL, end_date DATE NULL
);
CREATE TABLE IF NOT EXISTS attendance (
  id SERIAL PRIMARY KEY,
  student_id VARCHAR(10) NOT NULL, company_id VARCHAR(10) NOT NULL,
  clock_in TIMESTAMP NOT NULL, clock_out TIMESTAMP NULL,
  in_lat DOUBLE PRECISION NULL, in_lng DOUBLE PRECISION NULL, in_accuracy DOUBLE PRECISION NULL,
  in_distance DOUBLE PRECISION NULL, in_verified SMALLINT NOT NULL DEFAULT 0,
  out_lat DOUBLE PRECISION NULL, out_lng DOUBLE PRECISION NULL, out_accuracy DOUBLE PRECISION NULL,
  out_distance DOUBLE PRECISION NULL
);
CREATE TABLE IF NOT EXISTS reports (
  student_id VARCHAR(10) NOT NULL, company_id VARCHAR(10) NOT NULL,
  summary VARCHAR(300) NOT NULL DEFAULT '', reported_on DATE NOT NULL,
  PRIMARY KEY (student_id, company_id)
);
CREATE TABLE IF NOT EXISTS posts (
  id VARCHAR(10) PRIMARY KEY, company_id VARCHAR(10) NOT NULL,
  "type" VARCHAR(10) NOT NULL,            -- AD = internship opportunity, UPDATE = business news
  title VARCHAR(80) NOT NULL, body VARCHAR(600) NOT NULL, posted_on DATE NOT NULL
);
CREATE TABLE IF NOT EXISTS logbook (
  id VARCHAR(10) PRIMARY KEY, student_id VARCHAR(10) NOT NULL, company_id VARCHAR(10) NOT NULL,
  week DATE NOT NULL, body VARCHAR(1500) NOT NULL, status VARCHAR(12) NOT NULL,
  note VARCHAR(300) NOT NULL DEFAULT '', created_on DATE NOT NULL
);
CREATE TABLE IF NOT EXISTS evaluations (
  student_id VARCHAR(10) NOT NULL, company_id VARCHAR(10) NOT NULL,
  punctuality INT NOT NULL, skills INT NOT NULL, attitude INT NOT NULL, teamwork INT NOT NULL,
  "comment" VARCHAR(500) NOT NULL DEFAULT '', created_on DATE NOT NULL,
  PRIMARY KEY (student_id, company_id)
);
CREATE TABLE IF NOT EXISTS evaluation_forms (
  id VARCHAR(20) PRIMARY KEY, title VARCHAR(100) NOT NULL, status VARCHAR(10) NOT NULL,
  created_on DATE NOT NULL
);
CREATE TABLE IF NOT EXISTS evaluation_questions (
  id VARCHAR(20) PRIMARY KEY, form_id VARCHAR(20) NOT NULL, prompt VARCHAR(300) NOT NULL,
  question_type VARCHAR(20) NOT NULL, is_required SMALLINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0, options_text VARCHAR(1000) NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS evaluation_responses (
  student_id VARCHAR(10) NOT NULL, company_id VARCHAR(10) NOT NULL, form_id VARCHAR(20) NOT NULL,
  answers TEXT NOT NULL, submitted_on TIMESTAMP NOT NULL,
  PRIMARY KEY (student_id, company_id)
);
CREATE TABLE IF NOT EXISTS notifications (
  id SERIAL PRIMARY KEY, username VARCHAR(120) NOT NULL,
  created_at TIMESTAMP NOT NULL, "text" VARCHAR(300) NOT NULL, is_read SMALLINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS audit_log (
  id SERIAL PRIMARY KEY, created_at TIMESTAMP NOT NULL,
  actor VARCHAR(120) NOT NULL, "action" VARCHAR(60) NOT NULL, detail VARCHAR(300) NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS settings (k VARCHAR(40) PRIMARY KEY, v VARCHAR(255) NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS student_register (email VARCHAR(120) PRIMARY KEY, name VARCHAR(60) NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS access_requests (
  id VARCHAR(20) PRIMARY KEY, name VARCHAR(60) NOT NULL, program VARCHAR(60) NOT NULL,
  email VARCHAR(120) NOT NULL, reason VARCHAR(300) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_app_student    ON applications (student_id);
CREATE INDEX IF NOT EXISTS idx_app_company    ON applications (company_id, status);
CREATE INDEX IF NOT EXISTS idx_place_student  ON placements (student_id);
CREATE INDEX IF NOT EXISTS idx_place_company  ON placements (company_id, status);
CREATE INDEX IF NOT EXISTS idx_att_student    ON attendance (student_id, clock_in);
CREATE INDEX IF NOT EXISTS idx_log_student    ON logbook (student_id, week);
CREATE INDEX IF NOT EXISTS idx_note_user      ON notifications (username, is_read);
CREATE INDEX IF NOT EXISTS idx_audit_time     ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_user_ref       ON users (role, ref);
CREATE INDEX IF NOT EXISTS idx_access_requests_email ON access_requests (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_access_requests_status ON access_requests (status);

ALTER TABLE users ADD COLUMN IF NOT EXISTS must_onboard SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS location_lat DOUBLE PRECISION NULL;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS location_lng DOUBLE PRECISION NULL;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS location_address VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS maps_link VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS location_set_on TIMESTAMP NULL;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS location_set_by VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS in_lat DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS in_lng DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS in_accuracy DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS in_distance DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS in_verified SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS out_lat DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS out_lng DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS out_accuracy DOUBLE PRECISION NULL;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS out_distance DOUBLE PRECISION NULL;

-- Recommended: let the portal connect as a limited role instead of the project owner.
-- CREATE ROLE palm LOGIN PASSWORD 'choose-a-long-random-password';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO palm;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO palm;
