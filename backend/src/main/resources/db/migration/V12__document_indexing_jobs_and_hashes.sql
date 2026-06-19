ALTER TABLE documents
    ADD COLUMN content_hash VARCHAR(128);

CREATE INDEX documents_source_uri_hash_idx
    ON documents(source_id, source_uri, content_hash);

CREATE TABLE document_indexing_jobs (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_documents INTEGER NOT NULL DEFAULT 0,
    processed_documents INTEGER NOT NULL DEFAULT 0,
    total_chunks INTEGER NOT NULL DEFAULT 0,
    reused_chunks INTEGER NOT NULL DEFAULT 0,
    embedded_chunks INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX document_indexing_jobs_source_created_idx
    ON document_indexing_jobs(source_id, created_at DESC);

CREATE INDEX document_indexing_jobs_space_created_idx
    ON document_indexing_jobs(space_id, created_at DESC);

CREATE INDEX document_indexing_jobs_status_idx
    ON document_indexing_jobs(status);
