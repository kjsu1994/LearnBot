CREATE TABLE local_agent_tool_executions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL,
    workspace_id UUID,
    execution_target VARCHAR(64) NOT NULL,
    tool_name TEXT NOT NULL,
    approval_state VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_code VARCHAR(64),
    error TEXT,
    request_warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    response_warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX local_agent_tool_executions_agent_status_idx
    ON local_agent_tool_executions(user_id, agent_id, status, created_at);

CREATE INDEX local_agent_tool_executions_session_idx
    ON local_agent_tool_executions(session_id, created_at);

CREATE INDEX local_agent_tool_executions_workspace_idx
    ON local_agent_tool_executions(user_id, workspace_id, created_at DESC);
