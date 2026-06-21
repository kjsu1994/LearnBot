package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.DocumentSchemaProfileResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentSchemaProfileRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DocumentSchemaProfileRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<DocumentSchemaProfileResponse> findAll() {
        return jdbc.query("""
                SELECT id, schema_name, description, document_types::text, entity_types::text, relation_types::text,
                       enabled, default_profile, updated_at
                FROM document_schema_profiles
                ORDER BY default_profile DESC, schema_name
                """, this::mapProfile);
    }

    public Optional<DocumentSchemaProfileResponse> findDefaultEnabled() {
        List<DocumentSchemaProfileResponse> profiles = jdbc.query("""
                SELECT id, schema_name, description, document_types::text, entity_types::text, relation_types::text,
                       enabled, default_profile, updated_at
                FROM document_schema_profiles
                WHERE enabled = true
                ORDER BY default_profile DESC, schema_name
                LIMIT 1
                """, this::mapProfile);
        return profiles.stream().findFirst();
    }

    public Optional<DocumentSchemaProfileResponse> findByName(String schemaName) {
        List<DocumentSchemaProfileResponse> profiles = jdbc.query("""
                SELECT id, schema_name, description, document_types::text, entity_types::text, relation_types::text,
                       enabled, default_profile, updated_at
                FROM document_schema_profiles
                WHERE schema_name = :schemaName
                """, new MapSqlParameterSource().addValue("schemaName", schemaName), this::mapProfile);
        return profiles.stream().findFirst();
    }

    public DocumentSchemaProfileResponse update(
            String schemaName,
            String description,
            List<String> documentTypes,
            List<String> entityTypes,
            List<String> relationTypes,
            boolean enabled,
            boolean defaultProfile
    ) {
        if (defaultProfile) {
            jdbc.update("""
                    UPDATE document_schema_profiles
                    SET default_profile = false, updated_at = now()
                    WHERE schema_name <> :schemaName
                    """, new MapSqlParameterSource().addValue("schemaName", schemaName));
        }
        jdbc.update("""
                UPDATE document_schema_profiles
                SET description = :description,
                    document_types = CAST(:documentTypes AS jsonb),
                    entity_types = CAST(:entityTypes AS jsonb),
                    relation_types = CAST(:relationTypes AS jsonb),
                    enabled = :enabled,
                    default_profile = :defaultProfile,
                    updated_at = now()
                WHERE schema_name = :schemaName
                """, new MapSqlParameterSource()
                .addValue("schemaName", schemaName)
                .addValue("description", description)
                .addValue("documentTypes", toJson(documentTypes))
                .addValue("entityTypes", toJson(entityTypes))
                .addValue("relationTypes", toJson(relationTypes))
                .addValue("enabled", enabled)
                .addValue("defaultProfile", defaultProfile));
        return findByName(schemaName).orElseThrow(() -> new IllegalArgumentException("Unknown schema profile: " + schemaName));
    }

    private DocumentSchemaProfileResponse mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentSchemaProfileResponse(
                rs.getObject("id", UUID.class),
                rs.getString("schema_name"),
                rs.getString("description"),
                fromJsonList(rs.getString("document_types")),
                fromJsonList(rs.getString("entity_types")),
                fromJsonList(rs.getString("relation_types")),
                rs.getBoolean("enabled"),
                rs.getBoolean("default_profile"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private List<String> fromJsonList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid schema profile list.", ex);
        }
    }
}
