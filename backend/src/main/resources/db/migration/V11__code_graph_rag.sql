CREATE TABLE code_graph_nodes (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    index_version UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    node_key TEXT NOT NULL,
    node_type VARCHAR(48) NOT NULL,
    name TEXT NOT NULL,
    qualified_name TEXT,
    file_path TEXT,
    chunk_id UUID REFERENCES code_chunks(id) ON DELETE CASCADE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE code_graph_edges (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    index_version UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    source_node_id UUID NOT NULL REFERENCES code_graph_nodes(id) ON DELETE CASCADE,
    target_node_id UUID NOT NULL REFERENCES code_graph_nodes(id) ON DELETE CASCADE,
    edge_type VARCHAR(48) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    evidence_chunk_id UUID REFERENCES code_chunks(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX code_graph_nodes_version_key_idx
    ON code_graph_nodes(repository_id, index_version, node_key);

CREATE INDEX code_graph_nodes_active_idx
    ON code_graph_nodes(repository_id, active, node_type);

CREATE INDEX code_graph_nodes_chunk_idx
    ON code_graph_nodes(chunk_id);

CREATE INDEX code_graph_edges_active_source_idx
    ON code_graph_edges(repository_id, active, source_node_id, edge_type);

CREATE INDEX code_graph_edges_active_target_idx
    ON code_graph_edges(repository_id, active, target_node_id, edge_type);

CREATE INDEX code_graph_edges_evidence_idx
    ON code_graph_edges(evidence_chunk_id);
