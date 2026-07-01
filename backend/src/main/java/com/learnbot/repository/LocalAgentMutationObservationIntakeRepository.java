package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentToolResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public class LocalAgentMutationObservationIntakeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocalAgentMutationObservationIntakeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void saveAcceptedObservation(LocalAgentToolResponse response, Map<String, Object> requestInput) {
        Map<String, Object> observation = mapValue(response.output().get("acceptedMutationObservation"));
        if (observation.isEmpty()) {
            return;
        }
        UUID sourceRequestId = uuidValue(firstValue(observation.get("sourceRequestId"), requestInput.get("sourceRequestId")));
        UUID releaseAttemptId = uuidValue(firstValue(observation.get("releaseAttemptId"), requestInput.get("releaseAttemptId")));
        if (sourceRequestId == null || releaseAttemptId == null) {
            return;
        }
        Map<String, Object> candidate = mapValue(response.output().get("mutationResultIntakeCandidate"));
        jdbc.update("""
                INSERT INTO local_agent_mutation_observation_intake (
                    request_id, source_request_id, release_attempt_id, session_id, user_id,
                    agent_id, workspace_id, tool_name, status, accepted, verification_status,
                    observation, candidate, created_at
                )
                VALUES (
                    :requestId, :sourceRequestId, :releaseAttemptId, :sessionId, :userId,
                    :agentId, :workspaceId, :toolName, :status, :accepted, :verificationStatus,
                    CAST(:observation AS jsonb), CAST(:candidate AS jsonb), COALESCE(:finishedAt, now())
                )
                ON CONFLICT (request_id) DO UPDATE
                SET status = EXCLUDED.status,
                    accepted = EXCLUDED.accepted,
                    verification_status = EXCLUDED.verification_status,
                    observation = EXCLUDED.observation,
                    candidate = EXCLUDED.candidate,
                    created_at = EXCLUDED.created_at
                """, new MapSqlParameterSource()
                .addValue("requestId", response.requestId())
                .addValue("sourceRequestId", sourceRequestId)
                .addValue("releaseAttemptId", releaseAttemptId)
                .addValue("sessionId", response.sessionId())
                .addValue("userId", response.userId())
                .addValue("agentId", response.agentId())
                .addValue("workspaceId", response.workspaceId())
                .addValue("toolName", response.toolName().wireName())
                .addValue("status", String.valueOf(observation.getOrDefault("status", "UNKNOWN")))
                .addValue("accepted", Boolean.TRUE.equals(observation.get("accepted")))
                .addValue("verificationStatus", observation.get("verificationStatus"))
                .addValue("observation", toJson(observation))
                .addValue("candidate", toJson(candidate))
                .addValue("finishedAt", response.finishedAt()));
    }

    public Optional<Map<String, Object>> findLatestAcceptedMutationObservationForReleaseAttempt(
            UUID userId,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        return jdbc.query("""
                SELECT observation
                FROM local_agent_mutation_observation_intake
                WHERE user_id = :userId
                  AND source_request_id = :sourceRequestId
                  AND release_attempt_id = :releaseAttemptId
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId)
                .addValue("releaseAttemptId", releaseAttemptId),
                (rs, rowNum) -> fromJson(rs.getString("observation"), new TypeReference<Map<String, Object>>() {}))
                .stream()
                .findFirst();
    }

    public List<Map<String, Object>> findAcceptedMutationObservationsForReleaseAttempt(
            UUID userId,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        return jdbc.query("""
                SELECT observation
                FROM local_agent_mutation_observation_intake
                WHERE user_id = :userId
                  AND source_request_id = :sourceRequestId
                  AND release_attempt_id = :releaseAttemptId
                ORDER BY created_at ASC
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId)
                .addValue("releaseAttemptId", releaseAttemptId),
                (rs, rowNum) -> fromJson(rs.getString("observation"), new TypeReference<Map<String, Object>>() {}));
    }

    private Object firstValue(Object first, Object second) {
        return hasValue(first) ? first : second;
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            return UUID.fromString(text);
        }
        return null;
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid Local Agent mutation observation JSON.", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "null" : value, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Local Agent mutation observation JSON.", ex);
        }
    }
}
