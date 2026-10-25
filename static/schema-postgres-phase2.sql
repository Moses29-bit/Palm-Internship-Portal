-- Palm Internship Portal 3.0
-- Phase 2: Neon PostgreSQL database foundation
-- PostgreSQL 15+ / Neon compatible
--
-- IMPORTANT:
-- 1. Run this against the Neon database selected for the portal.
-- 2. This script does NOT create a database; Neon already provides it.
-- 3. Do not run this against the old MySQL/MariaDB database.
-- 4. These tables are isolated in palm_portal_v2; the current application
--    continues using its existing public-schema tables until Phase 2B.

BEGIN;

CREATE SCHEMA IF NOT EXISTS palm_portal_v2;
SET LOCAL search_path TO palm_portal_v2, public;

-- =========================================================
-- 1. ENUM TYPES
-- =========================================================

DO $$ BEGIN
    CREATE TYPE user_status AS ENUM ('PENDING','ACTIVE','SUSPENDED','DISABLED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE cycle_status AS ENUM ('DRAFT','UPCOMING','OPEN','APPLICATIONS_CLOSED','ACTIVE','COMPLETED','ARCHIVED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE eligibility_status AS ENUM ('ELIGIBLE','INVITED','REGISTERED','VERIFIED','ACTIVE','SUSPENDED','REMOVED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE access_request_status AS ENUM ('PENDING','APPROVED','REJECTED','CANCELLED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE company_status AS ENUM ('PENDING','UNDER_REVIEW','VERIFIED','ACTIVE','SUSPENDED','INACTIVE','REJECTED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE opportunity_status AS ENUM ('DRAFT','OPEN','CLOSED','FILLED','CANCELLED','ARCHIVED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE application_status AS ENUM (
        'DRAFT','SUBMITTED','UNDER_REVIEW','SHORTLISTED','FORWARDED',
        'COMPANY_REVIEW','INTERVIEW','ACCEPTED','REJECTED','WITHDRAWN',
        'DECLINED','EXPIRED','CANCELLED'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE placement_status AS ENUM (
        'OFFERED','PENDING_CONFIRMATION','CONFIRMED','ACTIVE',
        'SUSPENDED','COMPLETED','CANCELLED','TERMINATED'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE attendance_status AS ENUM (
        'PRESENT','ABSENT','LATE','EXCUSED','PENDING_REVIEW','APPROVED','DISPUTED'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE logbook_status AS ENUM ('DRAFT','SUBMITTED','APPROVED','RETURNED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE report_type AS ENUM ('STUDENT_FINAL_REPORT','COMPANY_REPORT','UNIVERSITY_ASSESSMENT');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE report_status AS ENUM ('DRAFT','SUBMITTED','UNDER_REVIEW','APPROVED','RETURNED','REJECTED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE document_type AS ENUM (
        'CV','STUDENT_REPORT','COMPANY_REPORT','UNIVERSITY_ASSESSMENT',
        'LOGBOOK_ATTACHMENT','OTHER'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- =========================================================
-- 2. USERS / AUTHENTICATION
-- =========================================================

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(150) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash TEXT NOT NULL,
    password_algorithm VARCHAR(50) NOT NULL DEFAULT 'PBKDF2-SHA256',
    status user_status NOT NULL DEFAULT 'PENDING',
    email_verified_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS roles (
    id SMALLSERIAL PRIMARY KEY,
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE IF NOT EXISTS permissions (
    id SMALLSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id SMALLINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id SMALLINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- =========================================================
-- 3. ACADEMIC STRUCTURE
-- =========================================================

CREATE TABLE IF NOT EXISTS departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS programmes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(250) NOT NULL UNIQUE,
    degree VARCHAR(120),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS students (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE SET NULL,
    student_number VARCHAR(80) NOT NULL UNIQUE,
    full_name VARCHAR(250) NOT NULL,
    institutional_email VARCHAR(320) NOT NULL UNIQUE,
    personal_email VARCHAR(320),
    programme_id UUID REFERENCES programmes(id) ON DELETE SET NULL,
    level VARCHAR(50),
    academic_year VARCHAR(30),
    expected_graduation_year INTEGER,
    phone VARCHAR(40),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 4. INTERNSHIP CYCLES
-- =========================================================

CREATE TABLE IF NOT EXISTS internship_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    academic_year VARCHAR(30) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    application_open TIMESTAMPTZ,
    application_close TIMESTAMPTZ,
    status cycle_status NOT NULL DEFAULT 'DRAFT',
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (end_date >= start_date),
    CHECK (application_close IS NULL OR application_open IS NULL OR application_close > application_open)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_cycles_academic_year_name
    ON internship_cycles (academic_year, name);

-- =========================================================
-- 5. STUDENT ELIGIBILITY / ACCESS REQUESTS
-- =========================================================

CREATE TABLE IF NOT EXISTS student_eligibility (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES internship_cycles(id) ON DELETE CASCADE,
    student_id UUID REFERENCES students(id) ON DELETE SET NULL,
    student_number VARCHAR(80) NOT NULL,
    full_name VARCHAR(250) NOT NULL,
    institutional_email VARCHAR(320) NOT NULL,
    programme_id UUID REFERENCES programmes(id) ON DELETE SET NULL,
    level VARCHAR(50),
    status eligibility_status NOT NULL DEFAULT 'ELIGIBLE',
    source VARCHAR(50) NOT NULL DEFAULT 'ADMIN_IMPORT',
    imported_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (cycle_id, student_number),
    UNIQUE (cycle_id, institutional_email)
);

CREATE TABLE IF NOT EXISTS access_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES internship_cycles(id) ON DELETE CASCADE,
    student_number VARCHAR(80),
    full_name VARCHAR(250) NOT NULL,
    institutional_email VARCHAR(320) NOT NULL,
    programme_text VARCHAR(250),
    level VARCHAR(50),
    reason TEXT,
    status access_request_status NOT NULL DEFAULT 'PENDING',
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    review_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_pending_access_request
    ON access_requests (cycle_id, LOWER(institutional_email))
    WHERE status = 'PENDING';

-- =========================================================
-- 6. COMPANIES / COMPANY USERS
-- =========================================================

CREATE TABLE IF NOT EXISTS companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(250) NOT NULL UNIQUE,
    registration_number VARCHAR(120),
    industry VARCHAR(150),
    focus TEXT,
    capacity INTEGER NOT NULL DEFAULT 0 CHECK (capacity >= 0),
    email VARCHAR(320),
    phone VARCHAR(40),
    website TEXT,
    address TEXT,
    about TEXT,
    status company_status NOT NULL DEFAULT 'PENDING',
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS company_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_title VARCHAR(150),
    is_primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, user_id)
);

CREATE TABLE IF NOT EXISTS opportunities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    cycle_id UUID NOT NULL REFERENCES internship_cycles(id) ON DELETE RESTRICT,
    title VARCHAR(250) NOT NULL,
    description TEXT NOT NULL,
    requirements TEXT,
    skills JSONB NOT NULL DEFAULT '[]'::jsonb,
    preferred_programmes JSONB NOT NULL DEFAULT '[]'::jsonb,
    location VARCHAR(250),
    work_mode VARCHAR(50),
    capacity INTEGER NOT NULL DEFAULT 1 CHECK (capacity > 0),
    application_deadline TIMESTAMPTZ,
    status opportunity_status NOT NULL DEFAULT 'DRAFT',
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 7. APPLICATIONS
-- =========================================================

CREATE TABLE IF NOT EXISTS applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES internship_cycles(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
    opportunity_id UUID REFERENCES opportunities(id) ON DELETE SET NULL,
    preference_rank INTEGER CHECK (preference_rank IS NULL OR preference_rank > 0),
    statement TEXT,
    status application_status NOT NULL DEFAULT 'DRAFT',
    student_note TEXT,
    school_note TEXT,
    company_note TEXT,
    submitted_at TIMESTAMPTZ,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS application_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    old_status application_status,
    new_status application_status NOT NULL,
    changed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 8. PLACEMENTS
-- =========================================================

CREATE TABLE IF NOT EXISTS placements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES internship_cycles(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
    opportunity_id UUID REFERENCES opportunities(id) ON DELETE SET NULL,
    application_id UUID REFERENCES applications(id) ON DELETE SET NULL,
    supervisor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    position_title VARCHAR(250),
    work_location TEXT,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status placement_status NOT NULL DEFAULT 'OFFERED',
    placed_on TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    notes TEXT,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (end_date >= start_date)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_active_student_placement
    ON placements (cycle_id, student_id)
    WHERE status IN ('OFFERED','PENDING_CONFIRMATION','CONFIRMED','ACTIVE','SUSPENDED');

CREATE TABLE IF NOT EXISTS placement_supervisors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placement_id UUID NOT NULL REFERENCES placements(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    company_name VARCHAR(250),
    supervisor_name VARCHAR(250) NOT NULL,
    supervisor_email VARCHAR(320),
    supervisor_phone VARCHAR(40),
    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 9. ATTENDANCE
-- =========================================================

CREATE TABLE IF NOT EXISTS attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placement_id UUID NOT NULL REFERENCES placements(id) ON DELETE CASCADE,
    attendance_date DATE NOT NULL,
    check_in TIMESTAMPTZ,
    check_out TIMESTAMPTZ,
    status attendance_status NOT NULL DEFAULT 'PENDING_REVIEW',
    notes TEXT,
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (placement_id, attendance_date)
);

-- =========================================================
-- 10. LOGBOOK
-- =========================================================

CREATE TABLE IF NOT EXISTS logbook_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placement_id UUID NOT NULL REFERENCES placements(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    week_number INTEGER NOT NULL CHECK (week_number > 0),
    week_start DATE,
    week_end DATE,
    activities TEXT NOT NULL,
    learning_outcomes TEXT,
    challenges TEXT,
    next_steps TEXT,
    status logbook_status NOT NULL DEFAULT 'DRAFT',
    submitted_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    review_comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (placement_id, week_number)
);

-- =========================================================
-- 11. MONITORING
-- =========================================================

CREATE TABLE IF NOT EXISTS monitoring_visits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placement_id UUID NOT NULL REFERENCES placements(id) ON DELETE CASCADE,
    coordinator_id UUID REFERENCES users(id) ON DELETE SET NULL,
    visit_date DATE NOT NULL,
    visit_type VARCHAR(50) NOT NULL DEFAULT 'ROUTINE',
    location TEXT,
    observations TEXT,
    action_items TEXT,
    outcome VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 12. EVALUATIONS
-- =========================================================

CREATE TABLE IF NOT EXISTS evaluations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placement_id UUID NOT NULL REFERENCES placements(id) ON DELETE CASCADE,
    evaluator_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    evaluator_name VARCHAR(250),
    evaluation_type VARCHAR(80) NOT NULL,
    score NUMERIC(5,2) CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    rating NUMERIC(4,2) CHECK (rating IS NULL OR (rating >= 0 AND rating <= 5)),
    strengths TEXT,
    weaknesses TEXT,
    comments TEXT,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 13. REPORTS
-- =========================================================

CREATE TABLE IF NOT EXISTS reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placement_id UUID NOT NULL REFERENCES placements(id) ON DELETE CASCADE,
    student_id UUID REFERENCES students(id) ON DELETE SET NULL,
    type report_type NOT NULL,
    title VARCHAR(250) NOT NULL,
    status report_status NOT NULL DEFAULT 'DRAFT',
    due_date DATE,
    submitted_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    review_comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 14. DOCUMENTS
-- =========================================================

CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    student_id UUID REFERENCES students(id) ON DELETE SET NULL,
    placement_id UUID REFERENCES placements(id) ON DELETE SET NULL,
    report_id UUID REFERENCES reports(id) ON DELETE CASCADE,
    document_type document_type NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    storage_key TEXT NOT NULL UNIQUE,
    mime_type VARCHAR(150) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    file_hash VARCHAR(128),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 15. NOTIFICATIONS
-- =========================================================

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(100) NOT NULL,
    title VARCHAR(250) NOT NULL,
    message TEXT NOT NULL,
    priority VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    related_entity VARCHAR(100),
    related_id UUID,
    read_at TIMESTAMPTZ,
    delivery_status VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    email_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 16. AUDIT LOG
-- =========================================================

CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(150) NOT NULL,
    entity_type VARCHAR(100),
    entity_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 17. SYSTEM SETTINGS
-- =========================================================

CREATE TABLE IF NOT EXISTS system_settings (
    setting_key VARCHAR(150) PRIMARY KEY,
    setting_value JSONB NOT NULL,
    description TEXT,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================================
-- 18. UPDATED_AT TRIGGER
-- =========================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_updated_at ON users;
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_students_updated_at ON students;
CREATE TRIGGER trg_students_updated_at BEFORE UPDATE ON students
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_cycles_updated_at ON internship_cycles;
CREATE TRIGGER trg_cycles_updated_at BEFORE UPDATE ON internship_cycles
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_eligibility_updated_at ON student_eligibility;
CREATE TRIGGER trg_eligibility_updated_at BEFORE UPDATE ON student_eligibility
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_access_requests_updated_at ON access_requests;
CREATE TRIGGER trg_access_requests_updated_at BEFORE UPDATE ON access_requests
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_companies_updated_at ON companies;
CREATE TRIGGER trg_companies_updated_at BEFORE UPDATE ON companies
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_opportunities_updated_at ON opportunities;
CREATE TRIGGER trg_opportunities_updated_at BEFORE UPDATE ON opportunities
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_applications_updated_at ON applications;
CREATE TRIGGER trg_applications_updated_at BEFORE UPDATE ON applications
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_placements_updated_at ON placements;
CREATE TRIGGER trg_placements_updated_at BEFORE UPDATE ON placements
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_attendance_updated_at ON attendance;
CREATE TRIGGER trg_attendance_updated_at BEFORE UPDATE ON attendance
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_logbook_updated_at ON logbook_entries;
CREATE TRIGGER trg_logbook_updated_at BEFORE UPDATE ON logbook_entries
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_evaluations_updated_at ON evaluations;
CREATE TRIGGER trg_evaluations_updated_at BEFORE UPDATE ON evaluations
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_reports_updated_at ON reports;
CREATE TRIGGER trg_reports_updated_at BEFORE UPDATE ON reports
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================================================
-- 19. INDEXES
-- =========================================================

CREATE INDEX IF NOT EXISTS ix_students_programme ON students(programme_id);
CREATE INDEX IF NOT EXISTS ix_students_active ON students(active);
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email_lower ON users (LOWER(email));
CREATE UNIQUE INDEX IF NOT EXISTS ux_students_institutional_email_lower
    ON students (LOWER(institutional_email));

CREATE INDEX IF NOT EXISTS ix_eligibility_cycle_status ON student_eligibility(cycle_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS ux_eligibility_cycle_email_lower
    ON student_eligibility (cycle_id, LOWER(institutional_email));
CREATE INDEX IF NOT EXISTS ix_access_requests_cycle_status ON access_requests(cycle_id, status);

CREATE INDEX IF NOT EXISTS ix_companies_status ON companies(status);
CREATE INDEX IF NOT EXISTS ix_company_users_company ON company_users(company_id);
CREATE INDEX IF NOT EXISTS ix_opportunities_cycle_status ON opportunities(cycle_id, status);
CREATE INDEX IF NOT EXISTS ix_opportunities_company ON opportunities(company_id);

CREATE INDEX IF NOT EXISTS ix_applications_student ON applications(student_id);
CREATE INDEX IF NOT EXISTS ix_applications_company ON applications(company_id);
CREATE INDEX IF NOT EXISTS ix_applications_cycle_status ON applications(cycle_id, status);
CREATE INDEX IF NOT EXISTS ix_applications_opportunity ON applications(opportunity_id);
CREATE INDEX IF NOT EXISTS ix_application_history_application ON application_status_history(application_id);

CREATE INDEX IF NOT EXISTS ix_placements_student ON placements(student_id);
CREATE INDEX IF NOT EXISTS ix_placements_company ON placements(company_id);
CREATE INDEX IF NOT EXISTS ix_placements_cycle_status ON placements(cycle_id, status);
CREATE INDEX IF NOT EXISTS ix_placements_opportunity ON placements(opportunity_id);

CREATE INDEX IF NOT EXISTS ix_attendance_placement_date ON attendance(placement_id, attendance_date);
CREATE INDEX IF NOT EXISTS ix_logbook_placement_week ON logbook_entries(placement_id, week_number);
CREATE INDEX IF NOT EXISTS ix_monitoring_placement_date ON monitoring_visits(placement_id, visit_date);
CREATE INDEX IF NOT EXISTS ix_evaluations_placement ON evaluations(placement_id);
CREATE INDEX IF NOT EXISTS ix_reports_placement_status ON reports(placement_id, status);
CREATE INDEX IF NOT EXISTS ix_documents_student ON documents(student_id);
CREATE INDEX IF NOT EXISTS ix_documents_placement ON documents(placement_id);

CREATE INDEX IF NOT EXISTS ix_notifications_user_unread
    ON notifications(user_id, created_at DESC)
    WHERE read_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_audit_actor_date
    ON audit_logs(actor_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_audit_entity
    ON audit_logs(entity_type, entity_id, created_at DESC);

-- =========================================================
-- 20. SEED ROLES
-- =========================================================

INSERT INTO roles (code, name, description) VALUES
('SUPER_ADMIN','Super Administrator','Full system administration and configuration'),
('INTERNSHIP_COORDINATOR','Internship Coordinator','Manages internship lifecycle and student/company coordination'),
('PLACEMENT_OFFICER','Placement Officer','Manages applications and placement operations'),
('DEPARTMENT_COORDINATOR','Department Coordinator','Supports internship activities for an academic department'),
('AUDITOR','Auditor','Read-only access to records and audit information'),
('STUDENT','Student','Student internship portal access'),
('COMPANY_ADMIN','Company Administrator','Manages company account and internship opportunities'),
('COMPANY_SUPERVISOR','Company Supervisor','Supervises assigned interns and completes evaluations')
ON CONFLICT (code) DO NOTHING;

-- =========================================================
-- 21. SEED PERMISSIONS
-- =========================================================

INSERT INTO permissions (code, description) VALUES
('VIEW_STUDENTS','View student records'),
('MANAGE_STUDENTS','Create/update student records'),
('IMPORT_STUDENTS','Import eligible student lists'),
('VIEW_STUDENT_LIST','View eligibility/import records'),
('MANAGE_STUDENT_LIST','Manage eligibility records'),
('REVIEW_ACCESS_REQUESTS','Approve or reject student access requests'),
('VIEW_COMPANIES','View companies'),
('MANAGE_COMPANIES','Manage companies'),
('VERIFY_COMPANIES','Verify company accounts'),
('VIEW_OPPORTUNITIES','View internship opportunities'),
('MANAGE_OPPORTUNITIES','Create and manage opportunities'),
('VIEW_APPLICATIONS','View applications'),
('REVIEW_APPLICATIONS','Review and decide applications'),
('VIEW_PLACEMENTS','View placements'),
('CREATE_PLACEMENT','Create placements'),
('MANAGE_PLACEMENTS','Manage placement lifecycle'),
('VIEW_ATTENDANCE','View attendance'),
('REVIEW_ATTENDANCE','Review attendance'),
('VIEW_LOGBOOKS','View logbooks'),
('REVIEW_LOGBOOKS','Review logbooks'),
('VIEW_REPORTS','View reports'),
('REVIEW_REPORTS','Review reports'),
('VIEW_EVALUATIONS','View evaluations'),
('MANAGE_EVALUATIONS','Manage evaluations'),
('VIEW_INTERNSHIP_CYCLES','View internship cycles'),
('MANAGE_INTERNSHIP_CYCLES','Manage internship cycles'),
('SEND_NOTIFICATIONS','Send notifications'),
('VIEW_ANALYTICS','View analytics'),
('VIEW_AUDIT_LOG','View audit logs'),
('MANAGE_USERS','Manage users'),
('MANAGE_ROLES','Manage roles'),
('MANAGE_SETTINGS','Manage system settings')
ON CONFLICT (code) DO NOTHING;

-- Super Admin receives every permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- Seed the same role-permission assignments as RolePermissions.java.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('INTERNSHIP_COORDINATOR','VIEW_STUDENTS'),
    ('INTERNSHIP_COORDINATOR','MANAGE_STUDENTS'),
    ('INTERNSHIP_COORDINATOR','VIEW_STUDENT_LIST'),
    ('INTERNSHIP_COORDINATOR','MANAGE_STUDENT_LIST'),
    ('INTERNSHIP_COORDINATOR','REVIEW_ACCESS_REQUESTS'),
    ('INTERNSHIP_COORDINATOR','VIEW_COMPANIES'),
    ('INTERNSHIP_COORDINATOR','MANAGE_COMPANIES'),
    ('INTERNSHIP_COORDINATOR','VERIFY_COMPANIES'),
    ('INTERNSHIP_COORDINATOR','VIEW_OPPORTUNITIES'),
    ('INTERNSHIP_COORDINATOR','MANAGE_OPPORTUNITIES'),
    ('INTERNSHIP_COORDINATOR','VIEW_APPLICATIONS'),
    ('INTERNSHIP_COORDINATOR','REVIEW_APPLICATIONS'),
    ('INTERNSHIP_COORDINATOR','VIEW_PLACEMENTS'),
    ('INTERNSHIP_COORDINATOR','CREATE_PLACEMENT'),
    ('INTERNSHIP_COORDINATOR','MANAGE_PLACEMENTS'),
    ('INTERNSHIP_COORDINATOR','VIEW_ATTENDANCE'),
    ('INTERNSHIP_COORDINATOR','REVIEW_ATTENDANCE'),
    ('INTERNSHIP_COORDINATOR','VIEW_LOGBOOKS'),
    ('INTERNSHIP_COORDINATOR','REVIEW_LOGBOOKS'),
    ('INTERNSHIP_COORDINATOR','VIEW_REPORTS'),
    ('INTERNSHIP_COORDINATOR','REVIEW_REPORTS'),
    ('INTERNSHIP_COORDINATOR','VIEW_EVALUATIONS'),
    ('INTERNSHIP_COORDINATOR','MANAGE_EVALUATIONS'),
    ('INTERNSHIP_COORDINATOR','VIEW_INTERNSHIP_CYCLES'),
    ('INTERNSHIP_COORDINATOR','MANAGE_INTERNSHIP_CYCLES'),
    ('INTERNSHIP_COORDINATOR','SEND_NOTIFICATIONS'),
    ('INTERNSHIP_COORDINATOR','VIEW_ANALYTICS'),
    ('PLACEMENT_OFFICER','VIEW_STUDENTS'),
    ('PLACEMENT_OFFICER','VIEW_STUDENT_LIST'),
    ('PLACEMENT_OFFICER','VIEW_COMPANIES'),
    ('PLACEMENT_OFFICER','MANAGE_COMPANIES'),
    ('PLACEMENT_OFFICER','VIEW_OPPORTUNITIES'),
    ('PLACEMENT_OFFICER','MANAGE_OPPORTUNITIES'),
    ('PLACEMENT_OFFICER','VIEW_APPLICATIONS'),
    ('PLACEMENT_OFFICER','REVIEW_APPLICATIONS'),
    ('PLACEMENT_OFFICER','VIEW_PLACEMENTS'),
    ('PLACEMENT_OFFICER','CREATE_PLACEMENT'),
    ('PLACEMENT_OFFICER','MANAGE_PLACEMENTS'),
    ('PLACEMENT_OFFICER','SEND_NOTIFICATIONS'),
    ('DEPARTMENT_COORDINATOR','VIEW_STUDENTS'),
    ('DEPARTMENT_COORDINATOR','VIEW_APPLICATIONS'),
    ('DEPARTMENT_COORDINATOR','VIEW_PLACEMENTS'),
    ('DEPARTMENT_COORDINATOR','VIEW_ATTENDANCE'),
    ('DEPARTMENT_COORDINATOR','VIEW_LOGBOOKS'),
    ('DEPARTMENT_COORDINATOR','REVIEW_LOGBOOKS'),
    ('DEPARTMENT_COORDINATOR','VIEW_REPORTS'),
    ('DEPARTMENT_COORDINATOR','REVIEW_REPORTS'),
    ('DEPARTMENT_COORDINATOR','VIEW_EVALUATIONS'),
    ('DEPARTMENT_COORDINATOR','VIEW_ANALYTICS'),
    ('AUDITOR','VIEW_STUDENTS'),
    ('AUDITOR','VIEW_COMPANIES'),
    ('AUDITOR','VIEW_APPLICATIONS'),
    ('AUDITOR','VIEW_PLACEMENTS'),
    ('AUDITOR','VIEW_ATTENDANCE'),
    ('AUDITOR','VIEW_LOGBOOKS'),
    ('AUDITOR','VIEW_REPORTS'),
    ('AUDITOR','VIEW_EVALUATIONS'),
    ('AUDITOR','VIEW_ANALYTICS'),
    ('AUDITOR','VIEW_AUDIT_LOG'),
    ('STUDENT','VIEW_OPPORTUNITIES'),
    ('STUDENT','VIEW_APPLICATIONS'),
    ('STUDENT','VIEW_PLACEMENTS'),
    ('STUDENT','VIEW_ATTENDANCE'),
    ('STUDENT','VIEW_LOGBOOKS'),
    ('STUDENT','VIEW_REPORTS'),
    ('STUDENT','VIEW_EVALUATIONS'),
    ('COMPANY_ADMIN','VIEW_COMPANIES'),
    ('COMPANY_ADMIN','MANAGE_COMPANIES'),
    ('COMPANY_ADMIN','VIEW_OPPORTUNITIES'),
    ('COMPANY_ADMIN','MANAGE_OPPORTUNITIES'),
    ('COMPANY_ADMIN','VIEW_APPLICATIONS'),
    ('COMPANY_ADMIN','REVIEW_APPLICATIONS'),
    ('COMPANY_ADMIN','VIEW_PLACEMENTS'),
    ('COMPANY_ADMIN','MANAGE_PLACEMENTS'),
    ('COMPANY_ADMIN','VIEW_ATTENDANCE'),
    ('COMPANY_ADMIN','REVIEW_ATTENDANCE'),
    ('COMPANY_ADMIN','VIEW_LOGBOOKS'),
    ('COMPANY_ADMIN','REVIEW_LOGBOOKS'),
    ('COMPANY_ADMIN','VIEW_EVALUATIONS'),
    ('COMPANY_ADMIN','MANAGE_EVALUATIONS'),
    ('COMPANY_SUPERVISOR','VIEW_PLACEMENTS'),
    ('COMPANY_SUPERVISOR','VIEW_ATTENDANCE'),
    ('COMPANY_SUPERVISOR','REVIEW_ATTENDANCE'),
    ('COMPANY_SUPERVISOR','VIEW_LOGBOOKS'),
    ('COMPANY_SUPERVISOR','REVIEW_LOGBOOKS'),
    ('COMPANY_SUPERVISOR','VIEW_EVALUATIONS'),
    ('COMPANY_SUPERVISOR','MANAGE_EVALUATIONS')
) AS assignments(role_code, permission_code)
JOIN roles r ON r.code = assignments.role_code
JOIN permissions p ON p.code = assignments.permission_code
ON CONFLICT DO NOTHING;

-- =========================================================
-- 22. SAFE DEFAULT SETTINGS
-- =========================================================

INSERT INTO system_settings (setting_key, setting_value, description) VALUES
('registration.require_eligibility_list', 'true', 'Students may register only when an eligibility record exists'),
('registration.require_email_verification', 'true', 'Require institutional email verification before activation'),
('registration.allow_domain_fallback', 'false', 'Never use email domain as a substitute for eligibility'),
('applications.max_active_per_student', '3', 'Default maximum number of active applications'),
('company.require_verification', 'true', 'Companies require institutional verification before active use'),
('documents.max_upload_size_mb', '10', 'Maximum document upload size'),
('notifications.email_enabled', 'true', 'Enable email notification delivery')
ON CONFLICT (setting_key) DO NOTHING;

COMMIT;

-- =========================================================
-- POST-INSTALL VERIFICATION QUERIES
-- =========================================================
-- Run these manually after installation:
--
-- SELECT table_name FROM information_schema.tables
-- WHERE table_schema='palm_portal_v2' ORDER BY table_name;
--
-- SELECT COUNT(*) FROM palm_portal_v2.roles;
-- SELECT COUNT(*) FROM palm_portal_v2.permissions;
-- SELECT COUNT(*) FROM palm_portal_v2.role_permissions;
-- SELECT * FROM palm_portal_v2.system_settings ORDER BY setting_key;
--
-- Expected critical setting:
-- SELECT setting_value FROM palm_portal_v2.system_settings
-- WHERE setting_key = 'registration.allow_domain_fallback';
-- Expected value: false
