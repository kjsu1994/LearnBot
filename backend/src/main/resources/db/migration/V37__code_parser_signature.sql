ALTER TABLE code_files
    ADD COLUMN parser_signature TEXT NOT NULL DEFAULT 'legacy',
    ADD COLUMN chunk_profile TEXT NOT NULL DEFAULT 'legacy';

CREATE INDEX code_files_repository_active_parser_idx
    ON code_files(repository_id, active, parser_signature, chunk_profile);
