CREATE TABLE source_objects (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    bucket TEXT NOT NULL,
    object_key TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id)
);

CREATE INDEX source_objects_source_id_idx ON source_objects(source_id);

CREATE TABLE crawl_audit_logs (
    id UUID PRIMARY KEY,
    source_id UUID REFERENCES data_sources(id) ON DELETE SET NULL,
    url TEXT NOT NULL,
    host TEXT,
    allowed_domain BOOLEAN NOT NULL,
    robots_allowed BOOLEAN,
    status_code INTEGER,
    success BOOLEAN NOT NULL,
    message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX crawl_audit_logs_source_id_idx ON crawl_audit_logs(source_id);
CREATE INDEX crawl_audit_logs_host_idx ON crawl_audit_logs(host);
CREATE INDEX crawl_audit_logs_started_at_idx ON crawl_audit_logs(started_at);
