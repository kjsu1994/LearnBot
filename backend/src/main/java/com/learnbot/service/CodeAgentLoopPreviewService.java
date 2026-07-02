package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopStopCondition;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.loop.CodeAgentLoopRecommendedActionFactory;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CodeAgentLoopPreviewService {
    private static final int DEFAULT_MAX_STEPS = 6;
    private static final int MIN_MAX_STEPS = 4;
    private static final int HARD_MAX_STEPS = 8;
    private static final int TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_RECENT_TIMELINES = 5;
    private static final int HARD_MAX_RECENT_TIMELINES = 20;

    private final CodeAgentLoopTimelineRepository timelineRepository;

    public CodeAgentLoopPreviewService(CodeAgentLoopTimelineRepository timelineRepository) {
        this.timelineRepository = timelineRepository;
    }

    public CodeAgentLoopPreviewResponse preview(UUID userId, UUID repositoryId, UUID spaceId, String instruction, Integer requestedMaxSteps) {
        int maxSteps = boundedMaxSteps(requestedMaxSteps);
        CodeAgentLoopPreviewResponse preview = new CodeAgentLoopPreviewResponse(
                UUID.randomUUID(),
                repositoryId,
                spaceId,
                "PREVIEW_ONLY",
                maxSteps,
                TIMEOUT_SECONDS,
                false,
                true,
                false,
                steps(),
                stopConditions(),
                warnings(instruction)
        );
        timelineRepository.createPreview(userId, instruction, preview);
        return preview;
    }

    public List<CodeAgentLoopTimelineSummary> recentTimelines(UUID userId, UUID repositoryId, Integer requestedLimit) {
        int limit = requestedLimit == null
                ? DEFAULT_RECENT_TIMELINES
                : Math.max(1, Math.min(HARD_MAX_RECENT_TIMELINES, requestedLimit));
        return timelineRepository.findRecent(userId, repositoryId, limit);
    }

    public CodeAgentLoopNextActionResponse nextAction(UUID userId, UUID repositoryId, UUID loopId) {
        Optional<CodeAgentLoopTimelineSummary> timeline = timelineRepository
                .findRecent(userId, repositoryId, HARD_MAX_RECENT_TIMELINES)
                .stream()
                .filter(candidate -> loopId == null || candidate.id().equals(loopId))
                .findFirst();
        if (timeline.isEmpty()) {
            return nextAction(
                    loopId,
                    repositoryId,
                    "NO_TIMELINE",
                    "ASK_USER",
                    "No loop timeline is available yet. Ask the user for the next bounded code-agent goal.",
                    null
            );
        }

        CodeAgentLoopTimelineSummary selected = timeline.get();
        Optional<CodeAgentLoopTimelineEventSummary> latestStop = latestEvent(selected, "STOP_OUTCOME_RECORDED");
        Optional<CodeAgentLoopTimelineEventSummary> latestReleaseBoundary = latestEvent(selected, "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED");
        Optional<CodeAgentLoopTimelineEventSummary> latestReleaseReadinessRefresh = latestEvent(selected, "LOCAL_AGENT_RELEASE_READINESS_REFRESHED");
        Optional<CodeAgentLoopTimelineEventSummary> latestApprovedExecutionFlowCompleted = latestEvent(selected, "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED");
        Optional<CodeAgentLoopTimelineEventSummary> latestFreshObservationEnqueue = latestEvent(selected, "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED");
        Optional<CodeAgentLoopTimelineEventSummary> latestFreshObservationComplete = latestEvent(selected, "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE");
        Optional<CodeAgentLoopTimelineEventSummary> latestDecision = latestEvent(selected, "LOOP_NEXT_DECISION_RECORDED");
        Optional<CodeAgentLoopTimelineEventSummary> latestApprovalRequest = latestEvent(selected, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED");
        Optional<CodeAgentLoopTimelineEventSummary> latestApproval = latestEvent(selected, "LOCAL_AGENT_APPROVAL_DECISION");
        Optional<CodeAgentLoopTimelineEventSummary> latestObservation = latestEvent(selected, "LOCAL_AGENT_OBSERVATION_RESULT");

        if (latestStop.isPresent()
                && isSameOrAfter(latestStop.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestStop.get(), latestReleaseBoundary.orElse(null))
                && isSameOrAfter(latestStop.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestStop.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestStop.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestStop.get(), latestFreshObservationComplete.orElse(null))) {
            return fromStopOutcome(selected, latestStop.get());
        }
        if (latestApprovedExecutionFlowCompleted.isPresent()
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestReleaseBoundary.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestFreshObservationComplete.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestApprovalRequest.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestApprovedExecutionFlowCompleted.get(), latestStop.orElse(null))) {
            return fromApprovedExecutionFlowCompleted(selected, latestApprovedExecutionFlowCompleted.get());
        }
        if (latestReleaseBoundary.isPresent()
                && isSameOrAfter(latestReleaseBoundary.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestReleaseBoundary.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestReleaseBoundary.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestReleaseBoundary.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestReleaseBoundary.get(), latestFreshObservationComplete.orElse(null))
                && isSameOrAfter(latestReleaseBoundary.get(), latestStop.orElse(null))) {
            return fromReleaseBoundary(selected, latestReleaseBoundary.get());
        }
        if (latestReleaseReadinessRefresh.isPresent()
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestReleaseBoundary.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestFreshObservationComplete.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestApprovalRequest.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestReleaseReadinessRefresh.get(), latestStop.orElse(null))) {
            return fromReleaseReadinessRefresh(selected, latestReleaseReadinessRefresh.get());
        }
        if (latestFreshObservationComplete.isPresent()
                && isSameOrAfter(latestFreshObservationComplete.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestApprovalRequest.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestFreshObservationComplete.get(), latestStop.orElse(null))) {
            return fromFreshObservationComplete(selected, latestFreshObservationComplete.get());
        }
        if (latestFreshObservationEnqueue.isPresent()
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestFreshObservationComplete.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestApprovalRequest.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestStop.orElse(null))) {
            return fromFreshObservationEnqueue(selected, latestFreshObservationEnqueue.get());
        }
        if (latestApprovalRequest.isPresent()
                && isSameOrAfter(latestApprovalRequest.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestFreshObservationComplete.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestStop.orElse(null))) {
            CodeAgentLoopTimelineEventSummary event = latestApprovalRequest.get();
            Map<String, Object> dryRunIntentHandoff = Boolean.TRUE.equals(event.details().get("validatedDryRunIntent"))
                    ? validatedDryRunIntentHandoffSummary(event)
                    : Map.of();
            return nextAction(
                    selected.id(),
                    selected.repositoryId(),
                    stringDetail(event, "status", "RECORDED"),
                    "WAIT_FOR_APPROVAL",
                    stringDetail(event, "nextAction", "Wait for explicit user approval before release, claim, or mutation."),
                    event,
                    dryRunIntentHandoff
            );
        }
        if (latestApproval.isPresent()
                && isSameOrAfter(latestApproval.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestApproval.get(), latestReleaseReadinessRefresh.orElse(null))
                && isSameOrAfter(latestApproval.get(), latestApprovedExecutionFlowCompleted.orElse(null))
                && isSameOrAfter(latestApproval.get(), latestFreshObservationEnqueue.orElse(null))
                && isSameOrAfter(latestApproval.get(), latestFreshObservationComplete.orElse(null))
                && isSameOrAfter(latestApproval.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestApproval.get(), latestStop.orElse(null))) {
            return fromApprovalDecision(selected, latestApproval.get());
        }
        if (latestDecision.isPresent()) {
            return fromNextDecision(selected, latestDecision.get());
        }
        if (latestObservation.isPresent()) {
            return nextAction(
                    selected.id(),
                    selected.repositoryId(),
                    "RECORDED",
                    "WAIT_FOR_APPROVAL",
                    "A Local Agent observation exists, but no server next-decision event has been recorded yet.",
                    latestObservation.get()
            );
        }
        return nextAction(
                selected.id(),
                selected.repositoryId(),
                "PREVIEW_ONLY",
                "ASK_USER",
                "Only the read-only loop preview is available. Ask for confirmation before selecting a Local Agent tool.",
                selected.events().stream().max(Comparator.comparingInt(CodeAgentLoopTimelineEventSummary::sequenceNumber)).orElse(null)
        );
    }

    private int boundedMaxSteps(Integer requestedMaxSteps) {
        if (requestedMaxSteps == null) {
            return DEFAULT_MAX_STEPS;
        }
        return Math.max(MIN_MAX_STEPS, Math.min(HARD_MAX_STEPS, requestedMaxSteps));
    }

    private CodeAgentLoopNextActionResponse fromStopOutcome(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "RECORDED"),
                "STOP_WITH_REASON",
                stringDetail(event, "action", "Stop the loop and report the blocking state."),
                event
        );
    }

    private CodeAgentLoopNextActionResponse fromNextDecision(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        String decisionKey = stringDetail(event, "decisionKey", "");
        if ("OBSERVATION_ACCEPTED".equals(decisionKey)) {
            return nextAction(
                    timeline.id(),
                    timeline.repositoryId(),
                    stringDetail(event, "status", "RECORDED"),
                    "QUEUE_READ_ONLY_OBSERVATION",
                    stringDetail(event, "nextAction", "Evaluate the observation before selecting another typed tool."),
                    event
            );
        }
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "RECORDED"),
                "STOP_WITH_REASON",
                stringDetail(event, "nextAction", "Stop after the Local Agent observation and report the blocking state."),
                event
        );
    }

    private CodeAgentLoopNextActionResponse fromFreshObservationEnqueue(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "FRESH_OBSERVATIONS_ENQUEUED"),
                "WAIT_FOR_FRESH_OBSERVATION_RESULTS",
                stringDetail(event, "nextAction", "Wait for fresh release-attempt Local Agent observations before any release or claimable mutation transition."),
                event,
                freshObservationEnqueueHandoffSummary(event)
        );
    }

    private CodeAgentLoopNextActionResponse fromFreshObservationComplete(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "FRESH_OBSERVATION_EVIDENCE_COMPLETE_RELEASE_GATED"),
                "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED",
                stringDetail(event, "nextAction", "Fresh release-attempt evidence is complete; inspect release readiness while release, claim, and mutation remain disabled."),
                event,
                freshObservationCompleteHandoffSummary(event)
        );
    }

    private CodeAgentLoopNextActionResponse fromReleaseReadinessRefresh(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED"),
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                stringDetail(event, "nextAction", "Release readiness was refreshed from fresh evidence; release, claim, and mutation remain disabled."),
                event,
                releaseReadinessRefreshHandoffSummary(event)
        );
    }

    private CodeAgentLoopNextActionResponse fromApprovedExecutionFlowCompleted(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED"),
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                stringDetail(event, "nextAction", "Report the completed approved Local Agent execution flow while final result publication and acknowledgement save remain disabled."),
                event,
                approvedExecutionFlowCompletedHandoffSummary(event)
        );
    }

    private CodeAgentLoopNextActionResponse fromApprovalDecision(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        String approvalState = stringDetail(event, "approvalState", "");
        String status = stringDetail(event, "status", "RECORDED");
        if ("APPROVED".equals(approvalState) && "APPROVED_HELD".equals(status)) {
            return nextAction(
                    timeline.id(),
                    timeline.repositoryId(),
                    "RECORDED",
                    "WAIT_FOR_RELEASE_GATE",
                    stringDetail(event, "nextAction", "Inspect release readiness and queue fresh release-attempt observations before any claimable mutation transition."),
                    event
            );
        }
        if ("DENIED".equals(approvalState) || "REJECTED".equals(status)) {
            return nextAction(
                    timeline.id(),
                    timeline.repositoryId(),
                    "RECORDED",
                    "STOP_WITH_REASON",
                    stringDetail(event, "nextAction", "Stop after approval denial without creating claimable work."),
                    event
            );
        }
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                "RECORDED",
                "WAIT_FOR_APPROVAL",
                "A Local Agent approval decision exists, but the side-effectful request is not approved-held yet.",
                event
        );
    }

    private CodeAgentLoopNextActionResponse fromReleaseBoundary(CodeAgentLoopTimelineSummary timeline, CodeAgentLoopTimelineEventSummary event) {
        if (handoffCreationDisabled(event)) {
            return nextAction(
                    timeline.id(),
                    timeline.repositoryId(),
                    stringDetail(event, "status", "RECORDED"),
                    "READY_HANDOFF_CREATION_DISABLED",
                    "Mutation handoff is modeled and preflight-ready, but request creation, push, claim, and mutation remain disabled.",
                    event,
                    creationDisabledHandoffSummary(event)
            );
        }
        return nextAction(
                timeline.id(),
                timeline.repositoryId(),
                stringDetail(event, "status", "RECORDED"),
                "STOP_WITH_REASON",
                stringDetail(event, "nextAction", "Report that release was refused and mutation remains disabled."),
                event,
                releaseBoundaryRefusalHandoffSummary(event)
        );
    }

    private Map<String, Object> releaseBoundaryRefusalHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.release-boundary-refusal-summary.v1");
        summary.put("status", "RELEASE_REVIEW_REFUSED_GATE_DISABLED");
        summary.put("sourceEventType", event.eventType());
        summary.put("sourceSequenceNumber", event.sequenceNumber());
        summary.put("sourceRequestId", event.details().get("requestId"));
        summary.put("releaseAttemptId", event.details().get("releaseAttemptId"));
        summary.put("boundaryStatus", event.details().get("boundaryStatus"));
        summary.put("actionMode", event.details().get("actionMode"));
        summary.put("blockingReasons", event.details().get("blockingReasons"));
        summary.put("releaseGateEnabled", event.details().get("releaseGateEnabled"));
        summary.put("requestCreationEnabled", event.details().get("requestCreationEnabled"));
        summary.put("pushEnabled", event.details().get("pushEnabled"));
        summary.put("claimEnabled", event.details().get("claimEnabled"));
        summary.put("claimable", event.details().get("claimable"));
        summary.put("mutationEnabled", event.details().get("mutationEnabled"));
        summary.put("verificationCommandExecutionEnabled", false);
        summary.put("rollbackRestoreEnabled", event.details().get("rollbackRestoreEnabled"));
        summary.put("ragFreshnessUpdateEnabled", event.details().get("ragFreshnessUpdateEnabled"));
        summary.put("finalResultEnabled", event.details().get("finalResultEnabled"));
        summary.put("publicationEnabled", event.details().get("publicationEnabled"));
        summary.put("finalAnswerGenerationEnabled", false);
        summary.put("deliveryEnabled", false);
        summary.put("acknowledgementEnabled", event.details().get("acknowledgementEnabled"));
        summary.put("runnerDecision", "NO_REQUEST_PREPARED");
        summary.put("message", event.details().getOrDefault(
                "message",
                "Release review refused the boundary; report the disabled release state without creating claimable mutation work."
        ));
        return summary;
    }

    private Map<String, Object> validatedDryRunIntentHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        Object requestId = event.details().get("requestId");
        summary.put("schema", "learnbot.code-agent.validated-dry-run-intent-review-handoff.v1");
        summary.put("status", "VALIDATED_DRY_RUN_INTENT_REVIEW");
        summary.put("sourceEventType", event.eventType());
        summary.put("sourceSequenceNumber", event.sequenceNumber());
        summary.put("sourceRequestId", requestId);
        summary.put("approvalState", event.details().get("approvalState"));
        summary.put("validatedDryRunIntent", event.details().get("validatedDryRunIntent"));
        summary.put("dryRunIntentPersisted", event.details().get("dryRunIntentPersisted"));
        summary.put("reviewSurface", event.details().get("reviewSurface"));
        summary.put("requestPersisted", event.details().get("requestPersisted"));
        summary.put("eligibilityRoute", requestId == null ? null : "GET /api/code-agent/local-patch-request/dry-run-intent/" + requestId + "/eligibility");
        summary.put("requestCreationEnabled", false);
        summary.put("queueEnabled", false);
        summary.put("pushEnabled", false);
        summary.put("claimEnabled", false);
        summary.put("claimable", false);
        summary.put("dryRunOnly", event.details().get("dryRunOnly"));
        summary.put("mutationAllowed", event.details().get("mutationAllowed"));
        summary.put("approvalBypassAllowed", false);
        summary.put("message", "Review the persisted validated dry-run intent eligibility before any future claimable non-mutating dry-run.");
        return summary;
    }

    private Map<String, Object> approvedExecutionFlowCompletedHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.approved-execution-flow-completed-handoff.v1");
        summary.put("status", "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED");
        summary.put("runnerDecision", "READY_FINAL_RESULT_DISABLED");
        summary.put("sourceEventType", event.eventType());
        summary.put("sourceSequenceNumber", event.sequenceNumber());
        summary.put("sourceRequestId", event.details().get("sourceRequestId"));
        summary.put("releaseAttemptId", event.details().get("releaseAttemptId"));
        summary.put("sessionId", event.details().get("sessionId"));
        summary.put("userId", event.details().get("userId"));
        summary.put("agentId", event.details().get("agentId"));
        summary.put("workspaceId", event.details().get("workspaceId"));
        summary.put("requestIdSource", event.details().get("requestIdSource"));
        summary.put("stepCount", event.details().get("stepCount"));
        summary.put("ordered", event.details().get("ordered"));
        summary.put("identityConsistent", event.details().get("identityConsistent"));
        summary.put("releaseAttemptLinked", event.details().get("releaseAttemptLinked"));
        summary.put("approvalRequestLinked", event.details().get("approvalRequestLinked"));
        summary.put("allTerminal", event.details().get("allTerminal"));
        summary.put("allSucceeded", event.details().get("allSucceeded"));
        summary.put("approvedFlowInspection", event.details().get("approvedFlowInspection"));
        summary.put("postRetryVerification", event.details().getOrDefault("postRetryVerification", Map.of()));
        summary.put("postRetryVerificationPassed", event.details().get("postRetryVerificationPassed"));
        summary.put("postRetryVerificationPartialReindexMarkerRequired", event.details().get("postRetryVerificationPartialReindexMarkerRequired"));
        summary.put("finalResultHandoff", event.details().getOrDefault("finalResultHandoff", Map.of()));
        summary.put("finalMutationReportSummaryStatus", stringDetail(event, "finalMutationReportSummaryStatus", "UNKNOWN_SUMMARY_AUDIT_ONLY"));
        summary.put("ragFreshnessMarkerStatus", stringDetail(event, "ragFreshnessMarkerStatus", "UNKNOWN_RAG_FRESHNESS_MARKER"));
        summary.put("partialReindexPlanStatus", stringDetail(event, "partialReindexPlanStatus", "UNKNOWN_PARTIAL_REINDEX_PLAN"));
        summary.put("partialReindexEnqueueBoundaryStatus", stringDetail(event, "partialReindexEnqueueBoundaryStatus", "UNKNOWN_PARTIAL_REINDEX_ENQUEUE_BOUNDARY"));
        summary.put("partialReindexEnqueueReady", event.details().get("partialReindexEnqueueReady"));
        summary.put("finalAnswerPublicationHandoffStatus", stringDetail(event, "finalAnswerPublicationHandoffStatus", "UNKNOWN_PUBLICATION_HANDOFF"));
        summary.put("acknowledgementSaveHandoffStatus", stringDetail(event, "acknowledgementSaveHandoffStatus", "UNKNOWN_ACKNOWLEDGEMENT_HANDOFF"));
        summary.put("finalResultEnabled", false);
        summary.put("publicationEnabled", false);
        summary.put("acknowledgementEnabled", false);
        summary.put("ragFreshnessUpdateEnabled", false);
        summary.put("followUpMutationEnabled", false);
        summary.put("mutationEnabled", false);
        summary.put("message", "Approved Local Agent execution flow is complete and visible for final-result handoff, but final publication, acknowledgement save, RAG freshness update, and follow-up mutation remain disabled.");
        return summary;
    }

    private boolean handoffCreationDisabled(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> releaseAttemptModel = mapDetail(event.details().get("releaseAttemptModel"));
        Map<String, Object> latestAttempt = mapDetail(releaseAttemptModel.get("latestAttempt"));
        Map<String, Object> blueprint = mapDetail(latestAttempt.get("mutationRequestBlueprint"));
        Map<String, Object> creationGate = mapDetail(latestAttempt.get("mutationRequestCreationGate"));
        Map<String, Object> preflight = mapDetail(latestAttempt.get("mutationDispatchPreflightBoundary"));
        return "REFUSED_REQUEST_CREATION_DISABLED".equals(blueprint.get("status"))
                && Boolean.TRUE.equals(blueprint.get("prerequisitesPassed"))
                && "REFUSED_CREATION_DISABLED".equals(creationGate.get("status"))
                && Boolean.TRUE.equals(creationGate.get("prerequisitesPassed"))
                && "READY_PREFLIGHT_DISABLED".equals(preflight.get("status"))
                && Boolean.TRUE.equals(preflight.get("prerequisitesPassed"));
    }

    private Map<String, Object> creationDisabledHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> releaseAttemptModel = mapDetail(event.details().get("releaseAttemptModel"));
        Map<String, Object> latestAttempt = mapDetail(releaseAttemptModel.get("latestAttempt"));
        Map<String, Object> blueprint = mapDetail(latestAttempt.get("mutationRequestBlueprint"));
        Map<String, Object> creationGate = mapDetail(latestAttempt.get("mutationRequestCreationGate"));
        Map<String, Object> pushGate = mapDetail(latestAttempt.get("mutationRequestPushGate"));
        Map<String, Object> claimGate = mapDetail(latestAttempt.get("mutationRequestClaimGate"));
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.creation-disabled-handoff-summary.v1");
        summary.put("status", "READY_HANDOFF_CREATION_DISABLED");
        summary.put("sourceBlueprintStatus", blueprint.get("status"));
        summary.put("sourceCreationGateStatus", creationGate.get("status"));
        summary.put("sourcePushGateStatus", pushGate.get("status"));
        summary.put("sourceClaimGateStatus", claimGate.get("status"));
        summary.put("expectedRequestCount", intDetail(creationGate, "expectedRequestCount"));
        summary.put("durableMutationExecutionRowCount", intDetail(creationGate, "durableMutationExecutionRowCount"));
        summary.put("persistedRequestCount", intDetail(creationGate, "persistedRequestCount"));
        summary.put("pushedRequestCount", intDetail(creationGate, "pushedRequestCount"));
        summary.put("claimableRequestCount", intDetail(creationGate, "claimableRequestCount"));
        summary.put("requestCreationEnabled", false);
        summary.put("pushEnabled", false);
        summary.put("claimEnabled", false);
        summary.put("mutationEnabled", false);
        summary.put("finalResultEnabled", false);
        summary.put("publicationEnabled", false);
        summary.put("acknowledgementEnabled", false);
        summary.put("runnerDecision", "WAIT_CREATION_GATE_DISABLED");
        summary.put("message", "The mutation handoff is ready to model, but no Local Agent mutation execution rows are created, pushed, claimable, or executable while request creation is disabled.");
        return summary;
    }

    private Map<String, Object> freshObservationEnqueueHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.release-gate-fresh-observation-enqueue-state.v1");
        summary.put("status", "WAIT_FOR_FRESH_OBSERVATION_RESULTS");
        summary.put("sourceEventType", event.eventType());
        summary.put("sourceSequenceNumber", event.sequenceNumber());
        summary.put("sourceRequestId", event.details().get("sourceRequestId"));
        summary.put("releaseAttemptId", event.details().get("releaseAttemptId"));
        summary.put("queuedRequestCount", event.details().get("queuedRequestCount"));
        summary.put("queuedRequestIds", event.details().get("queuedRequestIds"));
        summary.put("queuedToolNames", event.details().get("queuedToolNames"));
        summary.put("queuedApprovalStates", event.details().get("queuedApprovalStates"));
        summary.put("observationResultsRequired", event.details().get("observationResultsRequired"));
        summary.put("releaseGateEnabled", event.details().get("releaseGateEnabled"));
        summary.put("sourcePatchClaimEnabled", event.details().get("sourcePatchClaimEnabled"));
        summary.put("claimEnabled", event.details().get("claimEnabled"));
        summary.put("mutationEnabled", event.details().get("mutationEnabled"));
        summary.put("verificationCommandExecutionEnabled", event.details().get("verificationCommandExecutionEnabled"));
        summary.put("rollbackRestoreEnabled", event.details().get("rollbackRestoreEnabled"));
        summary.put("ragFreshnessUpdateEnabled", event.details().get("ragFreshnessUpdateEnabled"));
        summary.put("finalResultEnabled", event.details().get("finalResultEnabled"));
        summary.put("publicationEnabled", event.details().get("publicationEnabled"));
        summary.put("finalAnswerGenerationEnabled", event.details().get("finalAnswerGenerationEnabled"));
        summary.put("deliveryEnabled", event.details().get("deliveryEnabled"));
        summary.put("acknowledgementEnabled", event.details().get("acknowledgementEnabled"));
        summary.put("message", "Fresh release-attempt observations are queued; wait for their Local Agent results before release remains disabled.");
        return summary;
    }

    private Map<String, Object> freshObservationCompleteHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.release-gate-fresh-observation-complete-state.v1");
        summary.put("status", "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED");
        summary.put("sourceEventType", event.eventType());
        summary.put("sourceSequenceNumber", event.sequenceNumber());
        summary.put("sourceRequestId", event.details().get("sourceRequestId"));
        summary.put("releaseAttemptId", event.details().get("releaseAttemptId"));
        summary.put("evidenceComplete", event.details().get("evidenceComplete"));
        summary.put("requiredCount", event.details().get("requiredCount"));
        summary.put("linkedCount", event.details().get("linkedCount"));
        summary.put("missingCount", event.details().get("missingCount"));
        summary.put("sourceOnlyFallbackCount", event.details().get("sourceOnlyFallbackCount"));
        summary.put("blockingCount", event.details().get("blockingCount"));
        summary.put("linkedKeys", event.details().get("linkedKeys"));
        summary.put("blockingKeys", event.details().get("blockingKeys"));
        summary.put("freshObservationEvidenceCompleteness", event.details().get("freshObservationEvidenceCompleteness"));
        summary.put("freshObservationEvidenceStatus", event.details().get("freshObservationEvidenceStatus"));
        summary.put("releaseGateEnabled", event.details().get("releaseGateEnabled"));
        summary.put("sourcePatchClaimEnabled", event.details().get("sourcePatchClaimEnabled"));
        summary.put("claimEnabled", event.details().get("claimEnabled"));
        summary.put("mutationEnabled", event.details().get("mutationEnabled"));
        summary.put("verificationCommandExecutionEnabled", event.details().get("verificationCommandExecutionEnabled"));
        summary.put("rollbackRestoreEnabled", event.details().get("rollbackRestoreEnabled"));
        summary.put("ragFreshnessUpdateEnabled", event.details().get("ragFreshnessUpdateEnabled"));
        summary.put("finalResultEnabled", event.details().get("finalResultEnabled"));
        summary.put("publicationEnabled", event.details().get("publicationEnabled"));
        summary.put("finalAnswerGenerationEnabled", event.details().get("finalAnswerGenerationEnabled"));
        summary.put("deliveryEnabled", event.details().get("deliveryEnabled"));
        summary.put("acknowledgementEnabled", event.details().get("acknowledgementEnabled"));
        summary.put("message", "Fresh release-attempt evidence is complete, but release and mutation remain disabled.");
        return summary;
    }

    private Map<String, Object> releaseReadinessRefreshHandoffSummary(CodeAgentLoopTimelineEventSummary event) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.release-readiness-refresh-state.v1");
        summary.put("status", "RELEASE_READINESS_REFRESHED_RELEASE_GATED");
        summary.put("sourceEventType", event.eventType());
        summary.put("sourceSequenceNumber", event.sequenceNumber());
        summary.put("sourceRequestId", event.details().get("sourceRequestId"));
        summary.put("releaseAttemptId", event.details().get("releaseAttemptId"));
        summary.put("readyToRelease", event.details().get("readyToRelease"));
        summary.put("readinessMessage", event.details().get("readinessMessage"));
        summary.put("warningCount", event.details().get("warningCount"));
        summary.put("checkCount", event.details().get("checkCount"));
        summary.put("failedCheckKeys", event.details().get("failedCheckKeys"));
        summary.put("patchReleaseStatus", event.details().get("patchReleaseStatus"));
        summary.put("patchReleasePreconditionsPassed", event.details().get("patchReleasePreconditionsPassed"));
        summary.put("patchExecutionGateStatus", event.details().get("patchExecutionGateStatus"));
        summary.put("patchExecutionPreconditionsPassed", event.details().get("patchExecutionPreconditionsPassed"));
        summary.put("releaseAttemptReady", event.details().get("releaseAttemptReady"));
        summary.put("freshObservationEvidenceComplete", event.details().get("freshObservationEvidenceComplete"));
        summary.put("releaseAttemptFinalReadiness", event.details().get("releaseAttemptFinalReadiness"));
        summary.put("releaseGateEnabled", event.details().get("releaseGateEnabled"));
        summary.put("sourcePatchClaimEnabled", event.details().get("sourcePatchClaimEnabled"));
        summary.put("claimEnabled", event.details().get("claimEnabled"));
        summary.put("claimable", event.details().get("claimable"));
        summary.put("mutationEnabled", event.details().get("mutationEnabled"));
        summary.put("verificationCommandExecutionEnabled", event.details().get("verificationCommandExecutionEnabled"));
        summary.put("rollbackRestoreEnabled", event.details().get("rollbackRestoreEnabled"));
        summary.put("ragFreshnessUpdateEnabled", event.details().get("ragFreshnessUpdateEnabled"));
        summary.put("finalResultEnabled", event.details().get("finalResultEnabled"));
        summary.put("publicationEnabled", event.details().get("publicationEnabled"));
        summary.put("finalAnswerGenerationEnabled", event.details().get("finalAnswerGenerationEnabled"));
        summary.put("deliveryEnabled", event.details().get("deliveryEnabled"));
        summary.put("acknowledgementEnabled", event.details().get("acknowledgementEnabled"));
        summary.put("message", "Release readiness was refreshed from fresh evidence, but release and mutation remain disabled.");
        return summary;
    }

    private Optional<CodeAgentLoopTimelineEventSummary> latestEvent(CodeAgentLoopTimelineSummary timeline, String eventType) {
        return timeline.events().stream()
                .filter(event -> eventType.equals(event.eventType()))
                .max(Comparator.comparingInt(CodeAgentLoopTimelineEventSummary::sequenceNumber));
    }

    private boolean isSameOrAfter(CodeAgentLoopTimelineEventSummary candidate, CodeAgentLoopTimelineEventSummary comparison) {
        return comparison == null || candidate.sequenceNumber() >= comparison.sequenceNumber();
    }

    private String stringDetail(CodeAgentLoopTimelineEventSummary event, String key, String fallback) {
        Object value = event.details().get(key);
        return value == null ? fallback : value.toString();
    }

    private int intDetail(Map<String, Object> details, String key) {
        Object value = details.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private Map<String, Object> mapDetail(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private CodeAgentLoopNextActionResponse nextAction(
            UUID loopId,
            UUID repositoryId,
            String status,
            String actionKey,
            String reason,
            CodeAgentLoopTimelineEventSummary sourceEvent
    ) {
        return nextAction(loopId, repositoryId, status, actionKey, reason, sourceEvent, Map.of());
    }

    private CodeAgentLoopNextActionResponse nextAction(
            UUID loopId,
            UUID repositoryId,
            String status,
            String actionKey,
            String reason,
            CodeAgentLoopTimelineEventSummary sourceEvent,
            Map<String, Object> handoffSummary
    ) {
        return new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                status,
                actionKey,
                reason,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                sourceEvent == null ? null : sourceEvent.id(),
                sourceEvent == null ? null : sourceEvent.sequenceNumber(),
                sourceEvent == null ? null : sourceEvent.eventType(),
                handoffSummary,
                sourceEvent == null ? Map.of() : sourceEvent.details(),
                CodeAgentLoopRecommendedActionFactory.create(recommendedActionKey(actionKey))
        );
    }

    private String recommendedActionKey(String actionKey) {
        return switch (actionKey) {
            case "QUEUE_READ_ONLY_OBSERVATION" -> "PREVIEW_RUNNER_STEP";
            case "READY_HANDOFF_CREATION_DISABLED", "WAIT_FOR_RELEASE_GATE", "WAIT_FOR_FRESH_OBSERVATION_RESULTS",
                    "FRESH_EVIDENCE_COMPLETE_RELEASE_GATED" -> "CHECK_ENQUEUE_REFUSAL";
            case "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED" -> "STOP_AND_REPORT";
            case "RELEASE_READINESS_REFRESHED_RELEASE_GATED" -> "REVIEW_RELEASE_REFUSAL";
            case "STOP_WITH_REASON" -> "STOP_AND_REPORT";
            default -> "ASK_USER";
        };
    }

    private List<CodeAgentLoopStep> steps() {
        return List.of(
                new CodeAgentLoopStep(
                        1,
                        "PLAN",
                        "Retrieve code evidence and form a bounded repair plan.",
                        AgentExecutionTarget.SERVER_LOCAL,
                        null,
                        false,
                        false,
                        true,
                        "Stop and ask for clarification when evidence is weak or the target is ambiguous."
                ),
                new CodeAgentLoopStep(
                        2,
                        "SELECT_TOOL",
                        "Select the next typed tool from the approved Local Agent protocol.",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        null,
                        false,
                        false,
                        true,
                        "Stop when the requested tool is unavailable, unsafe, or outside the approved workspace."
                ),
                new CodeAgentLoopStep(
                        3,
                        "REQUEST_APPROVAL",
                        "Require explicit user approval before any side-effectful tool can run.",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.PATCH_APPLY,
                        true,
                        false,
                        true,
                        "Stop on approval denial, missing agent, disconnected agent, or unapproved workspace."
                ),
                new CodeAgentLoopStep(
                        4,
                        "OBSERVE",
                        "Consume non-mutating Local Agent observations such as repository status and patch dry-run output.",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        null,
                        false,
                        false,
                        true,
                        "Stop when observations report context mismatch, failed preflight, or stale evidence."
                ),
                new CodeAgentLoopStep(
                        5,
                        "COMPLETE_OR_PAUSE",
                        "Produce the next user-visible decision: ask, wait for approval, or report why mutation remains disabled.",
                        AgentExecutionTarget.SERVER_LOCAL,
                        null,
                        false,
                        false,
                        true,
                        "Stop before real patch apply, test execution, rollback restore, or final mutation publication."
                )
        );
    }

    private List<CodeAgentLoopStopCondition> stopConditions() {
        return List.of(
                new CodeAgentLoopStopCondition("MAX_STEPS", "Stop when the bounded step count is reached."),
                new CodeAgentLoopStopCondition("TIMEOUT", "Stop when the loop timeout is reached."),
                new CodeAgentLoopStopCondition("WEAK_EVIDENCE", "Ask for clarification instead of making risky changes."),
                new CodeAgentLoopStopCondition("APPROVAL_REQUIRED", "Pause before side-effectful Local Agent tools."),
                new CodeAgentLoopStopCondition("AGENT_UNAVAILABLE", "Stop when the selected Local Agent is disconnected or missing."),
                new CodeAgentLoopStopCondition("TOOL_FAILED", "Stop when a tool observation reports failure or unsafe state."),
                new CodeAgentLoopStopCondition("MUTATION_DISABLED", "Do not apply patches, run tests, restore rollback, update RAG freshness, or publish a mutation result in this preview slice.")
        );
    }

    private List<String> warnings(String instruction) {
        String normalizedInstruction = instruction == null ? "" : instruction.trim();
        return List.of(
                "Agent loop preview is read-only and does not create, push, claim, release, or execute Local Agent mutation requests.",
                normalizedInstruction.isBlank()
                        ? "No instruction text was available for this preview."
                        : "Instruction is used only to scope the preview; no model call or tool execution is started."
        );
    }
}
