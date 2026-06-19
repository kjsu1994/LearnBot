package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.SavedAnswerDetail;
import com.learnbot.dto.SavedAnswerRequest;
import com.learnbot.dto.SavedAnswerSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SavedAnswerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SavedAnswerRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public SavedAnswerDetail create(UUID userId, SavedAnswerRequest request, String answerType, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO saved_rag_answers (
                    id, user_id, space_id, answer_type, question, mode, answer,
                    citations, evidence, confidence, diagnostics, repository_id, title
                )
                VALUES (
                    :id, :userId, :spaceId, :answerType, :question, :mode, :answer,
                    CAST(:citations AS jsonb), CAST(:evidence AS jsonb), :confidence,
                    CAST(:diagnostics AS jsonb), :repositoryId, :title
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("spaceId", request.spaceId())
                .addValue("answerType", answerType)
                .addValue("question", request.question().trim())
                .addValue("mode", cleanNullable(request.mode()))
                .addValue("answer", request.answer().trim())
                .addValue("citations", toJsonArray(request.citations()))
                .addValue("evidence", toJsonArray(request.evidence()))
                .addValue("confidence", cleanNullable(request.confidence()))
                .addValue("diagnostics", toJsonArray(request.diagnostics()))
                .addValue("repositoryId", request.repositoryId())
                .addValue("title", title));
        return find(userId, id).orElseThrow();
    }

    public List<SavedAnswerSummary> list(UUID userId, UUID spaceId, String answerType, String query, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, space_id, answer_type, question, mode,
                       left(answer, 260) AS answer_preview, confidence, repository_id,
                       title, created_at, updated_at
                FROM saved_rag_answers
                WHERE user_id = :userId
                  AND deleted_at IS NULL
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", limit);
        if (spaceId != null) {
            sql.append(" AND space_id = :spaceId\n");
            params.addValue("spaceId", spaceId);
        }
        if (answerType != null && !answerType.isBlank()) {
            sql.append(" AND answer_type = :answerType\n");
            params.addValue("answerType", answerType);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (title ILIKE :query OR question ILIKE :query OR answer ILIKE :query)\n");
            params.addValue("query", "%" + query.trim() + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, this::mapSummary);
    }

    public Optional<SavedAnswerDetail> find(UUID userId, UUID id) {
        List<SavedAnswerDetail> rows = jdbc.query("""
                SELECT id, space_id, answer_type, question, mode, answer,
                       citations::text AS citations, evidence::text AS evidence,
                       confidence, diagnostics::text AS diagnostics, repository_id,
                       title, created_at, updated_at
                FROM saved_rag_answers
                WHERE id = :id
                  AND user_id = :userId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId), this::mapDetail);
        return rows.stream().findFirst();
    }

    public Optional<UUID> findSpaceId(UUID userId, UUID id) {
        List<UUID> rows = jdbc.query("""
                SELECT space_id
                FROM saved_rag_answers
                WHERE id = :id
                  AND user_id = :userId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId), (rs, rowNum) -> rs.getObject("space_id", UUID.class));
        return rows.stream().findFirst();
    }

    public void updateTitle(UUID userId, UUID id, String title) {
        jdbc.update("""
                UPDATE saved_rag_answers
                SET title = :title,
                    updated_at = now()
                WHERE id = :id
                  AND user_id = :userId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("title", title));
    }

    public void softDelete(UUID userId, UUID id) {
        jdbc.update("""
                UPDATE saved_rag_answers
                SET deleted_at = now(),
                    updated_at = now()
                WHERE id = :id
                  AND user_id = :userId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId));
    }

    private SavedAnswerSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new SavedAnswerSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("answer_type"),
                rs.getString("question"),
                rs.getString("mode"),
                rs.getString("answer_preview"),
                rs.getString("confidence"),
                rs.getObject("repository_id", UUID.class),
                rs.getString("title"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private SavedAnswerDetail mapDetail(ResultSet rs, int rowNum) throws SQLException {
        return new SavedAnswerDetail(
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("answer_type"),
                rs.getString("question"),
                rs.getString("mode"),
                rs.getString("answer"),
                fromJson(rs.getString("citations")),
                fromJson(rs.getString("evidence")),
                rs.getString("confidence"),
                fromJson(rs.getString("diagnostics")),
                rs.getObject("repository_id", UUID.class),
                rs.getString("title"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private String cleanNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJsonArray(JsonNode node) {
        JsonNode safeNode = node == null || node.isNull() ? objectMapper.createArrayNode() : node;
        if (!safeNode.isArray()) {
            safeNode = objectMapper.createArrayNode();
        }
        try {
            return objectMapper.writeValueAsString(safeNode);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private JsonNode fromJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createArrayNode();
        }
    }
}
