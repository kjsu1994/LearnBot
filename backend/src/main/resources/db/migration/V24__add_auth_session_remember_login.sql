ALTER TABLE auth_sessions
    ADD COLUMN remember_login BOOLEAN;

UPDATE auth_sessions
    SET remember_login = FALSE
    WHERE remember_login IS NULL;

ALTER TABLE auth_sessions
    ALTER COLUMN remember_login SET DEFAULT FALSE,
    ALTER COLUMN remember_login SET NOT NULL;
