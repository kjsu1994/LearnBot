CREATE TABLE code_repositories (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    git_url TEXT NOT NULL,
    branch TEXT NOT NULL,
    auth_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    local_path TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_indexed_commit VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE indexing_jobs (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_files INTEGER NOT NULL DEFAULT 0,
    processed_files INTEGER NOT NULL DEFAULT 0,
    total_chunks INTEGER NOT NULL DEFAULT 0,
    failed_files INTEGER NOT NULL DEFAULT 0,
    commit_hash VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE code_files (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    index_version UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    language VARCHAR(32) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE code_chunks (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES code_repositories(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES code_files(id) ON DELETE CASCADE,
    index_version UUID NOT NULL REFERENCES indexing_jobs(id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_type VARCHAR(48) NOT NULL,
    symbol_name TEXT,
    class_name TEXT,
    method_name TEXT,
    namespace_name TEXT,
    control_name TEXT,
    event_name TEXT,
    line_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding VECTOR(1024) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple',
            coalesce(file_id::text, '') || ' ' ||
            coalesce(file_path, '') || ' ' ||
            coalesce(chunk_type, '') || ' ' ||
            coalesce(symbol_name, '') || ' ' ||
            coalesce(class_name, '') || ' ' ||
            coalesce(method_name, '') || ' ' ||
            coalesce(namespace_name, '') || ' ' ||
            coalesce(control_name, '') || ' ' ||
            coalesce(event_name, '') || ' ' ||
            coalesce(content, '')
        )
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX code_repositories_status_idx ON code_repositories(status);
CREATE INDEX code_repositories_url_branch_idx ON code_repositories(git_url, branch);
CREATE INDEX indexing_jobs_repository_created_idx ON indexing_jobs(repository_id, created_at DESC);
CREATE INDEX code_files_repository_active_idx ON code_files(repository_id, active);
CREATE INDEX code_files_path_idx ON code_files(file_path);
CREATE INDEX code_chunks_repository_active_idx ON code_chunks(repository_id, active);
CREATE INDEX code_chunks_file_idx ON code_chunks(file_id);
CREATE INDEX code_chunks_symbol_idx ON code_chunks(symbol_name);
CREATE INDEX code_chunks_method_idx ON code_chunks(method_name);
CREATE INDEX code_chunks_search_vector_idx ON code_chunks USING GIN(search_vector);
CREATE INDEX code_chunks_embedding_idx ON code_chunks USING HNSW (embedding vector_cosine_ops);
