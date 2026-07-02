package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentLoopPreviewServiceTest {
    private final CodeAgentLoopTimelineRepository timelineRepository = mock(CodeAgentLoopTimelineRepository.class);
    private final CodeAgentLoopPreviewService service = new CodeAgentLoopPreviewService(timelineRepository);

    @Test
    void previewIsBoundedReadOnlyAndStopsBeforeMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();

        var preview = service.preview(userId, repositoryId, spaceId, "fix the failing parser test", 99);

        assertThat(preview.repositoryId()).isEqualTo(repositoryId);
        assertThat(preview.spaceId()).isEqualTo(spaceId);
        assertThat(preview.status()).isEqualTo("PREVIEW_ONLY");
        assertThat(preview.maxSteps()).isEqualTo(8);
        assertThat(preview.timeoutSeconds()).isEqualTo(120);
        assertThat(preview.cancellationEnabled()).isFalse();
        assertThat(preview.timelinePersistenceEnabled()).isTrue();
        assertThat(preview.mutationEnabled()).isFalse();
        assertThat(preview.steps()).hasSize(5);
        assertThat(preview.steps()).allSatisfy(step -> {
            assertThat(step.mayMutate()).isFalse();
            assertThat(step.enabled()).isTrue();
        });
        assertThat(preview.steps())
                .extracting("phase")
                .containsExactly("PLAN", "SELECT_TOOL", "REQUEST_APPROVAL", "OBSERVE", "COMPLETE_OR_PAUSE");
        assertThat(preview.steps().get(2).executionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(preview.steps().get(2).toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(preview.steps().get(2).requiresApproval()).isTrue();
        assertThat(preview.stopConditions())
                .extracting("key")
                .contains("MAX_STEPS", "TIMEOUT", "WEAK_EVIDENCE", "APPROVAL_REQUIRED", "AGENT_UNAVAILABLE", "TOOL_FAILED", "MUTATION_DISABLED");
        assertThat(preview.warnings()).allSatisfy(warning ->
                assertThat(warning).doesNotContain("enabled")
        );
        verify(timelineRepository).createPreview(userId, "fix the failing parser test", preview);
    }

    @Test
    void previewKeepsMinimumStepBudgetForDecisionAndObservation() {
        var preview = service.preview(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "inspect only", 1);

        assertThat(preview.maxSteps()).isEqualTo(4);
        assertThat(preview.steps())
                .extracting("phase")
                .contains("PLAN", "OBSERVE", "COMPLETE_OR_PAUSE");
    }

    @Test
    void recentTimelinesClampReadOnlyHistoryLimit() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        service.recentTimelines(userId, repositoryId, 99);

        verify(timelineRepository).findRecent(userId, repositoryId, 20);
    }

    @Test
    void nextActionQueuesReadOnlyObservationAfterAcceptedDecisionWithoutEnablingMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary decision = event(
                eventId,
                12,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of(
                        "status", "RECORDED",
                        "decisionKey", "OBSERVATION_ACCEPTED",
                        "nextAction", "Evaluate the Local Agent observation before selecting another typed tool.",
                        "requestCreationEnabled", false,
                        "mutationEnabled", false
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(decision))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.loopId()).isEqualTo(loopId);
        assertThat(result.actionKey()).isEqualTo("QUEUE_READ_ONLY_OBSERVATION");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("schema", "learnbot.code-agent.runner-recommended-action.v1")
                .containsEntry("actionKey", "PREVIEW_RUNNER_STEP")
                .containsEntry("label", "Preview runner step")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/preview")
                .containsEntry("enabled", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.sourceEventId()).isEqualTo(eventId);
        assertThat(result.sourceEventType()).isEqualTo("LOOP_NEXT_DECISION_RECORDED");
    }

    @Test
    void nextActionWaitsForApprovalAfterApprovalRequestCreationInsteadOfRequeueingReadOnly() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestEventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary decision = event(
                UUID.randomUUID(),
                12,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of(
                        "status", "RECORDED",
                        "decisionKey", "OBSERVATION_ACCEPTED",
                        "nextAction", "Evaluate the Local Agent observation before selecting another typed tool.",
                        "requestCreationEnabled", false,
                        "mutationEnabled", false
                )
        );
        CodeAgentLoopTimelineEventSummary approvalRequest = event(
                requestEventId,
                13,
                "LOCAL_AGENT_APPROVAL_REQUEST_CREATED",
                Map.of(
                        "status", "APPROVAL_REQUIRED",
                        "decisionKey", "APPROVAL_REQUEST_CREATED",
                        "nextAction", "Wait for explicit user approval before release, claim, or mutation.",
                        "approvalRequestCreated", true,
                        "releaseRequired", true,
                        "requestCreationEnabled", false,
                        "pushEnabled", false,
                        "claimEnabled", false,
                        "mutationEnabled", false
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(decision, approvalRequest))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("WAIT_FOR_APPROVAL");
        assertThat(result.reason()).isEqualTo("Wait for explicit user approval before release, claim, or mutation.");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.sourceEventId()).isEqualTo(requestEventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_APPROVAL_REQUEST_CREATED");
        assertThat(result.sourceDetails()).containsEntry("approvalRequestCreated", true);
    }

    @Test
    void nextActionSurfacesPersistedValidatedDryRunIntentReviewFromApprovalRequestEvent() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestEventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary approvalRequest = event(
                requestEventId,
                13,
                "LOCAL_AGENT_APPROVAL_REQUEST_CREATED",
                Map.ofEntries(
                        Map.entry("status", "APPROVAL_REQUIRED"),
                        Map.entry("requestId", requestId.toString()),
                        Map.entry("decisionKey", "VALIDATED_DRY_RUN_INTENT_REVIEW"),
                        Map.entry("nextAction", "Review the persisted validated dry-run intent before any future claimable non-mutating dry-run."),
                        Map.entry("approvalRequestCreated", true),
                        Map.entry("validatedDryRunIntent", true),
                        Map.entry("dryRunIntentPersisted", true),
                        Map.entry("reviewSurface", "CODE_WORKSPACE_LOOP_REVIEW"),
                        Map.entry("requestPersisted", true),
                        Map.entry("queueEnabled", false),
                        Map.entry("pushEnabled", false),
                        Map.entry("claimable", false),
                        Map.entry("dryRunOnly", true),
                        Map.entry("mutationAllowed", false),
                        Map.entry("requestCreationEnabled", false),
                        Map.entry("claimEnabled", false),
                        Map.entry("mutationEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(approvalRequest))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("WAIT_FOR_APPROVAL");
        assertThat(result.reason()).isEqualTo("Review the persisted validated dry-run intent before any future claimable non-mutating dry-run.");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.sourceEventId()).isEqualTo(requestEventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_APPROVAL_REQUEST_CREATED");
        assertThat(result.sourceDetails()).containsEntry("decisionKey", "VALIDATED_DRY_RUN_INTENT_REVIEW")
                .containsEntry("validatedDryRunIntent", true)
                .containsEntry("dryRunIntentPersisted", true)
                .containsEntry("reviewSurface", "CODE_WORKSPACE_LOOP_REVIEW")
                .containsEntry("requestPersisted", true)
                .containsEntry("queueEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        assertThat(result.handoffSummary()).containsEntry("schema", "learnbot.code-agent.validated-dry-run-intent-review-handoff.v1")
                .containsEntry("status", "VALIDATED_DRY_RUN_INTENT_REVIEW")
                .containsEntry("sourceRequestId", requestId.toString())
                .containsEntry("eligibilityRoute", "GET /api/code-agent/local-patch-request/dry-run-intent/" + requestId + "/eligibility")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("approvalBypassAllowed", false);
    }

    @Test
    void nextActionWaitsForReleaseGateAfterApprovedHeldPatchDecision() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID approvalEventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary approvalRequest = event(
                UUID.randomUUID(),
                13,
                "LOCAL_AGENT_APPROVAL_REQUEST_CREATED",
                Map.of(
                        "status", "APPROVAL_REQUIRED",
                        "decisionKey", "APPROVAL_REQUEST_CREATED",
                        "nextAction", "Wait for explicit user approval before release, claim, or mutation.",
                        "approvalRequestCreated", true,
                        "mutationEnabled", false
                )
        );
        CodeAgentLoopTimelineEventSummary approvalDecision = event(
                approvalEventId,
                14,
                "LOCAL_AGENT_APPROVAL_DECISION",
                Map.ofEntries(
                        Map.entry("status", "APPROVED_HELD"),
                        Map.entry("approvalState", "APPROVED"),
                        Map.entry("decisionKey", "APPROVAL_APPROVED_HELD"),
                        Map.entry("nextAction", "Inspect release readiness and queue fresh release-attempt observations before any claimable mutation transition."),
                        Map.entry("approvalRequestHeld", true),
                        Map.entry("releaseRequired", true),
                        Map.entry("releaseGateEnabled", false),
                        Map.entry("requestCreationEnabled", false),
                        Map.entry("pushEnabled", false),
                        Map.entry("claimEnabled", false),
                        Map.entry("mutationEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(approvalRequest, approvalDecision))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("WAIT_FOR_RELEASE_GATE");
        assertThat(result.reason()).isEqualTo("Inspect release readiness and queue fresh release-attempt observations before any claimable mutation transition.");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.recommendedAction())
                .containsEntry("actionKey", "CHECK_ENQUEUE_REFUSAL")
                .containsEntry("label", "Check enqueue refusal")
                .containsEntry("endpoint", "/api/code-agent/loop/runner/enqueue-read-only")
                .containsEntry("enabled", true)
                .containsEntry("mutationEnabled", false);
        assertThat(result.sourceEventId()).isEqualTo(approvalEventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_APPROVAL_DECISION");
        assertThat(result.sourceDetails())
                .containsEntry("approvalRequestHeld", true)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("mutationEnabled", false);
    }

    @Test
    void nextActionWaitsForFreshObservationResultsAfterReleaseFreshObservationEnqueue() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID gitStatusRequestId = UUID.randomUUID();
        UUID patchDryRunRequestId = UUID.randomUUID();
        UUID enqueueEventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary approvalDecision = event(
                UUID.randomUUID(),
                14,
                "LOCAL_AGENT_APPROVAL_DECISION",
                Map.of(
                        "status", "APPROVED_HELD",
                        "approvalState", "APPROVED",
                        "decisionKey", "APPROVAL_APPROVED_HELD",
                        "mutationEnabled", false
                )
        );
        CodeAgentLoopTimelineEventSummary freshEnqueue = event(
                enqueueEventId,
                15,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED",
                Map.ofEntries(
                        Map.entry("status", "FRESH_OBSERVATIONS_ENQUEUED"),
                        Map.entry("decisionKey", "WAIT_FOR_FRESH_OBSERVATION_RESULTS"),
                        Map.entry("nextAction", "Wait for fresh release-attempt Local Agent observations before any release or claimable mutation transition."),
                        Map.entry("sourceRequestId", sourceRequestId.toString()),
                        Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                        Map.entry("queuedRequestCount", 2),
                        Map.entry("queuedRequestIds", List.of(gitStatusRequestId.toString(), patchDryRunRequestId.toString())),
                        Map.entry("queuedToolNames", List.of("git.status", "patch.apply")),
                        Map.entry("queuedApprovalStates", List.of("NOT_REQUIRED", "APPROVED")),
                        Map.entry("observationResultsRequired", true),
                        Map.entry("releaseGateEnabled", false),
                        Map.entry("sourcePatchClaimEnabled", false),
                        Map.entry("claimEnabled", false),
                        Map.entry("mutationEnabled", false),
                        Map.entry("verificationCommandExecutionEnabled", false),
                        Map.entry("rollbackRestoreEnabled", false),
                        Map.entry("ragFreshnessUpdateEnabled", false),
                        Map.entry("finalResultEnabled", false),
                        Map.entry("publicationEnabled", false),
                        Map.entry("finalAnswerGenerationEnabled", false),
                        Map.entry("deliveryEnabled", false),
                        Map.entry("acknowledgementEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(approvalDecision, freshEnqueue))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("WAIT_FOR_FRESH_OBSERVATION_RESULTS");
        assertThat(result.reason()).contains("Wait for fresh release-attempt Local Agent observations");
        assertThat(result.sourceEventId()).isEqualTo(enqueueEventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.release-gate-fresh-observation-enqueue-state.v1")
                .containsEntry("status", "WAIT_FOR_FRESH_OBSERVATION_RESULTS")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("queuedRequestCount", 2)
                .containsEntry("observationResultsRequired", true)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("deliveryEnabled", false);
    }

    @Test
    void nextActionReportsFreshEvidenceCompleteButReleaseStillGated() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID completeEventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary genericDecision = event(
                UUID.randomUUID(),
                18,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of(
                        "status", "RECORDED",
                        "decisionKey", "OBSERVATION_ACCEPTED",
                        "mutationEnabled", false
                )
        );
        CodeAgentLoopTimelineEventSummary complete = event(
                completeEventId,
                19,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE",
                Map.ofEntries(
                        Map.entry("status", "FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED"),
                        Map.entry("decisionKey", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED"),
                        Map.entry("nextAction", "Fresh release-attempt evidence is complete; inspect release readiness while release, claim, and mutation remain disabled."),
                        Map.entry("sourceRequestId", sourceRequestId.toString()),
                        Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                        Map.entry("evidenceComplete", true),
                        Map.entry("requiredCount", 2),
                        Map.entry("linkedCount", 2),
                        Map.entry("missingCount", 0),
                        Map.entry("sourceOnlyFallbackCount", 0),
                        Map.entry("blockingCount", 0),
                        Map.entry("linkedKeys", List.of("repositoryVerification", "patchDryRun")),
                        Map.entry("blockingKeys", List.of()),
                        Map.entry("releaseGateEnabled", false),
                        Map.entry("sourcePatchClaimEnabled", false),
                        Map.entry("claimEnabled", false),
                        Map.entry("mutationEnabled", false),
                        Map.entry("verificationCommandExecutionEnabled", false),
                        Map.entry("rollbackRestoreEnabled", false),
                        Map.entry("ragFreshnessUpdateEnabled", false),
                        Map.entry("finalResultEnabled", false),
                        Map.entry("publicationEnabled", false),
                        Map.entry("finalAnswerGenerationEnabled", false),
                        Map.entry("deliveryEnabled", false),
                        Map.entry("acknowledgementEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(genericDecision, complete))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("FRESH_EVIDENCE_COMPLETE_RELEASE_GATED");
        assertThat(result.reason()).contains("Fresh release-attempt evidence is complete");
        assertThat(result.sourceEventId()).isEqualTo(completeEventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.release-gate-fresh-observation-complete-state.v1")
                .containsEntry("status", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("evidenceComplete", true)
                .containsEntry("requiredCount", 2)
                .containsEntry("linkedCount", 2)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("deliveryEnabled", false);
    }

    @Test
    void nextActionReportsReleaseReadinessRefreshAfterFreshEvidenceComplete() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID readinessEventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary complete = event(
                UUID.randomUUID(),
                19,
                "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE",
                Map.of(
                        "status", "FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED",
                        "decisionKey", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED",
                        "sourceRequestId", sourceRequestId.toString(),
                        "releaseAttemptId", releaseAttemptId.toString(),
                        "mutationEnabled", false
                )
        );
        CodeAgentLoopTimelineEventSummary readiness = event(
                readinessEventId,
                20,
                "LOCAL_AGENT_RELEASE_READINESS_REFRESHED",
                Map.ofEntries(
                        Map.entry("status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED"),
                        Map.entry("decisionKey", "RELEASE_READINESS_REFRESHED_RELEASE_GATED"),
                        Map.entry("nextAction", "Release readiness was refreshed from fresh evidence; release, claim, and mutation remain disabled."),
                        Map.entry("sourceRequestId", sourceRequestId.toString()),
                        Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                        Map.entry("readyToRelease", false),
                        Map.entry("readinessMessage", "Held patch request is not ready for Local Agent execution."),
                        Map.entry("warningCount", 1),
                        Map.entry("checkCount", 2),
                        Map.entry("failedCheckKeys", List.of("releaseGateEnabled")),
                        Map.entry("patchReleaseStatus", "BLOCKED_RELEASE_DISABLED"),
                        Map.entry("patchReleasePreconditionsPassed", false),
                        Map.entry("patchExecutionGateStatus", "BLOCKED_RELEASE_DISABLED"),
                        Map.entry("patchExecutionPreconditionsPassed", false),
                        Map.entry("releaseAttemptReady", false),
                        Map.entry("freshObservationEvidenceComplete", true),
                        Map.entry("releaseAttemptFinalReadiness", Map.of(
                                "releaseAttemptReady", false,
                                "freshObservationEvidenceComplete", true
                        )),
                        Map.entry("releaseGateEnabled", false),
                        Map.entry("sourcePatchClaimEnabled", false),
                        Map.entry("claimEnabled", false),
                        Map.entry("claimable", false),
                        Map.entry("mutationEnabled", false),
                        Map.entry("verificationCommandExecutionEnabled", false),
                        Map.entry("rollbackRestoreEnabled", false),
                        Map.entry("ragFreshnessUpdateEnabled", false),
                        Map.entry("finalResultEnabled", false),
                        Map.entry("publicationEnabled", false),
                        Map.entry("finalAnswerGenerationEnabled", false),
                        Map.entry("deliveryEnabled", false),
                        Map.entry("acknowledgementEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(complete, readiness))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("RELEASE_READINESS_REFRESHED_RELEASE_GATED");
        assertThat(result.reason()).contains("Release readiness was refreshed");
        assertThat(result.sourceEventId()).isEqualTo(readinessEventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_RELEASE_READINESS_REFRESHED");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.release-readiness-refresh-state.v1")
                .containsEntry("status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("readyToRelease", false)
                .containsEntry("patchReleaseStatus", "BLOCKED_RELEASE_DISABLED")
                .containsEntry("releaseAttemptReady", false)
                .containsEntry("freshObservationEvidenceComplete", true)
                .containsEntry("sourcePatchClaimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("deliveryEnabled", false);
    }

    @Test
    void nextActionPrefersLaterStopOutcomeAfterFailedObservation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary decision = event(
                UUID.randomUUID(),
                8,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "STOP_AFTER_OBSERVATION", "nextAction", "Stop after failed observation.")
        );
        CodeAgentLoopTimelineEventSummary stop = event(
                UUID.randomUUID(),
                9,
                "STOP_OUTCOME_RECORDED",
                Map.of("status", "RECORDED", "stopKey", "TOOL_FAILED", "action", "Report the failed tool observation.")
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(decision, stop))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("STOP_WITH_REASON");
        assertThat(result.reason()).isEqualTo("Report the failed tool observation.");
        assertThat(result.sourceEventType()).isEqualTo("STOP_OUTCOME_RECORDED");
        assertThat(result.mutationEnabled()).isFalse();
    }

    @Test
    void nextActionStopsWithReleaseBoundaryRefusalSummary() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary releaseBoundary = event(
                eventId,
                14,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                Map.ofEntries(
                        Map.entry("status", "RECORDED"),
                        Map.entry("decisionKey", "RELEASE_BOUNDARY_REFUSED"),
                        Map.entry("nextAction", "Report that release was refused and mutation remains disabled."),
                        Map.entry("requestId", "source-request-1"),
                        Map.entry("releaseAttemptId", "release-attempt-1"),
                        Map.entry("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED"),
                        Map.entry("actionMode", "REFUSAL_ONLY"),
                        Map.entry("blockingReasons", List.of("release gate is disabled", "held patch request remains non-claimable")),
                        Map.entry("releaseGateEnabled", false),
                        Map.entry("requestCreationEnabled", false),
                        Map.entry("pushEnabled", false),
                        Map.entry("claimEnabled", false),
                        Map.entry("claimable", false),
                        Map.entry("mutationEnabled", false),
                        Map.entry("rollbackRestoreEnabled", false),
                        Map.entry("ragFreshnessUpdateEnabled", false),
                        Map.entry("finalResultEnabled", false),
                        Map.entry("publicationEnabled", false),
                        Map.entry("acknowledgementEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(releaseBoundary))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.loopId()).isEqualTo(loopId);
        assertThat(result.actionKey()).isEqualTo("STOP_WITH_REASON");
        assertThat(result.reason()).isEqualTo("Report that release was refused and mutation remains disabled.");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.sourceEventId()).isEqualTo(eventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED");
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.release-boundary-refusal-summary.v1")
                .containsEntry("status", "RELEASE_REVIEW_REFUSED_GATE_DISABLED")
                .containsEntry("sourceRequestId", "source-request-1")
                .containsEntry("releaseAttemptId", "release-attempt-1")
                .containsEntry("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED")
                .containsEntry("actionMode", "REFUSAL_ONLY")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("runnerDecision", "NO_REQUEST_PREPARED");
    }

    @Test
    void nextActionDistinguishesReadyHandoffCreationDisabledAfterReleaseBoundaryRefusal() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary releaseBoundary = event(
                eventId,
                15,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                Map.of(
                        "status", "RECORDED",
                        "decisionKey", "RELEASE_BOUNDARY_REFUSED",
                        "releaseAttemptModel", Map.of(
                                "latestAttempt", Map.of(
                                        "mutationDispatchPreflightBoundary", Map.of(
                                                "status", "READY_PREFLIGHT_DISABLED",
                                                "prerequisitesPassed", true
                                        ),
                                        "mutationRequestBlueprint", Map.of(
                                                "status", "REFUSED_REQUEST_CREATION_DISABLED",
                                                "prerequisitesPassed", true,
                                                "requestCreationEnabled", false,
                                                "pushEnabled", false,
                                                "claimEnabled", false,
                                                "mutationAllowed", false
                                        ),
                                        "mutationRequestCreationGate", Map.of(
                                                "status", "REFUSED_CREATION_DISABLED",
                                                "prerequisitesPassed", true,
                                                "expectedRequestCount", 4,
                                                "durableMutationExecutionRowCount", 0,
                                                "persistedRequestCount", 0,
                                                "pushedRequestCount", 0,
                                                "claimableRequestCount", 0
                                        ),
                                        "mutationRequestPushGate", Map.of(
                                                "status", "REFUSED_PUSH_DISABLED"
                                        ),
                                        "mutationRequestClaimGate", Map.of(
                                                "status", "REFUSED_CLAIM_DISABLED"
                                        )
                                )
                        )
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(releaseBoundary))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.loopId()).isEqualTo(loopId);
        assertThat(result.status()).isEqualTo("RECORDED");
        assertThat(result.actionKey()).isEqualTo("READY_HANDOFF_CREATION_DISABLED");
        assertThat(result.reason()).contains("request creation, push, claim, and mutation remain disabled");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.creation-disabled-handoff-summary.v1")
                .containsEntry("status", "READY_HANDOFF_CREATION_DISABLED")
                .containsEntry("sourceBlueprintStatus", "REFUSED_REQUEST_CREATION_DISABLED")
                .containsEntry("sourceCreationGateStatus", "REFUSED_CREATION_DISABLED")
                .containsEntry("sourcePushGateStatus", "REFUSED_PUSH_DISABLED")
                .containsEntry("sourceClaimGateStatus", "REFUSED_CLAIM_DISABLED")
                .containsEntry("expectedRequestCount", 4)
                .containsEntry("durableMutationExecutionRowCount", 0)
                .containsEntry("persistedRequestCount", 0)
                .containsEntry("pushedRequestCount", 0)
                .containsEntry("claimableRequestCount", 0)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("runnerDecision", "WAIT_CREATION_GATE_DISABLED");
        assertThat(result.sourceEventId()).isEqualTo(eventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED");
    }

    @Test
    void nextActionReportsCompletedApprovedExecutionFlowAsFinalResultDisabledHandoff() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> inspection = Map.of(
                "schema", "learnbot.local-agent.approved-execution-flow-contract.v1",
                "requestIdSource", "durableCompletedRows",
                "stepCount", 4,
                "ordered", true,
                "identityConsistent", true,
                "releaseAttemptLinked", true,
                "approvalRequestLinked", true,
                "postRetryVerification", Map.of(
                        "schema", "learnbot.local-agent.post-retry-verification.v1",
                        "passed", true,
                        "partialReindexMarkerRequired", true
                ),
                "allTerminal", true,
                "steps", List.of(
                        Map.of("toolName", "patch.apply", "status", "SUCCEEDED"),
                        Map.of("toolName", "command.runAllowed", "status", "SUCCEEDED"),
                        Map.of("toolName", "git.status", "status", "SUCCEEDED"),
                        Map.of("toolName", "rollback.restore", "status", "SUCCEEDED")
                )
        );
        Map<String, Object> finalResultHandoff = Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1"),
                Map.entry("status", "READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("finalMutationReportSummaryStatus", "READY_SUMMARY_AUDIT_ONLY"),
                Map.entry("postRetryVerificationPassed", true),
                Map.entry("postRetryVerificationPartialReindexMarkerRequired", true),
                Map.entry("ragFreshnessMarkerStatus", "STALE_INDEX_WARNING_REQUIRED"),
                Map.entry("partialReindexPlanStatus", "PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED"),
                Map.entry("partialReindexEnqueueBoundaryStatus", "READY_ENQUEUE_DISABLED"),
                Map.entry("partialReindexEnqueueReady", true),
                Map.entry("finalAnswerPublicationHandoffStatus", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED"),
                Map.entry("acknowledgementSaveHandoffStatus", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED"),
                Map.entry("finalResultEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementSaveEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("mutationEnabled", false)
        );
        CodeAgentLoopTimelineEventSummary completed = event(
                eventId,
                31,
                "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED",
                Map.ofEntries(
                        Map.entry("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED"),
                        Map.entry("sourceRequestId", sourceRequestId.toString()),
                        Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                        Map.entry("sessionId", sessionId.toString()),
                        Map.entry("userId", userId.toString()),
                        Map.entry("agentId", agentId.toString()),
                        Map.entry("workspaceId", workspaceId.toString()),
                        Map.entry("approvedFlowInspection", inspection),
                        Map.entry("requestIdSource", "durableCompletedRows"),
                        Map.entry("stepCount", 4),
                        Map.entry("ordered", true),
                        Map.entry("identityConsistent", true),
                        Map.entry("releaseAttemptLinked", true),
                        Map.entry("approvalRequestLinked", true),
                        Map.entry("postRetryVerification", inspection.get("postRetryVerification")),
                        Map.entry("allTerminal", true),
                        Map.entry("allSucceeded", true),
                        Map.entry("finalResultHandoff", finalResultHandoff),
                        Map.entry("finalMutationReportSummaryStatus", "READY_SUMMARY_AUDIT_ONLY"),
                        Map.entry("postRetryVerificationPassed", true),
                        Map.entry("postRetryVerificationPartialReindexMarkerRequired", true),
                        Map.entry("ragFreshnessMarkerStatus", "STALE_INDEX_WARNING_REQUIRED"),
                        Map.entry("partialReindexPlanStatus", "PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED"),
                        Map.entry("partialReindexEnqueueBoundaryStatus", "READY_ENQUEUE_DISABLED"),
                        Map.entry("partialReindexEnqueueReady", true),
                        Map.entry("finalAnswerPublicationHandoffStatus", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED"),
                        Map.entry("acknowledgementSaveHandoffStatus", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED"),
                        Map.entry("nextAction", "Report the completed approved Local Agent execution flow while final result publication and acknowledgement save remain disabled."),
                        Map.entry("finalResultEnabled", false),
                        Map.entry("publicationEnabled", false),
                        Map.entry("acknowledgementEnabled", false),
                        Map.entry("ragFreshnessUpdateEnabled", false),
                        Map.entry("followUpMutationEnabled", false),
                        Map.entry("mutationEnabled", false)
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(completed))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.actionKey()).isEqualTo("APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED");
        assertThat(result.status()).isEqualTo("APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED");
        assertThat(result.sourceEventId()).isEqualTo(eventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.handoffSummary())
                .containsEntry("schema", "learnbot.code-agent.approved-execution-flow-completed-handoff.v1")
                .containsEntry("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED")
                .containsEntry("runnerDecision", "READY_FINAL_RESULT_DISABLED")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("requestIdSource", "durableCompletedRows")
                .containsEntry("stepCount", 4)
                .containsEntry("ordered", true)
                .containsEntry("identityConsistent", true)
                .containsEntry("releaseAttemptLinked", true)
                .containsEntry("approvalRequestLinked", true)
                .containsEntry("allTerminal", true)
                .containsEntry("allSucceeded", true)
                .containsEntry("finalMutationReportSummaryStatus", "READY_SUMMARY_AUDIT_ONLY")
                .containsEntry("postRetryVerificationPassed", true)
                .containsEntry("postRetryVerificationPartialReindexMarkerRequired", true)
                .containsEntry("ragFreshnessMarkerStatus", "STALE_INDEX_WARNING_REQUIRED")
                .containsEntry("partialReindexPlanStatus", "PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED")
                .containsEntry("partialReindexEnqueueBoundaryStatus", "READY_ENQUEUE_DISABLED")
                .containsEntry("partialReindexEnqueueReady", true)
                .containsEntry("finalAnswerPublicationHandoffStatus", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED")
                .containsEntry("acknowledgementSaveHandoffStatus", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED")
                .containsEntry("finalResultEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("followUpMutationEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(result.handoffSummary().get("approvedFlowInspection")).isEqualTo(inspection);
        assertThat(result.handoffSummary().get("postRetryVerification")).isEqualTo(inspection.get("postRetryVerification"));
        assertThat(result.handoffSummary().get("finalResultHandoff")).isEqualTo(finalResultHandoff);
        assertThat(result.recommendedAction()).containsEntry("actionKey", "STOP_AND_REPORT");
    }

    @Test
    void nextActionAsksUserWhenTimelineIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        when(timelineRepository.findRecent(userId, repositoryId, 20)).thenReturn(List.of());

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.loopId()).isEqualTo(loopId);
        assertThat(result.status()).isEqualTo("NO_TIMELINE");
        assertThat(result.actionKey()).isEqualTo("ASK_USER");
        assertThat(result.sourceDetails()).isEmpty();
        assertThat(result.mutationEnabled()).isFalse();
    }

    private CodeAgentLoopTimelineSummary timeline(
            UUID loopId,
            UUID repositoryId,
            List<CodeAgentLoopTimelineEventSummary> events
    ) {
        return new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                UUID.randomUUID(),
                "fix this bug",
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

    private CodeAgentLoopTimelineEventSummary event(UUID id, int sequenceNumber, String eventType, Map<String, Object> details) {
        return new CodeAgentLoopTimelineEventSummary(
                id,
                sequenceNumber,
                eventType,
                "COMPLETE_OR_PAUSE",
                AgentExecutionTarget.SERVER_LOCAL,
                null,
                false,
                false,
                true,
                details,
                OffsetDateTime.now()
        );
    }
}
