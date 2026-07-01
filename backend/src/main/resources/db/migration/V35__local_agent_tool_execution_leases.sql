ALTER TABLE local_agent_tool_executions
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS local_agent_tool_executions_running_lease_idx
    ON local_agent_tool_executions(lease_expires_at)
    WHERE status = 'RUNNING';
