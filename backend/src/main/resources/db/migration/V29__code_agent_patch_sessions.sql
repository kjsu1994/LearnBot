CREATE TABLE code_agent_patch_sessions (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES spaces(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    instruction TEXT NOT NULL,
    diff TEXT NOT NULL,
    target_files JSONB NOT NULL DEFAULT '[]'::jsonb,
    before_snapshots JSONB NOT NULL DEFAULT '[]'::jsonb,
    after_hashes JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    test_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ NULL,
    rolled_back_at TIMESTAMPTZ NULL
);

CREATE INDEX code_agent_patch_sessions_repository_created_idx
    ON code_agent_patch_sessions(repository_id, created_at DESC);

CREATE INDEX code_agent_patch_sessions_user_created_idx
    ON code_agent_patch_sessions(user_id, created_at DESC);
