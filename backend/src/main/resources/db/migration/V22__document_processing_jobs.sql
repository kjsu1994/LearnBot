ALTER TABLE data_sources
    ADD CONSTRAINT data_sources_status_check
    CHECK (status IN ('INDEXING', 'SEARCHABLE', 'READY', 'PARTIAL', 'INDEXED', 'FAILED')) NOT VALID;

UPDATE data_sources
SET status = 'READY'
WHERE status = 'INDEXED';

ALTER TABLE document_enrichment_jobs
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ;

CREATE TABLE document_graph_jobs (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES document_indexing_jobs(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(source_id, job_id)
);

CREATE INDEX document_graph_jobs_poll_idx
    ON document_graph_jobs(status, next_attempt_at, created_at);

CREATE INDEX document_graph_jobs_lease_idx
    ON document_graph_jobs(status, lease_until);

CREATE TABLE document_processing_diagnostics (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    job_id UUID REFERENCES document_indexing_jobs(id) ON DELETE SET NULL,
    stage VARCHAR(64) NOT NULL,
    analyzer VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    attempted_items INTEGER NOT NULL DEFAULT 0,
    processed_items INTEGER NOT NULL DEFAULT 0,
    failed_items INTEGER NOT NULL DEFAULT 0,
    node_count INTEGER NOT NULL DEFAULT 0,
    edge_count INTEGER NOT NULL DEFAULT 0,
    duration_millis BIGINT NOT NULL DEFAULT 0,
    message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX document_processing_diagnostics_job_idx
    ON document_processing_diagnostics(job_id, created_at);

CREATE INDEX document_processing_diagnostics_source_idx
    ON document_processing_diagnostics(source_id, created_at);
