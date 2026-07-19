CREATE TABLE local_agent_devices (
    agent_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    installation_id UUID,
    label VARCHAR(120),
    client_name VARCHAR(120),
    machine_name VARCHAR(120),
    os_name VARCHAR(80),
    os_version VARCHAR(80),
    architecture VARCHAR(32),
    agent_version VARCHAR(64),
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    workspaces JSONB NOT NULL DEFAULT '[]'::jsonb,
    configured_transport VARCHAR(32),
    active_transport VARCHAR(32),
    websocket_failure_count INTEGER NOT NULL DEFAULT 0,
    next_websocket_retry_at TIMESTAMPTZ,
    selected_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT local_agent_devices_websocket_failure_count_check
        CHECK (websocket_failure_count >= 0)
);

INSERT INTO local_agent_devices (
    agent_id,
    user_id,
    label,
    approved_at,
    last_seen_at,
    revoked_at,
    created_at,
    updated_at
)
SELECT DISTINCT ON (agent_id)
    agent_id,
    user_id,
    label,
    created_at,
    last_seen_at,
    revoked_at,
    created_at,
    COALESCE(last_seen_at, revoked_at, created_at)
FROM local_agent_tokens
ORDER BY agent_id, created_at ASC;

WITH first_active_device AS (
    SELECT DISTINCT ON (user_id) agent_id, user_id
    FROM local_agent_devices
    WHERE revoked_at IS NULL
    ORDER BY user_id, created_at ASC, agent_id ASC
)
UPDATE local_agent_devices device
SET selected_at = device.created_at
FROM first_active_device first_device
WHERE device.agent_id = first_device.agent_id;

ALTER TABLE local_agent_tokens
    ADD CONSTRAINT local_agent_tokens_device_fk
        FOREIGN KEY (agent_id) REFERENCES local_agent_devices(agent_id) ON DELETE CASCADE;

CREATE INDEX local_agent_devices_user_idx
    ON local_agent_devices(user_id, last_seen_at DESC NULLS LAST, created_at DESC);

CREATE INDEX local_agent_devices_active_user_idx
    ON local_agent_devices(user_id, agent_id)
    WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX local_agent_devices_selected_user_idx
    ON local_agent_devices(user_id)
    WHERE selected_at IS NOT NULL AND revoked_at IS NULL;

CREATE UNIQUE INDEX local_agent_devices_active_installation_idx
    ON local_agent_devices(user_id, installation_id)
    WHERE installation_id IS NOT NULL AND revoked_at IS NULL;

CREATE TABLE local_agent_enrollments (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL UNIQUE,
    user_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
    device_code_hash TEXT NOT NULL UNIQUE,
    user_code_hash TEXT NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL,
    label VARCHAR(120),
    client_name VARCHAR(120),
    machine_name VARCHAR(120),
    os_name VARCHAR(80),
    os_version VARCHAR(80),
    architecture VARCHAR(32),
    agent_version VARCHAR(64),
    installation_id UUID,
    poll_interval_seconds INTEGER NOT NULL DEFAULT 5,
    poll_violation_count INTEGER NOT NULL DEFAULT 0,
    last_polled_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ,
    denied_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    candidate_token_id UUID UNIQUE,
    candidate_token_hash TEXT UNIQUE,
    candidate_expires_at TIMESTAMPTZ,
    credential_confirm_by TIMESTAMPTZ,
    credential_issued_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT local_agent_enrollments_state_check
        CHECK (state IN ('PENDING', 'APPROVED', 'DENIED', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT local_agent_enrollments_poll_interval_check
        CHECK (poll_interval_seconds >= 5),
    CONSTRAINT local_agent_enrollments_poll_violation_check
        CHECK (poll_violation_count >= 0)
);

CREATE INDEX local_agent_enrollments_expiry_idx
    ON local_agent_enrollments(expires_at)
    WHERE state IN ('PENDING', 'APPROVED');

CREATE INDEX local_agent_enrollments_user_idx
    ON local_agent_enrollments(user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

CREATE TABLE local_agent_credential_rotations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES local_agent_devices(agent_id) ON DELETE CASCADE,
    current_token_id UUID NOT NULL REFERENCES local_agent_tokens(id) ON DELETE CASCADE,
    candidate_token_id UUID NOT NULL UNIQUE,
    candidate_token_hash TEXT NOT NULL UNIQUE,
    candidate_expires_at TIMESTAMPTZ NOT NULL,
    confirm_by TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX local_agent_credential_rotations_pending_agent_idx
    ON local_agent_credential_rotations(user_id, agent_id)
    WHERE confirmed_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX local_agent_credential_rotations_confirm_by_idx
    ON local_agent_credential_rotations(confirm_by)
    WHERE confirmed_at IS NULL AND cancelled_at IS NULL;

CREATE TABLE local_agent_rate_limits (
    scope VARCHAR(32) NOT NULL,
    key_hash TEXT NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL,
    PRIMARY KEY (scope, key_hash),
    CONSTRAINT local_agent_rate_limits_attempt_count_check CHECK (attempt_count > 0)
);

CREATE INDEX local_agent_rate_limits_window_idx
    ON local_agent_rate_limits(window_started_at);
