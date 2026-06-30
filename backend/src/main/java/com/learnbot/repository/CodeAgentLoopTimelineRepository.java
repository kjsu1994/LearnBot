package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CodeAgentLoopTimelineRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CodeAgentLoopTimelineRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public int createPreview(UUID userId, String instruction, CodeAgentLoopPreviewResponse preview) {
        jdbc.update("""
                INSERT INTO code_agent_loop_timelines (
                    id, user_id, repository_id, space_id, instruction, status, max_steps, timeout_seconds,
                    cancellation_enabled, timeline_persistence_enabled, mutation_enabled,
                    steps, stop_conditions, warnings, created_at
                )
                VALUES (
                    :id, :userId, :repositoryId, :spaceId, :instruction, :status, :maxSteps, :timeoutSeconds,
                    :cancellationEnabled, :timelinePersistenceEnabled, :mutationEnabled,
                    CAST(:steps AS jsonb), CAST(:stopConditions AS jsonb), CAST(:warnings AS jsonb), now()
                )
                """, new MapSqlParameterSource()
                .addValue("id", preview.loopId())
                .addValue("userId", userId)
                .addValue("repositoryId", preview.repositoryId())
                .addValue("spaceId", preview.spaceId())
                .addValue("instruction", instruction == null ? "" : instruction)
                .addValue("status", preview.status())
                .addValue("maxSteps", preview.maxSteps())
                .addValue("timeoutSeconds", preview.timeoutSeconds())
                .addValue("cancellationEnabled", preview.cancellationEnabled())
                .addValue("timelinePersistenceEnabled", preview.timelinePersistenceEnabled())
                .addValue("mutationEnabled", preview.mutationEnabled())
                .addValue("steps", toJson(preview.steps()))
                .addValue("stopConditions", toJson(preview.stopConditions()))
                .addValue("warnings", toJson(preview.warnings())));
        return createPreviewEvents(userId, preview);
    }

    public List<CodeAgentLoopTimelineSummary> findRecent(UUID userId, UUID repositoryId, int limit) {
        return jdbc.query("""
                SELECT id, repository_id, space_id, instruction, status, max_steps, timeout_seconds,
                       cancellation_enabled, timeline_persistence_enabled, mutation_enabled, created_at
                FROM code_agent_loop_timelines
                WHERE user_id = :userId AND repository_id = :repositoryId
                ORDER BY created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("repositoryId", repositoryId)
                .addValue("limit", limit), (rs, rowNum) -> mapTimeline(userId, rs));
    }

    public int appendObservationResult(UUID userId, UUID repositoryId, UUID loopId, LocalAgentToolResponse response, Map<String, Object> requestInput) {
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_OBSERVATION_RESULT",
                "OBSERVE",
                response.executionTarget(),
                response.toolName(),
                response.toolName().isSideEffectful(),
                observationDetails(response, requestInput)
        );
    }

    public int appendApprovalDecision(
            UUID userId,
            UUID repositoryId,
            UUID requestId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            AgentExecutionTarget executionTarget,
            LocalAgentToolName toolName,
            String approvalState,
            String status,
            UUID loopId,
            Map<String, Object> requestInput
    ) {
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_APPROVAL_DECISION",
                "REQUEST_APPROVAL",
                executionTarget,
                toolName,
                true,
                approvalDetails(requestId, sessionId, agentId, workspaceId, approvalState, status, requestInput)
        );
    }

    private int appendLatestEvent(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            String eventType,
            String phase,
            AgentExecutionTarget executionTarget,
            LocalAgentToolName toolName,
            boolean requiresApproval,
            Map<String, Object> details
    ) {
        return jdbc.update("""
                WITH latest AS (
                    SELECT id
                    FROM code_agent_loop_timelines
                    WHERE user_id = :userId
                      AND (
                        (CAST(:loopId AS uuid) IS NOT NULL AND id = CAST(:loopId AS uuid))
                        OR (CAST(:loopId AS uuid) IS NULL AND repository_id = :repositoryId)
                      )
                    ORDER BY created_at DESC
                    LIMIT 1
                ),
                next_sequence AS (
                    SELECT latest.id AS timeline_id, COALESCE(MAX(event.sequence_number), 0) + 1 AS sequence_number
                    FROM latest
                    LEFT JOIN code_agent_loop_timeline_events event ON event.timeline_id = latest.id
                    GROUP BY latest.id
                )
                INSERT INTO code_agent_loop_timeline_events (
                    id, timeline_id, user_id, sequence_number, event_type, phase, execution_target, tool_name,
                    requires_approval, may_mutate, enabled, details, created_at
                )
                SELECT
                    :id, timeline_id, :userId, sequence_number, :eventType, :phase, :executionTarget, :toolName,
                    :requiresApproval, false, true, CAST(:details AS jsonb), now()
                FROM next_sequence
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("userId", userId)
                .addValue("repositoryId", repositoryId)
                .addValue("loopId", loopId)
                .addValue("eventType", eventType)
                .addValue("phase", phase)
                .addValue("executionTarget", executionTarget == null ? null : executionTarget.name())
                .addValue("toolName", toolName == null ? null : toolName.wireName())
                .addValue("requiresApproval", requiresApproval)
                .addValue("details", toJson(details == null ? Map.of() : details)));
    }

    private CodeAgentLoopTimelineSummary mapTimeline(UUID userId, ResultSet rs) throws SQLException {
        UUID timelineId = rs.getObject("id", UUID.class);
        return new CodeAgentLoopTimelineSummary(
                timelineId,
                rs.getObject("repository_id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("instruction"),
                rs.getString("status"),
                rs.getInt("max_steps"),
                rs.getInt("timeout_seconds"),
                rs.getBoolean("cancellation_enabled"),
                rs.getBoolean("timeline_persistence_enabled"),
                rs.getBoolean("mutation_enabled"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                findEvents(userId, timelineId)
        );
    }

    private List<CodeAgentLoopTimelineEventSummary> findEvents(UUID userId, UUID timelineId) {
        return jdbc.query("""
                SELECT id, sequence_number, event_type, phase, execution_target, tool_name,
                       requires_approval, may_mutate, enabled, details::text AS details, created_at
                FROM code_agent_loop_timeline_events
                WHERE user_id = :userId AND timeline_id = :timelineId
                ORDER BY sequence_number ASC
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("timelineId", timelineId), (rs, rowNum) -> new CodeAgentLoopTimelineEventSummary(
                rs.getObject("id", UUID.class),
                rs.getInt("sequence_number"),
                rs.getString("event_type"),
                rs.getString("phase"),
                enumValue(AgentExecutionTarget.class, rs.getString("execution_target")),
                toolName(rs.getString("tool_name")),
                rs.getBoolean("requires_approval"),
                rs.getBoolean("may_mutate"),
                rs.getBoolean("enabled"),
                fromJsonMap(rs.getString("details")),
                rs.getObject("created_at", java.time.OffsetDateTime.class)
        ));
    }

    private int createPreviewEvents(UUID userId, CodeAgentLoopPreviewResponse preview) {
        int sequence = 1;
        insertEvent(
                preview,
                userId,
                sequence++,
                "LOOP_PREVIEW_CREATED",
                null,
                Map.of(
                        "status", preview.status(),
                        "maxSteps", preview.maxSteps(),
                        "timeoutSeconds", preview.timeoutSeconds(),
                        "mutationEnabled", preview.mutationEnabled(),
                        "timelinePersistenceEnabled", preview.timelinePersistenceEnabled(),
                        "cancellationEnabled", preview.cancellationEnabled()
                )
        );
        for (CodeAgentLoopStep step : preview.steps()) {
            insertEvent(preview, userId, sequence++, eventType(step.phase()), step, stepDetails(step));
        }
        insertEvent(
                preview,
                userId,
                sequence++,
                "STOP_CONDITIONS_REGISTERED",
                null,
                Map.of(
                        "stopConditions", preview.stopConditions(),
                        "warnings", preview.warnings()
                )
        );
        insertEvent(
                preview,
                userId,
                sequence++,
                "TIMEOUT_POLICY_REGISTERED",
                null,
                Map.of(
                        "status", "REGISTERED",
                        "timeoutSeconds", preview.timeoutSeconds(),
                        "mutationEnabled", preview.mutationEnabled()
                )
        );
        insertEvent(
                preview,
                userId,
                sequence++,
                "CANCELLATION_POLICY_REGISTERED",
                null,
                Map.of(
                        "status", preview.cancellationEnabled() ? "REGISTERED" : "DISABLED",
                        "cancellationEnabled", preview.cancellationEnabled(),
                        "mutationEnabled", preview.mutationEnabled()
                )
        );
        insertEvent(
                preview,
                userId,
                sequence++,
                "FINAL_RESULT_POLICY_REGISTERED",
                null,
                Map.of(
                        "status", "PENDING_PREVIEW_ONLY",
                        "finalResultEnabled", false,
                        "publicationEnabled", false,
                        "acknowledgementEnabled", false,
                        "mutationEnabled", preview.mutationEnabled()
                )
        );
        for (Map<String, Object> stopOutcome : stopOutcomePolicies(preview)) {
            insertEvent(
                    preview,
                    userId,
                    sequence++,
                    "STOP_OUTCOME_POLICY_REGISTERED",
                    null,
                    stopOutcome
            );
        }
        return sequence - 1;
    }

    private void insertEvent(
            CodeAgentLoopPreviewResponse preview,
            UUID userId,
            int sequence,
            String eventType,
            CodeAgentLoopStep step,
            Map<String, Object> details
    ) {
        jdbc.update("""
                INSERT INTO code_agent_loop_timeline_events (
                    id, timeline_id, user_id, sequence_number, event_type, phase, execution_target, tool_name,
                    requires_approval, may_mutate, enabled, details, created_at
                )
                VALUES (
                    :id, :timelineId, :userId, :sequenceNumber, :eventType, :phase, :executionTarget, :toolName,
                    :requiresApproval, :mayMutate, :enabled, CAST(:details AS jsonb), now()
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("timelineId", preview.loopId())
                .addValue("userId", userId)
                .addValue("sequenceNumber", sequence)
                .addValue("eventType", eventType)
                .addValue("phase", step == null ? null : step.phase())
                .addValue("executionTarget", step == null || step.executionTarget() == null ? null : step.executionTarget().name())
                .addValue("toolName", step == null || step.toolName() == null ? null : step.toolName().wireName())
                .addValue("requiresApproval", step != null && step.requiresApproval())
                .addValue("mayMutate", step != null && step.mayMutate())
                .addValue("enabled", step != null && step.enabled())
                .addValue("details", toJson(details == null ? Map.of() : details)));
    }

    private String eventType(String phase) {
        return switch (phase == null ? "" : phase) {
            case "PLAN" -> "MODEL_DECISION_PREVIEW";
            case "SELECT_TOOL" -> "TOOL_SELECTION_PREVIEW";
            case "REQUEST_APPROVAL" -> "APPROVAL_CHECKPOINT_PREVIEW";
            case "OBSERVE" -> "OBSERVATION_WAIT_PREVIEW";
            case "COMPLETE_OR_PAUSE" -> "COMPLETION_DECISION_PREVIEW";
            default -> "LOOP_STEP_PREVIEW";
        };
    }

    private Map<String, Object> stepDetails(CodeAgentLoopStep step) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("index", step.index());
        details.put("phase", step.phase());
        details.put("action", step.action());
        details.put("executionTarget", step.executionTarget() == null ? null : step.executionTarget().name());
        details.put("toolName", step.toolName() == null ? null : step.toolName().wireName());
        details.put("requiresApproval", step.requiresApproval());
        details.put("mayMutate", step.mayMutate());
        details.put("enabled", step.enabled());
        details.put("stopOnFailure", step.stopOnFailure());
        return details;
    }

    private List<Map<String, Object>> stopOutcomePolicies(CodeAgentLoopPreviewResponse preview) {
        return List.of(
                stopOutcomePolicy(
                        preview,
                        "WEAK_EVIDENCE",
                        "ASK_FOR_CLARIFICATION",
                        "Ask for clarification before taking risky local action."
                ),
                stopOutcomePolicy(
                        preview,
                        "AGENT_UNAVAILABLE",
                        "WAIT_FOR_LOCAL_AGENT",
                        "Stop until the selected Local Agent is connected and workspace-ready."
                ),
                stopOutcomePolicy(
                        preview,
                        "TOOL_FAILED",
                        "REPORT_TOOL_FAILURE",
                        "Report the failed tool observation and keep mutation disabled."
                ),
                stopOutcomePolicy(
                        preview,
                        "APPROVAL_DENIED",
                        "REPORT_APPROVAL_DENIED",
                        "Stop after approval denial without creating claimable work."
                )
        );
    }

    private Map<String, Object> stopOutcomePolicy(CodeAgentLoopPreviewResponse preview, String stopKey, String outcome, String action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "REGISTERED");
        details.put("stopKey", stopKey);
        details.put("outcome", outcome);
        details.put("action", action);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("mutationEnabled", preview.mutationEnabled());
        return details;
    }

    private Map<String, Object> observationDetails(LocalAgentToolResponse response, Map<String, Object> requestInput) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", response.requestId().toString());
        details.put("sessionId", response.sessionId().toString());
        details.put("agentId", response.agentId() == null ? null : response.agentId().toString());
        details.put("workspaceId", response.workspaceId() == null ? null : response.workspaceId().toString());
        details.put("status", response.status().name());
        details.put("failureCode", response.failureCode() == null ? null : response.failureCode().name());
        details.put("error", response.error());
        details.put("warnings", response.warnings());
        details.put("sourceRequestId", stringValue(requestInput.get("sourceRequestId"), response.output().get("sourceRequestId")));
        details.put("releaseAttemptId", stringValue(requestInput.get("releaseAttemptId"), response.output().get("releaseAttemptId")));
        details.put("freshObservationOnly", booleanValue(requestInput.get("freshObservationOnly"), response.output().get("freshObservationOnly")));
        details.put("dryRun", booleanValue(requestInput.get("dryRunOnly"), response.output().get("dryRun")));
        details.put("mutationApplied", booleanValue(requestInput.get("mutationAllowed"), response.output().get("mutationApplied")));
        if (response.output().containsKey("repositoryVerification")) {
            details.put("repositoryVerification", response.output().get("repositoryVerification"));
        }
        if (response.output().containsKey("snapshotCreated")) {
            details.put("snapshotCreated", response.output().get("snapshotCreated"));
        }
        return details;
    }

    private Map<String, Object> approvalDetails(
            UUID requestId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            String approvalState,
            String status,
            Map<String, Object> requestInput
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", requestId == null ? null : requestId.toString());
        details.put("sessionId", sessionId == null ? null : sessionId.toString());
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("approvalState", approvalState);
        details.put("status", status);
        details.put("sourceRequestId", stringValue(requestInput.get("sourceRequestId"), null));
        details.put("releaseAttemptId", stringValue(requestInput.get("releaseAttemptId"), null));
        details.put("freshObservationOnly", booleanValue(requestInput.get("freshObservationOnly"), null));
        return details;
    }

    private Object stringValue(Object primary, Object fallback) {
        Object value = primary == null ? fallback : primary;
        return value == null ? null : String.valueOf(value);
    }

    private Object booleanValue(Object primary, Object fallback) {
        Object value = primary == null ? fallback : primary;
        return value instanceof Boolean ? value : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid Code Agent loop timeline JSON.", ex);
        }
    }

    private Map<String, Object> fromJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid Code Agent loop timeline event JSON.", ex);
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
    }

    private LocalAgentToolName toolName(String value) {
        return value == null || value.isBlank() ? null : LocalAgentToolName.fromWireName(value);
    }
}
