CREATE TABLE local_agent_patch_release_attempts (
    id UUID PRIMARY KEY,
    source_request_id UUID NOT NULL REFERENCES local_agent_tool_executions(id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL,
    workspace_id UUID,
    status VARCHAR(64) NOT NULL,
    claimable BOOLEAN NOT NULL DEFAULT false,
    stale_window_seconds INTEGER NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ
);

CREATE INDEX local_agent_patch_release_attempts_source_idx
    ON local_agent_patch_release_attempts(user_id, source_request_id, created_at DESC);

CREATE INDEX local_agent_patch_release_attempts_status_idx
    ON local_agent_patch_release_attempts(user_id, status, claimable, created_at DESC);
