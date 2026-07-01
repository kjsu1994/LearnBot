CREATE TABLE local_agent_mutation_observation_intake (
    request_id UUID PRIMARY KEY REFERENCES local_agent_tool_executions(id) ON DELETE CASCADE,
    source_request_id UUID NOT NULL REFERENCES local_agent_tool_executions(id) ON DELETE CASCADE,
    release_attempt_id UUID NOT NULL REFERENCES local_agent_patch_release_attempts(id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL,
    workspace_id UUID,
    tool_name TEXT NOT NULL,
    status VARCHAR(64) NOT NULL,
    accepted BOOLEAN NOT NULL DEFAULT false,
    verification_status VARCHAR(64),
    observation JSONB NOT NULL DEFAULT '{}'::jsonb,
    candidate JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX local_agent_mutation_observation_intake_attempt_idx
    ON local_agent_mutation_observation_intake(user_id, source_request_id, release_attempt_id, created_at DESC);

CREATE INDEX local_agent_mutation_observation_intake_status_idx
    ON local_agent_mutation_observation_intake(user_id, status, created_at DESC);
