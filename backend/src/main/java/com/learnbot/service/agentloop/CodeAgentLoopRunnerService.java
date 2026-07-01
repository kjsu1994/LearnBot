package com.learnbot.service.agentloop;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopToolCandidate;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.LocalAgentToolGatewayService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (!"QUEUE_READ_ONLY_OBSERVATION".equals(nextAction.actionKey())) {
            return response(nextAction, "NO_REQUEST_PREPARED", nextAction.reason(), null);
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

        CodeAgentLoopToolCandidate candidate = new CodeAgentLoopToolCandidate(
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentApprovalState.NOT_REQUIRED,
                false,
                false,
                false,
                false,
                Map.copyOf(input),
                List.of("Runner preview prepared a read-only git.status candidate only. Enqueue and mutation remain disabled.")
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
        if (candidate.toolName() != LocalAgentToolName.GIT_STATUS
                || candidate.sideEffectful()
                || candidate.requiresApproval()
                || candidate.approvalState() != LocalAgentApprovalState.NOT_REQUIRED
                || candidate.mutationAllowed()) {
            return enqueueResponse(
                    preview,
                    "REFUSED_UNSAFE_CANDIDATE",
                    "Runner enqueue only accepts a non-side-effectful git.status candidate with no approval requirement.",
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
                "Queued the next read-only Local Agent git.status observation. Mutation remains disabled.",
                queued
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
                guardrails()
        );
    }

    private Map<String, Object> guardrails() {
        Map<String, Object> guardrails = new LinkedHashMap<>();
        guardrails.put("modelToolSelectionEnabled", false);
        guardrails.put("requestCreationEnabled", false);
        guardrails.put("enqueueEnabled", false);
        guardrails.put("sideEffectfulToolsBlocked", true);
        guardrails.put("allowedCandidateTool", LocalAgentToolName.GIT_STATUS.wireName());
        guardrails.put("approvalRequiredBeforeSideEffects", true);
        guardrails.put("mutationAllowed", false);
        return Map.copyOf(guardrails);
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
        summary.put("releaseBoundaryRoute", requestId == null ? null : "POST /api/local-agents/tools/" + requestId + "/release");
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
}
