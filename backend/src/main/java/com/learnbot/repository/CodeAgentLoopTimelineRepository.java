package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessCheck;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentFailureCode;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.loop.CodeAgentLoopRecommendedActionFactory;
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
    private static final int MAX_PATCH_OBSERVATION_CONTENT_CHARS = 40_000;

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

    public UUID findSpaceId(UUID userId, UUID repositoryId, UUID loopId) {
        List<UUID> rows = jdbc.query("""
                SELECT space_id
                FROM code_agent_loop_timelines
                WHERE user_id = :userId
                  AND repository_id = :repositoryId
                  AND id = :loopId
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("repositoryId", repositoryId)
                .addValue("loopId", loopId), (rs, rowNum) -> rs.getObject("space_id", UUID.class));
        return rows.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Code Agent loop timeline was not found."));
    }

    public int appendRunStarted(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId,
            String instruction
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "RUNNING");
        details.put("decisionKey", "OBSERVATION_ACCEPTED");
        details.put("nextAction", "Queue the first read-only Local Agent observation.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("QUEUE_SELECTED_READ_ONLY"));
        details.put("instruction", instruction == null ? "" : instruction);
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("followUpToolSelectionEnabled", true);
        details.put("approvalRequiredBeforeSideEffects", true);
        details.put("requestCreationEnabled", true);
        details.put("pushEnabled", true);
        details.put("claimEnabled", true);
        details.put("mutationEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOOP_NEXT_DECISION_RECORDED",
                "OBSERVE",
                AgentExecutionTarget.SERVER_LOCAL,
                null,
                false,
                details
        );
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

    public int appendReadOnlyRequestQueued(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID requestId,
            LocalAgentToolRequest request
    ) {
        Map<String, Object> details = requestDetails(request, "QUEUED", null);
        details.put("requestId", requestId == null ? null : requestId.toString());
        details.put("decisionKey", "WAIT_FOR_LOCAL_AGENT_OBSERVATION");
        details.put("nextAction", "Wait for the Local Agent to complete the queued read-only observation before advancing again.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("WAIT_FOR_LOCAL_AGENT"));
        details.put("readOnlyRequestQueued", true);
        details.put("requestCreationEnabled", true);
        details.put("enqueueEnabled", true);
        details.put("pushEnabled", true);
        details.put("claimEnabled", true);
        details.put("mutationEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_READ_ONLY_REQUEST_QUEUED",
                "OBSERVE",
                request.executionTarget(),
                request.toolName(),
                false,
                details
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
        Map<String, Object> details = approvalDetails(requestId, sessionId, agentId, workspaceId, approvalState, status, requestInput);
        details.put("decisionKey", "APPROVED".equals(approvalState) && "APPROVED_HELD".equals(status)
                ? "APPROVAL_APPROVED_HELD"
                : "APPROVAL_DECISION_RECORDED");
        details.put("nextAction", "APPROVED".equals(approvalState) && "APPROVED_HELD".equals(status)
                ? "Inspect release readiness and queue fresh release-attempt observations before any claimable mutation transition."
                : "Wait for approval completion or stop if approval was denied.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("APPROVED".equals(approvalState) && "APPROVED_HELD".equals(status)
                ? "CHECK_ENQUEUE_REFUSAL"
                : "REJECTED".equals(status) ? "STOP_AND_REPORT" : "ASK_USER"));
        details.put("approvalRequestHeld", "APPROVED_HELD".equals(status));
        details.put("releaseRequired", "APPROVED".equals(approvalState));
        details.put("releaseGateEnabled", false);
        details.put("requestCreationEnabled", false);
        details.put("pushEnabled", false);
        details.put("claimEnabled", false);
        details.put("mutationEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_APPROVAL_DECISION",
                "REQUEST_APPROVAL",
                executionTarget,
                toolName,
                true,
                details
        );
    }

    public int appendApprovalRequestCreated(
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
        Map<String, Object> details = approvalDetails(requestId, sessionId, agentId, workspaceId, approvalState, status, requestInput);
        boolean validatedDryRunIntent = Boolean.TRUE.equals(booleanValue(requestInput.get("validatedDryRunIntent"), null));
        details.put("decisionKey", validatedDryRunIntent ? "VALIDATED_DRY_RUN_INTENT_REVIEW" : "APPROVAL_REQUEST_CREATED");
        details.put("nextAction", validatedDryRunIntent
                ? "Review the persisted validated dry-run intent before any future claimable non-mutating dry-run."
                : "Wait for explicit user approval before release, claim, or mutation.");
        details.put("approvalRequestCreated", true);
        details.put("validatedDryRunIntent", validatedDryRunIntent);
        details.put("reviewSurface", validatedDryRunIntent ? "CODE_WORKSPACE_LOOP_REVIEW" : "LOCAL_AGENT_APPROVAL");
        details.put("releaseRequired", true);
        details.put("releaseEvidenceRequired", true);
        details.put("releaseGateEnabled", false);
        details.put("requestCreationEnabled", false);
        details.put("pushEnabled", false);
        details.put("claimEnabled", false);
        details.put("mutationEnabled", false);
        details.put("dryRunIntentReviewRequired", validatedDryRunIntent);
        details.put("claimable", false);
        details.put("dryRunOnly", booleanValue(requestInput.get("dryRunOnly"), null));
        details.put("mutationAllowed", booleanValue(requestInput.get("mutationAllowed"), null));
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_APPROVAL_REQUEST_CREATED",
                "REQUEST_APPROVAL",
                executionTarget,
                toolName,
                true,
                details
        );
    }

    public int appendReleaseBoundaryRefusal(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            AgentExecutionTarget executionTarget,
            LocalAgentToolName toolName,
            LocalAgentPatchReleaseBoundaryResponse boundary,
            Map<String, Object> requestInput
    ) {
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                "COMPLETE_OR_PAUSE",
                executionTarget,
                toolName,
                true,
                releaseBoundaryDetails(boundary, sessionId, agentId, workspaceId, requestInput)
        );
    }

    public int appendFreshObservationRequestsEnqueued(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            AgentExecutionTarget executionTarget,
            LocalAgentToolName sourceToolName,
            List<LocalAgentQueuedToolRequest> queuedRequests,
            Map<String, Object> requestInput
    ) {
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED",
                "OBSERVE",
                executionTarget,
                sourceToolName,
                true,
                freshObservationEnqueueDetails(
                        sourceRequestId,
                        releaseAttemptId,
                        sessionId,
                        agentId,
                        workspaceId,
                        queuedRequests,
                        requestInput
                )
        );
    }

    public int appendFreshObservationEvidenceComplete(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            AgentExecutionTarget executionTarget,
            LocalAgentToolName sourceToolName,
            Map<String, Object> evidenceCompleteness,
            List<Map<String, Object>> evidenceStatus
    ) {
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE",
                "COMPLETE_OR_PAUSE",
                executionTarget,
                sourceToolName,
                true,
                freshObservationEvidenceCompleteDetails(
                        sourceRequestId,
                        releaseAttemptId,
                        sessionId,
                        agentId,
                        workspaceId,
                        evidenceCompleteness,
                        evidenceStatus
                )
        );
    }

    public int appendReleaseReadinessRefreshed(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            AgentExecutionTarget executionTarget,
            LocalAgentToolName sourceToolName,
            LocalAgentPatchExecutionReadinessResponse readiness
    ) {
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_RELEASE_READINESS_REFRESHED",
                "COMPLETE_OR_PAUSE",
                executionTarget,
                sourceToolName,
                true,
                releaseReadinessRefreshDetails(
                        sourceRequestId,
                        releaseAttemptId,
                        sessionId,
                        agentId,
                        workspaceId,
                        readiness
                )
        );
    }

    public int appendApprovedExecutionFlowCompleted(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> approvedFlowInspection,
            Map<String, Object> finalResultHandoff
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED");
        details.put("sourceRequestId", sourceRequestId == null ? null : sourceRequestId.toString());
        details.put("releaseAttemptId", releaseAttemptId == null ? null : releaseAttemptId.toString());
        details.put("sessionId", sessionId == null ? null : sessionId.toString());
        details.put("userId", userId == null ? null : userId.toString());
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("approvedFlowInspection", approvedFlowInspection == null ? Map.of() : approvedFlowInspection);
        details.put("requestIdSource", approvedFlowInspection == null ? null : approvedFlowInspection.get("requestIdSource"));
        details.put("stepCount", approvedFlowInspection == null ? null : approvedFlowInspection.get("stepCount"));
        details.put("ordered", approvedFlowInspection == null ? null : approvedFlowInspection.get("ordered"));
        details.put("identityConsistent", approvedFlowInspection == null ? null : approvedFlowInspection.get("identityConsistent"));
        details.put("releaseAttemptLinked", approvedFlowInspection == null ? null : approvedFlowInspection.get("releaseAttemptLinked"));
        details.put("approvalRequestLinked", approvedFlowInspection == null ? null : approvedFlowInspection.get("approvalRequestLinked"));
        details.put("postRetryVerification", approvedFlowInspection == null ? Map.of() : approvedFlowInspection.get("postRetryVerification"));
        details.put("allTerminal", approvedFlowInspection == null ? null : approvedFlowInspection.get("allTerminal"));
        details.put("allSucceeded", allSucceeded(approvedFlowInspection));
        details.put("finalResultHandoff", finalResultHandoff == null ? Map.of() : finalResultHandoff);
        details.put("finalMutationReportSummaryStatus", finalResultHandoff == null ? null : finalResultHandoff.get("finalMutationReportSummaryStatus"));
        details.put("postRetryVerificationPassed", finalResultHandoff == null ? null : finalResultHandoff.get("postRetryVerificationPassed"));
        details.put("postRetryVerificationPartialReindexMarkerRequired", finalResultHandoff == null ? null : finalResultHandoff.get("postRetryVerificationPartialReindexMarkerRequired"));
        details.put("ragFreshnessMarkerStatus", finalResultHandoff == null ? null : finalResultHandoff.get("ragFreshnessMarkerStatus"));
        details.put("partialReindexPlanStatus", finalResultHandoff == null ? null : finalResultHandoff.get("partialReindexPlanStatus"));
        details.put("partialReindexEnqueueBoundaryStatus", finalResultHandoff == null ? null : finalResultHandoff.get("partialReindexEnqueueBoundaryStatus"));
        details.put("partialReindexEnqueueReady", finalResultHandoff == null ? null : finalResultHandoff.get("partialReindexEnqueueReady"));
        details.put("finalAnswerPublicationHandoffStatus", finalResultHandoff == null ? null : finalResultHandoff.get("finalAnswerPublicationHandoffStatus"));
        details.put("acknowledgementSaveHandoffStatus", finalResultHandoff == null ? null : finalResultHandoff.get("acknowledgementSaveHandoffStatus"));
        details.put("nextAction", "Report the completed approved Local Agent execution flow while final result publication and acknowledgement save remain disabled.");
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("ragFreshnessUpdateEnabled", false);
        details.put("followUpMutationEnabled", false);
        details.put("mutationEnabled", false);
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED",
                "COMPLETE_OR_PAUSE",
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                true,
                details
        );
    }

    public int appendFinalResultPublished(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID savedAnswerId,
            Map<String, Object> finalResult
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "FINAL_RESULT_PUBLISHED");
        details.put("savedAnswerId", savedAnswerId == null ? null : savedAnswerId.toString());
        details.put("finalResult", finalResult == null ? Map.of() : finalResult);
        details.put("sourceRequestId", finalResult == null ? null : finalResult.get("sourceRequestId"));
        details.put("releaseAttemptId", finalResult == null ? null : finalResult.get("releaseAttemptId"));
        details.put("staleIndexDisclosure", finalResult == null ? null : finalResult.get("staleIndexDisclosure"));
        details.put("finalResultEnabled", true);
        details.put("publicationEnabled", true);
        details.put("finalAnswerGenerationEnabled", true);
        details.put("finalAnswerDeliveryEnabled", true);
        details.put("acknowledgementEnabled", true);
        details.put("acknowledgementSaveEnabled", true);
        details.put("ragFreshnessUpdateEnabled", false);
        details.put("partialReindexEnabled", false);
        details.put("followUpMutationEnabled", false);
        details.put("mutationEnabled", false);
        details.put("nextAction", "Final result was published and saved. RAG freshness still requires partial reindex or explicit stale-index disclosure.");
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "CODE_AGENT_FINAL_RESULT_PUBLISHED",
                "COMPLETE",
                AgentExecutionTarget.SERVER_LOCAL,
                null,
                false,
                details
        );
    }

    public int appendStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            String stopKey,
            String outcome,
            String action,
            Map<String, Object> sourceDetails
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "RECORDED");
        details.put("stopKey", stopKey);
        details.put("outcome", outcome);
        details.put("action", action);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("mutationEnabled", false);
        details.put("source", sourceDetails == null ? Map.of() : sourceDetails);
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "STOP_OUTCOME_RECORDED",
                "COMPLETE_OR_PAUSE",
                null,
                null,
                false,
                details
        );
    }

    public int appendNextDecision(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentToolResponse response,
            Map<String, Object> requestInput
    ) {
        boolean succeeded = response.status() == LocalAgentToolStatus.SUCCEEDED || successfulPatchDryRunObservation(response);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "RECORDED");
        details.put("decisionKey", succeeded ? "OBSERVATION_ACCEPTED" : "STOP_AFTER_OBSERVATION");
        details.put("nextAction", succeeded
                ? "Evaluate the Local Agent observation before selecting another typed tool or asking for approval."
                : "Stop the loop after the failed Local Agent observation and report the blocking state.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create(succeeded ? "PREVIEW_RUNNER_STEP" : "STOP_AND_REPORT"));
        details.put("observationStatus", response.status() == null ? null : response.status().name());
        details.put("requestId", response.requestId() == null ? null : response.requestId().toString());
        details.put("sourceRequestId", stringValue(requestInput.get("sourceRequestId"), response.output().get("sourceRequestId")));
        details.put("releaseAttemptId", stringValue(requestInput.get("releaseAttemptId"), response.output().get("releaseAttemptId")));
        details.put("followUpToolSelectionEnabled", succeeded);
        details.put("approvalRequiredBeforeSideEffects", true);
        details.put("requestCreationEnabled", false);
        details.put("pushEnabled", false);
        details.put("claimEnabled", false);
        details.put("mutationEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("source", observationDetails(response, requestInput));
        return appendLatestEvent(
                userId,
                repositoryId,
                loopId,
                "LOOP_NEXT_DECISION_RECORDED",
                "COMPLETE_OR_PAUSE",
                AgentExecutionTarget.SERVER_LOCAL,
                null,
                false,
                details
        );
    }

    public int appendToolFailedStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentToolResponse response,
            Map<String, Object> requestInput
    ) {
        return appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "TOOL_FAILED",
                "REPORT_TOOL_FAILURE",
                "Report the failed tool observation and keep mutation disabled.",
                observationDetails(response, requestInput)
        );
    }

    public int appendTimedOutStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentToolResponse response,
            Map<String, Object> requestInput
    ) {
        return appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "TIMEOUT",
                "REPORT_TIMEOUT",
                "Report the timed-out Local Agent observation and keep mutation disabled.",
                observationDetails(response, requestInput)
        );
    }

    public int appendCancellationStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentToolResponse response,
            Map<String, Object> requestInput
    ) {
        return appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "CANCELLATION",
                "REPORT_CANCELLATION",
                "Report the cancelled Local Agent observation and keep mutation disabled.",
                observationDetails(response, requestInput)
        );
    }

    public int appendDisconnectedStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentToolResponse response,
            Map<String, Object> requestInput
    ) {
        return appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "AGENT_UNAVAILABLE",
                "WAIT_FOR_LOCAL_AGENT",
                "Stop until the selected Local Agent is connected and workspace-ready.",
                observationDetails(response, requestInput)
        );
    }

    public int appendApprovalDeniedStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID requestId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            String approvalState,
            String status,
            Map<String, Object> requestInput
    ) {
        return appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "APPROVAL_DENIED",
                "REPORT_APPROVAL_DENIED",
                "Stop after approval denial without creating claimable work.",
                approvalDetails(requestId, sessionId, agentId, workspaceId, approvalState, status, requestInput)
        );
    }

    public int appendAgentUnavailableStopOutcome(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentToolRequest request
    ) {
        return appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "AGENT_UNAVAILABLE",
                "WAIT_FOR_LOCAL_AGENT",
                "Stop until the selected Local Agent is connected and workspace-ready.",
                requestDetails(request, "AGENT_UNAVAILABLE", "Local Agent is not connected.")
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

    private boolean allSucceeded(Map<String, Object> approvedFlowInspection) {
        if (approvedFlowInspection == null || !(approvedFlowInspection.get("steps") instanceof List<?> steps)) {
            return false;
        }
        return !steps.isEmpty() && steps.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .allMatch(step -> "SUCCEEDED".equals(String.valueOf(step.get("status"))));
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
        details.put("outputSummary", outputSummary(response));
        return details;
    }

    public boolean successfulPatchDryRunObservation(LocalAgentToolResponse response) {
        Map<String, Object> output = response.output() == null ? Map.of() : response.output();
        return response.toolName() == LocalAgentToolName.PATCH_APPLY
                && response.status() == LocalAgentToolStatus.REJECTED
                && response.failureCode() == LocalAgentFailureCode.UNSAFE_TOOL
                && Boolean.TRUE.equals(output.get("dryRun"))
                && Boolean.TRUE.equals(output.get("preflightPassed"))
                && Boolean.TRUE.equals(output.get("snapshotCreated"))
                && Boolean.FALSE.equals(output.get("mutationApplied"))
                && dryRunContextMatches(output);
    }

    private boolean dryRunContextMatches(Map<String, Object> output) {
        if (!(output.get("files") instanceof List<?> files) || files.isEmpty()) {
            return false;
        }
        return files.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .allMatch(file -> Boolean.TRUE.equals(file.get("contextMatches")));
    }

    private Map<String, Object> outputSummary(LocalAgentToolResponse response) {
        Map<String, Object> output = response.output() == null ? Map.of() : response.output();
        Map<String, Object> summary = new LinkedHashMap<>();
        if (response.toolName() == LocalAgentToolName.WORKSPACE_SEARCH) {
            summary.put("query", output.get("query"));
            summary.put("matchCount", output.get("matchCount"));
            summary.put("matches", limitedPathMaps(output.get("matches"), 30, true));
        } else if (response.toolName() == LocalAgentToolName.WORKSPACE_TREE) {
            summary.put("entryCount", output.get("entryCount"));
            summary.put("truncated", output.get("truncated"));
            summary.put("entries", limitedPathMaps(output.get("entries"), 80, false));
        } else if (response.toolName() == LocalAgentToolName.FILE_READ) {
            summary.put("relativePath", output.get("relativePath"));
            summary.put("bytes", output.get("bytes"));
            summary.put("returnedBytes", output.get("returnedBytes"));
            summary.put("truncated", output.get("truncated"));
            summary.put("contentPreview", preview(output.get("content"), 1200));
            String contentForPatch = patchObservationContent(output.get("content"), output.get("truncated"));
            summary.put("contentForPatchAvailable", contentForPatch != null);
            if (contentForPatch != null) {
                summary.put("contentForPatch", contentForPatch);
            }
        } else if (response.toolName() == LocalAgentToolName.GIT_STATUS || response.toolName() == LocalAgentToolName.GIT_DIFF) {
            summary.put("clean", output.get("clean"));
            summary.put("branch", output.get("branch"));
            summary.put("changedFiles", output.get("changedFiles"));
            summary.put("diffPreview", preview(output.get("diff"), 1200));
        }
        return java.util.Collections.unmodifiableMap(summary);
    }

    private String patchObservationContent(Object value, Object truncated) {
        if (!(value instanceof String content) || Boolean.TRUE.equals(truncated)) {
            return null;
        }
        return content.length() <= MAX_PATCH_OBSERVATION_CONTENT_CHARS ? content : null;
    }

    private List<Map<String, Object>> limitedPathMaps(Object value, int limit, boolean includeSnippet) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object path = map.get("path");
            if (path == null || String.valueOf(path).isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", String.valueOf(path));
            if (map.containsKey("type")) {
                row.put("type", map.get("type"));
            }
            if (map.containsKey("bytes")) {
                row.put("bytes", map.get("bytes"));
            }
            if (includeSnippet) {
                row.put("line", map.get("line"));
                row.put("column", map.get("column"));
                row.put("snippet", preview(map.get("snippet"), 240));
            }
            result.add(java.util.Collections.unmodifiableMap(row));
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private String preview(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
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
        details.put("validatedDryRunIntent", booleanValue(requestInput.get("validatedDryRunIntent"), null));
        details.put("dryRunIntentPersisted", booleanValue(requestInput.get("dryRunIntentPersisted"), null));
        details.put("requestPersisted", booleanValue(requestInput.get("requestPersisted"), null));
        details.put("queueEnabled", booleanValue(requestInput.get("queueEnabled"), null));
        details.put("pushEnabled", booleanValue(requestInput.get("pushEnabled"), null));
        details.put("claimable", booleanValue(requestInput.get("claimable"), null));
        details.put("dryRunOnly", booleanValue(requestInput.get("dryRunOnly"), null));
        details.put("mutationAllowed", booleanValue(requestInput.get("mutationAllowed"), null));
        details.put("approvalRequestId", stringValue(requestInput.get("approvalRequestId"), null));
        details.put("targetFiles", requestInput.getOrDefault("targetFiles", List.of()));
        details.put("targetSelection", requestInput.getOrDefault("targetSelection", Map.of()));
        return details;
    }

    private Map<String, Object> releaseBoundaryDetails(
            LocalAgentPatchReleaseBoundaryResponse boundary,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> requestInput
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "RECORDED");
        details.put("decisionKey", "RELEASE_BOUNDARY_REFUSED");
        details.put("nextAction", "Report that release was refused and mutation remains disabled.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("STOP_AND_REPORT"));
        details.put("requestId", boundary.requestId() == null ? null : boundary.requestId().toString());
        details.put("sessionId", sessionId == null ? null : sessionId.toString());
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("boundaryStatus", boundary.status());
        details.put("actionMode", boundary.actionMode());
        details.put("message", boundary.message());
        details.put("blockingReasons", boundary.blockingReasons());
        details.put("releaseGateEnabled", boundary.releaseGateEnabled());
        details.put("requestCreationEnabled", boundary.requestCreationEnabled());
        details.put("pushEnabled", boundary.pushEnabled());
        details.put("claimEnabled", boundary.claimEnabled());
        details.put("claimable", boundary.claimable());
        details.put("mutationEnabled", boundary.mutationAllowed());
        details.put("mutationAllowed", boundary.mutationAllowed());
        details.put("applyEnabled", boundary.applyEnabled());
        details.put("testEnabled", boundary.testEnabled());
        details.put("rollbackRestoreEnabled", boundary.rollbackRestoreEnabled());
        details.put("ragFreshnessUpdateEnabled", boundary.ragFreshnessUpdateEnabled());
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("sourceRequestId", stringValue(requestInput.get("sourceRequestId"), null));
        details.put("releaseAttemptId", stringValue(requestInput.get("releaseAttemptId"), null));
        details.put("patchExecutionGate", boundary.patchExecutionGate());
        details.put("releaseEnablementChecklist", boundary.releaseEnablementChecklist());
        details.put("releaseAttemptModel", boundary.releaseAttemptModel());
        return details;
    }

    private Map<String, Object> freshObservationEnqueueDetails(
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            List<LocalAgentQueuedToolRequest> queuedRequests,
            Map<String, Object> requestInput
    ) {
        List<LocalAgentQueuedToolRequest> requests = queuedRequests == null ? List.of() : queuedRequests;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "FRESH_OBSERVATIONS_ENQUEUED");
        details.put("decisionKey", "WAIT_FOR_FRESH_OBSERVATION_RESULTS");
        details.put("nextAction", "Wait for fresh release-attempt Local Agent observations before any release or claimable mutation transition.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("CHECK_ENQUEUE_REFUSAL"));
        details.put("sourceRequestId", sourceRequestId == null ? stringValue(requestInput.get("sourceRequestId"), null) : sourceRequestId.toString());
        details.put("releaseAttemptId", releaseAttemptId == null ? stringValue(requestInput.get("releaseAttemptId"), null) : releaseAttemptId.toString());
        details.put("sessionId", sessionId == null ? null : sessionId.toString());
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("queuedRequestCount", requests.size());
        details.put("queuedRequestIds", requests.stream().map(request -> request.requestId().toString()).toList());
        details.put("queuedToolNames", requests.stream().map(request -> request.request().toolName().wireName()).toList());
        details.put("queuedApprovalStates", requests.stream().map(request -> request.request().approvalState().name()).toList());
        details.put("freshObservationOnly", true);
        details.put("observationResultsRequired", true);
        details.put("releaseGateEnabled", false);
        details.put("sourcePatchClaimEnabled", false);
        details.put("claimEnabled", false);
        details.put("mutationEnabled", false);
        details.put("verificationCommandExecutionEnabled", false);
        details.put("rollbackRestoreEnabled", false);
        details.put("ragFreshnessUpdateEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("finalAnswerGenerationEnabled", false);
        details.put("deliveryEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("source", requestInput == null ? Map.of() : requestInput);
        return details;
    }

    private Map<String, Object> freshObservationEvidenceCompleteDetails(
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> evidenceCompleteness,
            List<Map<String, Object>> evidenceStatus
    ) {
        Map<String, Object> completeness = evidenceCompleteness == null ? Map.of() : evidenceCompleteness;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED");
        details.put("decisionKey", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED");
        details.put("nextAction", "Fresh release-attempt evidence is complete; inspect release readiness while release, claim, and mutation remain disabled.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("CHECK_ENQUEUE_REFUSAL"));
        details.put("sourceRequestId", sourceRequestId == null ? null : sourceRequestId.toString());
        details.put("releaseAttemptId", releaseAttemptId == null ? null : releaseAttemptId.toString());
        details.put("sessionId", sessionId == null ? null : sessionId.toString());
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("evidenceComplete", completeness.get("complete"));
        details.put("requiredCount", completeness.get("requiredCount"));
        details.put("linkedCount", completeness.get("linkedCount"));
        details.put("missingCount", completeness.get("missingCount"));
        details.put("sourceOnlyFallbackCount", completeness.get("sourceOnlyFallbackCount"));
        details.put("blockingCount", completeness.get("blockingCount"));
        details.put("linkedKeys", completeness.get("linkedKeys"));
        details.put("blockingKeys", completeness.get("blockingKeys"));
        details.put("freshObservationEvidenceCompleteness", completeness);
        details.put("freshObservationEvidenceStatus", evidenceStatus == null ? List.of() : evidenceStatus);
        details.put("releaseGateEnabled", false);
        details.put("sourcePatchClaimEnabled", false);
        details.put("claimEnabled", false);
        details.put("mutationEnabled", false);
        details.put("verificationCommandExecutionEnabled", false);
        details.put("rollbackRestoreEnabled", false);
        details.put("ragFreshnessUpdateEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("finalAnswerGenerationEnabled", false);
        details.put("deliveryEnabled", false);
        details.put("acknowledgementEnabled", false);
        return details;
    }

    private Map<String, Object> releaseReadinessRefreshDetails(
            UUID sourceRequestId,
            UUID releaseAttemptId,
            UUID sessionId,
            UUID agentId,
            UUID workspaceId,
            LocalAgentPatchExecutionReadinessResponse readiness
    ) {
        Map<String, Object> patchReleaseReadiness = readiness == null || readiness.patchReleaseReadiness() == null
                ? Map.of()
                : readiness.patchReleaseReadiness();
        Map<String, Object> patchExecutionGate = readiness == null || readiness.patchExecutionGate() == null
                ? Map.of()
                : readiness.patchExecutionGate();
        Map<String, Object> latestAttempt = readiness == null || readiness.releaseAttemptModel() == null
                ? Map.of()
                : readiness.releaseAttemptModel().latestAttempt();
        Map<String, Object> finalReadiness = latestAttempt.get("releaseAttemptFinalReadiness") instanceof Map<?, ?> value
                ? value.entrySet().stream().collect(
                LinkedHashMap::new,
                (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()),
                LinkedHashMap::putAll
        )
                : Map.of();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED");
        details.put("decisionKey", "RELEASE_READINESS_REFRESHED_RELEASE_GATED");
        details.put("nextAction", "Release readiness was refreshed from fresh evidence; release, claim, and mutation remain disabled.");
        details.put("recommendedAction", CodeAgentLoopRecommendedActionFactory.create("REVIEW_RELEASE_REFUSAL"));
        details.put("sourceRequestId", sourceRequestId == null ? null : sourceRequestId.toString());
        details.put("releaseAttemptId", releaseAttemptId == null ? null : releaseAttemptId.toString());
        details.put("sessionId", sessionId == null ? null : sessionId.toString());
        details.put("agentId", agentId == null ? null : agentId.toString());
        details.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        details.put("readyToRelease", readiness != null && readiness.readyToRelease());
        details.put("readinessMessage", readiness == null ? null : readiness.message());
        details.put("warningCount", readiness == null || readiness.warnings() == null ? 0 : readiness.warnings().size());
        details.put("warnings", readiness == null || readiness.warnings() == null ? List.of() : readiness.warnings());
        details.put("checkCount", readiness == null || readiness.checks() == null ? 0 : readiness.checks().size());
        details.put("failedCheckKeys", readiness == null || readiness.checks() == null
                ? List.of()
                : readiness.checks().stream()
                .filter(check -> !check.passed())
                .map(LocalAgentPatchExecutionReadinessCheck::key)
                .toList());
        details.put("checks", readiness == null || readiness.checks() == null
                ? List.of()
                : readiness.checks().stream().map(this::readinessCheckDetails).toList());
        details.put("patchReleaseStatus", patchReleaseReadiness.get("status"));
        details.put("patchReleasePreconditionsPassed", patchReleaseReadiness.get("preconditionsPassed"));
        details.put("patchExecutionGateStatus", patchExecutionGate.get("status"));
        details.put("patchExecutionPreconditionsPassed", patchExecutionGate.get("preconditionsPassed"));
        details.put("releaseAttemptFinalReadiness", finalReadiness);
        details.put("releaseAttemptReady", finalReadiness.get("releaseAttemptReady"));
        details.put("freshObservationEvidenceComplete", finalReadiness.get("freshObservationEvidenceComplete"));
        details.put("releaseGateEnabled", false);
        details.put("sourcePatchClaimEnabled", false);
        details.put("claimEnabled", false);
        details.put("claimable", false);
        details.put("mutationEnabled", false);
        details.put("mutationAllowed", false);
        details.put("verificationCommandExecutionEnabled", false);
        details.put("rollbackRestoreEnabled", false);
        details.put("ragFreshnessUpdateEnabled", false);
        details.put("finalResultEnabled", false);
        details.put("publicationEnabled", false);
        details.put("finalAnswerGenerationEnabled", false);
        details.put("deliveryEnabled", false);
        details.put("acknowledgementEnabled", false);
        details.put("patchReleaseReadiness", patchReleaseReadiness);
        details.put("patchExecutionGate", patchExecutionGate);
        details.put("releaseAttemptModel", readiness == null ? null : readiness.releaseAttemptModel());
        return details;
    }

    private Map<String, Object> readinessCheckDetails(LocalAgentPatchExecutionReadinessCheck check) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("key", check.key());
        details.put("passed", check.passed());
        details.put("message", check.message());
        return details;
    }

    private Map<String, Object> requestDetails(LocalAgentToolRequest request, String status, String error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sessionId", request.sessionId() == null ? null : request.sessionId().toString());
        details.put("agentId", request.agentId() == null ? null : request.agentId().toString());
        details.put("workspaceId", request.workspaceId() == null ? null : request.workspaceId().toString());
        details.put("executionTarget", request.executionTarget() == null ? null : request.executionTarget().name());
        details.put("toolName", request.toolName() == null ? null : request.toolName().wireName());
        details.put("approvalState", request.approvalState() == null ? null : request.approvalState().name());
        details.put("status", status);
        details.put("error", error);
        details.put("sourceRequestId", stringValue(request.input().get("sourceRequestId"), null));
        details.put("releaseAttemptId", stringValue(request.input().get("releaseAttemptId"), null));
        details.put("freshObservationOnly", booleanValue(request.input().get("freshObservationOnly"), null));
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
