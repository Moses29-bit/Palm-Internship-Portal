-- Add durable, hashed email verification challenges for the current public schema.
-- Existing accounts remain unchanged; only new registrations require verification.
CREATE TABLE IF NOT EXISTS email_verification_tokens (
    email VARCHAR(254) PRIMARY KEY,
    code_hash VARCHAR(128) NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_sent_at_ms BIGINT NOT NULL
);
