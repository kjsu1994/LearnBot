CREATE TABLE rag_conversations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    domain TEXT NOT NULL CHECK (domain IN ('DOCUMENT', 'CODE')),
    repository_id UUID NULL REFERENCES code_repositories(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);

CREATE TABLE rag_conversation_turns (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES rag_conversations(id) ON DELETE CASCADE,
    parent_turn_id UUID NULL REFERENCES rag_conversation_turns(id) ON DELETE SET NULL,
    question TEXT NOT NULL,
    rewritten_question TEXT NULL,
    mode TEXT NULL,
    answer TEXT NOT NULL,
    confidence TEXT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    diagnostics JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX rag_conversations_user_space_domain_idx
    ON rag_conversations(user_id, space_id, domain, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX rag_conversations_created_retention_idx
    ON rag_conversations(created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX rag_conversation_turns_conversation_created_idx
    ON rag_conversation_turns(conversation_id, created_at);
