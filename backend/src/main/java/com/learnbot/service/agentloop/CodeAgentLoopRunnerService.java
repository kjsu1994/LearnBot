package com.learnbot.service.agentloop;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.loop.CodeAgentLoopFinalResultPublicationPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopM8EntryReadinessResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopReleaseReviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopRecommendedActionFactory;
import com.learnbot.dto.loop.CodeAgentLoopToolCandidate;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.LocalAgentToolGatewayService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeAgentLoopRunnerService {
    private final CodeAgentLoopPreviewService loopPreviewService;
    private final LocalAgentToolGatewayService toolGatewayService;

    public CodeAgentLoopRunnerService(
            CodeAgentLoopPreviewService loopPreviewService,
            LocalAgentToolGatewayService toolGatewayService
    ) {
        this.loopPreviewService = loopPreviewService;
        this.toolGatewayService = toolGatewayService;
    }

    public CodeAgentLoopRunnerPreviewResponse previewNextStep(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopNextActionResponse nextAction = loopPreviewService.nextAction(userId, repositoryId, loopId);
        if ("READY_HANDOFF_CREATION_DISABLED".equals(nextAction.actionKey())) {
            return response(
                    nextAction,
                    "WAIT_CREATION_GATE_DISABLED",
                    "Mutation handoff is ready, but Local Agent mutation request creation is disabled; no request is prepared.",
                    null,
                    nextAction.handoffSummary()
            );
        }
        if ("WAIT_FOR_RELEASE_GATE".equals(nextAction.actionKey())) {
            return response(
                    nextAction,
                    "WAIT_RELEASE_GATE_FRESH_OBSERVATIONS",
                    "Patch approval is held. Inspect release readiness and use the fresh-observation path before any release attempt; runner auto-enqueue and mutation remain disabled.",
                    null,
                    releaseGateHandoffSummary(nextAction)
            );
        }
        if ("WAIT_FOR_FRESH_OBSERVATION_RESULTS".equals(nextAction.actionKey())) {
            return response(
                    nextAction,
                    "WAIT_RELEASE_GATE_FRESH_OBSERVATION_RESULTS",
                    "Fresh release-attempt observations are queued. Wait for Local Agent results before release, claim, or mutation; runner auto-enqueue remains disabled.",
                    null,
                    nextAction.handoffSummary()
            );
        }
        if ("FRESH_EVIDENCE_COMPLETE_RELEASE_GATED".equals(nextAction.actionKey())) {
            return response(
                    nextAction,
                    "WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE",
                    "Fresh release-attempt evidence is complete, but release, claim, mutation, final publication, delivery, and acknowledgement remain disabled.",
                    null,
                    nextAction.handoffSummary()
            );
        }
        if ("RELEASE_READINESS_REFRESHED_RELEASE_GATED".equals(nextAction.actionKey())) {
            return response(
                    nextAction,
                    "WAIT_RELEASE_GATE_READINESS_REFRESHED",
                    "Release readiness was refreshed from fresh evidence, but release, claim, mutation, final publication, delivery, and acknowledgement remain disabled.",
                    null,
                    nextAction.handoffSummary()
            );
        }
        if ("APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED".equals(nextAction.actionKey())) {
            return response(
                    nextAction,
                    "READY_FINAL_RESULT_DISABLED",
                    "Approved Local Agent execution flow completed, but final result publication, RAG freshness update, acknowledgement, and follow-up mutation remain disabled.",
                    null,
                    nextAction.handoffSummary()
            );
        }
        if (!"QUEUE_READ_ONLY_OBSERVATION".equals(nextAction.actionKey())) {
            return response(nextAction, "NO_REQUEST_PREPARED", nextAction.reason(), null, nextAction.handoffSummary());
        }
        if (agentId == null || workspaceId == null) {
            return response(
                    nextAction,
                    "WAIT_FOR_AGENT_WORKSPACE",
                    "A read-only Local Agent observation is allowed, but agentId and workspaceId are required before preparing a tool candidate.",
                    null
            );
        }

        UUID sessionId = nextAction.loopId() == null ? UUID.randomUUID() : nextAction.loopId();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", 1);
        input.put("repositoryId", repositoryId.toString());
        if (nextAction.loopId() != null) {
            input.put("loopId", nextAction.loopId().toString());
        }
        input.put("purpose", "loop.readOnlyRepositoryObservation");
        input.put("sourceEventType", nextAction.sourceEventType());
        input.put("sourceSequenceNumber", nextAction.sourceSequenceNumber());
        input.put("freshObservationOnly", true);
        input.put("mutationAllowed", false);

        CodeAgentLoopTimelineEventSummary latestTreeOrSearch = latestDiscoveryObservation(userId, repositoryId, nextAction.loopId());
        LocalAgentToolName selectedTool = selectReadOnlyTool(userId, repositoryId, nextAction.loopId());
        if (selectedTool == LocalAgentToolName.WORKSPACE_TREE) {
            input.put("path", ".");
            input.put("maxEntries", 240);
            input.put("maxDepth", 4);
        }
        if (selectedTool == LocalAgentToolName.WORKSPACE_SEARCH) {
            input.put("path", ".");
            input.put("query", searchQuery(nextAction));
            input.put("maxMatches", 30);
            input.put("maxFiles", 20);
            input.put("maxBytesPerFile", 200_000);
        }
        if (selectedTool == LocalAgentToolName.GIT_DIFF) {
            input.put("maxBytes", 6000);
        }
        if (selectedTool == LocalAgentToolName.FILE_READ) {
            String path = selectFileReadPath(userId, repositoryId, nextAction.loopId());
            if (path == null) {
                return response(
                        nextAction,
                        "WAIT_FOR_NARROWER_GOAL",
                        "Workspace discovery completed, but no safe bounded file.read candidate was found. Ask the user for a narrower file or symbol target.",
                        null,
                        fileSelectionHandoff(latestTreeOrSearch, List.of())
                );
            }
            input.put("path", path);
            input.put("maxBytes", 80_000);
            input.put("selectionSchema", "learnbot.server.code-agent.file-read-selection.v1");
            input.put("selectionReason", "Selected from completed workspace.search/workspace.tree observations.");
        }

        CodeAgentLoopToolCandidate candidate = new CodeAgentLoopToolCandidate(
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                selectedTool,
                LocalAgentApprovalState.NOT_REQUIRED,
                false,
                false,
                false,
                false,
                safeRequestInput(input),
                List.of("Runner preview prepared a read-only " + selectedTool.wireName() + " candidate. Enqueue and mutation remain disabled.")
        );
        return response(
                nextAction,
                "PREPARED_READ_ONLY_CANDIDATE",
                "Prepared the next read-only Local Agent observation candidate. Enqueue remains disabled in this runner slice.",
                candidate
        );
    }

    public CodeAgentLoopRunnerEnqueueResponse enqueueReadOnlyNextStep(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopRunnerPreviewResponse preview = previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);
        CodeAgentLoopToolCandidate candidate = preview.candidate();
        if (!"PREPARED_READ_ONLY_CANDIDATE".equals(preview.runnerDecision()) || candidate == null) {
            return enqueueResponse(preview, "NOT_ENQUEUED", preview.reason(), null);
        }
        if (!safeReadOnlyCandidate(candidate)
                || candidate.sideEffectful()
                || candidate.requiresApproval()
                || candidate.approvalState() != LocalAgentApprovalState.NOT_REQUIRED
                || candidate.mutationAllowed()) {
            return enqueueResponse(
                    preview,
                    "REFUSED_UNSAFE_CANDIDATE",
                    "Runner enqueue only accepts an allowed non-side-effectful read-only candidate with no approval requirement.",
                    null
            );
        }

        LocalAgentQueuedToolRequest queued = toolGatewayService.enqueueReadOnly(new LocalAgentToolRequest(
                candidate.sessionId(),
                candidate.userId(),
                candidate.agentId(),
                candidate.workspaceId(),
                candidate.executionTarget(),
                candidate.toolName(),
                candidate.input(),
                candidate.approvalState(),
                null,
                candidate.warnings()
        ));
        return enqueueResponse(
                preview,
                "ENQUEUED_READ_ONLY_OBSERVATION",
                "Queued the next read-only Local Agent " + candidate.toolName().wireName() + " observation. Mutation remains disabled.",
                queued
        );
    }

    public CodeAgentLoopReleaseReviewResponse reviewReleaseGate(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopRunnerPreviewResponse preview = previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);
        if (!"WAIT_RELEASE_GATE_READINESS_REFRESHED".equals(preview.runnerDecision())) {
            return releaseReviewResponse(
                    preview,
                    "NOT_REVIEWED",
                    "Release review is available only after release readiness has been refreshed from fresh evidence.",
                    null
            );
        }
        UUID sourceRequestId = uuidValue(preview.handoffSummary().get("sourceRequestId"));
        if (sourceRequestId == null) {
            return releaseReviewResponse(
                    preview,
                    "NOT_REVIEWED_MISSING_SOURCE_REQUEST",
                    "Release review cannot run because the refreshed readiness handoff has no source patch request id.",
                    null
            );
        }
        LocalAgentPatchReleaseBoundaryResponse boundary = toolGatewayService.inspectPatchReleaseBoundary(userId, sourceRequestId);
        return releaseReviewResponse(
                preview,
                "RELEASE_REVIEW_REFUSED_GATE_DISABLED",
                "Release review recorded the disabled release boundary. The source patch remains non-claimable and mutation remains disabled.",
                boundary
        );
    }

    public CodeAgentLoopFinalResultPublicationPreviewResponse previewFinalResultPublication(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopRunnerPreviewResponse preview = previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);
        Map<String, Object> handoffSummary = preview.handoffSummary() == null ? Map.of() : preview.handoffSummary();
        Map<String, Object> finalResultHandoff = objectMap(handoffSummary.get("finalResultHandoff"));
        boolean ready = "READY_FINAL_RESULT_DISABLED".equals(preview.runnerDecision())
                && "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED".equals(preview.actionKey())
                && "learnbot.code-agent.approved-execution-flow-completed-handoff.v1".equals(handoffSummary.get("schema"))
                && "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1".equals(finalResultHandoff.get("schema"));
        return new CodeAgentLoopFinalResultPublicationPreviewResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                ready ? "READY_FINAL_RESULT_PUBLICATION_DISABLED" : "NOT_READY_FOR_FINAL_RESULT_PUBLICATION",
                ready
                        ? "Final-result report and final-answer publication handoff are visible, but publication, delivery, acknowledgement save, RAG freshness update, and mutation remain disabled."
                        : preview.reason(),
                ready,
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
                handoffSummary,
                finalResultHandoff,
                preview
        );
    }

    public CodeAgentLoopM8EntryReadinessResponse previewM8EntryReadiness(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopFinalResultPublicationPreviewResponse publicationPreview =
                previewFinalResultPublication(userId, repositoryId, loopId, agentId, workspaceId);
        boolean finalResultHandoffReady = "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1"
                .equals(publicationPreview.finalResultHandoff().get("schema"));
        boolean publicationPreviewReady = publicationPreview.finalResultReady()
                && "READY_FINAL_RESULT_PUBLICATION_DISABLED".equals(publicationPreview.publicationDecision())
                && !publicationPreview.publicationEnabled()
                && !publicationPreview.finalAnswerDeliveryEnabled()
                && !publicationPreview.acknowledgementSaveEnabled()
                && !publicationPreview.ragFreshnessUpdateEnabled()
                && !publicationPreview.mutationEnabled();
        boolean ready = finalResultHandoffReady && publicationPreviewReady;
        List<String> blockingReasons = ready
                ? List.of()
                : List.of("M7 final-result handoff and disabled publication preview are not both ready.");
        return new CodeAgentLoopM8EntryReadinessResponse(
                publicationPreview.loopId(),
                publicationPreview.repositoryId(),
                publicationPreview.status(),
                publicationPreview.actionKey(),
                ready ? "M7_CLOSURE_READY" : "M7_CLOSURE_NOT_READY",
                ready ? "M8_ENTRY_READY" : "M8_ENTRY_BLOCKED",
                ready
                        ? "M7 has a completed approved-flow final-result handoff and an audit-only publication preview. M8 productization can start, but no M8 execution or delivery controls are enabled."
                        : publicationPreview.reason(),
                ready,
                ready,
                finalResultHandoffReady,
                publicationPreviewReady,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                blockingReasons,
                publicationPreview.handoffSummary(),
                publicationPreview.finalResultHandoff(),
                publicationPreview
        );
    }

    private CodeAgentLoopRunnerPreviewResponse response(
            CodeAgentLoopNextActionResponse nextAction,
            String runnerDecision,
            String reason,
            CodeAgentLoopToolCandidate candidate
    ) {
        return response(nextAction, runnerDecision, reason, candidate, Map.of());
    }

    private CodeAgentLoopRunnerPreviewResponse response(
            CodeAgentLoopNextActionResponse nextAction,
            String runnerDecision,
            String reason,
            CodeAgentLoopToolCandidate candidate,
            Map<String, Object> handoffSummary
    ) {
        return new CodeAgentLoopRunnerPreviewResponse(
                nextAction.loopId(),
                nextAction.repositoryId(),
                nextAction.status(),
                nextAction.actionKey(),
                runnerDecision,
                reason,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                handoffSummary,
                nextAction,
                candidate,
                guardrails(),
                CodeAgentLoopRecommendedActionFactory.create(recommendedActionKey(nextAction, runnerDecision, candidate))
        );
    }

    private Map<String, Object> guardrails() {
        Map<String, Object> guardrails = new LinkedHashMap<>();
        guardrails.put("modelToolSelectionEnabled", false);
        guardrails.put("requestCreationEnabled", false);
        guardrails.put("enqueueEnabled", false);
        guardrails.put("sideEffectfulToolsBlocked", true);
        guardrails.put("allowedCandidateTools", List.of(
                LocalAgentToolName.WORKSPACE_TREE.wireName(),
                LocalAgentToolName.WORKSPACE_SEARCH.wireName(),
                LocalAgentToolName.FILE_READ.wireName(),
                LocalAgentToolName.GIT_STATUS.wireName(),
                LocalAgentToolName.GIT_DIFF.wireName()
        ));
        guardrails.put("approvalRequiredBeforeSideEffects", true);
        guardrails.put("mutationAllowed", false);
        return Map.copyOf(guardrails);
    }

    private LocalAgentToolName selectReadOnlyTool(UUID userId, UUID repositoryId, UUID loopId) {
        if (loopId == null) {
            return LocalAgentToolName.GIT_STATUS;
        }
        var timelines = loopPreviewService.recentTimelines(userId, repositoryId, 10);
        if (timelines == null) {
            return LocalAgentToolName.GIT_STATUS;
        }
        return timelines.stream()
                .filter(timeline -> loopId.equals(timeline.id()))
                .findFirst()
                .map(timeline -> {
                    long succeededTree = succeededReadOnlyObservations(timeline.events(), LocalAgentToolName.WORKSPACE_TREE);
                    long succeededSearch = succeededReadOnlyObservations(timeline.events(), LocalAgentToolName.WORKSPACE_SEARCH);
                    long succeededFileReads = succeededReadOnlyObservations(timeline.events(), LocalAgentToolName.FILE_READ);
                    long succeededStatus = succeededReadOnlyObservations(timeline.events(), LocalAgentToolName.GIT_STATUS);
                    long succeededDiff = succeededReadOnlyObservations(timeline.events(), LocalAgentToolName.GIT_DIFF);
                    if (succeededTree == 0) {
                        return LocalAgentToolName.WORKSPACE_TREE;
                    }
                    if (succeededSearch == 0) {
                        return LocalAgentToolName.WORKSPACE_SEARCH;
                    }
                    if (succeededFileReads < Math.min(3, fileReadCandidates(timeline).size())) {
                        return LocalAgentToolName.FILE_READ;
                    }
                    return succeededStatus > succeededDiff ? LocalAgentToolName.GIT_DIFF : LocalAgentToolName.GIT_STATUS;
                })
                .orElse(LocalAgentToolName.GIT_STATUS);
    }

    private long succeededReadOnlyObservations(List<CodeAgentLoopTimelineEventSummary> events, LocalAgentToolName toolName) {
        return events.stream()
                .filter(event -> "LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType()))
                .filter(event -> event.toolName() == toolName)
                .filter(event -> !event.mayMutate())
                .filter(event -> "SUCCEEDED".equals(String.valueOf(event.details().get("status"))))
                .count();
    }

    private boolean safeReadOnlyCandidate(CodeAgentLoopToolCandidate candidate) {
        return (candidate.toolName() == LocalAgentToolName.WORKSPACE_TREE
                    || candidate.toolName() == LocalAgentToolName.WORKSPACE_SEARCH
                    || candidate.toolName() == LocalAgentToolName.FILE_READ
                    || candidate.toolName() == LocalAgentToolName.GIT_STATUS
                    || candidate.toolName() == LocalAgentToolName.GIT_DIFF)
                && candidate.approvalState() == LocalAgentApprovalState.NOT_REQUIRED
                && !candidate.sideEffectful()
                && !candidate.requiresApproval()
                && !candidate.mutationAllowed();
    }

    private String selectFileReadPath(UUID userId, UUID repositoryId, UUID loopId) {
        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
        if (timeline == null) {
            return null;
        }
        Set<String> alreadyRead = new LinkedHashSet<>();
        for (CodeAgentLoopTimelineEventSummary event : timeline.events()) {
            if ("LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType())
                    && event.toolName() == LocalAgentToolName.FILE_READ
                    && "SUCCEEDED".equals(String.valueOf(event.details().get("status")))) {
                String path = summaryPath(event);
                if (path != null) {
                    alreadyRead.add(path);
                }
            }
        }
        return fileReadCandidates(timeline).stream()
                .filter(path -> !alreadyRead.contains(path))
                .findFirst()
                .orElse(null);
    }

    private List<String> fileReadCandidates(CodeAgentLoopTimelineSummary timeline) {
        if (timeline == null || timeline.events() == null) {
            return List.of();
        }
        LinkedHashSet<String> ranked = new LinkedHashSet<>();
        for (CodeAgentLoopTimelineEventSummary event : timeline.events()) {
            if ("LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType())
                    && event.toolName() == LocalAgentToolName.WORKSPACE_SEARCH
                    && "SUCCEEDED".equals(String.valueOf(event.details().get("status")))) {
                ranked.addAll(pathsFromOutputSummary(event, "matches"));
            }
        }
        for (CodeAgentLoopTimelineEventSummary event : timeline.events()) {
            if ("LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType())
                    && event.toolName() == LocalAgentToolName.WORKSPACE_TREE
                    && "SUCCEEDED".equals(String.valueOf(event.details().get("status")))) {
                pathsFromOutputSummary(event, "entries").stream()
                        .filter(this::likelyReadableSourcePath)
                        .forEach(ranked::add);
            }
        }
        return ranked.stream()
                .filter(this::safeRelativePath)
                .limit(5)
                .toList();
    }

    private List<String> pathsFromOutputSummary(CodeAgentLoopTimelineEventSummary event, String key) {
        Map<String, Object> outputSummary = objectMap(event.details().get("outputSummary"));
        Object items = outputSummary.get(key);
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                if ("entries".equals(key) && map.containsKey("type") && !"file".equals(String.valueOf(map.get("type")))) {
                    continue;
                }
                Object path = map.get("path");
                if (path != null && safeRelativePath(String.valueOf(path))) {
                    paths.add(String.valueOf(path));
                }
            }
        }
        return List.copyOf(paths);
    }

    private String summaryPath(CodeAgentLoopTimelineEventSummary event) {
        Object path = objectMap(event.details().get("outputSummary")).get("relativePath");
        return path == null || String.valueOf(path).isBlank() ? null : String.valueOf(path);
    }

    private boolean safeRelativePath(String path) {
        if (path == null || path.isBlank() || ".".equals(path)) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        return !normalized.startsWith("/")
                && !normalized.matches("^[A-Za-z]:.*")
                && !normalized.contains("../")
                && !normalized.equals("..")
                && !normalized.contains("\u0000");
    }

    private boolean likelyReadableSourcePath(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (lower.isBlank()) {
            return false;
        }
        if (lower.contains("/node_modules/") || lower.contains("/.git/") || lower.contains("/build/")
                || lower.contains("/dist/") || lower.contains("/target/") || lower.contains("/bin/")
                || lower.contains("/obj/")) {
            return false;
        }
        return lower.endsWith(".java")
                || lower.endsWith(".cs")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".py")
                || lower.endsWith(".go")
                || lower.endsWith(".rs")
                || lower.endsWith(".kt")
                || lower.endsWith(".md")
                || lower.endsWith(".json")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".css")
                || lower.endsWith(".html");
    }

    private CodeAgentLoopTimelineSummary timeline(UUID userId, UUID repositoryId, UUID loopId) {
        if (loopId == null) {
            return null;
        }
        return loopPreviewService.recentTimelines(userId, repositoryId, 20).stream()
                .filter(candidate -> loopId.equals(candidate.id()))
                .findFirst()
                .orElse(null);
    }

    private CodeAgentLoopTimelineEventSummary latestDiscoveryObservation(UUID userId, UUID repositoryId, UUID loopId) {
        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
        if (timeline == null || timeline.events() == null) {
            return null;
        }
        return timeline.events().stream()
                .filter(event -> event.toolName() == LocalAgentToolName.WORKSPACE_SEARCH
                        || event.toolName() == LocalAgentToolName.WORKSPACE_TREE)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private Map<String, Object> fileSelectionHandoff(CodeAgentLoopTimelineEventSummary source, List<String> selectedPaths) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.server.code-agent.file-read-selection.v1");
        result.put("status", selectedPaths == null || selectedPaths.isEmpty() ? "NO_SAFE_CANDIDATES" : "SELECTED");
        result.put("sourceEventType", source == null ? null : source.eventType());
        result.put("sourceSequenceNumber", source == null ? null : source.sequenceNumber());
        result.put("selectedPaths", selectedPaths == null ? List.of() : selectedPaths);
        result.put("maxSelectedFiles", 5);
        result.put("maxBytesPerFile", 80_000);
        result.put("mutationAllowed", false);
        return java.util.Collections.unmodifiableMap(result);
    }

    private String searchQuery(CodeAgentLoopNextActionResponse nextAction) {
        Object value = nextAction.sourceDetails() == null ? null : nextAction.sourceDetails().get("instruction");
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.isBlank()) {
            return "TODO";
        }
        String normalized = raw.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_.$/-]+", " ").trim();
        if (normalized.isBlank()) {
            return "TODO";
        }
        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private Map<String, Object> safeRequestInput(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input != null) {
            input.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(key, value);
                }
            });
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private String recommendedActionKey(
            CodeAgentLoopNextActionResponse nextAction,
            String runnerDecision,
            CodeAgentLoopToolCandidate candidate
    ) {
        if ("PREPARED_READ_ONLY_CANDIDATE".equals(runnerDecision)
                && candidate != null
                && safeReadOnlyCandidate(candidate)
                && !candidate.mutationAllowed()) {
            return "QUEUE_SELECTED_READ_ONLY";
        }
        if ("WAIT_RELEASE_GATE_READINESS_REFRESHED".equals(runnerDecision)) {
            return "REVIEW_RELEASE_REFUSAL";
        }
        if ("WAIT_CREATION_GATE_DISABLED".equals(runnerDecision)
                || "WAIT_RELEASE_GATE_FRESH_OBSERVATIONS".equals(runnerDecision)
                || "WAIT_RELEASE_GATE_FRESH_OBSERVATION_RESULTS".equals(runnerDecision)
                || "WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE".equals(runnerDecision)) {
            return "CHECK_ENQUEUE_REFUSAL";
        }
        if ("READY_FINAL_RESULT_DISABLED".equals(runnerDecision)) {
            return "STOP_AND_REPORT";
        }
        if ("WAIT_FOR_AGENT_WORKSPACE".equals(runnerDecision)) {
            return "SELECT_LOCAL_AGENT_WORKSPACE";
        }
        if ("STOP_WITH_REASON".equals(nextAction.actionKey()) || "NO_REQUEST_PREPARED".equals(runnerDecision)) {
            return "STOP_AND_REPORT";
        }
        return "ASK_USER";
    }

    private Map<String, Object> releaseGateHandoffSummary(CodeAgentLoopNextActionResponse nextAction) {
        Map<String, Object> details = nextAction.sourceDetails() == null ? Map.of() : nextAction.sourceDetails();
        Object requestId = details.get("requestId");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema", "learnbot.code-agent.release-gate-fresh-observation-handoff.v1");
        summary.put("status", "WAIT_FOR_RELEASE_GATE");
        summary.put("runnerDecision", "WAIT_RELEASE_GATE_FRESH_OBSERVATIONS");
        summary.put("sourceEventType", nextAction.sourceEventType());
        summary.put("sourceSequenceNumber", nextAction.sourceSequenceNumber());
        summary.put("sourceRequestId", requestId);
        summary.put("approvalState", details.get("approvalState"));
        summary.put("sourceStatus", details.get("status"));
        summary.put("approvalRequestHeld", details.get("approvalRequestHeld"));
        summary.put("releaseRequired", details.get("releaseRequired"));
        summary.put("readinessRoute", requestId == null ? null : "GET /api/local-agents/tools/" + requestId + "/readiness");
        summary.put("freshObservationsRoute", requestId == null ? null : "POST /api/local-agents/tools/" + requestId + "/fresh-observations");
        summary.put("releaseBoundaryRoute", requestId == null ? null : "POST /api/local-agents/tools/" + requestId + "/release-for-execution");
        summary.put("runnerAutoEnqueueEnabled", false);
        summary.put("freshObservationAutoEnqueueEnabled", false);
        summary.put("sourcePatchRequestCreationEnabled", false);
        summary.put("sourcePatchPushEnabled", false);
        summary.put("sourcePatchClaimEnabled", false);
        summary.put("mutationEnabled", false);
        summary.put("verificationCommandExecutionEnabled", false);
        summary.put("rollbackRestoreEnabled", false);
        summary.put("ragFreshnessUpdateEnabled", false);
        summary.put("finalResultEnabled", false);
        summary.put("publicationEnabled", false);
        summary.put("acknowledgementEnabled", false);
        summary.put("message", "Use the Local Agent release readiness and fresh-observation endpoints for this approved-held patch; the runner does not create, push, claim, or execute mutation work.");
        return java.util.Collections.unmodifiableMap(summary);
    }

    private CodeAgentLoopRunnerEnqueueResponse enqueueResponse(
            CodeAgentLoopRunnerPreviewResponse preview,
            String runnerDecision,
            String reason,
            LocalAgentQueuedToolRequest queued
    ) {
        boolean enqueued = queued != null;
        return new CodeAgentLoopRunnerEnqueueResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                runnerDecision,
                reason,
                enqueued,
                enqueued,
                enqueued,
                false,
                false,
                false,
                false,
                false,
                preview.handoffSummary(),
                preview,
                queued
        );
    }

    private CodeAgentLoopReleaseReviewResponse releaseReviewResponse(
            CodeAgentLoopRunnerPreviewResponse preview,
            String runnerDecision,
            String reason,
            LocalAgentPatchReleaseBoundaryResponse boundary
    ) {
        return new CodeAgentLoopReleaseReviewResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                runnerDecision,
                reason,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                preview.handoffSummary(),
                preview,
                boundary
        );
    }

    private UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
