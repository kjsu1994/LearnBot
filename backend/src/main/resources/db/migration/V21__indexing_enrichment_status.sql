ALTER TABLE document_indexing_jobs
    ADD COLUMN searchable_at TIMESTAMPTZ,
    ADD COLUMN enrichment_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN enrichment_message TEXT;

ALTER TABLE indexing_jobs
    ADD COLUMN searchable_at TIMESTAMPTZ,
    ADD COLUMN enrichment_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN enrichment_message TEXT;

CREATE TABLE document_enrichment_jobs (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES document_indexing_jobs(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(source_id, job_id)
);

CREATE INDEX document_enrichment_jobs_poll_idx
    ON document_enrichment_jobs(status, next_attempt_at, created_at);

CREATE INDEX document_enrichment_jobs_lease_idx
    ON document_enrichment_jobs(status, lease_until);
