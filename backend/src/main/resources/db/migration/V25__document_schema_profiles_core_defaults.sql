UPDATE document_schema_profiles
SET default_profile = false,
    updated_at = now()
WHERE default_profile = true
  AND schema_name <> 'CORE';

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
    '00000000-0000-0000-0000-000000000021',
    'CORE',
    'General-purpose document graph schema used as the default fallback for common documents.',
    '["GENERAL_DOCUMENT"]'::jsonb,
    '["DOCUMENT","SECTION","TOPIC","TERM","REQUIREMENT","PROCEDURE","TABLE","FIGURE","DATE","PERSON","ORGANIZATION"]'::jsonb,
    '["DOCUMENT_HAS_SECTION","SECTION_HAS_TOPIC","TOPIC_RELATED_TO_TERM","REQUIREMENT_REFERENCES_PROCEDURE","TABLE_DESCRIBES_TOPIC","FIGURE_DESCRIBES_TOPIC"]'::jsonb,
    true,
    true
) ON CONFLICT (schema_name) DO UPDATE SET
    description = EXCLUDED.description,
    document_types = EXCLUDED.document_types,
    entity_types = EXCLUDED.entity_types,
    relation_types = EXCLUDED.relation_types,
    enabled = true,
    default_profile = true,
    updated_at = now();

UPDATE document_schema_profiles
SET default_profile = false,
    enabled = false,
    description = 'Example schema for satellite ground support equipment documents. Enable and adapt it only when this domain is relevant.',
    updated_at = now()
WHERE schema_name = 'SATELLITE_GSE';
