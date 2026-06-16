CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE data_sources (
    id UUID PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    name TEXT NOT NULL,
    location TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding VECTOR(1024) NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX data_sources_status_idx ON data_sources(status);
CREATE INDEX data_sources_type_idx ON data_sources(type);
CREATE INDEX documents_source_id_idx ON documents(source_id);
CREATE INDEX document_chunks_document_id_idx ON document_chunks(document_id);
CREATE INDEX document_chunks_search_vector_idx ON document_chunks USING GIN(search_vector);
CREATE INDEX document_chunks_embedding_idx ON document_chunks USING HNSW (embedding vector_cosine_ops);
