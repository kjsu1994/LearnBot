CREATE TABLE code_agent_loop_timeline_events (
    id UUID PRIMARY KEY,
    timeline_id UUID NOT NULL REFERENCES code_agent_loop_timelines(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    phase VARCHAR(64),
    execution_target VARCHAR(64),
    tool_name TEXT,
    requires_approval BOOLEAN NOT NULL DEFAULT false,
    may_mutate BOOLEAN NOT NULL DEFAULT false,
    enabled BOOLEAN NOT NULL DEFAULT false,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX code_agent_loop_timeline_events_sequence_idx
    ON code_agent_loop_timeline_events(timeline_id, sequence_number);

CREATE INDEX code_agent_loop_timeline_events_user_idx
    ON code_agent_loop_timeline_events(user_id, created_at DESC);
