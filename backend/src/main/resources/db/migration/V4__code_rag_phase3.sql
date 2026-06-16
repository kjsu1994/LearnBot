CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE code_repositories
    ADD COLUMN credential_username TEXT,
    ADD COLUMN credential_token_iv TEXT,
    ADD COLUMN credential_token_ciphertext TEXT,
    ADD COLUMN credential_updated_at TIMESTAMPTZ;

CREATE INDEX code_files_repository_path_active_idx ON code_files(repository_id, file_path, active);
CREATE INDEX code_chunks_reference_idx ON code_chunks(repository_id, active, symbol_name, method_name, class_name);
