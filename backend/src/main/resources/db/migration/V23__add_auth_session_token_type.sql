ALTER TABLE auth_sessions
    ADD COLUMN token_type TEXT;

UPDATE auth_sessions
SET token_type = 'ACCESS'
WHERE token_type IS NULL;

ALTER TABLE auth_sessions
    ALTER COLUMN token_type SET DEFAULT 'ACCESS',
    ALTER COLUMN token_type SET NOT NULL;

CREATE INDEX auth_sessions_token_type_idx ON auth_sessions (token_type);
