ALTER TABLE code_repositories
    ADD COLUMN IF NOT EXISTS content_fingerprint VARCHAR(80),
    ADD COLUMN IF NOT EXISTS worktree_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS analyzer_version VARCHAR(80),
    ADD COLUMN IF NOT EXISTS index_schema_version VARCHAR(32);

ALTER TABLE indexing_jobs
    ADD COLUMN IF NOT EXISTS content_fingerprint VARCHAR(80),
    ADD COLUMN IF NOT EXISTS worktree_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS analyzer_version VARCHAR(80),
    ADD COLUMN IF NOT EXISTS index_schema_version VARCHAR(32);
