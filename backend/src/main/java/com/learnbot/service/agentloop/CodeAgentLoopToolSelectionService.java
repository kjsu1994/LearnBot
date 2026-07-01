package com.learnbot.service.agentloop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.loop.CodeAgentLoopApprovalRequestPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalRequestResponse;
import com.learnbot.dto.loop.CodeAgentLoopSelectedToolEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopSideEffectBoundaryResponse;
import com.learnbot.dto.loop.CodeAgentLoopToolCandidate;
import com.learnbot.dto.loop.CodeAgentLoopToolSelectionResponse;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.LocalAgentToolGatewayService;
import com.learnbot.service.OllamaClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeAgentLoopToolSelectionService {
    private static final int MAX_TOOL_SELECTION_TOKENS = 400;

    private final CodeAgentLoopRunnerService runnerService;
    private final LocalAgentToolGatewayService toolGatewayService;
    private final CodeAgentLocalPatchRequestService localPatchRequestService;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public CodeAgentLoopToolSelectionService(
            CodeAgentLoopRunnerService runnerService,
            LocalAgentToolGatewayService toolGatewayService,
            CodeAgentLocalPatchRequestService localPatchRequestService,
            OllamaClient ollamaClient,
            ObjectMapper objectMapper
    ) {
        this.runnerService = runnerService;
        this.toolGatewayService = toolGatewayService;
        this.localPatchRequestService = localPatchRequestService;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public CodeAgentLoopToolSelectionResponse selectNextToolPreview(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopRunnerPreviewResponse preview = runnerService.previewNextStep(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        CodeAgentLoopToolCandidate candidate = preview.candidate();
        if (!"PREPARED_READ_ONLY_CANDIDATE".equals(preview.runnerDecision()) || candidate == null) {
            return response(
                    preview,
                    "NO_MODEL_SELECTION",
                    preview.reason(),
                    false,
                    false,
                    false,
                    null,
                    null
            );
        }

        try {
            JsonNode modelDecision = objectMapper.readTree(cleanJson(ollamaClient.chatResult(
                    systemPrompt(),
                    userPrompt(preview, candidate),
                    MAX_TOOL_SELECTION_TOKENS
            ).content()));
            if (acceptsReadOnlyGitStatus(modelDecision, candidate)) {
                return response(
                        preview,
                        "MODEL_SELECTED_READ_ONLY_CANDIDATE",
                        "Model selected the allowed read-only git.status candidate. Execution and mutation remain disabled in this preview.",
                        true,
                        true,
                        true,
                        candidate,
                        modelMap(modelDecision)
                );
            }
            return response(
                    preview,
                    "MODEL_SELECTION_REJECTED_FALLBACK_READ_ONLY",
                    "Model output did not match the allowed read-only git.status contract; deterministic read-only fallback was retained.",
                    true,
                    false,
                    false,
                    candidate,
                    modelMap(modelDecision)
            );
        } catch (Exception ex) {
            return response(
                    preview,
                    "MODEL_SELECTION_FAILED_FALLBACK_READ_ONLY",
                    "Model tool selection failed; deterministic read-only git.status fallback was retained.",
                    true,
                    false,
                    false,
                    candidate,
                    Map.of("error", ex.getClass().getSimpleName())
            );
        }
    }

    public CodeAgentLoopSelectedToolEnqueueResponse enqueueSelectedReadOnlyNextStep(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopToolSelectionResponse selection = selectNextToolPreview(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        CodeAgentLoopToolCandidate candidate = selection.candidate();
        if (candidate == null) {
            return enqueueResponse(
                    selection,
                    "NOT_ENQUEUED",
                    selection.reason(),
                    null
            );
        }
        if (!safeReadOnlyCandidate(candidate)) {
            return enqueueResponse(
                    selection,
                    "REFUSED_UNSAFE_SELECTED_CANDIDATE",
                    "Selected tool candidate failed the read-only enqueue guardrail.",
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
                selection,
                selection.selectedByModel()
                        ? "ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION"
                        : "ENQUEUED_FALLBACK_READ_ONLY_OBSERVATION",
                selection.selectedByModel()
                        ? "Queued the model-selected read-only Local Agent git.status observation. Mutation remains disabled."
                        : "Queued the deterministic read-only Local Agent git.status fallback after model selection was unavailable or rejected. Mutation remains disabled.",
                queued
        );
    }

    public CodeAgentLoopSideEffectBoundaryResponse previewSideEffectBoundary(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopRunnerPreviewResponse preview = runnerService.previewNextStep(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        CodeAgentLoopToolCandidate candidate = preview.candidate();
        if (!"PREPARED_READ_ONLY_CANDIDATE".equals(preview.runnerDecision()) || candidate == null) {
            return sideEffectResponse(
                    preview,
                    "NO_SIDE_EFFECT_BOUNDARY_SELECTION",
                    preview.reason(),
                    false,
                    false,
                    Map.of()
            );
        }
        try {
            JsonNode modelDecision = objectMapper.readTree(cleanJson(ollamaClient.chatResult(
                    sideEffectBoundarySystemPrompt(),
                    sideEffectBoundaryUserPrompt(preview, candidate),
                    MAX_TOOL_SELECTION_TOKENS
            ).content()));
            if (proposesPatchApply(modelDecision)) {
                return sideEffectResponse(
                        preview,
                        "SIDE_EFFECTFUL_PATCH_REQUIRES_APPROVAL_RELEASE",
                        "Model proposed patch.apply, but side-effectful tools require explicit approval and release before any request can be created or queued.",
                        true,
                        true,
                        modelMap(modelDecision)
                );
            }
            return sideEffectResponse(
                    preview,
                    "NO_SIDE_EFFECTFUL_TOOL_PROPOSED",
                    "Model did not propose a side-effectful tool. No approval, release, request creation, enqueue, or mutation is enabled.",
                    true,
                    false,
                    modelMap(modelDecision)
            );
        } catch (Exception ex) {
            return sideEffectResponse(
                    preview,
                    "SIDE_EFFECT_BOUNDARY_MODEL_FAILED",
                    "Model side-effect boundary selection failed. No approval, release, request creation, enqueue, or mutation is enabled.",
                    true,
                    false,
                    Map.of("error", ex.getClass().getSimpleName())
            );
        }
    }

    public CodeAgentLoopApprovalRequestPreviewResponse previewPatchApprovalRequest(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopSideEffectBoundaryResponse boundary = previewSideEffectBoundary(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        if (!"SIDE_EFFECTFUL_PATCH_REQUIRES_APPROVAL_RELEASE".equals(boundary.boundaryDecision())) {
            return approvalResponse(
                    boundary,
                    "NO_APPROVAL_REQUEST_PREPARED",
                    boundary.reason(),
                    null
            );
        }
        if (agentId == null || workspaceId == null) {
            return approvalResponse(
                    boundary,
                    "WAIT_FOR_AGENT_WORKSPACE",
                    "patch.apply requires agentId and workspaceId before an approval request can be previewed.",
                    null
            );
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", 1);
        input.put("repositoryId", repositoryId.toString());
        if (boundary.loopId() != null) {
            input.put("loopId", boundary.loopId().toString());
        }
        input.put("purpose", "loop.patchApprovalRequestPreview");
        input.put("sourceBoundaryDecision", boundary.boundaryDecision());
        input.put("approvalRequired", true);
        input.put("releaseRequired", true);
        input.put("releaseEvidenceAvailable", false);
        input.put("releaseGateEnabled", false);
        input.put("requestCreationEnabled", false);
        input.put("enqueueEnabled", false);
        input.put("mutationAllowed", false);

        CodeAgentLoopToolCandidate candidate = new CodeAgentLoopToolCandidate(
                boundary.loopId() == null ? UUID.randomUUID() : boundary.loopId(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                true,
                true,
                false,
                false,
                Map.copyOf(input),
                java.util.List.of("patch.apply approval request is preview-only; request creation, enqueue, release, claim, and mutation remain disabled.")
        );
        return approvalResponse(
                boundary,
                "PREPARED_PATCH_APPROVAL_REQUEST_PREVIEW",
                "Prepared preview metadata for a future patch.apply approval request. No request is created, queued, released, claimed, or mutated.",
                candidate
        );
    }

    public CodeAgentLoopPatchApprovalRequestResponse createPatchApprovalRequest(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        CodeAgentLoopApprovalRequestPreviewResponse preview = previewPatchApprovalRequest(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        CodeAgentLoopToolCandidate candidate = preview.candidate();
        if (candidate == null) {
            return patchApprovalRequestResponse(
                    preview,
                    "NO_APPROVAL_REQUEST_CREATED",
                    preview.reason(),
                    null
            );
        }
        if (!safePatchApprovalCandidate(candidate)) {
            return patchApprovalRequestResponse(
                    preview,
                    "REFUSED_UNSAFE_PATCH_APPROVAL_CANDIDATE",
                    "Patch approval candidate failed the side-effect approval guardrail.",
                    null
            );
        }

        LocalAgentToolExecutionResponse approvalRequest = toolGatewayService.createApprovalRequest(new LocalAgentToolRequest(
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
        return patchApprovalRequestResponse(
                preview,
                "CREATED_PATCH_APPROVAL_REQUEST",
                "Created a patch.apply approval request in APPROVAL_REQUIRED state. Release, push, claim, mutation, final publication, and acknowledgement remain disabled.",
                approvalRequest
        );
    }

    public CodeAgentLoopPatchApprovalRequestResponse createValidatedPatchApprovalRequest(
            UUID userId,
            UUID repositoryId,
            UUID spaceId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId,
            String instruction,
            String diff,
            List<String> targetFiles
    ) {
        CodeAgentLoopApprovalRequestPreviewResponse preview = previewPatchApprovalRequest(
                userId,
                repositoryId,
                loopId,
                agentId,
                workspaceId
        );
        CodeAgentLoopToolCandidate candidate = preview.candidate();
        if (candidate == null) {
            return patchApprovalRequestResponse(
                    preview,
                    "NO_VALIDATED_PATCH_APPROVAL_REQUEST_CREATED",
                    preview.reason(),
                    null
            );
        }
        if (!safePatchApprovalCandidate(candidate)) {
            return patchApprovalRequestResponse(
                    preview,
                    "REFUSED_UNSAFE_VALIDATED_PATCH_APPROVAL_CANDIDATE",
                    "Patch approval candidate failed the validated side-effect approval guardrail.",
                    null
            );
        }

        LocalAgentToolExecutionResponse approvalRequest = localPatchRequestService.prepare(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                instruction,
                diff,
                targetFiles
        );
        return patchApprovalRequestResponse(
                preview,
                "CREATED_VALIDATED_PATCH_APPROVAL_REQUEST",
                "Created a validated patch.apply approval request in APPROVAL_REQUIRED state. Release, push, claim, mutation, final publication, and acknowledgement remain disabled.",
                approvalRequest
        );
    }

    private boolean acceptsReadOnlyGitStatus(JsonNode root, CodeAgentLoopToolCandidate candidate) {
        String toolName = text(root, "toolName");
        String actionKey = text(root, "actionKey");
        boolean readOnly = root.path("readOnly").asBoolean(false);
        boolean mutationAllowed = root.path("mutationAllowed").asBoolean(true);
        boolean requiresApproval = root.path("requiresApproval").asBoolean(true);
        return LocalAgentToolName.GIT_STATUS.equals(candidate.toolName())
                && LocalAgentApprovalState.NOT_REQUIRED.equals(candidate.approvalState())
                && !candidate.sideEffectful()
                && !candidate.requiresApproval()
                && !candidate.mutationAllowed()
                && LocalAgentToolName.GIT_STATUS.wireName().equals(toolName)
                && "QUEUE_READ_ONLY_OBSERVATION".equals(actionKey)
                && readOnly
                && !mutationAllowed
                && !requiresApproval;
    }

    private boolean safeReadOnlyCandidate(CodeAgentLoopToolCandidate candidate) {
        return candidate.toolName() == LocalAgentToolName.GIT_STATUS
                && candidate.approvalState() == LocalAgentApprovalState.NOT_REQUIRED
                && !candidate.sideEffectful()
                && !candidate.requiresApproval()
                && !candidate.mutationAllowed();
    }

    private boolean proposesPatchApply(JsonNode root) {
        return LocalAgentToolName.PATCH_APPLY.wireName().equals(text(root, "toolName"))
                && root.path("requiresApproval").asBoolean(false);
    }

    private boolean safePatchApprovalCandidate(CodeAgentLoopToolCandidate candidate) {
        return candidate.toolName() == LocalAgentToolName.PATCH_APPLY
                && candidate.executionTarget() == AgentExecutionTarget.USER_LOCAL_AGENT
                && candidate.approvalState() == LocalAgentApprovalState.REQUIRED
                && candidate.sideEffectful()
                && candidate.requiresApproval()
                && !candidate.enqueueEnabled()
                && !candidate.mutationAllowed()
                && candidate.agentId() != null
                && candidate.workspaceId() != null;
    }

    private CodeAgentLoopToolSelectionResponse response(
            CodeAgentLoopRunnerPreviewResponse preview,
            String selectionDecision,
            String reason,
            boolean modelAttempted,
            boolean modelAccepted,
            boolean selectedByModel,
            CodeAgentLoopToolCandidate candidate,
            Map<String, Object> modelDecision
    ) {
        return new CodeAgentLoopToolSelectionResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                selectionDecision,
                reason,
                modelAttempted,
                modelAccepted,
                selectedByModel,
                false,
                false,
                false,
                false,
                false,
                preview,
                candidate,
                modelDecision == null ? Map.of() : Map.copyOf(modelDecision),
                guardrails()
        );
    }

    private CodeAgentLoopSelectedToolEnqueueResponse enqueueResponse(
            CodeAgentLoopToolSelectionResponse selection,
            String runnerDecision,
            String reason,
            LocalAgentQueuedToolRequest queued
    ) {
        boolean enqueued = queued != null;
        return new CodeAgentLoopSelectedToolEnqueueResponse(
                selection.loopId(),
                selection.repositoryId(),
                selection.status(),
                selection.actionKey(),
                runnerDecision,
                reason,
                selection.modelToolSelectionAttempted(),
                selection.modelToolSelectionAccepted(),
                selection.selectedByModel(),
                enqueued,
                enqueued,
                enqueued,
                false,
                false,
                false,
                false,
                false,
                selection,
                queued
        );
    }

    private CodeAgentLoopSideEffectBoundaryResponse sideEffectResponse(
            CodeAgentLoopRunnerPreviewResponse preview,
            String boundaryDecision,
            String reason,
            boolean modelAttempted,
            boolean modelProposedSideEffectfulTool,
            Map<String, Object> modelDecision
    ) {
        return new CodeAgentLoopSideEffectBoundaryResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                boundaryDecision,
                reason,
                modelAttempted,
                modelProposedSideEffectfulTool,
                modelProposedSideEffectfulTool,
                modelProposedSideEffectfulTool,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                preview,
                modelDecision == null ? Map.of() : Map.copyOf(modelDecision),
                sideEffectGuardrails()
        );
    }

    private CodeAgentLoopApprovalRequestPreviewResponse approvalResponse(
            CodeAgentLoopSideEffectBoundaryResponse boundary,
            String approvalDecision,
            String reason,
            CodeAgentLoopToolCandidate candidate
    ) {
        boolean prepared = candidate != null;
        return new CodeAgentLoopApprovalRequestPreviewResponse(
                boundary.loopId(),
                boundary.repositoryId(),
                boundary.status(),
                boundary.actionKey(),
                approvalDecision,
                reason,
                prepared,
                prepared,
                prepared,
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
                boundary,
                candidate,
                approvalGuardrails()
        );
    }

    private CodeAgentLoopPatchApprovalRequestResponse patchApprovalRequestResponse(
            CodeAgentLoopApprovalRequestPreviewResponse preview,
            String approvalDecision,
            String reason,
            LocalAgentToolExecutionResponse approvalRequest
    ) {
        boolean created = approvalRequest != null;
        return new CodeAgentLoopPatchApprovalRequestResponse(
                preview.loopId(),
                preview.repositoryId(),
                preview.status(),
                preview.actionKey(),
                approvalDecision,
                reason,
                created,
                created || preview.approvalRequired(),
                created || preview.releaseRequired(),
                false,
                false,
                created,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                preview,
                approvalRequest,
                patchApprovalRequestGuardrails()
        );
    }

    private Map<String, Object> guardrails() {
        Map<String, Object> guardrails = new LinkedHashMap<>();
        guardrails.put("modelToolSelectionEnabled", true);
        guardrails.put("allowedTools", java.util.List.of(LocalAgentToolName.GIT_STATUS.wireName()));
        guardrails.put("requestCreationEnabled", false);
        guardrails.put("enqueueEnabled", false);
        guardrails.put("sideEffectfulToolsBlocked", true);
        guardrails.put("approvalRequiredBeforeSideEffects", true);
        guardrails.put("mutationAllowed", false);
        return Map.copyOf(guardrails);
    }

    private Map<String, Object> sideEffectGuardrails() {
        Map<String, Object> guardrails = new LinkedHashMap<>();
        guardrails.put("modelToolSelectionEnabled", true);
        guardrails.put("sideEffectfulBoundaryPreview", true);
        guardrails.put("allowedSideEffectfulToolForBoundaryOnly", LocalAgentToolName.PATCH_APPLY.wireName());
        guardrails.put("approvalRequiredBeforeSideEffects", true);
        guardrails.put("releaseRequiredBeforeClaim", true);
        guardrails.put("releaseGateEnabled", false);
        guardrails.put("requestCreationEnabled", false);
        guardrails.put("enqueueEnabled", false);
        guardrails.put("mutationAllowed", false);
        return Map.copyOf(guardrails);
    }

    private Map<String, Object> approvalGuardrails() {
        Map<String, Object> guardrails = new LinkedHashMap<>();
        guardrails.put("approvalPreviewEnabled", true);
        guardrails.put("approvalRequiredBeforeSideEffects", true);
        guardrails.put("releaseEvidenceRequired", true);
        guardrails.put("releaseEvidenceAvailable", false);
        guardrails.put("releaseGateEnabled", false);
        guardrails.put("requestCreationEnabled", false);
        guardrails.put("enqueueEnabled", false);
        guardrails.put("claimEnabled", false);
        guardrails.put("mutationAllowed", false);
        return Map.copyOf(guardrails);
    }

    private Map<String, Object> patchApprovalRequestGuardrails() {
        Map<String, Object> guardrails = new LinkedHashMap<>();
        guardrails.put("approvalRequestCreationEnabled", true);
        guardrails.put("approvalRequiredBeforeSideEffects", true);
        guardrails.put("createdStatus", "APPROVAL_REQUIRED");
        guardrails.put("claimableStatuses", java.util.List.of("PENDING", "APPROVED"));
        guardrails.put("releaseEvidenceRequired", true);
        guardrails.put("releaseEvidenceAvailable", false);
        guardrails.put("releaseGateEnabled", false);
        guardrails.put("enqueueEnabled", false);
        guardrails.put("pushEnabled", false);
        guardrails.put("claimEnabled", false);
        guardrails.put("mutationAllowed", false);
        return Map.copyOf(guardrails);
    }

    private Map<String, Object> modelMap(JsonNode root) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionKey", text(root, "actionKey"));
        result.put("toolName", text(root, "toolName"));
        result.put("reason", text(root, "reason"));
        result.put("readOnly", root.path("readOnly").asBoolean(false));
        result.put("requiresApproval", root.path("requiresApproval").asBoolean(true));
        result.put("mutationAllowed", root.path("mutationAllowed").asBoolean(true));
        return Map.copyOf(result);
    }

    private String systemPrompt() {
        return """
                You select one safe LearnBot Local Agent tool candidate.
                Return JSON only.
                Allowed tools: git.status.
                You must not select side-effectful tools.
                mutationAllowed must be false.
                requiresApproval must be false.
                """;
    }

    private String userPrompt(CodeAgentLoopRunnerPreviewResponse preview, CodeAgentLoopToolCandidate candidate) {
        return """
                Next action: %s
                Runner decision: %s
                Candidate tool: %s
                Candidate is read-only: %s
                Candidate requires approval: %s
                Candidate mutationAllowed: %s

                Return this JSON shape:
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"..."}
                """.formatted(
                preview.actionKey(),
                preview.runnerDecision(),
                candidate.toolName().wireName(),
                !candidate.sideEffectful(),
                candidate.requiresApproval(),
                candidate.mutationAllowed()
        );
    }

    private String sideEffectBoundarySystemPrompt() {
        return """
                You identify whether the next LearnBot Local Agent step would require a side-effectful tool.
                Return JSON only.
                Allowed side-effectful tool for boundary preview: patch.apply.
                If patch.apply is needed, requiresApproval must be true and mutationAllowed must be false.
                Do not claim execution is enabled.
                """;
    }

    private String sideEffectBoundaryUserPrompt(CodeAgentLoopRunnerPreviewResponse preview, CodeAgentLoopToolCandidate candidate) {
        return """
                Next action: %s
                Runner decision: %s
                Current safe candidate tool: %s

                Return one of these JSON shapes:
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"..."}
                {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"..."}
                """.formatted(
                preview.actionKey(),
                preview.runnerDecision(),
                candidate.toolName().wireName()
        );
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

    private String text(JsonNode root, String field) {
        return root == null || root.path(field).isMissingNode() ? "" : root.path(field).asText("");
    }
}
