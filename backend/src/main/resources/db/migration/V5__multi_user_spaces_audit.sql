CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE spaces (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    created_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE space_members (
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, user_id)
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT,
    space_id UUID REFERENCES spaces(id) ON DELETE SET NULL,
    message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE indexing_job_failures (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    file_path TEXT,
    stage TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO spaces (id, name, description)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default', 'Initial workspace for existing LearnBot data.')
ON CONFLICT (id) DO NOTHING;

ALTER TABLE data_sources
    ADD COLUMN space_id UUID REFERENCES spaces(id),
    ADD COLUMN created_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID REFERENCES app_users(id) ON DELETE SET NULL;

UPDATE data_sources
SET space_id = '00000000-0000-0000-0000-000000000001'
WHERE space_id IS NULL;

ALTER TABLE data_sources
    ALTER COLUMN space_id SET NOT NULL;

ALTER TABLE code_repositories
    ADD COLUMN space_id UUID REFERENCES spaces(id),
    ADD COLUMN created_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID REFERENCES app_users(id) ON DELETE SET NULL;

UPDATE code_repositories
SET space_id = '00000000-0000-0000-0000-000000000001'
WHERE space_id IS NULL;

ALTER TABLE code_repositories
    ALTER COLUMN space_id SET NOT NULL;

CREATE INDEX auth_sessions_token_hash_idx ON auth_sessions(token_hash);
CREATE INDEX auth_sessions_user_expires_idx ON auth_sessions(user_id, expires_at);
CREATE INDEX app_users_status_idx ON app_users(status);
CREATE INDEX space_members_user_idx ON space_members(user_id);
CREATE INDEX audit_logs_created_idx ON audit_logs(created_at DESC);
CREATE INDEX audit_logs_actor_idx ON audit_logs(actor_user_id);
CREATE INDEX audit_logs_space_idx ON audit_logs(space_id);
CREATE INDEX data_sources_space_deleted_idx ON data_sources(space_id, deleted_at);
CREATE INDEX code_repositories_space_deleted_idx ON code_repositories(space_id, deleted_at);
CREATE INDEX indexing_job_failures_job_idx ON indexing_job_failures(job_id, created_at);
