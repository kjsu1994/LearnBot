package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentFailureCode;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
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
public class LocalAgentToolExecutionRepository {
    private static final int TOOL_EXECUTION_LEASE_SECONDS = 300;
    private static final String TOOL_EXECUTION_LEASE_TIMEOUT_WARNING = "Local Agent tool execution lease timed out before completion.";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocalAgentToolExecutionRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public LocalAgentToolExecution create(UUID id, LocalAgentToolRequest request) {
        jdbc.update("""
                INSERT INTO local_agent_tool_executions (
                    id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
                    approval_state, status, input, request_warnings, created_at
                )
                VALUES (
                    :id, :sessionId, :userId, :agentId, :workspaceId, :executionTarget, :toolName,
                    :approvalState, :status, CAST(:input AS jsonb), CAST(:requestWarnings AS jsonb), :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sessionId", request.sessionId())
                .addValue("userId", request.userId())
                .addValue("agentId", request.agentId())
                .addValue("workspaceId", request.workspaceId())
                .addValue("executionTarget", request.executionTarget().name())
                .addValue("toolName", request.toolName().wireName())
                .addValue("approvalState", request.approvalState().name())
                .addValue("status", initialStatus(request).name())
                .addValue("input", toJson(request.input()))
                .addValue("requestWarnings", toJson(request.warnings()))
                .addValue("createdAt", request.createdAt()));
        return find(id).orElseThrow();
    }

    public Optional<LocalAgentToolExecution> claimNext(UUID userId, UUID agentId) {
        List<UUID> ids = jdbc.query("""
                SELECT id
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND agent_id = :agentId
                  AND status IN ('PENDING', 'APPROVED')
                ORDER BY created_at ASC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("agentId", agentId), (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        UUID id = ids.get(0);
        int updated = jdbc.update("""
                UPDATE local_agent_tool_executions
                SET status = 'RUNNING',
                    started_at = COALESCE(started_at, now()),
                    lease_expires_at = now() + (:leaseSeconds * INTERVAL '1 second')
                WHERE id = :id
                  AND status IN ('PENDING', 'APPROVED')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("leaseSeconds", TOOL_EXECUTION_LEASE_SECONDS));
        return updated == 0 ? Optional.empty() : find(id);
    }

    public List<LocalAgentToolExecution> expireTimedOutLeases() {
        return jdbc.query("""
                UPDATE local_agent_tool_executions
                SET status = 'TIMED_OUT',
                    failure_code = 'TIMEOUT',
                    error = :error,
                    response_warnings = COALESCE(response_warnings, '[]'::jsonb) || CAST(:warning AS jsonb),
                    finished_at = COALESCE(finished_at, now()),
                    lease_expires_at = NULL
                WHERE status = 'RUNNING'
                  AND lease_expires_at IS NOT NULL
                  AND lease_expires_at < now()
                RETURNING id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
                          approval_state, status, input::text, output::text, failure_code, error,
                          request_warnings::text, response_warnings::text, created_at, started_at, finished_at
                """, new MapSqlParameterSource()
                .addValue("error", TOOL_EXECUTION_LEASE_TIMEOUT_WARNING)
                .addValue("warning", toJson(List.of(TOOL_EXECUTION_LEASE_TIMEOUT_WARNING))),
                this::mapExecution);
    }

    public Optional<LocalAgentToolExecution> find(UUID id) {
        List<LocalAgentToolExecution> executions = jdbc.query("""
                SELECT id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
                       approval_state, status, input::text, output::text, failure_code, error,
                       request_warnings::text, response_warnings::text, created_at, started_at, finished_at
                FROM local_agent_tool_executions
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id), this::mapExecution);
        return executions.stream().findFirst();
    }

    public List<LocalAgentToolExecution> findPendingApprovalsForUser(UUID userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbc.query("""
                SELECT id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
                       approval_state, status, input::text, output::text, failure_code, error,
                       request_warnings::text, response_warnings::text, created_at, started_at, finished_at
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND approval_state = 'REQUIRED'
                  AND status = 'APPROVAL_REQUIRED'
                ORDER BY created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", safeLimit), this::mapExecution);
    }

    public List<LocalAgentToolExecution> findCompletedApprovedExecutionFlowRowsForReleaseAttempt(
            UUID userId,
            UUID releaseAttemptId
    ) {
        return jdbc.query("""
                SELECT id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
                       approval_state, status, input::text, output::text, failure_code, error,
                       request_warnings::text, response_warnings::text, created_at, started_at, finished_at
                FROM (
                    SELECT DISTINCT ON (tool_name)
                           id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
                           approval_state, status, input, output, failure_code, error,
                           request_warnings, response_warnings, created_at, started_at, finished_at
                    FROM local_agent_tool_executions
                    WHERE user_id = :userId
                      AND input ->> 'releaseAttemptId' = :releaseAttemptId
                      AND execution_target = 'USER_LOCAL_AGENT'
                      AND approval_state = 'APPROVED'
                      AND status IN ('SUCCEEDED', 'FAILED', 'REJECTED', 'TIMED_OUT', 'DISCONNECTED', 'CANCELLED')
                      AND finished_at IS NOT NULL
                      AND tool_name IN ('patch.apply', 'command.runAllowed', 'git.status', 'rollback.restore')
                    ORDER BY tool_name, finished_at DESC NULLS LAST, created_at DESC
                ) latest
                ORDER BY CASE tool_name
                    WHEN 'patch.apply' THEN 1
                    WHEN 'command.runAllowed' THEN 2
                    WHEN 'git.status' THEN 3
                    WHEN 'rollback.restore' THEN 4
                    ELSE 99
                END
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("releaseAttemptId", releaseAttemptId.toString()),
                this::mapExecution);
    }

    public int countMutationEnabledExecutionRowsForReleaseAttempt(UUID userId, UUID releaseAttemptId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND input ->> 'releaseAttemptId' = :releaseAttemptId
                  AND input ->> 'mutationAllowed' = 'true'
                  AND execution_target = 'USER_LOCAL_AGENT'
                  AND tool_name IN ('patch.apply', 'command.runAllowed', 'git.status', 'rollback.restore')
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("releaseAttemptId", releaseAttemptId.toString()), Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<Map<String, Object>> findLatestRepositoryVerificationForSourceRequest(UUID userId, UUID sourceRequestId) {
        List<Map<String, Object>> results = jdbc.query("""
                SELECT output -> 'repositoryVerification' AS repository_verification
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND input ->> 'sourceRequestId' = :sourceRequestId
                  AND jsonb_exists(output, 'repositoryVerification')
                ORDER BY finished_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId.toString()),
                (rs, rowNum) -> fromJson(rs.getString("repository_verification"), new TypeReference<Map<String, Object>>() {}));
        return results.stream().findFirst();
    }

    public Optional<Map<String, Object>> findLatestRepositoryVerificationForReleaseAttempt(
            UUID userId,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        List<Map<String, Object>> results = jdbc.query("""
                SELECT output -> 'repositoryVerification' AS repository_verification
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND input ->> 'sourceRequestId' = :sourceRequestId
                  AND input ->> 'releaseAttemptId' = :releaseAttemptId
                  AND jsonb_exists(output, 'repositoryVerification')
                ORDER BY finished_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId.toString())
                .addValue("releaseAttemptId", releaseAttemptId.toString()),
                (rs, rowNum) -> fromJson(rs.getString("repository_verification"), new TypeReference<Map<String, Object>>() {}));
        return results.stream().findFirst();
    }

    public Optional<Map<String, Object>> findLatestPatchDryRunOutputForSourceRequest(UUID userId, UUID sourceRequestId) {
        List<Map<String, Object>> results = jdbc.query("""
                SELECT output
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND input ->> 'sourceRequestId' = :sourceRequestId
                  AND tool_name = 'patch.apply'
                  AND output ->> 'dryRun' = 'true'
                ORDER BY finished_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId.toString()),
                (rs, rowNum) -> fromJson(rs.getString("output"), new TypeReference<Map<String, Object>>() {}));
        return results.stream().findFirst();
    }

    public Optional<Map<String, Object>> findLatestPatchDryRunOutputForReleaseAttempt(
            UUID userId,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        List<Map<String, Object>> results = jdbc.query("""
                SELECT output
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND input ->> 'sourceRequestId' = :sourceRequestId
                  AND input ->> 'releaseAttemptId' = :releaseAttemptId
                  AND tool_name = 'patch.apply'
                  AND output ->> 'dryRun' = 'true'
                ORDER BY finished_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId.toString())
                .addValue("releaseAttemptId", releaseAttemptId.toString()),
                (rs, rowNum) -> fromJson(rs.getString("output"), new TypeReference<Map<String, Object>>() {}));
        return results.stream().findFirst();
    }

    public Optional<Map<String, Object>> findLatestAcceptedMutationObservationForReleaseAttempt(
            UUID userId,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        List<Map<String, Object>> results = jdbc.query("""
                SELECT output -> 'acceptedMutationObservation' AS accepted_observation
                FROM local_agent_tool_executions
                WHERE user_id = :userId
                  AND input ->> 'sourceRequestId' = :sourceRequestId
                  AND input ->> 'releaseAttemptId' = :releaseAttemptId
                  AND jsonb_exists(output, 'acceptedMutationObservation')
                ORDER BY finished_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sourceRequestId", sourceRequestId.toString())
                .addValue("releaseAttemptId", releaseAttemptId.toString()),
                (rs, rowNum) -> fromJson(rs.getString("accepted_observation"), new TypeReference<Map<String, Object>>() {}));
        return results.stream().findFirst();
    }

    public Optional<LocalAgentToolExecution> updateApprovalDecision(
            UUID id,
            UUID userId,
            LocalAgentApprovalState approvalState,
            LocalAgentToolStatus status,
            String warning
    ) {
        int updated = jdbc.update("""
                UPDATE local_agent_tool_executions
                SET approval_state = :approvalState,
                    status = :status,
                    request_warnings = request_warnings || CAST(:warning AS jsonb),
                    finished_at = CASE WHEN :finished THEN COALESCE(finished_at, now()) ELSE finished_at END
                WHERE id = :id
                  AND user_id = :userId
                  AND approval_state = 'REQUIRED'
                  AND status = 'APPROVAL_REQUIRED'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("approvalState", approvalState.name())
                .addValue("status", status.name())
                .addValue("warning", toJson(List.of(warning)))
                .addValue("finished", status == LocalAgentToolStatus.REJECTED));
        return updated == 0 ? Optional.empty() : find(id);
    }

    public Optional<LocalAgentToolExecution> releaseApprovedHeldPatch(UUID id, UUID userId, String warning) {
        int updated = jdbc.update("""
                UPDATE local_agent_tool_executions
                SET status = 'APPROVED',
                    request_warnings = request_warnings || CAST(:warning AS jsonb)
                WHERE id = :id
                  AND user_id = :userId
                  AND tool_name = 'patch.apply'
                  AND execution_target = 'USER_LOCAL_AGENT'
                  AND approval_state = 'APPROVED'
                  AND status = 'APPROVED_HELD'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("warning", toJson(List.of(warning))));
        return updated == 0 ? Optional.empty() : find(id);
    }

    public Optional<LocalAgentToolExecution> releaseApprovedHeldPatchWithMutationInput(
            UUID id,
            UUID userId,
            Map<String, Object> mutationInput,
            String warning
    ) {
        int updated = jdbc.update("""
                UPDATE local_agent_tool_executions
                SET status = 'APPROVED',
                    input = CAST(:input AS jsonb),
                    request_warnings = request_warnings || CAST(:warning AS jsonb)
                WHERE id = :id
                  AND user_id = :userId
                  AND tool_name = 'patch.apply'
                  AND execution_target = 'USER_LOCAL_AGENT'
                  AND approval_state = 'APPROVED'
                  AND status = 'APPROVED_HELD'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("input", toJson(mutationInput))
                .addValue("warning", toJson(List.of(warning))));
        return updated == 0 ? Optional.empty() : find(id);
    }

    public void complete(LocalAgentToolResponse response) {
        jdbc.update("""
                UPDATE local_agent_tool_executions
                SET status = :status,
                    output = CAST(:output AS jsonb),
                    failure_code = :failureCode,
                    error = :error,
                    response_warnings = CAST(:warnings AS jsonb),
                    started_at = COALESCE(started_at, :startedAt),
                    finished_at = COALESCE(:finishedAt, now()),
                    lease_expires_at = NULL
                WHERE id = :id
                  AND user_id = :userId
                  AND agent_id = :agentId
                  AND status IN ('PENDING', 'APPROVED', 'RUNNING')
                """, new MapSqlParameterSource()
                .addValue("id", response.requestId())
                .addValue("userId", response.userId())
                .addValue("agentId", response.agentId())
                .addValue("status", response.status().name())
                .addValue("output", toJson(response.output()))
                .addValue("failureCode", response.failureCode() == null ? null : response.failureCode().name())
                .addValue("error", response.error())
                .addValue("warnings", toJson(response.warnings()))
                .addValue("startedAt", response.startedAt())
                .addValue("finishedAt", response.finishedAt()));
    }

    private LocalAgentToolStatus initialStatus(LocalAgentToolRequest request) {
        return switch (request.approvalState()) {
            case APPROVED -> LocalAgentToolStatus.APPROVED;
            case REQUIRED -> LocalAgentToolStatus.APPROVAL_REQUIRED;
            case DENIED -> LocalAgentToolStatus.REJECTED;
            case EXPIRED -> LocalAgentToolStatus.CANCELLED;
            case NOT_REQUIRED -> LocalAgentToolStatus.PENDING;
        };
    }

    private LocalAgentToolExecution mapExecution(ResultSet rs, int rowNum) throws SQLException {
        String failureCode = rs.getString("failure_code");
        return new LocalAgentToolExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                AgentExecutionTarget.valueOf(rs.getString("execution_target")),
                LocalAgentToolName.fromWireName(rs.getString("tool_name")),
                LocalAgentApprovalState.valueOf(rs.getString("approval_state")),
                LocalAgentToolStatus.valueOf(rs.getString("status")),
                fromJson(rs.getString("input"), new TypeReference<Map<String, Object>>() {}),
                fromJson(rs.getString("output"), new TypeReference<Map<String, Object>>() {}),
                failureCode == null ? null : LocalAgentFailureCode.valueOf(failureCode),
                rs.getString("error"),
                fromJson(rs.getString("request_warnings"), new TypeReference<List<String>>() {}),
                fromJson(rs.getString("response_warnings"), new TypeReference<List<String>>() {}),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class)
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid Local Agent tool JSON.", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "null" : value, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Local Agent tool JSON.", ex);
        }
    }
}
