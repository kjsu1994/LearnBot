package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;
import com.learnbot.service.LocalAgentToolExecution;
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
public class LocalAgentPatchReleaseAttemptRepository {
    public static final String DISABLED_STATUS = "CREATED_DISABLED";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocalAgentPatchReleaseAttemptRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public LocalAgentPatchReleaseAttempt createDisabled(
            UUID id,
            LocalAgentToolExecution source,
            int staleWindowSeconds,
            Map<String, Object> evidence,
            List<String> failureReasons
    ) {
        jdbc.update("""
                INSERT INTO local_agent_patch_release_attempts (
                    id, source_request_id, session_id, user_id, agent_id, workspace_id,
                    status, claimable, stale_window_seconds, evidence, failure_reasons,
                    created_at, updated_at
                )
                VALUES (
                    :id, :sourceRequestId, :sessionId, :userId, :agentId, :workspaceId,
                    :status, false, :staleWindowSeconds, CAST(:evidence AS jsonb), CAST(:failureReasons AS jsonb),
                    now(), now()
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sourceRequestId", source.id())
                .addValue("sessionId", source.sessionId())
                .addValue("userId", source.userId())
                .addValue("agentId", source.agentId())
                .addValue("workspaceId", source.workspaceId())
                .addValue("status", DISABLED_STATUS)
                .addValue("staleWindowSeconds", staleWindowSeconds)
                .addValue("evidence", toJson(evidence == null ? Map.of() : evidence))
                .addValue("failureReasons", toJson(failureReasons == null ? List.of() : failureReasons)));
        return find(id).orElseThrow();
    }

    public Optional<LocalAgentPatchReleaseAttempt> find(UUID id) {
        List<LocalAgentPatchReleaseAttempt> attempts = jdbc.query("""
                SELECT id, source_request_id, session_id, user_id, agent_id, workspace_id,
                       status, claimable, stale_window_seconds, evidence::text, failure_reasons::text,
                       created_at, updated_at, released_at
                FROM local_agent_patch_release_attempts
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id), this::mapAttempt);
        return attempts.stream().findFirst();
    }

    public Optional<LocalAgentPatchReleaseAttempt> findLatestForSourceRequest(UUID userId, UUID sourceRequestId) {
        List<LocalAgentPatchReleaseAttempt> attempts = jdbc.query("""
                SELECT id, source_request_id, session_id, user_id, agent_id, workspace_id,
                       status, claimable, stale_window_seconds, evidence::text, failure_reasons::text,
                       created_at, updated_at, released_at
                FROM local_agent_patch_release_attempts
                WHERE user_id = :userId
                  AND source_request_id = :sourceRequestId
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId), this::mapAttempt);
        return attempts.stream().findFirst();
    }

    private LocalAgentPatchReleaseAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new LocalAgentPatchReleaseAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("source_request_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("claimable"),
                rs.getInt("stale_window_seconds"),
                fromJson(rs.getString("evidence"), new TypeReference<Map<String, Object>>() {}),
                fromJson(rs.getString("failure_reasons"), new TypeReference<List<String>>() {}),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("released_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid Local Agent patch release attempt JSON.", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "null" : value, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Local Agent patch release attempt JSON.", ex);
        }
    }
}
