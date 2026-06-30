CREATE TABLE code_agent_loop_timelines (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL,
    space_id UUID,
    instruction TEXT NOT NULL,
    status VARCHAR(64) NOT NULL,
    max_steps INTEGER NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    cancellation_enabled BOOLEAN NOT NULL DEFAULT false,
    timeline_persistence_enabled BOOLEAN NOT NULL DEFAULT true,
    mutation_enabled BOOLEAN NOT NULL DEFAULT false,
    steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    stop_conditions JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX code_agent_loop_timelines_user_repo_idx
    ON code_agent_loop_timelines(user_id, repository_id, created_at DESC);
