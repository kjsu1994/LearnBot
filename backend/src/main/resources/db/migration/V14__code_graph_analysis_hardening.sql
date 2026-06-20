CREATE TABLE code_analysis_diagnostics (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    index_version UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    stage VARCHAR(48) NOT NULL,
    analyzer VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    mode VARCHAR(24),
    attempted_files INTEGER NOT NULL DEFAULT 0,
    analyzed_files INTEGER NOT NULL DEFAULT 0,
    failed_files INTEGER NOT NULL DEFAULT 0,
    resolved_relations INTEGER NOT NULL DEFAULT 0,
    unresolved_relations INTEGER NOT NULL DEFAULT 0,
    node_count INTEGER NOT NULL DEFAULT 0,
    edge_count INTEGER NOT NULL DEFAULT 0,
    duration_millis BIGINT NOT NULL DEFAULT 0,
    message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX code_analysis_diagnostics_job_idx
    ON code_analysis_diagnostics(repository_id, index_version, created_at);

CREATE TABLE code_graph_enrichment_jobs (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    index_version UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(repository_id, index_version)
);

CREATE INDEX code_graph_enrichment_jobs_poll_idx
    ON code_graph_enrichment_jobs(status, next_attempt_at, created_at);

WITH duplicates AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY repository_id, index_version, source_node_id, target_node_id, edge_type
               ORDER BY confidence DESC, created_at ASC, id
           ) AS duplicate_rank
    FROM code_graph_edges
)
DELETE FROM code_graph_edges edge
USING duplicates duplicate
WHERE edge.id = duplicate.id
  AND duplicate.duplicate_rank > 1;

CREATE UNIQUE INDEX code_graph_edges_version_relation_idx
    ON code_graph_edges(repository_id, index_version, source_node_id, target_node_id, edge_type);
