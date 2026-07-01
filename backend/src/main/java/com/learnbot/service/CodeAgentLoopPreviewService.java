package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopStopCondition;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
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
        Optional<CodeAgentLoopTimelineEventSummary> latestFreshObservationEnqueue = latestEvent(selected, "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED");
        Optional<CodeAgentLoopTimelineEventSummary> latestDecision = latestEvent(selected, "LOOP_NEXT_DECISION_RECORDED");
        Optional<CodeAgentLoopTimelineEventSummary> latestApprovalRequest = latestEvent(selected, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED");
        Optional<CodeAgentLoopTimelineEventSummary> latestApproval = latestEvent(selected, "LOCAL_AGENT_APPROVAL_DECISION");
        Optional<CodeAgentLoopTimelineEventSummary> latestObservation = latestEvent(selected, "LOCAL_AGENT_OBSERVATION_RESULT");

        if (latestStop.isPresent()
                && isSameOrAfter(latestStop.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestStop.get(), latestReleaseBoundary.orElse(null))) {
            return fromStopOutcome(selected, latestStop.get());
        }
        if (latestReleaseBoundary.isPresent()
                && isSameOrAfter(latestReleaseBoundary.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestReleaseBoundary.get(), latestStop.orElse(null))) {
            return fromReleaseBoundary(selected, latestReleaseBoundary.get());
        }
        if (latestFreshObservationEnqueue.isPresent()
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestApprovalRequest.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestFreshObservationEnqueue.get(), latestStop.orElse(null))) {
            return fromFreshObservationEnqueue(selected, latestFreshObservationEnqueue.get());
        }
        if (latestApprovalRequest.isPresent()
                && isSameOrAfter(latestApprovalRequest.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestApprovalRequest.get(), latestStop.orElse(null))) {
            return nextAction(
                    selected.id(),
                    selected.repositoryId(),
                    stringDetail(latestApprovalRequest.get(), "status", "RECORDED"),
                    "WAIT_FOR_APPROVAL",
                    stringDetail(latestApprovalRequest.get(), "nextAction", "Wait for explicit user approval before release, claim, or mutation."),
                    latestApprovalRequest.get()
            );
        }
        if (latestApproval.isPresent()
                && isSameOrAfter(latestApproval.get(), latestDecision.orElse(null))
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
                "WAIT_FOR_RELEASE_GATE",
                stringDetail(event, "nextAction", "Wait for release gate enablement or report that mutation remains disabled."),
                event
        );
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
                sourceEvent == null ? Map.of() : sourceEvent.details()
        );
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
