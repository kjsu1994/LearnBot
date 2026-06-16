CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS documents_title_trgm_idx
    ON documents USING GIN (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS documents_source_uri_trgm_idx
    ON documents USING GIN (source_uri gin_trgm_ops);

CREATE INDEX IF NOT EXISTS document_chunks_content_trgm_idx
    ON document_chunks USING GIN (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS code_chunks_file_path_trgm_idx
    ON code_chunks USING GIN (file_path gin_trgm_ops);

CREATE INDEX IF NOT EXISTS code_chunks_symbol_name_trgm_idx
    ON code_chunks USING GIN (symbol_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS code_chunks_method_name_trgm_idx
    ON code_chunks USING GIN (method_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS code_chunks_content_trgm_idx
    ON code_chunks USING GIN (content gin_trgm_ops);
