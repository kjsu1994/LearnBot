ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS app_users_deleted_at_idx
    ON app_users(deleted_at);
