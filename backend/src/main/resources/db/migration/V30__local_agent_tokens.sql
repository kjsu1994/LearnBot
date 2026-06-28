CREATE TABLE local_agent_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL,
    label TEXT,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX local_agent_tokens_user_idx
    ON local_agent_tokens(user_id, created_at DESC);

CREATE INDEX local_agent_tokens_agent_idx
    ON local_agent_tokens(user_id, agent_id);

CREATE INDEX local_agent_tokens_active_idx
    ON local_agent_tokens(token_hash, expires_at)
    WHERE revoked_at IS NULL;
