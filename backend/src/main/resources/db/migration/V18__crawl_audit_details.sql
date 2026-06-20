ALTER TABLE crawl_audit_logs
    ADD COLUMN IF NOT EXISTS reason_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS depth INTEGER,
    ADD COLUMN IF NOT EXISTS referrer_url TEXT,
    ADD COLUMN IF NOT EXISTS normalized_url TEXT,
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(256),
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS crawl_audit_logs_reason_code_idx ON crawl_audit_logs(reason_code);
