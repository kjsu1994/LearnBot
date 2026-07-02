package com.learnbot.service.agentloop;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import com.learnbot.repository.LocalAgentMutationObservationIntakeRepository;
import com.learnbot.repository.LocalAgentPatchReleaseAttemptRepository;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import com.learnbot.service.LocalAgentToolGatewayService;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToolExecution;
import com.learnbot.service.LocalAgentToolPusher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentLoopRunnerServiceTest {
    private final CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
    private final LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
    private final CodeAgentLoopRunnerService service = new CodeAgentLoopRunnerService(loopPreviewService, toolGatewayService);

    @Test
    void previewNextStepPreparesReadOnlyGitStatusCandidateForAcceptedObservationDecision() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                12,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.runnerDecision()).isEqualTo("PREPARED_READ_ONLY_CANDIDATE");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().sessionId()).isEqualTo(loopId);
        assertThat(result.candidate().executionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(result.candidate().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(result.candidate().approvalState()).isEqualTo(LocalAgentApprovalState.NOT_REQUIRED);
        assertThat(result.candidate().sideEffectful()).isFalse();
        assertThat(result.candidate().requiresApproval()).isFalse();
        assertThat(result.candidate().enqueueEnabled()).isFalse();
        assertThat(result.candidate().mutationAllowed()).isFalse();
        assertThat(result.candidate().input())
                .containsEntry("repositoryId", repositoryId.toString())
                .containsEntry("loopId", loopId.toString())
                .containsEntry("freshObservationOnly", true)
                .containsEntry("mutationAllowed", false);
        assertThat(result.recommendedAction())
                .containsEntry("schema", "learnbot.code-agent.runner-recommended-action.v1")
                .containsEntry("actionKey", "QUEUE_SELECTED_READ_ONLY")
                .containsEntry("label", "Queue read-only step")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/enqueue-selected-read-only")
                .containsEntry("enabled", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewNextStepPreparesReadOnlyGitDiffCandidateAfterSucceededGitStatusObservation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                12,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(loopPreviewService.recentTimelines(userId, repositoryId, 10)).thenReturn(List.of(timeline(
                repositoryId,
                loopId,
                List.of(observationEvent(LocalAgentToolName.GIT_STATUS))
        )));

        var result = service.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.runnerDecision()).isEqualTo("PREPARED_READ_ONLY_CANDIDATE");
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().toolName()).isEqualTo(LocalAgentToolName.GIT_DIFF);
        assertThat(result.candidate().input())
                .containsEntry("repositoryId", repositoryId.toString())
                .containsEntry("loopId", loopId.toString())
                .containsEntry("freshObservationOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("maxBytes", 6000);
        assertThat(result.guardrails())
                .containsEntry("allowedCandidateTools", List.of("git.status", "git.diff"))
                .containsEntry("mutationAllowed", false);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
    }

    @Test
    void previewNextStepWaitsWhenAgentWorkspaceIdentityIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                Map.of()
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, null, UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("WAIT_FOR_AGENT_WORKSPACE");
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "SELECT_LOCAL_AGENT_WORKSPACE")
                .containsEntry("enabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewNextStepDoesNotPrepareCandidateForStopAction() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "STOP_WITH_REASON",
                "Tool failed.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                "STOP_OUTCOME_RECORDED",
                Map.of("stopKey", "TOOL_FAILED")
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("NO_REQUEST_PREPARED");
        assertThat(result.candidate()).isNull();
        assertThat(result.actionKey()).isEqualTo("STOP_WITH_REASON");
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "STOP_AND_REPORT")
                .containsEntry("enabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewNextStepPreservesReleaseRefusalStopHandoffWithoutPreparingRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.release-boundary-refusal-summary.v1"),
                Map.entry("status", "RELEASE_REVIEW_REFUSED_GATE_DISABLED"),
                Map.entry("sourceRequestId", UUID.randomUUID().toString()),
                Map.entry("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED"),
                Map.entry("actionMode", "REFUSAL_ONLY"),
                Map.entry("releaseGateEnabled", false),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("claimable", false),
                Map.entry("mutationEnabled", false),
                Map.entry("runnerDecision", "NO_REQUEST_PREPARED")
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "STOP_WITH_REASON",
                "Report that release was refused and mutation remains disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                22,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                handoffSummary,
                Map.of("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED")
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("NO_REQUEST_PREPARED");
        assertThat(result.actionKey()).isEqualTo("STOP_WITH_REASON");
        assertThat(result.candidate()).isNull();
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.handoffSummary())
                .containsEntry("status", "RELEASE_REVIEW_REFUSED_GATE_DISABLED")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "STOP_AND_REPORT")
                .containsEntry("enabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewNextStepPreservesCreationDisabledHandoffSummaryWithoutPreparingRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        Map<String, Object> handoffSummary = creationDisabledHandoffSummary();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "READY_HANDOFF_CREATION_DISABLED",
                "Mutation handoff is ready, but creation is disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                17,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                handoffSummary,
                Map.of("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED")
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("WAIT_CREATION_GATE_DISABLED");
        assertThat(result.reason()).contains("request creation is disabled");
        assertThat(result.candidate()).isNull();
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.handoffSummary())
                .containsEntry("expectedRequestCount", 4)
                .containsEntry("durableMutationExecutionRowCount", 0)
                .containsEntry("persistedRequestCount", 0)
                .containsEntry("pushedRequestCount", 0)
                .containsEntry("claimableRequestCount", 0)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "CHECK_ENQUEUE_REFUSAL")
                .containsEntry("label", "Check enqueue refusal")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/enqueue-read-only")
                .containsEntry("enabled", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewNextStepPointsWaitForReleaseGateToFreshObservationRoutesWithoutQueueing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "WAIT_FOR_RELEASE_GATE",
                "Inspect release readiness and queue fresh release-attempt observations before any claimable mutation transition.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                18,
                "LOCAL_AGENT_APPROVAL_DECISION",
                Map.ofEntries(
                        Map.entry("requestId", sourceRequestId.toString()),
                        Map.entry("approvalState", "APPROVED"),
                        Map.entry("status", "APPROVED_HELD"),
                        Map.entry("approvalRequestHeld", true),
                        Map.entry("releaseRequired", true),
                        Map.entry("releaseGateEnabled", false),
                        Map.entry("mutationEnabled", false)
                )
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_FRESH_OBSERVATIONS");
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "CHECK_ENQUEUE_REFUSAL")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/enqueue-read-only")
                .containsEntry("enabled", true)
                .containsEntry("mutationEnabled", false);
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.release-gate-fresh-observation-handoff.v1")
                .containsEntry("status", "WAIT_FOR_RELEASE_GATE")
                .containsEntry("runnerDecision", "WAIT_RELEASE_GATE_FRESH_OBSERVATIONS")
                .containsEntry("sourceEventType", "LOCAL_AGENT_APPROVAL_DECISION")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("approvalState", "APPROVED")
                .containsEntry("sourceStatus", "APPROVED_HELD")
                .containsEntry("approvalRequestHeld", true)
                .containsEntry("releaseRequired", true)
                .containsEntry("readinessRoute", "GET /api/local-agents/tools/" + sourceRequestId + "/readiness")
                .containsEntry("freshObservationsRoute", "POST /api/local-agents/tools/" + sourceRequestId + "/fresh-observations")
                .containsEntry("releaseBoundaryRoute", "POST /api/local-agents/tools/" + sourceRequestId + "/release-for-execution")
                .containsEntry("runnerAutoEnqueueEnabled", false)
                .containsEntry("freshObservationAutoEnqueueEnabled", false)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewNextStepPreservesQueuedFreshObservationStateWithoutQueueingMoreWork() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID gitStatusRequestId = UUID.randomUUID();
        UUID patchDryRunRequestId = UUID.randomUUID();
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.release-gate-fresh-observation-enqueue-state.v1"),
                Map.entry("status", "WAIT_FOR_FRESH_OBSERVATION_RESULTS"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("queuedRequestCount", 2),
                Map.entry("queuedRequestIds", List.of(gitStatusRequestId.toString(), patchDryRunRequestId.toString())),
                Map.entry("queuedToolNames", List.of("git.status", "patch.apply")),
                Map.entry("observationResultsRequired", true),
                Map.entry("sourcePatchClaimEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("mutationEnabled", false),
                Map.entry("verificationCommandExecutionEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("finalAnswerGenerationEnabled", false),
                Map.entry("deliveryEnabled", false)
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "FRESH_OBSERVATIONS_ENQUEUED",
                "WAIT_FOR_FRESH_OBSERVATION_RESULTS",
                "Wait for fresh release-attempt Local Agent observations before any release or claimable mutation transition.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                19,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString())
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_FRESH_OBSERVATION_RESULTS");
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "CHECK_ENQUEUE_REFUSAL")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/enqueue-read-only")
                .containsEntry("enabled", true)
                .containsEntry("mutationEnabled", false);
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.handoffSummary())
                .containsEntry("queuedRequestCount", 2)
                .containsEntry("observationResultsRequired", true)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void previewNextStepPreservesFreshEvidenceCompleteReleaseGatedState() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.release-gate-fresh-observation-complete-state.v1"),
                Map.entry("status", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("evidenceComplete", true),
                Map.entry("requiredCount", 2),
                Map.entry("linkedCount", 2),
                Map.entry("missingCount", 0),
                Map.entry("sourceOnlyFallbackCount", 0),
                Map.entry("sourcePatchClaimEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("mutationEnabled", false),
                Map.entry("verificationCommandExecutionEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("finalAnswerGenerationEnabled", false),
                Map.entry("deliveryEnabled", false)
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED",
                "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED",
                "Fresh release-attempt evidence is complete; inspect release readiness while release, claim, and mutation remain disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                20,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString())
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE");
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "CHECK_ENQUEUE_REFUSAL")
                .containsEntry("label", "Check enqueue refusal")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/enqueue-read-only")
                .containsEntry("enabled", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.handoffSummary())
                .containsEntry("evidenceComplete", true)
                .containsEntry("linkedCount", 2)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void previewNextStepPreservesReleaseReadinessRefreshGatedState() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.release-readiness-refresh-state.v1"),
                Map.entry("status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("readyToRelease", false),
                Map.entry("patchReleaseStatus", "BLOCKED_RELEASE_DISABLED"),
                Map.entry("releaseAttemptReady", false),
                Map.entry("freshObservationEvidenceComplete", true),
                Map.entry("sourcePatchClaimEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("claimable", false),
                Map.entry("mutationEnabled", false),
                Map.entry("verificationCommandExecutionEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("finalAnswerGenerationEnabled", false),
                Map.entry("deliveryEnabled", false)
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "Release readiness was refreshed from fresh evidence; release, claim, and mutation remain disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                21,
                "LOCAL_AGENT_RELEASE_READINESS_REFRESHED",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString())
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_READINESS_REFRESHED");
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "REVIEW_RELEASE_REFUSAL")
                .containsEntry("label", "Review release refusal")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/release-review")
                .containsEntry("enabled", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.handoffSummary())
                .containsEntry("readyToRelease", false)
                .containsEntry("releaseAttemptReady", false)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void reviewReleaseGateRecordsBoundaryRefusalOnlyAfterReadinessRefresh() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.release-readiness-refresh-state.v1"),
                Map.entry("status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("readyToRelease", false),
                Map.entry("sourcePatchClaimEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("mutationEnabled", false)
        );
        LocalAgentPatchReleaseBoundaryResponse boundary = new LocalAgentPatchReleaseBoundaryResponse(
                sourceRequestId,
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
                List.of("release gate is disabled", "held patch request remains non-claimable"),
                "Release action is modeled, but the release gate is disabled so the held patch remains non-claimable.",
                Map.of("status", "BLOCKED_RELEASE_DISABLED", "preconditionsPassed", false),
                Map.of("releaseGateEnabled", false, "claimable", false),
                null
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "Release readiness was refreshed from fresh evidence; release, claim, and mutation remain disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                21,
                "LOCAL_AGENT_RELEASE_READINESS_REFRESHED",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString())
        ));
        when(toolGatewayService.inspectPatchReleaseBoundary(userId, sourceRequestId)).thenReturn(boundary);

        var result = service.reviewReleaseGate(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("RELEASE_REVIEW_REFUSED_GATE_DISABLED");
        assertThat(result.boundary()).isEqualTo(boundary);
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.preview().runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_READINESS_REFRESHED");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.boundary().releaseGateEnabled()).isFalse();
        assertThat(result.boundary().claimEnabled()).isFalse();
        assertThat(result.boundary().claimable()).isFalse();
        assertThat(result.boundary().mutationAllowed()).isFalse();
        verify(toolGatewayService).inspectPatchReleaseBoundary(userId, sourceRequestId);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void reviewReleaseGateDoesNotRecordBoundaryForNonReadinessRefreshState() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED",
                "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED",
                "Fresh release-attempt evidence is complete; inspect release readiness while release, claim, and mutation remain disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                20,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE",
                Map.of("status", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED"),
                Map.of()
        ));

        var result = service.reviewReleaseGate(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("NOT_REVIEWED");
        assertThat(result.boundary()).isNull();
        assertThat(result.preview().runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE");
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(toolGatewayService, never()).inspectPatchReleaseBoundary(any(), any());
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void enqueueReadOnlyNextStepDoesNotQueueForReleaseGateFreshObservationHandoff() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "WAIT_FOR_RELEASE_GATE",
                "Inspect release readiness.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                18,
                "LOCAL_AGENT_APPROVAL_DECISION",
                Map.of("requestId", sourceRequestId.toString(), "approvalState", "APPROVED", "status", "APPROVED_HELD")
        ));

        var result = service.enqueueReadOnlyNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("NOT_ENQUEUED");
        assertThat(result.queuedRequest()).isNull();
        assertThat(result.preview().runnerDecision()).isEqualTo("WAIT_RELEASE_GATE_FRESH_OBSERVATIONS");
        assertThat(result.handoffSummary())
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("freshObservationsRoute", "POST /api/local-agents/tools/" + sourceRequestId + "/fresh-observations")
                .containsEntry("runnerAutoEnqueueEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void enqueueReadOnlyNextStepQueuesOnlyPreparedGitStatusCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID queuedRequestId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                12,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(toolGatewayService.enqueueReadOnly(any(LocalAgentToolRequest.class))).thenAnswer(invocation -> {
            LocalAgentToolRequest request = invocation.getArgument(0);
            return new LocalAgentQueuedToolRequest(queuedRequestId, request);
        });

        var result = service.enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.runnerDecision()).isEqualTo("ENQUEUED_READ_ONLY_OBSERVATION");
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.enqueueEnabled()).isTrue();
        assertThat(result.pushEnabled()).isTrue();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.queuedRequest().requestId()).isEqualTo(queuedRequestId);
        assertThat(result.queuedRequest().request().sessionId()).isEqualTo(loopId);
        assertThat(result.queuedRequest().request().userId()).isEqualTo(userId);
        assertThat(result.queuedRequest().request().agentId()).isEqualTo(agentId);
        assertThat(result.queuedRequest().request().workspaceId()).isEqualTo(workspaceId);
        assertThat(result.queuedRequest().request().executionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(result.queuedRequest().request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(result.queuedRequest().request().approvalState()).isEqualTo(LocalAgentApprovalState.NOT_REQUIRED);
        assertThat(result.queuedRequest().request().input())
                .containsEntry("repositoryId", repositoryId.toString())
                .containsEntry("loopId", loopId.toString())
                .containsEntry("freshObservationOnly", true)
                .containsEntry("mutationAllowed", false);
    }

    @Test
    void enqueueReadOnlyNextStepDoesNotQueueWhenPreviewCannotPrepareCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "STOP_WITH_REASON",
                "Tool failed.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                "STOP_OUTCOME_RECORDED",
                Map.of()
        ));

        var result = service.enqueueReadOnlyNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("NOT_ENQUEUED");
        assertThat(result.queuedRequest()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void enqueueReadOnlyNextStepPreservesCreationDisabledHandoffSummaryWithoutQueueing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        Map<String, Object> handoffSummary = creationDisabledHandoffSummary();
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "READY_HANDOFF_CREATION_DISABLED",
                "Mutation handoff is ready, but creation is disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                17,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                handoffSummary,
                Map.of("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED")
        ));

        var result = service.enqueueReadOnlyNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("NOT_ENQUEUED");
        assertThat(result.reason()).contains("request creation is disabled");
        assertThat(result.queuedRequest()).isNull();
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.preview().runnerDecision()).isEqualTo("WAIT_CREATION_GATE_DISABLED");
        assertThat(result.preview().handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void runnerReadOnlyEnqueueCanBeClaimedCompletedAndRecordedOnLoopTimeline() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runner = new CodeAgentLoopRunnerService(previewService, realGateway);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                14,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(invocation.getArgument(0), invocation.getArgument(1), LocalAgentToolStatus.PENDING));

        var enqueued = runner.enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);
        UUID requestId = enqueued.queuedRequest().requestId();
        LocalAgentToolRequest request = enqueued.queuedRequest().request();
        LocalAgentToolExecution running = execution(requestId, request, LocalAgentToolStatus.RUNNING);
        when(repository.expireTimedOutLeases()).thenReturn(List.of());
        when(repository.claimNext(userId, agentId)).thenReturn(Optional.of(running));
        when(repository.find(requestId)).thenReturn(Optional.of(running));

        var claimed = realGateway.claimNext(userId, agentId).orElseThrow();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                loopId,
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("clean", true, "branch", "main"),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );

        realGateway.complete(response);

        assertThat(enqueued.runnerDecision()).isEqualTo("ENQUEUED_READ_ONLY_OBSERVATION");
        assertThat(claimed.requestId()).isEqualTo(requestId);
        assertThat(claimed.request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        verify(toolPusher).sendToolRequest(enqueued.queuedRequest());
        verify(repository).claimNext(userId, agentId);
        verify(repository).complete(any(LocalAgentToolResponse.class));
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(loopTimelineRepository).appendNextDecision(userId, repositoryId, loopId, response, request.input());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void previewNextStepReportsCompletedApprovedExecutionFlowWithoutOpeningFinalResultControls() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.approved-execution-flow-completed-handoff.v1"),
                Map.entry("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED"),
                Map.entry("runnerDecision", "READY_FINAL_RESULT_DISABLED"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("requestIdSource", "durableCompletedRows"),
                Map.entry("stepCount", 4),
                Map.entry("ordered", true),
                Map.entry("identityConsistent", true),
                Map.entry("releaseAttemptLinked", true),
                Map.entry("allTerminal", true),
                Map.entry("allSucceeded", true),
                Map.entry("finalMutationReportSummaryStatus", "READY_SUMMARY_AUDIT_ONLY"),
                Map.entry("ragFreshnessMarkerStatus", "STALE_INDEX_WARNING_REQUIRED"),
                Map.entry("finalAnswerPublicationHandoffStatus", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("acknowledgementSaveHandoffStatus", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED"),
                Map.entry("finalResultEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("followUpMutationEnabled", false),
                Map.entry("mutationEnabled", false)
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "Report the completed approved Local Agent execution flow while final result publication and acknowledgement save remain disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                31,
                "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString(), "releaseAttemptId", releaseAttemptId.toString())
        ));

        var result = service.previewNextStep(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.runnerDecision()).isEqualTo("READY_FINAL_RESULT_DISABLED");
        assertThat(result.actionKey()).isEqualTo("APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED");
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.handoffSummary()).isEqualTo(handoffSummary);
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "STOP_AND_REPORT")
                .containsEntry("enabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void previewFinalResultPublicationExposesAuditOnlyHandoffWithoutOpeningPublicationControls() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        Map<String, Object> finalResultHandoff = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1"),
                Map.entry("status", "READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("finalMutationReportSummaryStatus", "READY_SUMMARY_AUDIT_ONLY"),
                Map.entry("ragFreshnessMarkerStatus", "STALE_INDEX_WARNING_REQUIRED"),
                Map.entry("finalAnswerPublicationHandoffStatus", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("acknowledgementSaveHandoffStatus", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED"),
                Map.entry("staleIndexDisclosureModeled", true),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementSaveEnabled", false),
                Map.entry("mutationEnabled", false)
        );
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.approved-execution-flow-completed-handoff.v1"),
                Map.entry("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED"),
                Map.entry("runnerDecision", "READY_FINAL_RESULT_DISABLED"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("finalMutationReportSummaryStatus", "READY_SUMMARY_AUDIT_ONLY"),
                Map.entry("ragFreshnessMarkerStatus", "STALE_INDEX_WARNING_REQUIRED"),
                Map.entry("finalAnswerPublicationHandoffStatus", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("acknowledgementSaveHandoffStatus", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED"),
                Map.entry("finalResultEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("followUpMutationEnabled", false),
                Map.entry("mutationEnabled", false),
                Map.entry("finalResultHandoff", finalResultHandoff)
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "Report the completed approved Local Agent execution flow while final result publication and acknowledgement save remain disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                31,
                "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString(), "releaseAttemptId", releaseAttemptId.toString())
        ));

        var result = service.previewFinalResultPublication(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.publicationDecision()).isEqualTo("READY_FINAL_RESULT_PUBLICATION_DISABLED");
        assertThat(result.finalResultReady()).isTrue();
        assertThat(result.finalResultHandoff()).containsEntry("schema", "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1")
                .containsEntry("status", "READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED")
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.runnerPreview().runnerDecision()).isEqualTo("READY_FINAL_RESULT_DISABLED");
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.finalAnswerGenerationEnabled()).isFalse();
        assertThat(result.finalAnswerDeliveryEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.acknowledgementSaveEnabled()).isFalse();
        assertThat(result.ragFreshnessUpdateEnabled()).isFalse();
        assertThat(result.partialReindexEnabled()).isFalse();
        assertThat(result.followUpMutationEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
    }

    @Test
    void previewM8EntryReadinessMarksM7ClosedWithoutEnablingM8Work() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        Map<String, Object> finalResultHandoff = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1"),
                Map.entry("status", "READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementSaveEnabled", false),
                Map.entry("mutationEnabled", false)
        );
        Map<String, Object> handoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.approved-execution-flow-completed-handoff.v1"),
                Map.entry("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED"),
                Map.entry("runnerDecision", "READY_FINAL_RESULT_DISABLED"),
                Map.entry("sourceRequestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("finalResultHandoff", finalResultHandoff)
        );
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "Report the completed approved Local Agent execution flow while final result publication remains disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                31,
                "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED",
                handoffSummary,
                Map.of("sourceRequestId", sourceRequestId.toString(), "releaseAttemptId", releaseAttemptId.toString())
        ));

        var result = service.previewM8EntryReadiness(userId, repositoryId, loopId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.m7ClosureDecision()).isEqualTo("M7_CLOSURE_READY");
        assertThat(result.m8EntryDecision()).isEqualTo("M8_ENTRY_READY");
        assertThat(result.m7ClosureReady()).isTrue();
        assertThat(result.m8EntryReady()).isTrue();
        assertThat(result.finalResultHandoffReady()).isTrue();
        assertThat(result.finalResultPublicationPreviewReady()).isTrue();
        assertThat(result.blockingReasons()).isEmpty();
        assertThat(result.finalResultPublicationPreview().publicationDecision()).isEqualTo("READY_FINAL_RESULT_PUBLICATION_DISABLED");
        assertThat(result.m8WorkEnabled()).isFalse();
        assertThat(result.cliPackagingEnabled()).isFalse();
        assertThat(result.installerEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.finalAnswerDeliveryEnabled()).isFalse();
        assertThat(result.acknowledgementSaveEnabled()).isFalse();
        assertThat(result.ragFreshnessUpdateEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
    }

    private LocalAgentToolExecution execution(
            UUID requestId,
            LocalAgentToolRequest request,
            LocalAgentToolStatus status
    ) {
        return new LocalAgentToolExecution(
                requestId,
                request.sessionId(),
                request.userId(),
                request.agentId(),
                request.workspaceId(),
                request.executionTarget(),
                request.toolName(),
                request.approvalState(),
                status,
                request.input(),
                Map.of(),
                null,
                null,
                request.warnings(),
                List.of(),
                request.createdAt(),
                status == LocalAgentToolStatus.RUNNING ? OffsetDateTime.now() : null,
                null
        );
    }

    private Map<String, Object> creationDisabledHandoffSummary() {
        return Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.creation-disabled-handoff-summary.v1"),
                Map.entry("status", "READY_HANDOFF_CREATION_DISABLED"),
                Map.entry("expectedRequestCount", 4),
                Map.entry("durableMutationExecutionRowCount", 0),
                Map.entry("persistedRequestCount", 0),
                Map.entry("pushedRequestCount", 0),
                Map.entry("claimableRequestCount", 0),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("mutationEnabled", false),
                Map.entry("runnerDecision", "WAIT_CREATION_GATE_DISABLED")
        );
    }

    private CodeAgentLoopTimelineSummary timeline(
            UUID repositoryId,
            UUID loopId,
            List<CodeAgentLoopTimelineEventSummary> events
    ) {
        return new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                UUID.randomUUID(),
                "instruction",
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                events
        );
    }

    private CodeAgentLoopTimelineEventSummary observationEvent(LocalAgentToolName toolName) {
        return new CodeAgentLoopTimelineEventSummary(
                UUID.randomUUID(),
                1,
                "LOCAL_AGENT_OBSERVATION_RESULT",
                "OBSERVE",
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                false,
                false,
                true,
                Map.of("status", "SUCCEEDED"),
                OffsetDateTime.now()
        );
    }
}
