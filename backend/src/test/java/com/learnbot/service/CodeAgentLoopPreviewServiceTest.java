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
    void nextActionWaitsForReleaseGateAfterReleaseBoundaryRefusal() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CodeAgentLoopTimelineEventSummary releaseBoundary = event(
                eventId,
                14,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                Map.of(
                        "status", "RECORDED",
                        "decisionKey", "RELEASE_BOUNDARY_REFUSED",
                        "nextAction", "Wait for release gate enablement or report that mutation remains disabled.",
                        "releaseGateEnabled", false,
                        "requestCreationEnabled", false,
                        "pushEnabled", false,
                        "claimEnabled", false,
                        "mutationEnabled", false
                )
        );
        when(timelineRepository.findRecent(userId, repositoryId, 20))
                .thenReturn(List.of(timeline(loopId, repositoryId, List.of(releaseBoundary))));

        var result = service.nextAction(userId, repositoryId, loopId);

        assertThat(result.loopId()).isEqualTo(loopId);
        assertThat(result.actionKey()).isEqualTo("WAIT_FOR_RELEASE_GATE");
        assertThat(result.reason()).isEqualTo("Wait for release gate enablement or report that mutation remains disabled.");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.finalResultEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementEnabled()).isFalse();
        assertThat(result.sourceEventId()).isEqualTo(eventId);
        assertThat(result.sourceEventType()).isEqualTo("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED");
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
