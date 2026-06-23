DROP INDEX IF EXISTS app_users_active_email_uq;

ALTER TABLE app_users
    DROP CONSTRAINT IF EXISTS app_users_email_key;

CREATE UNIQUE INDEX app_users_active_email_uq
    ON app_users (lower(email))
    WHERE status <> 'DELETED'
      AND deleted_at IS NULL;
