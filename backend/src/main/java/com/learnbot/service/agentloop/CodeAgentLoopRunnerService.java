package com.learnbot.service.agentloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.SavedAnswerDetail;
import com.learnbot.dto.SavedAnswerRequest;
import com.learnbot.dto.loop.CodeAgentLoopFinalResultPublicationPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopFinalResultPublicationResponse;
import com.learnbot.dto.loop.CodeAgentLoopM8EntryReadinessResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopReleaseReviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopRecommendedActionFactory;
import com.learnbot.dto.loop.CodeAgentLoopToolCandidate;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.AppUser;
import com.learnbot.service.LocalAgentToolGatewayService;
import com.learnbot.service.SavedAnswerService;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SavedAnswerService savedAnswerService;
    private final CodeAgentLoopTimelineRepository timelineRepository;
    private final LearnBotProperties properties;
    private final ObjectMapper objectMapper;

    public CodeAgentLoopRunnerService(
            CodeAgentLoopPreviewService loopPreviewService,
            LocalAgentToolGatewayService toolGatewayService
    ) {
        this(loopPreviewService, toolGatewayService, null, null, new LearnBotProperties(), new ObjectMapper());
    }

    @Autowired
    public CodeAgentLoopRunnerService(
            CodeAgentLoopPreviewService loopPreviewService,
            LocalAgentToolGatewayService toolGatewayService,
            SavedAnswerService savedAnswerService,
            CodeAgentLoopTimelineRepository timelineRepository,
            LearnBotProperties properties,
            ObjectMapper objectMapper
    ) {
        this.loopPreviewService = loopPreviewService;
        this.toolGatewayService = toolGatewayService;
        this.savedAnswerService = savedAnswerService;
        this.timelineRepository = timelineRepository;
        this.properties = properties == null ? new LearnBotProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
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

        List<CodeAgentLoopToolCandidate> candidates = readOnlyToolCandidates(userId, repositoryId, nextAction, agentId, workspaceId);
        if (candidates.isEmpty()) {
            CodeAgentLoopTimelineEventSummary latestTreeOrSearch = latestDiscoveryObservation(userId, repositoryId, nextAction.loopId());
            return response(
                    nextAction,
                    "WAIT_FOR_NARROWER_GOAL",
                    "Workspace discovery completed, but no safe bounded read-only candidate was found. Ask the user for a narrower file or symbol target.",
                    null,
                    fileSelectionHandoff(latestTreeOrSearch, List.of())
            );
        }
        CodeAgentLoopToolCandidate candidate = deterministicReadOnlyCandidate(userId, repositoryId, nextAction, candidates);
        return response(
                nextAction,
                "PREPARED_READ_ONLY_CANDIDATE",
                "Prepared the next read-only Local Agent observation candidate. Enqueue remains disabled in this runner slice.",
                candidate,
                Map.of("readOnlyCandidateCount", candidates.size())
        );
    }

    public List<CodeAgentLoopToolCandidate> readOnlyToolCandidates(
            UUID userId,
            UUID repositoryId,
            CodeAgentLoopNextActionResponse nextAction,
            UUID agentId,
            UUID workspaceId
    ) {
        if (nextAction == null || agentId == null || workspaceId == null || !"QUEUE_READ_ONLY_OBSERVATION".equals(nextAction.actionKey())) {
            return List.of();
        }
        UUID sessionId = nextAction.loopId() == null ? UUID.randomUUID() : nextAction.loopId();
        List<CodeAgentLoopToolCandidate> candidates = new ArrayList<>();
        Set<String> candidateKeys = new LinkedHashSet<>();

        addReadOnlyCandidate(candidates, candidateKeys, sessionId, userId, agentId, workspaceId, LocalAgentToolName.WORKSPACE_TREE,
                baseReadOnlyInput(repositoryId, nextAction, Map.of(
                        "path", ".",
                        "maxEntries", 240,
                        "maxDepth", 4
                )));

        addReadOnlyCandidate(candidates, candidateKeys, sessionId, userId, agentId, workspaceId, LocalAgentToolName.WORKSPACE_SEARCH,
                baseReadOnlyInput(repositoryId, nextAction, Map.of(
                        "path", ".",
                        "query", searchQuery(nextAction),
                        "maxMatches", 30,
                        "maxFiles", 20,
                        "maxBytesPerFile", 200_000
                )));

        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, nextAction.loopId());
        Set<String> alreadyRead = alreadyReadPaths(timeline);
        for (String path : fileReadCandidates(timeline, instructionText(nextAction))) {
            if (alreadyRead.contains(path)) {
                continue;
            }
            addReadOnlyCandidate(candidates, candidateKeys, sessionId, userId, agentId, workspaceId, LocalAgentToolName.FILE_READ,
                    baseReadOnlyInput(repositoryId, nextAction, Map.of(
                            "path", path,
                            "maxBytes", 80_000,
                            "selectionSchema", "learnbot.server.code-agent.file-read-selection.v1",
                            "selectionReason", "Selected from completed workspace.search/workspace.tree observations."
                    )));
        }

        addReadOnlyCandidate(candidates, candidateKeys, sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS,
                baseReadOnlyInput(repositoryId, nextAction, Map.of()));
        addReadOnlyCandidate(candidates, candidateKeys, sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_DIFF,
                baseReadOnlyInput(repositoryId, nextAction, Map.of("maxBytes", 6000)));

        return List.copyOf(candidates);
    }

    private CodeAgentLoopToolCandidate deterministicReadOnlyCandidate(
            UUID userId,
            UUID repositoryId,
            CodeAgentLoopNextActionResponse nextAction,
            List<CodeAgentLoopToolCandidate> candidates
    ) {
        LocalAgentToolName selectedTool = selectReadOnlyTool(userId, repositoryId, nextAction);
        String selectedPath = selectedTool == LocalAgentToolName.FILE_READ
                ? selectFileReadPath(userId, repositoryId, nextAction)
                : null;
        return candidates.stream()
                .filter(candidate -> candidate.toolName() == selectedTool)
                .filter(candidate -> selectedPath == null || selectedPath.equals(String.valueOf(candidate.input().get("path"))))
                .findFirst()
                .orElse(candidates.get(0));
    }

    private Map<String, Object> baseReadOnlyInput(
            UUID repositoryId,
            CodeAgentLoopNextActionResponse nextAction,
            Map<String, Object> toolInput
    ) {
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
        if (toolInput != null) {
            input.putAll(toolInput);
        }
        return input;
    }

    private void addReadOnlyCandidate(
            List<CodeAgentLoopToolCandidate> candidates,
            Set<String> candidateKeys,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            LocalAgentToolName selectedTool,
            Map<String, Object> input
    ) {
        String key = selectedTool.wireName() + ":" + String.valueOf(input.getOrDefault("path", "")) + ":" + String.valueOf(input.getOrDefault("query", ""));
        if (!candidateKeys.add(key)) {
            return;
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
                safeRequestInput(input == null ? Map.of() : input),
                List.of("Runner preview prepared a read-only " + selectedTool.wireName() + " candidate. Enqueue and mutation remain disabled.")
        );
        if (safeReadOnlyCandidate(candidate)) {
            candidates.add(candidate);
        }
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

    public CodeAgentLoopFinalResultPublicationResponse publishFinalResult(
            AppUser user,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        UUID userId = user.id();
        CodeAgentLoopFinalResultPublicationPreviewResponse preview =
                previewFinalResultPublication(userId, repositoryId, loopId, agentId, workspaceId);
        if (!preview.finalResultReady()) {
            return finalPublicationNotPublished(preview, "NOT_READY_FOR_FINAL_RESULT_PUBLICATION", preview.reason());
        }
        if (!properties.getCode().isFinalResultPublicationEnabled()
                || !properties.getCode().isFinalAnswerSaveEnabled()
                || savedAnswerService == null
                || timelineRepository == null) {
            return finalPublicationNotPublished(
                    preview,
                    "FINAL_RESULT_PUBLICATION_DISABLED",
                    "Final-result publication is disabled or required persistence services are unavailable."
            );
        }

        UUID spaceId = timelineRepository.findSpaceId(userId, repositoryId, loopId);
        Map<String, Object> finalResult = finalResultMap(preview);
        String finalAnswer = finalAnswerText(finalResult, preview.finalResultHandoff());
        SavedAnswerDetail saved = savedAnswerService.create(user, new SavedAnswerRequest(
                spaceId,
                "CODE",
                "Code Agent final result for loop " + loopId,
                "LOCAL_AGENT_CODE_AGENT_LOOP",
                finalAnswer,
                objectMapper.createArrayNode(),
                objectMapper.valueToTree(List.of(finalResult)),
                "HIGH",
                objectMapper.valueToTree(List.of(Map.of(
                        "schema", "learnbot.code-agent.final-result-publication.diagnostics.v1",
                        "publicationDecision", "FINAL_RESULT_PUBLISHED",
                        "partialReindexEnabled", false,
                        "ragFreshnessUpdateEnabled", false
                ))),
                repositoryId,
                "Code Agent final result"
        ));
        Map<String, Object> savedFinalResult = new LinkedHashMap<>(finalResult);
        savedFinalResult.put("savedAnswerId", saved.id().toString());
        timelineRepository.appendFinalResultPublished(userId, repositoryId, loopId, saved.id(), savedFinalResult);

        return new CodeAgentLoopFinalResultPublicationResponse(
                preview.loopId(),
                preview.repositoryId(),
                "FINAL_RESULT_PUBLISHED",
                "FINAL_RESULT_PUBLISHED",
                "FINAL_RESULT_PUBLISHED",
                "Final result was published and saved. RAG freshness still requires partial reindex or explicit stale-index disclosure.",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                saved.id(),
                finalAnswer,
                stringValue(finalResult.get("staleIndexDisclosure")),
                preview.handoffSummary(),
                preview.finalResultHandoff(),
                saved,
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

    private LocalAgentToolName selectReadOnlyTool(UUID userId, UUID repositoryId, CodeAgentLoopNextActionResponse nextAction) {
        UUID loopId = nextAction == null ? null : nextAction.loopId();
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
                    if (succeededFileReads < Math.min(3, fileReadCandidates(timeline, instructionText(nextAction)).size())) {
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

    private String selectFileReadPath(UUID userId, UUID repositoryId, CodeAgentLoopNextActionResponse nextAction) {
        UUID loopId = nextAction == null ? null : nextAction.loopId();
        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
        if (timeline == null) {
            return null;
        }
        Set<String> alreadyRead = alreadyReadPaths(timeline);
        return fileReadCandidates(timeline, instructionText(nextAction)).stream()
                .filter(path -> !alreadyRead.contains(path))
                .findFirst()
                .orElse(null);
    }

    private Set<String> alreadyReadPaths(CodeAgentLoopTimelineSummary timeline) {
        Set<String> alreadyRead = new LinkedHashSet<>();
        if (timeline == null || timeline.events() == null) {
            return alreadyRead;
        }
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
        return alreadyRead;
    }

    private List<String> fileReadCandidates(CodeAgentLoopTimelineSummary timeline, String instruction) {
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
        List<String> hints = filenameHints(instruction);
        return ranked.stream()
                .filter(this::safeRelativePath)
                .sorted((left, right) -> Integer.compare(candidateScore(right, hints), candidateScore(left, hints)))
                .limit(5)
                .toList();
    }

    private List<String> filenameHints(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return List.of();
        }
        String[] tokens = instruction.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9_.-]+");
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (String token : tokens) {
            String clean = trimFilenameToken(token);
            if (clean.length() >= 2) {
                hints.add(clean);
                int dot = clean.lastIndexOf('.');
                if (dot > 0) {
                    hints.add(clean.substring(0, dot));
                }
            }
        }
        String normalized = instruction.toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        addNaturalLanguageFileHints(normalized, hints);
        return List.copyOf(hints);
    }

    private void addNaturalLanguageFileHints(String normalizedInstruction, LinkedHashSet<String> hints) {
        if (normalizedInstruction == null || normalizedInstruction.isBlank()) {
            return;
        }
        Map<String, List<String>> aliases = Map.ofEntries(
                Map.entry("html", List.of("html", "htm")),
                Map.entry("htm", List.of("html", "htm")),
                Map.entry("javascript", List.of("js", "jsx", "mjs", "cjs")),
                Map.entry("js", List.of("js", "jsx", "mjs", "cjs")),
                Map.entry("typescript", List.of("ts", "tsx")),
                Map.entry("ts", List.of("ts", "tsx")),
                Map.entry("java", List.of("java")),
                Map.entry("c#", List.of("cs")),
                Map.entry("csharp", List.of("cs")),
                Map.entry("cs", List.of("cs")),
                Map.entry("c++", List.of("cpp", "cc", "cxx", "hpp", "hh", "hxx")),
                Map.entry("cpp", List.of("cpp", "cc", "cxx", "hpp", "hh", "hxx")),
                Map.entry("python", List.of("py")),
                Map.entry("py", List.of("py")),
                Map.entry("markdown", List.of("md", "markdown")),
                Map.entry("readme", List.of("readme", "md", "markdown")),
                Map.entry("json", List.of("json")),
                Map.entry("yaml", List.of("yaml", "yml")),
                Map.entry("yml", List.of("yaml", "yml")),
                Map.entry("css", List.of("css", "scss", "sass", "less")),
                Map.entry("txt", List.of("txt")),
                Map.entry("text", List.of("txt", "text"))
        );
        aliases.forEach((alias, values) -> {
            if (normalizedInstruction.contains(alias)) {
                hints.addAll(values);
            }
        });
        if (normalizedInstruction.contains("파일")) {
            hints.add("file");
        }
    }

    private String trimFilenameToken(String token) {
        if (token == null) {
            return "";
        }
        String clean = token;
        while (!clean.isBlank() && "._-".indexOf(clean.charAt(0)) >= 0) {
            clean = clean.substring(1);
        }
        while (!clean.isBlank() && "._-".indexOf(clean.charAt(clean.length() - 1)) >= 0) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private int candidateScore(String path, List<String> hints) {
        if (hints == null || hints.isEmpty() || path == null) {
            return 0;
        }
        String normalized = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        String fileName = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
        int score = 0;
        for (String hint : hints) {
            if (hint.isBlank()) {
                continue;
            }
            if (fileName.equals(hint) || stem.equals(hint)) {
                score = Math.max(score, 100);
            } else if (fileName.startsWith(hint + ".")) {
                score = Math.max(score, 90);
            } else if (extension.equals(hint)) {
                score = Math.max(score, 85);
            } else if (fileName.contains(hint)) {
                score = Math.max(score, 60);
            } else if (normalized.contains("/" + hint) || normalized.contains(hint)) {
                score = Math.max(score, 20);
            }
        }
        return score;
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
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (fileName.equals("readme") || fileName.startsWith("readme.")) {
            return true;
        }
        return lower.endsWith(".java")
                || lower.endsWith(".cs")
                || lower.endsWith(".c")
                || lower.endsWith(".h")
                || lower.endsWith(".cpp")
                || lower.endsWith(".cc")
                || lower.endsWith(".cxx")
                || lower.endsWith(".hpp")
                || lower.endsWith(".hh")
                || lower.endsWith(".hxx")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".py")
                || lower.endsWith(".go")
                || lower.endsWith(".rs")
                || lower.endsWith(".kt")
                || lower.endsWith(".md")
                || lower.endsWith(".txt")
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
        String raw = instructionText(nextAction);
        if (raw.isBlank()) {
            return "TODO";
        }
        String normalized = raw.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}_.$/-]+", " ").trim();
        if (normalized.isBlank()) {
            return "TODO";
        }
        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    private String instructionText(CodeAgentLoopNextActionResponse nextAction) {
        Object value = nextAction == null || nextAction.sourceDetails() == null ? null : nextAction.sourceDetails().get("instruction");
        return value == null ? "" : String.valueOf(value).trim();
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

    private CodeAgentLoopFinalResultPublicationResponse finalPublicationNotPublished(
            CodeAgentLoopFinalResultPublicationPreviewResponse preview,
            String decision,
            String reason
    ) {
        return new CodeAgentLoopFinalResultPublicationResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                decision,
                reason,
                preview.finalResultReady(),
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
                null,
                null,
                stringValue(preview.finalResultHandoff().get("staleIndexDisclosureText")),
                preview.handoffSummary(),
                preview.finalResultHandoff(),
                null,
                preview
        );
    }

    private Map<String, Object> finalResultMap(CodeAgentLoopFinalResultPublicationPreviewResponse preview) {
        Map<String, Object> handoff = preview.finalResultHandoff();
        Map<String, Object> approvedFlow = objectMap(preview.handoffSummary().get("approvedFlowInspection"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.code-agent.final-result-publication.v1");
        result.put("status", "FINAL_RESULT_PUBLISHED");
        result.put("loopId", preview.loopId() == null ? null : preview.loopId().toString());
        result.put("repositoryId", preview.repositoryId() == null ? null : preview.repositoryId().toString());
        result.put("sourceRequestId", stringValue(firstNonNull(handoff.get("sourceRequestId"), approvedFlow.get("sourceRequestId"))));
        result.put("releaseAttemptId", stringValue(firstNonNull(handoff.get("releaseAttemptId"), approvedFlow.get("releaseAttemptId"))));
        result.put("stepCount", firstNonNull(approvedFlow.get("stepCount"), preview.handoffSummary().get("stepCount")));
        result.put("ordered", firstNonNull(approvedFlow.get("ordered"), preview.handoffSummary().get("ordered")));
        result.put("allTerminal", firstNonNull(approvedFlow.get("allTerminal"), preview.handoffSummary().get("allTerminal")));
        result.put("allSucceeded", firstNonNull(preview.handoffSummary().get("allSucceeded"), true));
        result.put("steps", approvedFlow.getOrDefault("steps", List.of()));
        result.put("targetFiles", handoff.getOrDefault("targetFiles", List.of()));
        result.put("finalMutationReportSummaryStatus", handoff.get("finalMutationReportSummaryStatus"));
        result.put("postRetryVerificationPassed", handoff.get("postRetryVerificationPassed"));
        result.put("ragFreshnessMarkerStatus", handoff.get("ragFreshnessMarkerStatus"));
        result.put("partialReindexPlanStatus", handoff.get("partialReindexPlanStatus"));
        result.put("partialReindexEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("staleIndexDisclosure", staleIndexDisclosure(handoff));
        result.put("publicationEnabled", true);
        result.put("acknowledgementSaveEnabled", true);
        result.put("mutationEnabled", false);
        return java.util.Collections.unmodifiableMap(result);
    }

    private String finalAnswerText(Map<String, Object> finalResult, Map<String, Object> handoff) {
        List<String> lines = new ArrayList<>();
        lines.add("Code Agent work completed.");
        lines.add("");
        lines.add("- Patch applied: completed");
        lines.add("- Verification: " + boolText(finalResult.get("postRetryVerificationPassed")));
        lines.add("- Execution steps: " + stringValue(finalResult.get("stepCount")) + ", ordered=" + stringValue(finalResult.get("ordered")));
        lines.add("- Rollback verification: " + rollbackStatus(finalResult.get("steps")));
        lines.add("- Target files: " + joined(finalResult.get("targetFiles")));
        lines.add("- RAG freshness: " + stringValue(finalResult.get("ragFreshnessMarkerStatus")));
        lines.add("");
        lines.add(staleIndexDisclosure(handoff));
        lines.add("");
        lines.add("Execution evidence was saved to Saved Answers and the loop timeline.");
        return String.join("\n", lines);
    }

    private String rollbackStatus(Object stepsValue) {
        if (stepsValue instanceof List<?> steps) {
            for (Object item : steps) {
                Map<String, Object> step = objectMap(item);
                if ("rollback.restore".equals(stringValue(step.get("toolName")))) {
                    return "SUCCEEDED".equals(stringValue(step.get("status"))) ? "completed" : stringValue(step.get("status"));
                }
            }
        }
        return "not observed";
    }

    private String staleIndexDisclosure(Map<String, Object> handoff) {
        String text = stringValue(firstNonNull(
                handoff.get("staleIndexDisclosureText"),
                handoff.get("staleIndexDisclosure")
        ));
        if (text != null && !text.isBlank()) {
            return text;
        }
        return "Local files changed and code RAG may be stale until partial reindex completes.";
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String boolText(Object value) {
        return Boolean.TRUE.equals(value) ? "passed" : "needs review";
    }

    private String joined(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return "none";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
