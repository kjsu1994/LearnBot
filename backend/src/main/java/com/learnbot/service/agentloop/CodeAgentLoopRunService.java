package com.learnbot.service.agentloop;

import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.PatchFileDiff;
import com.learnbot.dto.loop.CodeAgentLoopRunResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunStatusResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopSelectedToolEnqueueResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodePatchFileLoader;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.OllamaClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeAgentLoopRunService {
    private final CodeAgentLoopPreviewService loopPreviewService;
    private final CodeAgentLoopToolSelectionService toolSelectionService;
    private final CodeAgentLoopRunnerService runnerService;
    private final CodeAgentService codeAgentService;
    private final CodeAgentLocalPatchRequestService localPatchRequestService;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public CodeAgentLoopRunService(
            CodeAgentLoopPreviewService loopPreviewService,
            CodeAgentLoopToolSelectionService toolSelectionService,
            CodeAgentLoopRunnerService runnerService,
            CodeAgentService codeAgentService,
            CodeAgentLocalPatchRequestService localPatchRequestService
    ) {
        this(loopPreviewService, toolSelectionService, runnerService, codeAgentService, localPatchRequestService, null, new ObjectMapper());
    }

    public CodeAgentLoopRunService(
            CodeAgentLoopPreviewService loopPreviewService,
            CodeAgentLoopToolSelectionService toolSelectionService,
            CodeAgentLoopRunnerService runnerService,
            CodeAgentService codeAgentService,
            CodeAgentLocalPatchRequestService localPatchRequestService,
            OllamaClient ollamaClient,
            ObjectMapper objectMapper
    ) {
        this.loopPreviewService = loopPreviewService;
        this.toolSelectionService = toolSelectionService;
        this.runnerService = runnerService;
        this.codeAgentService = codeAgentService;
        this.localPatchRequestService = localPatchRequestService;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public CodeAgentLoopRunResponse start(
            UUID userId,
            UUID repositoryId,
            UUID spaceId,
            String instruction,
            Integer requestedMaxSteps,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopPreviewResponse run = loopPreviewService.startRun(
                userId,
                repositoryId,
                spaceId,
                instruction,
                requestedMaxSteps,
                agentId,
                workspaceId
        );
        List<String> warnings = new ArrayList<>();
        warnings.add("Loop run created. Read-only Local Agent observation is the only automatic first step.");
        warnings.add("Patch mutation, tests, rollback, final publication, and partial reindex still require the existing approval and release gates.");

        boolean attempted = agentId != null && workspaceId != null;
        CodeAgentLoopSelectedToolEnqueueResponse enqueue = null;
        if (attempted) {
            try {
                enqueue = toolSelectionService.enqueueSelectedReadOnlyNextStep(
                        userId,
                        repositoryId,
                        run.loopId(),
                        agentId,
                        workspaceId
                );
            } catch (IllegalStateException | IllegalArgumentException ex) {
                warnings.add(ex.getMessage());
            }
        } else {
            warnings.add("agentId and workspaceId are required before read-only Local Agent work can be queued.");
        }

        boolean queued = enqueue != null && enqueue.queuedRequest() != null;
        return new CodeAgentLoopRunResponse(
                "learnbot.server.code-agent.loop-run.v1",
                run.loopId(),
                repositoryId,
                spaceId,
                agentId,
                workspaceId,
                instruction,
                run.maxSteps(),
                queued ? "READ_ONLY_QUEUED" : "RUN_CREATED",
                true,
                attempted,
                queued,
                false,
                true,
                queued
                        ? "Wait for the Local Agent read-only observation, then continue the loop from the recorded result."
                        : "Connect/select the Local Agent workspace, then enqueue the next read-only runner step.",
                queued ? enqueue.queuedRequest() : null,
                List.copyOf(warnings)
        );
    }

    public CodeAgentLoopRunStatusResponse status(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopNextActionResponse nextAction = loopPreviewService.nextAction(userId, repositoryId, loopId);
        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
        String actionKey = nextAction.actionKey();
        boolean waiting = "WAIT_FOR_LOCAL_AGENT_OBSERVATION".equals(actionKey)
                || "WAIT_FOR_APPROVAL".equals(actionKey)
                || "WAIT_FOR_RELEASE_GATE".equals(actionKey)
                || "WAIT_FOR_FRESH_OBSERVATION_RESULTS".equals(actionKey);
        String runnerDecision = switch (actionKey) {
            case "QUEUE_READ_ONLY_OBSERVATION" -> "ADVANCE_AVAILABLE";
            case "WAIT_FOR_LOCAL_AGENT_OBSERVATION" -> "WAITING_FOR_LOCAL_AGENT";
            case "WAIT_FOR_APPROVAL" -> "WAITING_FOR_APPROVAL";
            case "WAIT_FOR_RELEASE_GATE" -> "WAITING_FOR_RELEASE_GATE";
            case "STOP_WITH_REASON" -> "STOPPED";
            default -> "NO_AUTOMATIC_ADVANCE";
        };
        return new CodeAgentLoopRunStatusResponse(
                "learnbot.server.code-agent.loop-run-status.v1",
                loopId,
                repositoryId,
                agentId,
                workspaceId,
                nextAction.status(),
                actionKey,
                runnerDecision,
                nextAction.reason(),
                "QUEUE_READ_ONLY_OBSERVATION".equals(actionKey),
                waiting,
                false,
                true,
                nextAction,
                null,
                timeline,
                finalReport(nextAction, timeline),
                List.of("Mutation remains approval-gated; server-local mutation fallback is disabled.")
        );
    }

    public CodeAgentLoopRunStatusResponse advance(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopNextActionResponse before = loopPreviewService.nextAction(userId, repositoryId, loopId);
        if (!"QUEUE_READ_ONLY_OBSERVATION".equals(before.actionKey())) {
            return status(userId, repositoryId, loopId, agentId, workspaceId);
        }
        CodeAgentLoopRunnerEnqueueResponse patchProposal = tryCreatePatchApprovalRequest(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        if (patchProposal != null) {
            CodeAgentLoopNextActionResponse afterPatch = loopPreviewService.nextAction(userId, repositoryId, loopId);
            CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
            return new CodeAgentLoopRunStatusResponse(
                    "learnbot.server.code-agent.loop-run-status.v1",
                    loopId,
                    repositoryId,
                    agentId,
                    workspaceId,
                    afterPatch.status(),
                    afterPatch.actionKey(),
                    patchProposal.runnerDecision(),
                    patchProposal.reason(),
                    false,
                    false,
                    false,
                    true,
                    afterPatch,
                    patchProposal,
                    timeline,
                    finalReport(afterPatch, timeline),
                    List.of("Patch proposal was attempted after bounded file.read observations; mutation remains approval-gated.")
            );
        }
        CodeAgentLoopRunnerEnqueueResponse enqueue = runnerService.enqueueReadOnlyNextStep(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        CodeAgentLoopNextActionResponse after = loopPreviewService.nextAction(userId, repositoryId, loopId);
        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
        return new CodeAgentLoopRunStatusResponse(
                "learnbot.server.code-agent.loop-run-status.v1",
                loopId,
                repositoryId,
                agentId,
                workspaceId,
                after.status(),
                after.actionKey(),
                enqueue.runnerDecision(),
                enqueue.reason(),
                false,
                enqueue.queuedRequest() != null,
                false,
                true,
                after,
                enqueue,
                timeline,
                finalReport(after, timeline),
                List.of("Advance attempted one bounded read-only step only; mutation remains approval-gated.")
        );
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

    private Map<String, Object> finalReport(CodeAgentLoopNextActionResponse nextAction, CodeAgentLoopTimelineSummary timeline) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "learnbot.server.code-agent.loop-final-report.v1");
        report.put("status", nextAction.actionKey());
        report.put("reason", nextAction.reason());
        report.put("mutationApplied", false);
        report.put("rollbackRequired", false);
        report.put("partialReindexStatus", "NOT_ENQUEUED");
        if (timeline != null && timeline.events() != null) {
            report.put("eventCount", timeline.events().size());
            report.put("readOnlyObservationCount", timeline.events().stream()
                    .filter(event -> "LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType()))
                    .filter(event -> event.toolName() != null && !event.toolName().isSideEffectful())
                    .count());
            latestEvent(timeline, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED").ifPresent(event -> {
                Object requestId = event.details().get("requestId");
                report.put("approvalRequestCreated", true);
                report.put("approvalRequestId", requestId);
                report.put("approvalRoute", requestId == null ? null : "/api/local-agents/tools/" + requestId + "/approve");
                report.put("toolName", event.toolName() == null ? null : event.toolName().wireName());
                report.put("targetFiles", event.details().get("targetFiles"));
            });
            latestEvent(timeline, "STOP_OUTCOME_RECORDED").ifPresent(event -> {
                report.put("stopKey", event.details().get("stopKey"));
                report.put("blockedSource", event.details().get("source"));
            });
        }
        return java.util.Collections.unmodifiableMap(report);
    }

    private java.util.Optional<CodeAgentLoopTimelineEventSummary> latestEvent(
            CodeAgentLoopTimelineSummary timeline,
            String eventType
    ) {
        if (timeline == null || timeline.events() == null) {
            return java.util.Optional.empty();
        }
        return timeline.events().stream()
                .filter(event -> eventType.equals(event.eventType()))
                .reduce((first, second) -> second);
    }

    private CodeAgentLoopRunnerEnqueueResponse tryCreatePatchApprovalRequest(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopTimelineSummary timeline = timeline(userId, repositoryId, loopId);
        if (timeline == null || timeline.events() == null || agentId == null || workspaceId == null) {
            return null;
        }
        if (hasApprovalRequest(timeline) || fileReadTargets(timeline).isEmpty()) {
            return null;
        }
        var nextPreview = runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);
        if (nextPreview.candidate() != null && nextPreview.candidate().toolName() == LocalAgentToolName.FILE_READ) {
            return null;
        }

        TargetFileSelection targetSelection = selectPatchTargets(timeline);
        List<String> targetFiles = targetSelection.targetFiles();
        if (!targetSelection.ready()) {
            loopPreviewService.appendPatchProposalBlocked(
                    userId,
                    repositoryId,
                    loopId,
                    targetSelection.stopKey(),
                    targetSelection.message(),
                    patchBlockedDetails(targetFiles, "target-selection", null, null)
            );
            return patchProposalResponse(loopId, repositoryId, targetSelection.stopKey(), targetSelection.message());
        }
        List<CodePatchFileLoader.LoadedPatchFile> observedFiles = observedPatchFiles(timeline, targetFiles);
        String patchSource = observedFiles.isEmpty() ? "indexed-loader" : "local-agent-file-read";
        try {
            CodeAgentPatchResponse patch = observedFiles.isEmpty()
                    ? codeAgentService.patch(
                    repositoryId,
                    timeline.spaceId(),
                    timeline.spaceId() == null ? List.of() : List.of(timeline.spaceId()),
                    timeline.instruction(),
                    targetFiles
            )
                    : codeAgentService.patchFromLoadedFiles(timeline.instruction(), observedFiles);
            if ((patch == null || !patch.valid()) && !observedFiles.isEmpty() && observedFiles.size() < targetFiles.size()) {
                patch = codeAgentService.patch(
                        repositoryId,
                        timeline.spaceId(),
                        timeline.spaceId() == null ? List.of() : List.of(timeline.spaceId()),
                        timeline.instruction(),
                        targetFiles
                );
                patchSource = "indexed-loader-fallback";
            }
            if (patch == null || !patch.valid() || patch.files() == null || patch.files().isEmpty()) {
                loopPreviewService.appendPatchProposalBlocked(
                        userId,
                        repositoryId,
                        loopId,
                        "PATCH_PROPOSAL_BLOCKED",
                        "Patch proposal did not produce a valid unified diff.",
                        patchBlockedDetails(targetFiles, patchSource, patch, null)
                );
                return patchProposalResponse(loopId, repositoryId, "PATCH_PROPOSAL_BLOCKED", "Patch proposal did not produce a valid unified diff.");
            }
            String diff = patch.files().stream()
                    .map(PatchFileDiff::diff)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse("");
            if (diff.isBlank()) {
                loopPreviewService.appendPatchProposalBlocked(
                        userId,
                        repositoryId,
                        loopId,
                        "PATCH_PROPOSAL_BLOCKED",
                        "Patch proposal returned no diff body.",
                        patchBlockedDetails(targetFiles, patchSource, patch, null)
                );
                return patchProposalResponse(loopId, repositoryId, "PATCH_PROPOSAL_BLOCKED", "Patch proposal returned no diff body.");
            }
            LocalAgentToolExecutionResponse approvalRequest = localPatchRequestService.prepare(
                    repositoryId,
                    timeline.spaceId(),
                    userId,
                    agentId,
                    workspaceId,
                    loopId,
                    timeline.instruction(),
                    diff,
                    targetFiles,
                    observedFiles
            );
            return patchProposalResponse(
                    loopId,
                    repositoryId,
                    "CREATED_VALIDATED_PATCH_APPROVAL_REQUEST",
                    "Created a validated patch.apply approval request from completed file.read observations. Release, claim, mutation, tests, rollback, final publication, and partial reindex remain disabled.",
                    approvalRequest
            );
        } catch (RuntimeException ex) {
            loopPreviewService.appendPatchProposalBlocked(
                    userId,
                    repositoryId,
                    loopId,
                    "PATCH_PROPOSAL_BLOCKED",
                    "Patch proposal or approval request creation failed.",
                    patchBlockedDetails(targetFiles, patchSource, null, ex)
            );
            return patchProposalResponse(loopId, repositoryId, "PATCH_PROPOSAL_BLOCKED", "Patch proposal or approval request creation failed: " + ex.getMessage());
        }
    }

    private boolean hasApprovalRequest(CodeAgentLoopTimelineSummary timeline) {
        return timeline.events().stream()
                .anyMatch(event -> "LOCAL_AGENT_APPROVAL_REQUEST_CREATED".equals(event.eventType())
                        && event.toolName() == LocalAgentToolName.PATCH_APPLY);
    }

    private List<String> fileReadTargets(CodeAgentLoopTimelineSummary timeline) {
        Set<String> targets = new LinkedHashSet<>();
        for (CodeAgentLoopTimelineEventSummary event : timeline.events()) {
            if (!"LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType())
                    || event.toolName() != LocalAgentToolName.FILE_READ
                    || !"SUCCEEDED".equals(String.valueOf(event.details().get("status")))) {
                continue;
            }
            Object outputSummary = event.details().get("outputSummary");
            if (outputSummary instanceof Map<?, ?> map) {
                Object path = map.get("relativePath");
                if (path != null && safeRelativePath(String.valueOf(path))) {
                    targets.add(String.valueOf(path));
                }
            }
        }
        return List.copyOf(targets);
    }

    private TargetFileSelection selectPatchTargets(CodeAgentLoopTimelineSummary timeline) {
        String instruction = timeline == null ? "" : timeline.instruction();
        List<String> candidates = fileReadTargets(timeline);
        List<String> safeCandidates = candidates == null
                ? List.of()
                : candidates.stream()
                .filter(this::safeRelativePath)
                .distinct()
                .toList();
        if (safeCandidates.isEmpty()) {
            return TargetFileSelection.blocked("PATCH_PROPOSAL_BLOCKED", "No readable candidate file was selected for patching.", safeCandidates);
        }
        if (safeCandidates.size() == 1) {
            return TargetFileSelection.ready(safeCandidates);
        }

        TargetFileSelection modelSelection = selectPatchTargetsWithModel(timeline, safeCandidates);
        if (modelSelection != null && modelSelection.ready()) {
            return modelSelection;
        }

        List<String> explicitMatches = safeCandidates.stream()
                .filter(path -> instructionMentionsPath(instruction, path))
                .toList();
        if (explicitMatches.size() == 1) {
            return TargetFileSelection.ready(explicitMatches);
        }

        if (mentionsReadme(instruction)) {
            List<String> readmeMatches = safeCandidates.stream()
                    .filter(this::isReadmePath)
                    .sorted((left, right) -> Integer.compare(readmePriority(left), readmePriority(right)))
                    .toList();
            if (readmeMatches.size() == 1 || (readmeMatches.size() > 1 && readmePriority(readmeMatches.get(0)) < readmePriority(readmeMatches.get(1)))) {
                return TargetFileSelection.ready(List.of(readmeMatches.get(0)));
            }
        }

        return TargetFileSelection.blocked(
                "AMBIGUOUS_TARGET_FILES",
                "Multiple candidate files were read, but the instruction did not identify one target file clearly.",
                safeCandidates
        );
    }

    private TargetFileSelection selectPatchTargetsWithModel(CodeAgentLoopTimelineSummary timeline, List<String> safeCandidates) {
        if (ollamaClient == null || timeline == null || safeCandidates == null || safeCandidates.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(cleanJson(ollamaClient.chatResult(
                    targetSelectionSystemPrompt(),
                    targetSelectionUserPrompt(timeline, safeCandidates),
                    500
            ).content()));
            if (root.path("needsClarification").asBoolean(false)) {
                return null;
            }
            List<String> selected = new ArrayList<>();
            Set<String> allowed = new LinkedHashSet<>(safeCandidates);
            for (JsonNode node : root.path("targetFiles")) {
                String path = node.asText("");
                if (allowed.contains(path)) {
                    selected.add(path);
                }
            }
            if (selected.size() == 1) {
                return TargetFileSelection.ready(selected);
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String targetSelectionSystemPrompt() {
        return """
                You select the exact target file for a LearnBot local code edit.
                Return JSON only.
                Select targetFiles only from the provided candidate list.
                If the user asks for one file and it is clear, return exactly one target file.
                If unclear, set needsClarification=true and return no target files.
                Do not invent paths.
                """;
    }

    private String targetSelectionUserPrompt(CodeAgentLoopTimelineSummary timeline, List<String> safeCandidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("Instruction:\n").append(timeline.instruction()).append("\n\nCandidate files:\n");
        for (String path : safeCandidates) {
            builder.append("- ").append(path).append("\n");
        }
        builder.append("\nRead observations:\n");
        for (CodeAgentLoopTimelineEventSummary event : timeline.events() == null ? List.<CodeAgentLoopTimelineEventSummary>of() : timeline.events()) {
            if (!"LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType())
                    || event.toolName() != LocalAgentToolName.FILE_READ
                    || !"SUCCEEDED".equals(String.valueOf(event.details().get("status")))) {
                continue;
            }
            Object outputSummary = event.details().get("outputSummary");
            if (outputSummary instanceof Map<?, ?> map) {
                Object path = map.get("relativePath");
                Object preview = map.get("contentPreview");
                builder.append("FILE: ").append(path == null ? "" : path).append("\n");
                builder.append("PREVIEW: ").append(preview == null ? "" : preview).append("\n");
            }
        }
        builder.append("""

                JSON shape:
                {"targetFiles":["path/from/candidates"],"reason":"...","confidence":"low|medium|high","needsClarification":false}
                """);
        return builder.toString();
    }

    private String cleanJson(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```[A-Za-z]*\\s*", "");
            clean = clean.replaceFirst("\\s*```$", "");
        }
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        return start >= 0 && end > start ? clean.substring(start, end + 1) : clean;
    }

    private boolean instructionMentionsPath(String instruction, String path) {
        String normalizedInstruction = normalizeForMention(instruction);
        String normalizedPath = normalizeForMention(path);
        String basename = normalizedPath.contains("/")
                ? normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1)
                : normalizedPath;
        String stem = basename.contains(".") ? basename.substring(0, basename.lastIndexOf('.')) : basename;
        return (!normalizedPath.isBlank() && normalizedInstruction.contains(normalizedPath))
                || (!basename.isBlank() && normalizedInstruction.contains(basename))
                || (stem.length() >= 4 && normalizedInstruction.contains(stem))
                || instructionMentionsStemAlias(normalizedInstruction, stem);
    }

    private boolean instructionMentionsStemAlias(String normalizedInstruction, String stem) {
        if (normalizedInstruction == null || normalizedInstruction.isBlank() || stem == null || stem.isBlank()) {
            return false;
        }
        if (stem.endsWith("file") && stem.length() > "file".length()) {
            String prefix = stem.substring(0, stem.length() - "file".length());
            return prefix.length() >= 4
                    && (normalizedInstruction.contains(prefix + "파일")
                    || normalizedInstruction.contains(prefix + " file")
                    || normalizedInstruction.contains(prefix + "-file")
                    || normalizedInstruction.contains(prefix + "_file"));
        }
        return false;
    }

    private String normalizeForMention(String value) {
        return value == null
                ? ""
                : value.replace('\\', '/').trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean mentionsReadme(String instruction) {
        return normalizeForMention(instruction).contains("readme");
    }

    private boolean isReadmePath(String path) {
        String basename = normalizeForMention(path);
        if (basename.contains("/")) {
            basename = basename.substring(basename.lastIndexOf('/') + 1);
        }
        return basename.equals("readme")
                || basename.equals("readme.md")
                || basename.equals("readme.txt")
                || basename.startsWith("readme.");
    }

    private int readmePriority(String path) {
        String basename = normalizeForMention(path);
        if (basename.contains("/")) {
            basename = basename.substring(basename.lastIndexOf('/') + 1);
        }
        if (basename.equals("readme.md")) return 0;
        if (basename.equals("readme.txt")) return 1;
        if (basename.equals("readme")) return 2;
        return 10;
    }

    private List<CodePatchFileLoader.LoadedPatchFile> observedPatchFiles(CodeAgentLoopTimelineSummary timeline, List<String> targetFiles) {
        if (timeline == null || timeline.events() == null || targetFiles == null || targetFiles.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = new LinkedHashSet<>(targetFiles);
        Map<String, CodePatchFileLoader.LoadedPatchFile> filesByPath = new LinkedHashMap<>();
        for (CodeAgentLoopTimelineEventSummary event : timeline.events()) {
            if (!"LOCAL_AGENT_OBSERVATION_RESULT".equals(event.eventType())
                    || event.toolName() != LocalAgentToolName.FILE_READ
                    || !"SUCCEEDED".equals(String.valueOf(event.details().get("status")))) {
                continue;
            }
            Object outputSummary = event.details().get("outputSummary");
            if (!(outputSummary instanceof Map<?, ?> map)) {
                continue;
            }
            Object pathValue = map.get("relativePath");
            Object contentValue = map.get("contentForPatch");
            String path = pathValue == null ? "" : String.valueOf(pathValue);
            if (!allowed.contains(path) || !safeRelativePath(path) || !(contentValue instanceof String content)) {
                continue;
            }
            filesByPath.put(path, new CodePatchFileLoader.LoadedPatchFile(null, path, languageForPath(path), content));
        }
        return List.copyOf(filesByPath.values());
    }

    private String languageForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt")) return "kotlin";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".md") || lower.contains("readme")) return "markdown";
        if (lower.endsWith(".txt")) return "text";
        return "text";
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

    private Map<String, Object> patchBlockedDetails(List<String> targetFiles, String patchSource, CodeAgentPatchResponse patch, RuntimeException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schema", "learnbot.server.code-agent.patch-proposal-result.v1");
        details.put("targetFiles", targetFiles == null ? List.of() : targetFiles);
        details.put("patchSource", patchSource);
        details.put("valid", patch != null && patch.valid());
        details.put("summary", patch == null ? null : patch.summary());
        details.put("riskLevel", patch == null ? null : patch.riskLevel());
        details.put("warnings", patch == null || patch.warnings() == null ? List.of() : patch.warnings());
        details.put("testSuggestions", patch == null || patch.testSuggestions() == null ? List.of() : patch.testSuggestions());
        details.put("error", ex == null ? null : ex.getMessage());
        details.put("mutationAllowed", false);
        details.put("approvalRequestCreated", false);
        return java.util.Collections.unmodifiableMap(details);
    }

    private record TargetFileSelection(
            boolean ready,
            List<String> targetFiles,
            String stopKey,
            String message
    ) {
        private static TargetFileSelection ready(List<String> targetFiles) {
            return new TargetFileSelection(true, List.copyOf(targetFiles), "READY", "Target file selection is clear.");
        }

        private static TargetFileSelection blocked(String stopKey, String message, List<String> candidates) {
            return new TargetFileSelection(false, List.copyOf(candidates), stopKey, message);
        }
    }

    private CodeAgentLoopRunnerEnqueueResponse patchProposalResponse(
            UUID loopId,
            UUID repositoryId,
            String decision,
            String reason
    ) {
        return patchProposalResponse(loopId, repositoryId, decision, reason, null);
    }

    private CodeAgentLoopRunnerEnqueueResponse patchProposalResponse(
            UUID loopId,
            UUID repositoryId,
            String decision,
            String reason,
            LocalAgentToolExecutionResponse approvalRequest
    ) {
        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("schema", "learnbot.server.code-agent.patch-proposal-approval-handoff.v1");
        handoff.put("approvalRequestCreated", approvalRequest != null);
        handoff.put("approvalRequestId", approvalRequest == null ? null : approvalRequest.requestId().toString());
        handoff.put("toolName", LocalAgentToolName.PATCH_APPLY.wireName());
        handoff.put("approvalRoute", approvalRequest == null ? null : "/api/local-agents/tools/" + approvalRequest.requestId() + "/approve");
        handoff.put("mutationEnabled", false);
        handoff.put("releaseRequired", approvalRequest != null);
        handoff.put("releaseGateEnabled", false);
        return new CodeAgentLoopRunnerEnqueueResponse(
                loopId,
                repositoryId,
                approvalRequest == null ? "RECORDED" : approvalRequest.status().name(),
                approvalRequest == null ? "STOP_WITH_REASON" : "WAIT_FOR_APPROVAL",
                decision,
                reason,
                approvalRequest != null,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                java.util.Collections.unmodifiableMap(handoff),
                null,
                null
        );
    }
}
