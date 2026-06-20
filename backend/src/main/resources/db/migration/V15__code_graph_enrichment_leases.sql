ALTER TABLE code_graph_enrichment_jobs
    ADD COLUMN lease_owner TEXT,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN heartbeat_at TIMESTAMPTZ;

CREATE INDEX code_graph_enrichment_jobs_lease_idx
    ON code_graph_enrichment_jobs(status, lease_until);
