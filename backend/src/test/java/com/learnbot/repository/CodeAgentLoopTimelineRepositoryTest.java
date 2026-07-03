package com.learnbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopStopCondition;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessCheck;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentPatchReleaseAttemptModel;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentLoopTimelineRepositoryTest {

    @Test
    void createPreviewPersistsAuditOnlyLoopTimelineFields() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID loopId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        CodeAgentLoopPreviewResponse preview = new CodeAgentLoopPreviewResponse(
                loopId,
                repositoryId,
                spaceId,
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                List.of(new CodeAgentLoopStep(
                        1,
                        "PLAN",
                        "Retrieve code evidence.",
                        AgentExecutionTarget.SERVER_LOCAL,
                        null,
                        false,
                        false,
                        true,
                        "Stop on weak evidence."
                )),
                List.of(new CodeAgentLoopStopCondition("MUTATION_DISABLED", "Do not apply patches.")),
                List.of("Preview only.")
        );

        repository.createPreview(userId, "fix this bug", preview);

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(11)).update(anyString(), params.capture());
        MapSqlParameterSource values = params.getAllValues().get(0);
        assertThat(values.getValue("id")).isEqualTo(loopId);
        assertThat(values.getValue("userId")).isEqualTo(userId);
        assertThat(values.getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(values.getValue("spaceId")).isEqualTo(spaceId);
        assertThat(values.getValue("instruction")).isEqualTo("fix this bug");
        assertThat(values.getValue("status")).isEqualTo("PREVIEW_ONLY");
        assertThat(values.getValue("maxSteps")).isEqualTo(6);
        assertThat(values.getValue("timeoutSeconds")).isEqualTo(120);
        assertThat(values.getValue("cancellationEnabled")).isEqualTo(false);
        assertThat(values.getValue("timelinePersistenceEnabled")).isEqualTo(true);
        assertThat(values.getValue("mutationEnabled")).isEqualTo(false);
        assertThat((String) values.getValue("steps")).contains("PLAN").contains("mayMutate");
        assertThat((String) values.getValue("stopConditions")).contains("MUTATION_DISABLED");
        assertThat((String) values.getValue("warnings")).contains("Preview only.");
        MapSqlParameterSource createdEvent = params.getAllValues().get(1);
        assertThat(createdEvent.getValue("timelineId")).isEqualTo(loopId);
        assertThat(createdEvent.getValue("sequenceNumber")).isEqualTo(1);
        assertThat(createdEvent.getValue("eventType")).isEqualTo("LOOP_PREVIEW_CREATED");
        assertThat(createdEvent.getValue("mayMutate")).isEqualTo(false);
        MapSqlParameterSource stepEvent = params.getAllValues().get(2);
        assertThat(stepEvent.getValue("sequenceNumber")).isEqualTo(2);
        assertThat(stepEvent.getValue("eventType")).isEqualTo("MODEL_DECISION_PREVIEW");
        assertThat(stepEvent.getValue("phase")).isEqualTo("PLAN");
        assertThat(stepEvent.getValue("executionTarget")).isEqualTo("SERVER_LOCAL");
        assertThat(stepEvent.getValue("requiresApproval")).isEqualTo(false);
        assertThat(stepEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat(stepEvent.getValue("enabled")).isEqualTo(true);
        assertThat((String) stepEvent.getValue("details")).contains("Retrieve code evidence.");
        MapSqlParameterSource stopEvent = params.getAllValues().get(3);
        assertThat(stopEvent.getValue("eventType")).isEqualTo("STOP_CONDITIONS_REGISTERED");
        assertThat((String) stopEvent.getValue("details")).contains("MUTATION_DISABLED");
        MapSqlParameterSource timeoutEvent = params.getAllValues().get(4);
        assertThat(timeoutEvent.getValue("eventType")).isEqualTo("TIMEOUT_POLICY_REGISTERED");
        assertThat(timeoutEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) timeoutEvent.getValue("details")).contains("\"timeoutSeconds\":120");
        MapSqlParameterSource cancellationEvent = params.getAllValues().get(5);
        assertThat(cancellationEvent.getValue("eventType")).isEqualTo("CANCELLATION_POLICY_REGISTERED");
        assertThat(cancellationEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) cancellationEvent.getValue("details")).contains("\"cancellationEnabled\":false");
        MapSqlParameterSource finalResultEvent = params.getAllValues().get(6);
        assertThat(finalResultEvent.getValue("eventType")).isEqualTo("FINAL_RESULT_POLICY_REGISTERED");
        assertThat(finalResultEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) finalResultEvent.getValue("details")).contains("\"finalResultEnabled\":false");
        MapSqlParameterSource weakEvidenceEvent = params.getAllValues().get(7);
        assertThat(weakEvidenceEvent.getValue("eventType")).isEqualTo("STOP_OUTCOME_POLICY_REGISTERED");
        assertThat(weakEvidenceEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) weakEvidenceEvent.getValue("details")).contains("\"stopKey\":\"WEAK_EVIDENCE\"");
        MapSqlParameterSource approvalDeniedEvent = params.getAllValues().get(10);
        assertThat(approvalDeniedEvent.getValue("eventType")).isEqualTo("STOP_OUTCOME_POLICY_REGISTERED");
        assertThat(approvalDeniedEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) approvalDeniedEvent.getValue("details")).contains("\"stopKey\":\"APPROVAL_DENIED\"");
    }

    @Test
    void findRecentScopesReadOnlyTimelinesToUserAndRepository() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                ArgumentMatchers.any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<CodeAgentLoopTimelineSummary>>any()
        )).thenReturn(List.of());

        var result = repository.findRecent(userId, repositoryId, 5);

        assertThat(result).isEmpty();
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(anyString(), params.capture(), ArgumentMatchers.<RowMapper<CodeAgentLoopTimelineSummary>>any());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("limit")).isEqualTo(5);
    }

    @Test
    void appendObservationResultPersistsAuditOnlyEventForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                requestId,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "dryRun", true,
                        "mutationApplied", false,
                        "snapshotCreated", true
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("dry-run only")
        );

        repository.appendObservationResult(userId, repositoryId, loopId, response, Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "freshObservationOnly", true,
                "dryRunOnly", true,
                "mutationAllowed", false
        ));

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_OBSERVATION_RESULT");
        assertThat(params.getValue().getValue("phase")).isEqualTo("OBSERVE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains(requestId.toString())
                .contains(sourceRequestId.toString())
                .contains("\"freshObservationOnly\":true")
                .contains("\"dryRun\":true")
                .contains("\"mutationApplied\":false")
                .contains("\"snapshotCreated\":true");
    }

    @Test
    void appendObservationResultKeepsBoundedFileReadContentForPatchProposal() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.FILE_READ,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "relativePath", "notes.txt",
                        "bytes", 12,
                        "returnedBytes", 12,
                        "truncated", false,
                        "content", "hello world\n"
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );

        repository.appendObservationResult(userId, repositoryId, loopId, response, Map.of());

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"relativePath\":\"notes.txt\"")
                .contains("\"contentForPatchAvailable\":true")
                .contains("\"contentForPatch\":\"hello world\\n\"");
    }

    @Test
    void appendApprovalDecisionPersistsAuditOnlyDecisionEventForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();

        repository.appendApprovalDecision(
                userId,
                repositoryId,
                requestId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                "APPROVED",
                "APPROVED_HELD",
                loopId,
                Map.of("sourceRequestId", requestId.toString())
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_APPROVAL_DECISION");
        assertThat(params.getValue().getValue("phase")).isEqualTo("REQUEST_APPROVAL");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains(requestId.toString())
                .contains(sessionId.toString())
                .contains(agentId.toString())
                .contains(workspaceId.toString())
                .contains("\"approvalState\":\"APPROVED\"")
                .contains("\"status\":\"APPROVED_HELD\"")
                .contains("\"decisionKey\":\"APPROVAL_APPROVED_HELD\"")
                .contains("\"recommendedAction\":{")
                .contains("\"schema\":\"learnbot.code-agent.runner-recommended-action.v1\"")
                .contains("\"actionKey\":\"CHECK_ENQUEUE_REFUSAL\"")
                .contains("\"endpoint\":\"/api/code-agent/loop/runner/enqueue-read-only\"")
                .contains("\"approvalRequestHeld\":true")
                .contains("\"releaseRequired\":true")
                .contains("\"releaseGateEnabled\":false")
                .contains("\"requestCreationEnabled\":false")
                .contains("\"pushEnabled\":false")
                .contains("\"claimEnabled\":false")
                .contains("\"mutationEnabled\":false");
    }

    @Test
    void appendApprovalRequestCreatedPersistsWaitForApprovalEventForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();

        repository.appendApprovalRequestCreated(
                userId,
                repositoryId,
                requestId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                "REQUIRED",
                "APPROVAL_REQUIRED",
                loopId,
                Map.of("mutationAllowed", false)
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_APPROVAL_REQUEST_CREATED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("REQUEST_APPROVAL");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains(requestId.toString())
                .contains(sessionId.toString())
                .contains(agentId.toString())
                .contains(workspaceId.toString())
                .contains("\"decisionKey\":\"APPROVAL_REQUEST_CREATED\"")
                .contains("\"approvalRequestCreated\":true")
                .contains("\"releaseRequired\":true")
                .contains("\"requestCreationEnabled\":false")
                .contains("\"pushEnabled\":false")
                .contains("\"claimEnabled\":false")
                .contains("\"mutationEnabled\":false");
    }

    @Test
    void appendApprovalRequestCreatedMarksValidatedDryRunIntentReviewWithoutOpeningClaim() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();

        repository.appendApprovalRequestCreated(
                userId,
                repositoryId,
                requestId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                "REQUIRED",
                "APPROVAL_REQUIRED",
                loopId,
                Map.of(
                        "validatedDryRunIntent", true,
                        "dryRunIntentPersisted", true,
                        "requestPersisted", true,
                        "queueEnabled", false,
                        "pushEnabled", false,
                        "claimable", false,
                        "dryRunOnly", true,
                        "mutationAllowed", false,
                        "approvalRequestId", "apr-1234567890abcdef",
                        "sourceRequestId", "source-request-1"
                )
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_APPROVAL_REQUEST_CREATED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("REQUEST_APPROVAL");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains(requestId.toString())
                .contains(sessionId.toString())
                .contains("\"decisionKey\":\"VALIDATED_DRY_RUN_INTENT_REVIEW\"")
                .contains("\"nextAction\":\"Review the persisted validated dry-run intent before any future claimable non-mutating dry-run.\"")
                .contains("\"reviewSurface\":\"CODE_WORKSPACE_LOOP_REVIEW\"")
                .contains("\"validatedDryRunIntent\":true")
                .contains("\"dryRunIntentPersisted\":true")
                .contains("\"requestPersisted\":true")
                .contains("\"queueEnabled\":false")
                .contains("\"pushEnabled\":false")
                .contains("\"claimable\":false")
                .contains("\"dryRunOnly\":true")
                .contains("\"mutationAllowed\":false")
                .contains("\"dryRunIntentReviewRequired\":true")
                .contains("\"releaseGateEnabled\":false")
                .contains("\"requestCreationEnabled\":false")
                .contains("\"claimEnabled\":false")
                .contains("\"finalResultEnabled\":false")
                .contains("\"approvalRequestId\":\"apr-1234567890abcdef\"")
                .contains("\"sourceRequestId\":\"source-request-1\"");
    }

    @Test
    void appendFreshObservationRequestsEnqueuedPersistsQueuedRequestIdsWithoutOpeningMutation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID gitStatusRequestId = UUID.randomUUID();
        UUID patchDryRunRequestId = UUID.randomUUID();
        List<LocalAgentQueuedToolRequest> queued = List.of(
                new LocalAgentQueuedToolRequest(gitStatusRequestId, new LocalAgentToolRequest(
                        sessionId,
                        userId,
                        agentId,
                        workspaceId,
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.GIT_STATUS,
                        Map.of("freshObservationOnly", true),
                        LocalAgentApprovalState.NOT_REQUIRED,
                        null,
                        List.of()
                )),
                new LocalAgentQueuedToolRequest(patchDryRunRequestId, new LocalAgentToolRequest(
                        sessionId,
                        userId,
                        agentId,
                        workspaceId,
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.PATCH_APPLY,
                        Map.of("freshObservationOnly", true, "dryRunOnly", true, "mutationAllowed", false),
                        LocalAgentApprovalState.APPROVED,
                        null,
                        List.of()
                ))
        );

        repository.appendFreshObservationRequestsEnqueued(
                userId,
                repositoryId,
                loopId,
                sourceRequestId,
                releaseAttemptId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                queued,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString())
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("OBSERVE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"status\":\"FRESH_OBSERVATIONS_ENQUEUED\"")
                .contains("\"decisionKey\":\"WAIT_FOR_FRESH_OBSERVATION_RESULTS\"")
                .contains("\"actionKey\":\"CHECK_ENQUEUE_REFUSAL\"")
                .contains("\"requestCreationEnabled\":false")
                .contains(sourceRequestId.toString())
                .contains(releaseAttemptId.toString())
                .contains(gitStatusRequestId.toString())
                .contains(patchDryRunRequestId.toString())
                .contains("\"queuedToolNames\":[\"git.status\",\"patch.apply\"]")
                .contains("\"queuedApprovalStates\":[\"NOT_REQUIRED\",\"APPROVED\"]")
                .contains("\"observationResultsRequired\":true")
                .contains("\"sourcePatchClaimEnabled\":false")
                .contains("\"mutationEnabled\":false")
                .contains("\"verificationCommandExecutionEnabled\":false")
                .contains("\"rollbackRestoreEnabled\":false")
                .contains("\"ragFreshnessUpdateEnabled\":false")
                .contains("\"finalAnswerGenerationEnabled\":false")
                .contains("\"deliveryEnabled\":false");
    }

    @Test
    void appendFreshObservationEvidenceCompletePersistsReleaseGatedCompletionEvent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        repository.appendFreshObservationEvidenceComplete(
                userId,
                repositoryId,
                loopId,
                sourceRequestId,
                releaseAttemptId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of(
                        "complete", true,
                        "requiredCount", 2,
                        "linkedCount", 2,
                        "missingCount", 0,
                        "sourceOnlyFallbackCount", 0,
                        "blockingCount", 0,
                        "linkedKeys", List.of("repositoryVerification", "patchDryRun"),
                        "blockingKeys", List.of()
                ),
                List.of(
                        Map.of("key", "repositoryVerification", "status", "RELEASE_ATTEMPT_LINKED"),
                        Map.of("key", "patchDryRun", "status", "RELEASE_ATTEMPT_LINKED")
                )
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE");
        assertThat(params.getValue().getValue("phase")).isEqualTo("COMPLETE_OR_PAUSE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"status\":\"FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED\"")
                .contains("\"decisionKey\":\"FRESH_EVIDENCE_COMPLETE_RELEASE_GATED\"")
                .contains("\"actionKey\":\"CHECK_ENQUEUE_REFUSAL\"")
                .contains("\"mutationEnabled\":false")
                .contains(sourceRequestId.toString())
                .contains(releaseAttemptId.toString())
                .contains("\"evidenceComplete\":true")
                .contains("\"requiredCount\":2")
                .contains("\"linkedCount\":2")
                .contains("\"missingCount\":0")
                .contains("\"sourceOnlyFallbackCount\":0")
                .contains("\"releaseGateEnabled\":false")
                .contains("\"sourcePatchClaimEnabled\":false")
                .contains("\"mutationEnabled\":false")
                .contains("\"verificationCommandExecutionEnabled\":false")
                .contains("\"rollbackRestoreEnabled\":false")
                .contains("\"ragFreshnessUpdateEnabled\":false")
                .contains("\"finalAnswerGenerationEnabled\":false")
                .contains("\"deliveryEnabled\":false");
    }

    @Test
    void appendReleaseReadinessRefreshedPersistsAuditOnlyReleaseGatedEvent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        repository.appendReleaseReadinessRefreshed(
                userId,
                repositoryId,
                loopId,
                sourceRequestId,
                releaseAttemptId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                new LocalAgentPatchExecutionReadinessResponse(
                        sourceRequestId,
                        false,
                        List.of(
                                new LocalAgentPatchExecutionReadinessCheck("snapshotManifestPreview", true, "Snapshot is present."),
                                new LocalAgentPatchExecutionReadinessCheck("releaseGateEnabled", false, "Release remains disabled.")
                        ),
                        List.of("Release remains disabled."),
                        "Held patch request is not ready for Local Agent execution.",
                        Map.of("status", "BLOCKED_RELEASE_DISABLED", "preconditionsPassed", false),
                        Map.of("status", "BLOCKED_RELEASE_DISABLED", "preconditionsPassed", false),
                        new LocalAgentPatchReleaseAttemptModel(
                                "learnbot.local-agent.patch-release-attempt.v1",
                                "DISABLED_RELEASE_GATE",
                                true,
                                false,
                                120,
                                List.of(),
                                Map.of(
                                        "id", releaseAttemptId.toString(),
                                        "releaseAttemptFinalReadiness", Map.of(
                                                "releaseAttemptReady", false,
                                                "freshObservationEvidenceComplete", true
                                        )
                                ),
                                "Release attempt remains non-claimable."
                        ),
                        Map.of(),
                        Map.of(),
                        Map.of("status", "MATCH"),
                        Map.of("status", "MATCH")
                )
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_RELEASE_READINESS_REFRESHED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("COMPLETE_OR_PAUSE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"status\":\"RELEASE_READINESS_REFRESHED_RELEASE_GATED\"")
                .contains("\"decisionKey\":\"RELEASE_READINESS_REFRESHED_RELEASE_GATED\"")
                .contains("\"actionKey\":\"REVIEW_RELEASE_REFUSAL\"")
                .contains("\"endpoint\":\"/api/code-agent/loop/runner/release-review\"")
                .contains(sourceRequestId.toString())
                .contains(releaseAttemptId.toString())
                .contains("\"readyToRelease\":false")
                .contains("\"warningCount\":1")
                .contains("\"failedCheckKeys\":[\"releaseGateEnabled\"]")
                .contains("\"patchReleaseStatus\":\"BLOCKED_RELEASE_DISABLED\"")
                .contains("\"patchReleasePreconditionsPassed\":false")
                .contains("\"releaseAttemptReady\":false")
                .contains("\"freshObservationEvidenceComplete\":true")
                .contains("\"releaseGateEnabled\":false")
                .contains("\"sourcePatchClaimEnabled\":false")
                .contains("\"claimable\":false")
                .contains("\"mutationAllowed\":false")
                .contains("\"verificationCommandExecutionEnabled\":false")
                .contains("\"rollbackRestoreEnabled\":false")
                .contains("\"ragFreshnessUpdateEnabled\":false")
                .contains("\"finalAnswerGenerationEnabled\":false")
                .contains("\"deliveryEnabled\":false")
                .contains("\"acknowledgementEnabled\":false");
    }

    @Test
    void appendStopOutcomePersistsAuditOnlyRecordedStopEventForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        repository.appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                "APPROVAL_DENIED",
                "REPORT_APPROVAL_DENIED",
                "Stop after approval denial without creating claimable work.",
                Map.of("requestId", requestId.toString(), "status", "REJECTED")
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("STOP_OUTCOME_RECORDED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("COMPLETE_OR_PAUSE");
        assertThat(params.getValue().getValue("executionTarget")).isNull();
        assertThat(params.getValue().getValue("toolName")).isNull();
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(false);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"status\":\"RECORDED\"")
                .contains("\"stopKey\":\"APPROVAL_DENIED\"")
                .contains("\"outcome\":\"REPORT_APPROVAL_DENIED\"")
                .contains("\"finalResultEnabled\":false")
                .contains("\"publicationEnabled\":false")
                .contains("\"acknowledgementEnabled\":false")
                .contains("\"mutationEnabled\":false")
                .contains(requestId.toString());
    }

    @Test
    void appendNextDecisionPersistsAuditOnlyDecisionAfterObservation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                requestId,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("clean", false),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );

        repository.appendNextDecision(userId, repositoryId, loopId, response, Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "releaseAttemptId", releaseAttemptId.toString()
        ));

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOOP_NEXT_DECISION_RECORDED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("COMPLETE_OR_PAUSE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("SERVER_LOCAL");
        assertThat(params.getValue().getValue("toolName")).isNull();
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(false);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"decisionKey\":\"OBSERVATION_ACCEPTED\"")
                .contains("\"actionKey\":\"PREVIEW_RUNNER_STEP\"")
                .contains("\"endpoint\":\"/api/code-agent/loop/runner/preview\"")
                .contains("\"followUpToolSelectionEnabled\":true")
                .contains("\"approvalRequiredBeforeSideEffects\":true")
                .contains("\"requestCreationEnabled\":false")
                .contains("\"pushEnabled\":false")
                .contains("\"claimEnabled\":false")
                .contains("\"mutationEnabled\":false")
                .contains("\"finalResultEnabled\":false")
                .contains("\"publicationEnabled\":false")
                .contains("\"acknowledgementEnabled\":false")
                .contains(requestId.toString())
                .contains(sourceRequestId.toString())
                .contains(releaseAttemptId.toString());
    }

    @Test
    void appendReleaseBoundaryRefusalPersistsClosedLoopDecisionForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentPatchReleaseBoundaryResponse boundary = new LocalAgentPatchReleaseBoundaryResponse(
                requestId,
                "RELEASE_REFUSED_GATE_DISABLED",
                "REFUSAL_ONLY",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of("release gate is disabled"),
                "Release action is modeled, but the release gate is disabled so the held patch remains non-claimable.",
                Map.of("preconditionsPassed", true),
                Map.of("status", "BLOCKED_ENABLEMENT_DISABLED"),
                new LocalAgentPatchReleaseAttemptModel(
                        "local-agent.patch-release-attempt.v1",
                        "DISABLED_RELEASE_ATTEMPT_EXISTS",
                        true,
                        false,
                        120,
                        List.of(),
                        Map.of("claimable", false),
                        "Release attempt remains disabled."
                )
        );

        repository.appendReleaseBoundaryRefusal(
                userId,
                repositoryId,
                loopId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                boundary,
                Map.of()
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED");
        assertThat(params.getValue().getValue("phase")).isEqualTo("COMPLETE_OR_PAUSE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"decisionKey\":\"RELEASE_BOUNDARY_REFUSED\"")
                .contains("\"nextAction\":\"Report that release was refused and mutation remains disabled.\"")
                .contains("\"actionKey\":\"STOP_AND_REPORT\"")
                .contains("\"enabled\":false")
                .contains("\"boundaryStatus\":\"RELEASE_REFUSED_GATE_DISABLED\"")
                .contains("\"releaseGateEnabled\":false")
                .contains("\"requestCreationEnabled\":false")
                .contains("\"pushEnabled\":false")
                .contains("\"claimEnabled\":false")
                .contains("\"mutationEnabled\":false")
                .contains("\"finalResultEnabled\":false")
                .contains("\"publicationEnabled\":false")
                .contains("\"acknowledgementEnabled\":false")
                .contains(requestId.toString())
                .contains(sessionId.toString())
                .contains(agentId.toString())
                .contains(workspaceId.toString());
    }

    @Test
    void appendAgentUnavailableStopOutcomePersistsRequestContextWithoutCreatingWork() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString()),
                com.learnbot.dto.LocalAgentApprovalState.REQUIRED,
                null,
                List.of()
        );

        repository.appendAgentUnavailableStopOutcome(userId, repositoryId, loopId, request);

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("STOP_OUTCOME_RECORDED");
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains("\"stopKey\":\"AGENT_UNAVAILABLE\"")
                .contains("\"outcome\":\"WAIT_FOR_LOCAL_AGENT\"")
                .contains("\"finalResultEnabled\":false")
                .contains("\"publicationEnabled\":false")
                .contains("\"acknowledgementEnabled\":false")
                .contains("\"mutationEnabled\":false")
                .contains(agentId.toString())
                .contains(workspaceId.toString())
                .contains("\"toolName\":\"patch.apply\"")
                .contains("\"approvalState\":\"REQUIRED\"");
    }
}
