ALTER TABLE code_repositories
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) NOT NULL DEFAULT 'GIT',
    ADD COLUMN IF NOT EXISTS source_label TEXT,
    ADD COLUMN IF NOT EXISTS source_hash VARCHAR(128);

UPDATE code_repositories
SET source_type = COALESCE(source_type, 'GIT'),
    source_label = COALESCE(source_label, git_url)
WHERE source_label IS NULL;

ALTER TABLE code_repositories
    ALTER COLUMN git_url DROP NOT NULL,
    ALTER COLUMN branch DROP NOT NULL;

CREATE INDEX IF NOT EXISTS code_repositories_source_type_idx ON code_repositories(source_type);
CREATE INDEX IF NOT EXISTS code_repositories_source_hash_idx ON code_repositories(source_hash);
