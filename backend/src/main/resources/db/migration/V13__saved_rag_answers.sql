CREATE TABLE saved_rag_answers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    answer_type VARCHAR(16) NOT NULL,
    question TEXT NOT NULL,
    mode VARCHAR(64),
    answer TEXT NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence VARCHAR(64),
    diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb,
    repository_id UUID REFERENCES code_repositories(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX saved_rag_answers_user_created_idx
    ON saved_rag_answers(user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX saved_rag_answers_user_space_created_idx
    ON saved_rag_answers(user_id, space_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX saved_rag_answers_user_type_created_idx
    ON saved_rag_answers(user_id, answer_type, created_at DESC)
    WHERE deleted_at IS NULL;
