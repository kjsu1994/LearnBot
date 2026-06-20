CREATE TABLE document_graph_nodes (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents(id) ON DELETE CASCADE,
    chunk_id UUID REFERENCES document_chunks(id) ON DELETE CASCADE,
    node_key TEXT NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    label TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_graph_edges (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    source_node_id UUID NOT NULL REFERENCES document_graph_nodes(id) ON DELETE CASCADE,
    target_node_id UUID NOT NULL REFERENCES document_graph_nodes(id) ON DELETE CASCADE,
    edge_type VARCHAR(32) NOT NULL,
    weight DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX document_graph_nodes_source_key_idx
    ON document_graph_nodes(source_id, node_key);

CREATE UNIQUE INDEX document_graph_edges_source_relation_idx
    ON document_graph_edges(source_id, source_node_id, target_node_id, edge_type);

CREATE INDEX document_graph_nodes_chunk_idx
    ON document_graph_nodes(chunk_id);

CREATE INDEX document_graph_edges_source_idx
    ON document_graph_edges(source_id, source_node_id, edge_type);

CREATE INDEX document_graph_edges_target_idx
    ON document_graph_edges(source_id, target_node_id, edge_type);
