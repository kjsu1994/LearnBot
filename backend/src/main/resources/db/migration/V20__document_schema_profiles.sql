ALTER TABLE document_graph_nodes
    ALTER COLUMN node_type TYPE VARCHAR(64);

ALTER TABLE document_graph_edges
    ALTER COLUMN edge_type TYPE VARCHAR(96);

CREATE TABLE document_schema_profiles (
    id UUID PRIMARY KEY,
    schema_name VARCHAR(64) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    document_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    entity_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    relation_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT true,
    default_profile BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX document_schema_profiles_default_idx
    ON document_schema_profiles(default_profile)
    WHERE default_profile = true;

INSERT INTO document_schema_profiles (
    id,
    schema_name,
    description,
    document_types,
    entity_types,
    relation_types,
    enabled,
    default_profile
) VALUES (
    '00000000-0000-0000-0000-000000000020',
    'SATELLITE_GSE',
    '위성 지상지원장비 소프트웨어 문서용 스키마',
    '["REQUIREMENT_SPEC","DESIGN_SPEC","ICD","TEST_PROCEDURE","TEST_RESULT","OPERATION_MANUAL","TROUBLESHOOTING_GUIDE"]'::jsonb,
    '["REQUIREMENT","FUNCTION","SOFTWARE_MODULE","GROUND_EQUIPMENT","INTERFACE","COMMAND","TELEMETRY","PARAMETER","TEST_CASE","ERROR_CODE","FAULT","RESOLUTION"]'::jsonb,
    '["REQUIREMENT_IMPLEMENTED_BY_FUNCTION","REQUIREMENT_VERIFIED_BY_TEST_CASE","FUNCTION_USES_INTERFACE","FUNCTION_SENDS_COMMAND","FUNCTION_RECEIVES_TELEMETRY","COMMAND_HAS_PARAMETER","TELEMETRY_HAS_PARAMETER","ERROR_CODE_INDICATES_FAULT","FAULT_RESOLVED_BY_PROCEDURE"]'::jsonb,
    true,
    true
) ON CONFLICT (schema_name) DO NOTHING;
