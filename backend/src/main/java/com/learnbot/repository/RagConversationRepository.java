package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.RagConversationDetail;
import com.learnbot.dto.RagConversationSummary;
import com.learnbot.dto.RagConversationTurn;
import com.learnbot.dto.RagConversationTurnContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RagConversationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RagConversationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public RagConversationSummary create(UUID userId, UUID spaceId, String domain, UUID repositoryId, String title) {
        softDeleteExpired(userId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_conversations (id, user_id, space_id, domain, repository_id, title)
                VALUES (:id, :userId, :spaceId, :domain, :repositoryId, :title)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("spaceId", spaceId)
                .addValue("domain", domain)
                .addValue("repositoryId", repositoryId)
                .addValue("title", title));
        return findSummary(userId, id).orElseThrow();
    }

    public List<RagConversationSummary> list(UUID userId, UUID spaceId, String domain, int limit) {
        softDeleteExpired(userId);
        return jdbc.query("""
                SELECT id, space_id, domain, repository_id, title, created_at, updated_at
                FROM rag_conversations
                WHERE user_id = :userId
                  AND space_id = :spaceId
                  AND domain = :domain
                  AND deleted_at IS NULL
                  AND created_at >= now() - interval '7 days'
                ORDER BY updated_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("spaceId", spaceId)
                .addValue("domain", domain)
                .addValue("limit", Math.max(1, Math.min(limit, 100))), this::mapSummary);
    }

    public Optional<RagConversationSummary> findSummary(UUID userId, UUID id) {
        softDeleteExpired(userId);
        return jdbc.query("""
                SELECT id, space_id, domain, repository_id, title, created_at, updated_at
                FROM rag_conversations
                WHERE user_id = :userId
                  AND id = :id
                  AND deleted_at IS NULL
                  AND created_at >= now() - interval '7 days'
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("id", id), this::mapSummary).stream().findFirst();
    }

    public Optional<RagConversationDetail> findDetail(UUID userId, UUID id) {
        Optional<RagConversationSummary> summary = findSummary(userId, id);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RagConversationDetail(summary.get(), listTurns(id)));
    }

    public List<RagConversationTurn> listTurns(UUID conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, parent_turn_id, question, rewritten_question, mode, answer,
                       confidence, citations::text AS citations, evidence::text AS evidence,
                       diagnostics::text AS diagnostics, metadata::text AS metadata, created_at
                FROM rag_conversation_turns
                WHERE conversation_id = :conversationId
                ORDER BY created_at ASC
                """, new MapSqlParameterSource().addValue("conversationId", conversationId), this::mapTurn);
    }

    public boolean turnBelongsToConversation(UUID conversationId, UUID turnId) {
        if (conversationId == null || turnId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rag_conversation_turns
                WHERE conversation_id = :conversationId
                  AND id = :turnId
                """, new MapSqlParameterSource()
                .addValue("conversationId", conversationId)
                .addValue("turnId", turnId), Integer.class);
        return count != null && count > 0;
    }

    public List<RagConversationTurnContext> recentTurnContexts(UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT question, answer, evidence::text AS evidence
                FROM rag_conversation_turns
                WHERE conversation_id = :conversationId
                ORDER BY created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("conversationId", conversationId)
                .addValue("limit", Math.max(1, Math.min(limit, 8))), (rs, rowNum) -> new RagConversationTurnContext(
                rs.getString("question"),
                rs.getString("answer"),
                fromJson(rs.getString("evidence"))
        ));
    }

    public RagConversationTurn addTurn(
            UUID conversationId,
            UUID parentTurnId,
            String question,
            String rewrittenQuestion,
            String mode,
            String answer,
            String confidence,
            JsonNode citations,
            JsonNode evidence,
            JsonNode diagnostics,
            JsonNode metadata
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_conversation_turns (
                    id, conversation_id, parent_turn_id, question, rewritten_question, mode, answer,
                    confidence, citations, evidence, diagnostics, metadata
                )
                VALUES (
                    :id, :conversationId, :parentTurnId, :question, :rewrittenQuestion, :mode, :answer,
                    :confidence, CAST(:citations AS jsonb), CAST(:evidence AS jsonb),
                    CAST(:diagnostics AS jsonb), CAST(:metadata AS jsonb)
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("conversationId", conversationId)
                .addValue("parentTurnId", parentTurnId)
                .addValue("question", question)
                .addValue("rewrittenQuestion", rewrittenQuestion)
                .addValue("mode", mode)
                .addValue("answer", answer)
                .addValue("confidence", confidence)
                .addValue("citations", toJson(citations, true))
                .addValue("evidence", toJson(evidence, true))
                .addValue("diagnostics", toJson(diagnostics, true))
                .addValue("metadata", toJson(metadata, false)));
        jdbc.update("""
                UPDATE rag_conversations
                SET updated_at = now()
                WHERE id = :conversationId
                """, new MapSqlParameterSource().addValue("conversationId", conversationId));
        return listTurns(conversationId).stream().filter(turn -> turn.id().equals(id)).findFirst().orElseThrow();
    }

    public void softDelete(UUID userId, UUID id) {
        jdbc.update("""
                UPDATE rag_conversations
                SET deleted_at = now(), updated_at = now()
                WHERE id = :id
                  AND user_id = :userId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId));
    }

    public int softDeleteExpired(UUID userId) {
        return jdbc.update("""
                UPDATE rag_conversations
                SET deleted_at = now(), updated_at = now()
                WHERE user_id = :userId
                  AND deleted_at IS NULL
                  AND created_at < now() - interval '7 days'
                """, new MapSqlParameterSource().addValue("userId", userId));
    }

    private RagConversationSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new RagConversationSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("domain"),
                rs.getObject("repository_id", UUID.class),
                rs.getString("title"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private RagConversationTurn mapTurn(ResultSet rs, int rowNum) throws SQLException {
        return new RagConversationTurn(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("parent_turn_id", UUID.class),
                rs.getString("question"),
                rs.getString("rewritten_question"),
                rs.getString("mode"),
                rs.getString("answer"),
                rs.getString("confidence"),
                fromJson(rs.getString("citations")),
                fromJson(rs.getString("evidence")),
                fromJson(rs.getString("diagnostics")),
                fromJson(rs.getString("metadata")),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(JsonNode node, boolean arrayFallback) {
        JsonNode safeNode = node == null || node.isNull()
                ? (arrayFallback ? objectMapper.createArrayNode() : objectMapper.createObjectNode())
                : node;
        try {
            return objectMapper.writeValueAsString(safeNode);
        } catch (JsonProcessingException ex) {
            return arrayFallback ? "[]" : "{}";
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
