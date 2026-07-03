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
import com.learnbot.dto.loop.CodeAgentLoopSubmissionPlanResponse;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
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

    public CodeAgentLoopPreviewResponse startRun(
            UUID userId,
            UUID repositoryId,
            UUID spaceId,
            String instruction,
            Integer requestedMaxSteps,
            UUID agentId,
            UUID workspaceId
    ) {
        int maxSteps = boundedMaxSteps(requestedMaxSteps);
        CodeAgentLoopPreviewResponse run = new CodeAgentLoopPreviewResponse(
                UUID.randomUUID(),
                repositoryId,
                spaceId,
                "RUNNING",
                maxSteps,
                TIMEOUT_SECONDS,
                false,
                true,
                false,
                steps(),
                stopConditions(),
                warnings(instruction)
        );
        timelineRepository.createPreview(userId, instruction, run);
        timelineRepository.appendRunStarted(userId, repositoryId, run.loopId(), agentId, workspaceId, instruction);
        return run;
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

    public CodeAgentLoopSubmissionPlanResponse submissionPlan(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            String instruction,
            Integer requestedMaxSteps,
            Map<String, Object> patchDryRunApprovalHandoffPreview
    ) {
        int maxSteps = boundedMaxSteps(requestedMaxSteps);
        Map<String, Object> bodyPreview = new LinkedHashMap<>();
        bodyPreview.put("repositoryId", repositoryId);
        bodyPreview.put("spaceId", spaceId);
        bodyPreview.put("instruction", instruction);
        bodyPreview.put("maxSteps", maxSteps);
        bodyPreview.put("agentId", agentId);
        bodyPreview.put("workspaceId", workspaceId);
        if (patchDryRunApprovalHandoffPreview != null && !patchDryRunApprovalHandoffPreview.isEmpty()) {
            bodyPreview.put("patchDryRunApprovalHandoffPreview", patchDryRunApprovalHandoffPreview);
        }
        Map<String, Object> approvalHandoffPlan = patchDryRunApprovalHandoffPlan(
                repositoryId,
                spaceId,
                agentId,
                workspaceId,
                patchDryRunApprovalHandoffPreview
        );
        Map<String, Object> approvalReviewPreview = patchDryRunApprovalReviewPreview(approvalHandoffPlan);
        return new CodeAgentLoopSubmissionPlanResponse(
                "learnbot.server.code-agent.loop-submission-plan.v1",
                repositoryId,
                spaceId,
                agentId,
                workspaceId,
                instruction,
                maxSteps,
                "POST",
                "/api/code-agent/loop/preview",
                bodyPreview,
                approvalHandoffPlan,
                approvalReviewPreview,
                List.of(
                        "POST /api/code-agent/loop/runner/preview",
                        "POST /api/code-agent/loop/runner/select-tool-preview",
                        "POST /api/code-agent/loop/runner/enqueue-selected-read-only",
                        "POST /api/code-agent/loop/runner/validated-patch-approval-request"
                ),
                true,
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
                true,
                true,
                "Authoritative server-side handoff plan only; loop preview execution and Local Agent work creation remain disabled."
        );
    }

    private Map<String, Object> patchDryRunApprovalHandoffPlan(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> handoff
    ) {
        Map<String, Object> plan = new LinkedHashMap<>();
        boolean provided = handoff != null && !handoff.isEmpty();
        boolean handoffPrepared = provided
                && Boolean.TRUE.equals(handoff.get("approvalHandoffPrepared"))
                && Boolean.TRUE.equals(handoff.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(handoff.get("nonWritingPreflightPassed"))
                && "APPROVAL_HANDOFF_PREPARED".equals(String.valueOf(handoff.get("status")));
        plan.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1");
        plan.put("status", !provided
                ? "HANDOFF_NOT_PROVIDED"
                : handoffPrepared ? "READY_APPROVAL_REQUEST_PREVIEW_DISABLED" : "HANDOFF_NOT_READY");
        plan.put("repositoryId", repositoryId);
        plan.put("spaceId", spaceId);
        plan.put("agentId", agentId);
        plan.put("workspaceId", workspaceId);
        plan.put("handoffProvided", provided);
        plan.put("handoffPrepared", handoffPrepared);
        plan.put("sourceSchema", provided ? handoff.get("schema") : null);
        plan.put("sourceStatus", provided ? handoff.get("status") : null);
        plan.put("toolName", provided ? handoff.get("toolName") : "patch.apply");
        plan.put("executionTarget", provided ? handoff.get("executionTarget") : "USER_LOCAL_AGENT");
        plan.put("approvalKind", provided ? handoff.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        plan.put("approvalState", handoffPrepared ? "REQUIRED_BEFORE_SNAPSHOT_DRY_RUN" : "NOT_PREPARED");
        plan.put("targetFiles", provided ? handoff.get("targetFiles") : List.of());
        plan.put("diffValidationPassed", provided && Boolean.TRUE.equals(handoff.get("diffValidationPassed")));
        plan.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(handoff.get("requestEnvelopePrepared")));
        plan.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(handoff.get("nonWritingPreflightPassed")));
        plan.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        plan.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        plan.put("requestCreationEnabled", false);
        plan.put("approvalRequestCreationEnabled", false);
        plan.put("enqueueEnabled", false);
        plan.put("claimEnabled", false);
        plan.put("snapshotCreationEnabled", false);
        plan.put("patchDryRunExecutionEnabled", false);
        plan.put("mutationEnabled", false);
        plan.put("testExecutionEnabled", false);
        plan.put("finalPublicationEnabled", false);
        plan.put("partialReindexEnabled", false);
        plan.put("reason", !provided
                ? "No CLI patch dry-run approval handoff preview was supplied with the submission plan request."
                : handoffPrepared
                ? "Validated CLI dry-run approval handoff can be reviewed by the server, but approval request creation, release, queueing, claim, snapshot-writing dry-run, mutation, tests, final publication, and partial reindex remain disabled in this plan."
                : "CLI dry-run approval handoff is present but not ready; the server will not create approval or release work from it.");
        return plan;
    }

    private Map<String, Object> patchDryRunApprovalReviewPreview(Map<String, Object> handoffPlan) {
        boolean handoffProvided = Boolean.TRUE.equals(handoffPlan.get("handoffProvided"));
        boolean handoffPrepared = Boolean.TRUE.equals(handoffPlan.get("handoffPrepared"));
        String status = !handoffProvided
                ? "HANDOFF_NOT_PROVIDED"
                : handoffPrepared ? "READY_BROWSER_REVIEW_DISABLED" : "HANDOFF_NOT_READY";
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1");
        preview.put("status", status);
        preview.put("reviewSurface", "CODE_WORKSPACE_LOOP_REVIEW");
        preview.put("sourcePlanSchema", handoffPlan.get("schema"));
        preview.put("sourcePlanStatus", handoffPlan.get("status"));
        preview.put("repositoryId", handoffPlan.get("repositoryId"));
        preview.put("spaceId", handoffPlan.get("spaceId"));
        preview.put("agentId", handoffPlan.get("agentId"));
        preview.put("workspaceId", handoffPlan.get("workspaceId"));
        preview.put("toolName", handoffPlan.get("toolName"));
        preview.put("executionTarget", handoffPlan.get("executionTarget"));
        preview.put("approvalKind", handoffPlan.get("approvalKind"));
        preview.put("approvalState", handoffPrepared ? "AWAITING_USER_REVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", handoffPlan.get("targetFiles"));
        preview.put("diffValidationPassed", handoffPlan.get("diffValidationPassed"));
        preview.put("requestEnvelopePrepared", handoffPlan.get("requestEnvelopePrepared"));
        preview.put("nonWritingPreflightPassed", handoffPlan.get("nonWritingPreflightPassed"));
        preview.put("approvalReviewPrepared", handoffPrepared);
        preview.put("browserReviewReady", handoffPrepared);
        preview.put("userApprovalRequired", handoffPrepared);
        preview.put("approvalRequestEndpoint", handoffPlan.get("approvalRequestEndpoint"));
        preview.put("releaseReviewEndpoint", handoffPlan.get("releaseReviewEndpoint"));
        preview.put("requestCreationEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("enqueueEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("reason", !handoffProvided
                ? "No CLI dry-run approval handoff evidence is available to review."
                : handoffPrepared
                ? "CLI dry-run approval evidence is ready for browser review, but approval creation, persistence, release, queueing, claim, snapshot-writing dry-run, mutation, tests, final publication, and partial reindex remain disabled."
                : "CLI dry-run approval evidence is present but incomplete, so no browser approval review can be prepared.");
        return preview;
    }

    public Map<String, Object> approvalIntentPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> review
    ) {
        boolean provided = review != null && !review.isEmpty();
        boolean ready = provided
                && "READY_BROWSER_REVIEW_DISABLED".equals(String.valueOf(review.get("status")))
                && Boolean.TRUE.equals(review.get("approvalReviewPrepared"))
                && Boolean.TRUE.equals(review.get("browserReviewReady"))
                && Boolean.TRUE.equals(review.get("userApprovalRequired"))
                && Boolean.TRUE.equals(review.get("diffValidationPassed"))
                && Boolean.TRUE.equals(review.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(review.get("nonWritingPreflightPassed"));
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1");
        intent.put("status", !provided
                ? "REVIEW_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_INTENT_DISABLED" : "REVIEW_NOT_READY");
        intent.put("repositoryId", repositoryId);
        intent.put("spaceId", spaceId);
        intent.put("agentId", agentId);
        intent.put("workspaceId", workspaceId);
        intent.put("reviewProvided", provided);
        intent.put("approvalIntentPrepared", ready);
        intent.put("sourceReviewSchema", provided ? review.get("schema") : null);
        intent.put("sourceReviewStatus", provided ? review.get("status") : null);
        intent.put("sourceReviewSurface", provided ? review.get("reviewSurface") : null);
        intent.put("toolName", provided ? review.get("toolName") : "patch.apply");
        intent.put("executionTarget", provided ? review.get("executionTarget") : "USER_LOCAL_AGENT");
        intent.put("approvalKind", provided ? review.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        intent.put("approvalState", ready ? "USER_REVIEW_REQUIRED" : "NOT_PREPARED");
        intent.put("targetFiles", provided ? review.get("targetFiles") : List.of());
        intent.put("diffValidationPassed", provided && Boolean.TRUE.equals(review.get("diffValidationPassed")));
        intent.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(review.get("requestEnvelopePrepared")));
        intent.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(review.get("nonWritingPreflightPassed")));
        intent.put("browserReviewReady", provided && Boolean.TRUE.equals(review.get("browserReviewReady")));
        intent.put("userApprovalRequired", provided && Boolean.TRUE.equals(review.get("userApprovalRequired")));
        intent.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        intent.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        intent.put("approvalIntent", approvalIntentEnvelope(ready, review));
        intent.put("approvalIntentCreationEnabled", false);
        intent.put("approvalPersistenceEnabled", false);
        intent.put("requestCreationEnabled", false);
        intent.put("approvalRequestCreationEnabled", false);
        intent.put("enqueueEnabled", false);
        intent.put("claimEnabled", false);
        intent.put("snapshotCreationEnabled", false);
        intent.put("patchDryRunExecutionEnabled", false);
        intent.put("mutationEnabled", false);
        intent.put("testExecutionEnabled", false);
        intent.put("finalPublicationEnabled", false);
        intent.put("partialReindexEnabled", false);
        intent.put("approvalBypassAllowed", false);
        intent.put("reason", !provided
                ? "No browser approval review preview was supplied; the server cannot shape an approval intent."
                : ready
                ? "Browser review evidence is ready to shape an approval intent, but intent creation, approval persistence, request creation, queueing, claim, snapshot-writing dry-run, mutation, tests, final publication, and partial reindex remain disabled."
                : "Browser approval review evidence is present but incomplete, so no approval intent can be prepared.");
        return intent;
    }

    private Map<String, Object> approvalIntentEnvelope(boolean ready, Map<String, Object> review) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-intent.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("approvalAction", "APPROVE_SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("approvalIntentPrepared", ready);
        envelope.put("dryRunOnly", true);
        envelope.put("mutationAllowed", false);
        envelope.put("targetFiles", ready ? review.get("targetFiles") : List.of());
        envelope.put("approvalPersistenceEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("approvalRequestCreationEnabled", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    public Map<String, Object> approvalRequestCreationPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> intent
    ) {
        boolean provided = intent != null && !intent.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_INTENT_DISABLED".equals(String.valueOf(intent.get("status")))
                && Boolean.TRUE.equals(intent.get("approvalIntentPrepared"))
                && Boolean.TRUE.equals(intent.get("reviewProvided"))
                && Boolean.TRUE.equals(intent.get("diffValidationPassed"))
                && Boolean.TRUE.equals(intent.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(intent.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(intent.get("browserReviewReady"))
                && Boolean.TRUE.equals(intent.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1");
        preview.put("status", !provided
                ? "APPROVAL_INTENT_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_REQUEST_CREATION_DISABLED" : "APPROVAL_INTENT_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalIntentProvided", provided);
        preview.put("approvalRequestCreationPrepared", ready);
        preview.put("approvalPersistencePrepared", ready);
        preview.put("sourceIntentSchema", provided ? intent.get("schema") : null);
        preview.put("sourceIntentStatus", provided ? intent.get("status") : null);
        preview.put("toolName", provided ? intent.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? intent.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? intent.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVAL_REQUIRED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? intent.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(intent.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(intent.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(intent.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(intent.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(intent.get("userApprovalRequired")));
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalPersistencePreview", approvalPersistencePreviewEnvelope(ready, intent));
        preview.put("approvalRequestPreview", approvalRequestPreviewEnvelope(ready, intent));
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval intent preview was supplied; approval persistence and request creation cannot be modeled."
                : ready
                ? "Approval intent is ready to model browser approval persistence and approval-request creation, but persistence, request creation, queueing, claim, snapshot-writing dry-run, mutation, tests, rollback, final publication, and partial reindex remain disabled."
                : "Approval intent preview is present but incomplete; no approval persistence or request creation preview can be prepared.");
        return preview;
    }

    private Map<String, Object> approvalPersistencePreviewEnvelope(boolean ready, Map<String, Object> intent) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-persistence-preview.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("approvalPersistencePrepared", ready);
        envelope.put("approvalAction", "APPROVE_SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("sourceIntentStatus", ready ? intent.get("status") : null);
        envelope.put("approvalPersistenceEnabled", false);
        envelope.put("serverApprovalRecordCreated", false);
        envelope.put("approvalBypassAllowed", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    private Map<String, Object> approvalRequestPreviewEnvelope(boolean ready, Map<String, Object> intent) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-request-preview.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("approvalRequestCreationPrepared", ready);
        envelope.put("toolName", ready ? intent.get("toolName") : "patch.apply");
        envelope.put("executionTarget", ready ? intent.get("executionTarget") : "USER_LOCAL_AGENT");
        envelope.put("approvalKind", ready ? intent.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("targetFiles", ready ? intent.get("targetFiles") : List.of());
        envelope.put("dryRunOnly", true);
        envelope.put("mutationAllowed", false);
        envelope.put("approvalRequestCreationEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    public Map<String, Object> approvalDecisionPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> requestCreation
    ) {
        boolean provided = requestCreation != null && !requestCreation.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_REQUEST_CREATION_DISABLED".equals(String.valueOf(requestCreation.get("status")))
                && Boolean.TRUE.equals(requestCreation.get("approvalRequestCreationPrepared"))
                && Boolean.TRUE.equals(requestCreation.get("approvalPersistencePrepared"))
                && Boolean.TRUE.equals(requestCreation.get("approvalIntentProvided"))
                && Boolean.TRUE.equals(requestCreation.get("diffValidationPassed"))
                && Boolean.TRUE.equals(requestCreation.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(requestCreation.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(requestCreation.get("browserReviewReady"))
                && Boolean.TRUE.equals(requestCreation.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1");
        preview.put("status", !provided
                ? "APPROVAL_REQUEST_CREATION_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_DECISION_DISABLED" : "APPROVAL_REQUEST_CREATION_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalRequestCreationProvided", provided);
        preview.put("approvalDecisionPrepared", ready);
        preview.put("sourceRequestCreationSchema", provided ? requestCreation.get("schema") : null);
        preview.put("sourceRequestCreationStatus", provided ? requestCreation.get("status") : null);
        preview.put("toolName", provided ? requestCreation.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? requestCreation.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? requestCreation.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "AWAITING_BROWSER_DECISION_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? requestCreation.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(requestCreation.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(requestCreation.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(requestCreation.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(requestCreation.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(requestCreation.get("userApprovalRequired")));
        preview.put("decisionOptions", decisionOptions(ready));
        preview.put("heldRequestReview", heldRequestReviewPreview(ready, requestCreation));
        preview.put("approvalDecisionEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("heldRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval request-creation preview was supplied; browser approval decision cannot be modeled."
                : ready
                ? "Browser approval decision can be modeled for approve or deny, but decision persistence, approval persistence, request creation, queueing, claim, snapshot-writing dry-run, mutation, tests, rollback, final publication, and partial reindex remain disabled."
                : "Approval request-creation preview is present but incomplete; no browser approval decision can be prepared.");
        return preview;
    }

    private List<Map<String, Object>> decisionOptions(boolean ready) {
        return List.of(
                decisionOption("APPROVE_SNAPSHOT_WRITING_DRY_RUN", ready),
                decisionOption("DENY_SNAPSHOT_WRITING_DRY_RUN", ready)
        );
    }

    private Map<String, Object> decisionOption(String action, boolean ready) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("action", action);
        option.put("prepared", ready);
        option.put("enabled", false);
        option.put("approvalDecisionPersistenceEnabled", false);
        option.put("requestCreationEnabled", false);
        option.put("mutationEnabled", false);
        return option;
    }

    private Map<String, Object> heldRequestReviewPreview(boolean ready, Map<String, Object> requestCreation) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1");
        review.put("status", ready ? "READY_HELD_REQUEST_REVIEW_DISABLED" : "NOT_READY");
        review.put("heldRequestReviewPrepared", ready);
        review.put("sourceRequestCreationStatus", ready ? requestCreation.get("status") : null);
        review.put("targetFiles", ready ? requestCreation.get("targetFiles") : List.of());
        review.put("approvalState", ready ? "APPROVAL_REQUIRED_HELD_PREVIEW" : "NOT_PREPARED");
        review.put("heldRequestCreated", false);
        review.put("approvalDecisionRecorded", false);
        review.put("approvalPersistenceEnabled", false);
        review.put("requestCreationEnabled", false);
        review.put("enqueueEnabled", false);
        review.put("claimEnabled", false);
        review.put("claimable", false);
        review.put("snapshotCreationEnabled", false);
        review.put("patchDryRunExecutionEnabled", false);
        review.put("mutationEnabled", false);
        return review;
    }

    public Map<String, Object> approvalDecisionPersistencePreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> decision
    ) {
        boolean provided = decision != null && !decision.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_DECISION_DISABLED".equals(String.valueOf(decision.get("status")))
                && Boolean.TRUE.equals(decision.get("approvalDecisionPrepared"))
                && Boolean.TRUE.equals(decision.get("approvalRequestCreationProvided"))
                && Boolean.TRUE.equals(decision.get("diffValidationPassed"))
                && Boolean.TRUE.equals(decision.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(decision.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(decision.get("browserReviewReady"))
                && Boolean.TRUE.equals(decision.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1");
        preview.put("status", !provided
                ? "APPROVAL_DECISION_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED" : "APPROVAL_DECISION_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalDecisionProvided", provided);
        preview.put("approvalDecisionPersistencePrepared", ready);
        preview.put("heldRequestReviewPrepared", ready);
        preview.put("sourceDecisionSchema", provided ? decision.get("schema") : null);
        preview.put("sourceDecisionStatus", provided ? decision.get("status") : null);
        preview.put("toolName", provided ? decision.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? decision.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? decision.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVAL_DECISION_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? decision.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(decision.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(decision.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(decision.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(decision.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(decision.get("userApprovalRequired")));
        preview.put("decisionPersistencePreview", decisionPersistenceEnvelope(ready, decision));
        preview.put("heldRequestReview", ready ? decision.get("heldRequestReview") : heldRequestReviewPreview(false, Map.of()));
        preview.put("approvalDecisionEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview");
        preview.put("approvalDecisionPersistenceEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("heldRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval decision preview was supplied; decision persistence cannot be modeled."
                : ready
                ? "Approval decision persistence and held-request review can be modeled, but no decision, approval, request, queue, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Approval decision preview is present but incomplete; no decision persistence preview can be prepared.");
        return preview;
    }

    private Map<String, Object> decisionPersistenceEnvelope(boolean ready, Map<String, Object> decision) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("approvalDecisionPersistencePrepared", ready);
        envelope.put("sourceDecisionStatus", ready ? decision.get("status") : null);
        envelope.put("decisionOptions", ready ? decision.get("decisionOptions") : List.of());
        envelope.put("approvalDecisionPersistenceEnabled", false);
        envelope.put("approvalDecisionRecorded", false);
        envelope.put("approvalDecisionPersisted", false);
        envelope.put("approvalPersistenceEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("heldRequestCreated", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    public Map<String, Object> heldRequestReviewActionPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> decisionPersistence
    ) {
        boolean provided = decisionPersistence != null && !decisionPersistence.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED".equals(String.valueOf(decisionPersistence.get("status")))
                && Boolean.TRUE.equals(decisionPersistence.get("approvalDecisionPersistencePrepared"))
                && Boolean.TRUE.equals(decisionPersistence.get("heldRequestReviewPrepared"))
                && Boolean.TRUE.equals(decisionPersistence.get("diffValidationPassed"))
                && Boolean.TRUE.equals(decisionPersistence.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(decisionPersistence.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(decisionPersistence.get("browserReviewReady"))
                && Boolean.TRUE.equals(decisionPersistence.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1");
        preview.put("status", !provided
                ? "DECISION_PERSISTENCE_NOT_PROVIDED"
                : ready ? "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED" : "DECISION_PERSISTENCE_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalDecisionPersistenceProvided", provided);
        preview.put("heldRequestReviewActionPrepared", ready);
        preview.put("heldRequestReviewPrepared", provided && Boolean.TRUE.equals(decisionPersistence.get("heldRequestReviewPrepared")));
        preview.put("sourceDecisionPersistenceSchema", provided ? decisionPersistence.get("schema") : null);
        preview.put("sourceDecisionPersistenceStatus", provided ? decisionPersistence.get("status") : null);
        preview.put("toolName", provided ? decisionPersistence.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? decisionPersistence.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? decisionPersistence.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "HELD_REQUEST_BROWSER_REVIEW_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? decisionPersistence.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(decisionPersistence.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(decisionPersistence.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(decisionPersistence.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(decisionPersistence.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(decisionPersistence.get("userApprovalRequired")));
        preview.put("heldRequestReview", ready ? decisionPersistence.get("heldRequestReview") : heldRequestReviewPreview(false, Map.of()));
        preview.put("reviewActions", heldRequestReviewActions(ready));
        preview.put("approvalDecisionPersistenceEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview");
        preview.put("heldRequestReviewEndpoint", "/api/code-agent/loop/runner/patch-dry-run-held-request-review-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval decision-persistence preview was supplied; held request review actions cannot be modeled."
                : ready
                ? "Held request review actions can be displayed for browser review, but all approval persistence, request creation, queueing, claiming, snapshot dry-run, mutation, tests, rollback, final publication, and partial reindex work remain disabled."
                : "Approval decision-persistence preview is present but incomplete; no held request review action preview can be prepared.");
        return preview;
    }

    private List<Map<String, Object>> heldRequestReviewActions(boolean ready) {
        return List.of(
                heldRequestReviewAction("REVIEW_HELD_APPROVAL", ready),
                heldRequestReviewAction("APPROVE_HELD_APPROVAL", ready),
                heldRequestReviewAction("DENY_HELD_APPROVAL", ready)
        );
    }

    private Map<String, Object> heldRequestReviewAction(String action, boolean ready) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("action", action);
        option.put("prepared", ready);
        option.put("enabled", false);
        option.put("heldRequestReviewEnabled", false);
        option.put("approvalDecisionPersistenceEnabled", false);
        option.put("requestCreationEnabled", false);
        option.put("mutationEnabled", false);
        return option;
    }

    public Map<String, Object> approvalActionPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> heldRequestReview
    ) {
        boolean provided = heldRequestReview != null && !heldRequestReview.isEmpty();
        boolean ready = provided
                && "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED".equals(String.valueOf(heldRequestReview.get("status")))
                && Boolean.TRUE.equals(heldRequestReview.get("heldRequestReviewActionPrepared"))
                && Boolean.TRUE.equals(heldRequestReview.get("heldRequestReviewPrepared"))
                && Boolean.TRUE.equals(heldRequestReview.get("diffValidationPassed"))
                && Boolean.TRUE.equals(heldRequestReview.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(heldRequestReview.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(heldRequestReview.get("browserReviewReady"))
                && Boolean.TRUE.equals(heldRequestReview.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1");
        preview.put("status", !provided
                ? "HELD_REQUEST_REVIEW_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_ACTION_DISABLED" : "HELD_REQUEST_REVIEW_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("heldRequestReviewProvided", provided);
        preview.put("approvalActionPrepared", ready);
        preview.put("heldRequestReviewPrepared", provided && Boolean.TRUE.equals(heldRequestReview.get("heldRequestReviewPrepared")));
        preview.put("sourceHeldRequestReviewSchema", provided ? heldRequestReview.get("schema") : null);
        preview.put("sourceHeldRequestReviewStatus", provided ? heldRequestReview.get("status") : null);
        preview.put("toolName", provided ? heldRequestReview.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? heldRequestReview.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? heldRequestReview.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "AWAITING_APPROVE_OR_DENY_ACTION_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? heldRequestReview.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(heldRequestReview.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(heldRequestReview.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(heldRequestReview.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(heldRequestReview.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(heldRequestReview.get("userApprovalRequired")));
        preview.put("sourceReviewActions", provided ? heldRequestReview.getOrDefault("reviewActions", List.of()) : List.of());
        preview.put("approvalActions", approvalActions(ready));
        preview.put("heldRequestReviewEndpoint", "/api/code-agent/loop/runner/patch-dry-run-held-request-review-preview");
        preview.put("approvalActionEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-action-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No held-request review preview was supplied; approval actions cannot be modeled."
                : ready
                ? "Approve and deny actions can be displayed for browser review, but no action, approval, request, queue, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Held-request review preview is present but incomplete; no approval action preview can be prepared.");
        return preview;
    }

    private List<Map<String, Object>> approvalActions(boolean ready) {
        return List.of(
                approvalAction("APPROVE_SNAPSHOT_WRITING_DRY_RUN", ready),
                approvalAction("DENY_SNAPSHOT_WRITING_DRY_RUN", ready)
        );
    }

    private Map<String, Object> approvalAction(String action, boolean ready) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("action", action);
        option.put("prepared", ready);
        option.put("enabled", false);
        option.put("approvalActionEnabled", false);
        option.put("approvalPersistenceEnabled", false);
        option.put("requestCreationEnabled", false);
        option.put("mutationEnabled", false);
        return option;
    }

    public Map<String, Object> approvalActionPersistencePreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> approvalAction
    ) {
        boolean provided = approvalAction != null && !approvalAction.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_ACTION_DISABLED".equals(String.valueOf(approvalAction.get("status")))
                && Boolean.TRUE.equals(approvalAction.get("approvalActionPrepared"))
                && Boolean.TRUE.equals(approvalAction.get("heldRequestReviewProvided"))
                && Boolean.TRUE.equals(approvalAction.get("heldRequestReviewPrepared"))
                && Boolean.TRUE.equals(approvalAction.get("diffValidationPassed"))
                && Boolean.TRUE.equals(approvalAction.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(approvalAction.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(approvalAction.get("browserReviewReady"))
                && Boolean.TRUE.equals(approvalAction.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1");
        preview.put("status", !provided
                ? "APPROVAL_ACTION_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED" : "APPROVAL_ACTION_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalActionProvided", provided);
        preview.put("approvalActionPersistencePrepared", ready);
        preview.put("heldRequestReviewPrepared", provided && Boolean.TRUE.equals(approvalAction.get("heldRequestReviewPrepared")));
        preview.put("sourceApprovalActionSchema", provided ? approvalAction.get("schema") : null);
        preview.put("sourceApprovalActionStatus", provided ? approvalAction.get("status") : null);
        preview.put("toolName", provided ? approvalAction.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? approvalAction.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? approvalAction.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVAL_ACTION_PERSISTENCE_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? approvalAction.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(approvalAction.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(approvalAction.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(approvalAction.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(approvalAction.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(approvalAction.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? approvalAction.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("approvalActionPersistence", approvalActionPersistenceEnvelope(ready, approvalAction));
        preview.put("approvalActionEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-action-preview");
        preview.put("approvalActionPersistenceEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval action preview was supplied; approval action persistence cannot be modeled."
                : ready
                ? "Approval action persistence and the future approval-record boundary can be modeled, but no action, approval, request, queue, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Approval action preview is present but incomplete; no approval action persistence preview can be prepared.");
        return preview;
    }

    private Map<String, Object> approvalActionPersistenceEnvelope(boolean ready, Map<String, Object> approvalAction) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("approvalActionPersistencePrepared", ready);
        envelope.put("sourceApprovalActionStatus", ready ? approvalAction.get("status") : null);
        envelope.put("approvalActions", ready ? approvalAction.getOrDefault("approvalActions", List.of()) : List.of());
        envelope.put("approvalActionPersistenceEnabled", false);
        envelope.put("approvalActionRecorded", false);
        envelope.put("approvalActionPersisted", false);
        envelope.put("serverApprovalRecordCreated", false);
        envelope.put("approvalPersistenceEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    public Map<String, Object> approvalRecordPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> actionPersistence
    ) {
        boolean provided = actionPersistence != null && !actionPersistence.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED".equals(String.valueOf(actionPersistence.get("status")))
                && Boolean.TRUE.equals(actionPersistence.get("approvalActionPersistencePrepared"))
                && Boolean.TRUE.equals(actionPersistence.get("approvalActionProvided"))
                && Boolean.TRUE.equals(actionPersistence.get("heldRequestReviewPrepared"))
                && Boolean.TRUE.equals(actionPersistence.get("diffValidationPassed"))
                && Boolean.TRUE.equals(actionPersistence.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(actionPersistence.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(actionPersistence.get("browserReviewReady"))
                && Boolean.TRUE.equals(actionPersistence.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1");
        preview.put("status", !provided
                ? "APPROVAL_ACTION_PERSISTENCE_NOT_PROVIDED"
                : ready ? "READY_APPROVAL_RECORD_DISABLED" : "APPROVAL_ACTION_PERSISTENCE_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalActionPersistenceProvided", provided);
        preview.put("approvalRecordPrepared", ready);
        preview.put("localAgentRequestCreationPrepared", ready);
        preview.put("sourceApprovalActionPersistenceSchema", provided ? actionPersistence.get("schema") : null);
        preview.put("sourceApprovalActionPersistenceStatus", provided ? actionPersistence.get("status") : null);
        preview.put("toolName", provided ? actionPersistence.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? actionPersistence.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? actionPersistence.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVAL_RECORD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? actionPersistence.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(actionPersistence.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(actionPersistence.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(actionPersistence.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(actionPersistence.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(actionPersistence.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? actionPersistence.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("approvalRecord", approvalRecordEnvelope(ready, actionPersistence));
        preview.put("approvalActionPersistenceEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview");
        preview.put("approvalRecordEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-record-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval action-persistence preview was supplied; approval record and request creation cannot be modeled."
                : ready
                ? "Approval record and Local Agent request creation can be modeled, but no approval record, request, queue, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Approval action-persistence preview is present but incomplete; no approval record preview can be prepared.");
        return preview;
    }

    private Map<String, Object> approvalRecordEnvelope(boolean ready, Map<String, Object> actionPersistence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-approval-record.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("approvalRecordPrepared", ready);
        envelope.put("sourceApprovalActionPersistenceStatus", ready ? actionPersistence.get("status") : null);
        envelope.put("approvalActions", ready ? actionPersistence.getOrDefault("approvalActions", List.of()) : List.of());
        envelope.put("approvalRecordCreationEnabled", false);
        envelope.put("serverApprovalRecordCreated", false);
        envelope.put("approvalRequestCreationEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentRequestEnvelopePreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> approvalRecord
    ) {
        boolean provided = approvalRecord != null && !approvalRecord.isEmpty();
        boolean ready = provided
                && "READY_APPROVAL_RECORD_DISABLED".equals(String.valueOf(approvalRecord.get("status")))
                && Boolean.TRUE.equals(approvalRecord.get("approvalRecordPrepared"))
                && Boolean.TRUE.equals(approvalRecord.get("localAgentRequestCreationPrepared"))
                && Boolean.TRUE.equals(approvalRecord.get("diffValidationPassed"))
                && Boolean.TRUE.equals(approvalRecord.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(approvalRecord.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(approvalRecord.get("browserReviewReady"))
                && Boolean.TRUE.equals(approvalRecord.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1");
        preview.put("status", !provided
                ? "APPROVAL_RECORD_NOT_PROVIDED"
                : ready ? "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED" : "APPROVAL_RECORD_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("approvalRecordProvided", provided);
        preview.put("localAgentRequestEnvelopePrepared", ready);
        preview.put("localAgentRequestCreationPrepared", ready);
        preview.put("sourceApprovalRecordSchema", provided ? approvalRecord.get("schema") : null);
        preview.put("sourceApprovalRecordStatus", provided ? approvalRecord.get("status") : null);
        preview.put("toolName", provided ? approvalRecord.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? approvalRecord.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? approvalRecord.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? approvalRecord.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(approvalRecord.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(approvalRecord.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(approvalRecord.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(approvalRecord.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(approvalRecord.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? approvalRecord.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("localAgentRequestEnvelope", localAgentRequestEnvelope(ready, approvalRecord));
        preview.put("approvalRecordEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-record-preview");
        preview.put("localAgentRequestEnvelopeEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No approval-record preview was supplied; Local Agent request envelope creation cannot be modeled."
                : ready
                ? "The future Local Agent patch.apply dry-run request envelope can be modeled, but no request, queue, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Approval-record preview is present but incomplete; no Local Agent request envelope preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentRequestEnvelope(boolean ready, Map<String, Object> approvalRecord) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("toolName", "patch.apply");
        envelope.put("executionTarget", "USER_LOCAL_AGENT");
        envelope.put("approvalKind", "SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "REQUIRES_APPROVAL_RECORD");
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("targetFiles", ready ? approvalRecord.get("targetFiles") : List.of());
        envelope.put("approvalActions", ready ? approvalRecord.getOrDefault("approvalActions", List.of()) : List.of());
        envelope.put("localAgentRequestEnvelopePrepared", ready);
        envelope.put("approvalRecordCreationEnabled", false);
        envelope.put("approvalPersistenceEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("pushEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        envelope.put("testExecutionEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentRequestCreationPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> requestEnvelope
    ) {
        boolean provided = requestEnvelope != null && !requestEnvelope.isEmpty();
        boolean ready = provided
                && "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED".equals(String.valueOf(requestEnvelope.get("status")))
                && Boolean.TRUE.equals(requestEnvelope.get("localAgentRequestEnvelopePrepared"))
                && Boolean.TRUE.equals(requestEnvelope.get("localAgentRequestCreationPrepared"))
                && Boolean.TRUE.equals(requestEnvelope.get("diffValidationPassed"))
                && Boolean.TRUE.equals(requestEnvelope.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(requestEnvelope.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(requestEnvelope.get("browserReviewReady"))
                && Boolean.TRUE.equals(requestEnvelope.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_REQUEST_ENVELOPE_NOT_PROVIDED"
                : ready ? "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED" : "LOCAL_AGENT_REQUEST_ENVELOPE_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentRequestEnvelopeProvided", provided);
        preview.put("localAgentRequestEnvelopePrepared", ready);
        preview.put("localAgentRequestCreationPrepared", ready);
        preview.put("queueHandoffPrepared", ready);
        preview.put("sourceLocalAgentRequestEnvelopeSchema", provided ? requestEnvelope.get("schema") : null);
        preview.put("sourceLocalAgentRequestEnvelopeStatus", provided ? requestEnvelope.get("status") : null);
        preview.put("toolName", provided ? requestEnvelope.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? requestEnvelope.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? requestEnvelope.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? requestEnvelope.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(requestEnvelope.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(requestEnvelope.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(requestEnvelope.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(requestEnvelope.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(requestEnvelope.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? requestEnvelope.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("localAgentRequestCreation", localAgentRequestCreationEnvelope(ready, requestEnvelope));
        preview.put("localAgentRequestEnvelopeEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview");
        preview.put("localAgentRequestCreationEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent request-envelope preview was supplied; durable request creation cannot be modeled."
                : ready
                ? "Durable Local Agent patch.apply dry-run request creation can be modeled, but no request row, queue, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Local Agent request-envelope preview is present but incomplete; no durable request-creation preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentRequestCreationEnvelope(boolean ready, Map<String, Object> requestEnvelope) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("toolName", "patch.apply");
        envelope.put("executionTarget", "USER_LOCAL_AGENT");
        envelope.put("approvalKind", "SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "REQUIRES_REQUEST_ENVELOPE");
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("targetFiles", ready ? requestEnvelope.get("targetFiles") : List.of());
        envelope.put("approvalActions", ready ? requestEnvelope.getOrDefault("approvalActions", List.of()) : List.of());
        envelope.put("localAgentRequestCreationPrepared", ready);
        envelope.put("queueHandoffPrepared", ready);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("durableLocalAgentRequestCreated", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("pushEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("claimable", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        envelope.put("testExecutionEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentQueuePreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> requestCreation
    ) {
        boolean provided = requestCreation != null && !requestCreation.isEmpty();
        boolean ready = provided
                && "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED".equals(String.valueOf(requestCreation.get("status")))
                && Boolean.TRUE.equals(requestCreation.get("localAgentRequestCreationPrepared"))
                && Boolean.TRUE.equals(requestCreation.get("queueHandoffPrepared"))
                && Boolean.TRUE.equals(requestCreation.get("diffValidationPassed"))
                && Boolean.TRUE.equals(requestCreation.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(requestCreation.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(requestCreation.get("browserReviewReady"))
                && Boolean.TRUE.equals(requestCreation.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_REQUEST_CREATION_NOT_PROVIDED"
                : ready ? "READY_LOCAL_AGENT_QUEUE_DISABLED" : "LOCAL_AGENT_REQUEST_CREATION_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentRequestCreationProvided", provided);
        preview.put("localAgentRequestCreationPrepared", ready);
        preview.put("queueHandoffPrepared", ready);
        preview.put("pushHandoffPrepared", ready);
        preview.put("claimHandoffPrepared", ready);
        preview.put("sourceLocalAgentRequestCreationSchema", provided ? requestCreation.get("schema") : null);
        preview.put("sourceLocalAgentRequestCreationStatus", provided ? requestCreation.get("status") : null);
        preview.put("toolName", provided ? requestCreation.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? requestCreation.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? requestCreation.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? requestCreation.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(requestCreation.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(requestCreation.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(requestCreation.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(requestCreation.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(requestCreation.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? requestCreation.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("localAgentQueue", localAgentQueueEnvelope(ready, requestCreation));
        preview.put("localAgentRequestCreationEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview");
        preview.put("localAgentQueueEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent request-creation preview was supplied; queue, push, and claim handoff cannot be modeled."
                : ready
                ? "Local Agent queue, push, and claim handoff can be modeled, but no request row, enqueue, push, claim, snapshot dry-run, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Local Agent request-creation preview is present but incomplete; no queue handoff preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentQueueEnvelope(boolean ready, Map<String, Object> requestCreation) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("toolName", "patch.apply");
        envelope.put("executionTarget", "USER_LOCAL_AGENT");
        envelope.put("approvalKind", "SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "REQUIRES_REQUEST_CREATION");
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("targetFiles", ready ? requestCreation.get("targetFiles") : List.of());
        envelope.put("approvalActions", ready ? requestCreation.getOrDefault("approvalActions", List.of()) : List.of());
        envelope.put("queueHandoffPrepared", ready);
        envelope.put("pushHandoffPrepared", ready);
        envelope.put("claimHandoffPrepared", ready);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("durableLocalAgentRequestCreated", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("pushEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("claimable", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        envelope.put("testExecutionEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentClaimReadinessPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> queue
    ) {
        boolean provided = queue != null && !queue.isEmpty();
        boolean ready = provided
                && "READY_LOCAL_AGENT_QUEUE_DISABLED".equals(String.valueOf(queue.get("status")))
                && Boolean.TRUE.equals(queue.get("queueHandoffPrepared"))
                && Boolean.TRUE.equals(queue.get("pushHandoffPrepared"))
                && Boolean.TRUE.equals(queue.get("claimHandoffPrepared"))
                && Boolean.TRUE.equals(queue.get("diffValidationPassed"))
                && Boolean.TRUE.equals(queue.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(queue.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(queue.get("browserReviewReady"))
                && Boolean.TRUE.equals(queue.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_QUEUE_NOT_PROVIDED"
                : ready ? "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED" : "LOCAL_AGENT_QUEUE_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentQueueProvided", provided);
        preview.put("queueHandoffPrepared", ready);
        preview.put("pushHandoffPrepared", ready);
        preview.put("claimHandoffPrepared", ready);
        preview.put("snapshotDryRunReadinessPrepared", ready);
        preview.put("sourceLocalAgentQueueSchema", provided ? queue.get("schema") : null);
        preview.put("sourceLocalAgentQueueStatus", provided ? queue.get("status") : null);
        preview.put("toolName", provided ? queue.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? queue.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? queue.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? queue.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(queue.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(queue.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(queue.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(queue.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(queue.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? queue.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("localAgentClaimReadiness", localAgentClaimReadinessEnvelope(ready, queue));
        preview.put("localAgentQueueEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview");
        preview.put("localAgentClaimReadinessEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent queue preview was supplied; claim and snapshot-writing dry-run readiness cannot be modeled."
                : ready
                ? "Claim and snapshot-writing dry-run readiness can be modeled, but no claim, snapshot, dry-run execution, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Local Agent queue preview is present but incomplete; no claim or snapshot-writing dry-run readiness preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentClaimReadinessEnvelope(boolean ready, Map<String, Object> queue) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("toolName", "patch.apply");
        envelope.put("executionTarget", "USER_LOCAL_AGENT");
        envelope.put("approvalKind", "SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "REQUIRES_QUEUE_HANDOFF");
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("targetFiles", ready ? queue.get("targetFiles") : List.of());
        envelope.put("approvalActions", ready ? queue.getOrDefault("approvalActions", List.of()) : List.of());
        envelope.put("queueHandoffPrepared", ready);
        envelope.put("pushHandoffPrepared", ready);
        envelope.put("claimHandoffPrepared", ready);
        envelope.put("snapshotDryRunReadinessPrepared", ready);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("durableLocalAgentRequestCreated", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("pushEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("claimable", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        envelope.put("testExecutionEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentSnapshotDryRunPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> claimReadiness
    ) {
        boolean provided = claimReadiness != null && !claimReadiness.isEmpty();
        boolean ready = provided
                && "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED".equals(String.valueOf(claimReadiness.get("status")))
                && Boolean.TRUE.equals(claimReadiness.get("snapshotDryRunReadinessPrepared"))
                && Boolean.TRUE.equals(claimReadiness.get("queueHandoffPrepared"))
                && Boolean.TRUE.equals(claimReadiness.get("pushHandoffPrepared"))
                && Boolean.TRUE.equals(claimReadiness.get("claimHandoffPrepared"))
                && Boolean.TRUE.equals(claimReadiness.get("diffValidationPassed"))
                && Boolean.TRUE.equals(claimReadiness.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(claimReadiness.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(claimReadiness.get("browserReviewReady"))
                && Boolean.TRUE.equals(claimReadiness.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_CLAIM_READINESS_NOT_PROVIDED"
                : ready ? "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED" : "LOCAL_AGENT_CLAIM_READINESS_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentClaimReadinessProvided", provided);
        preview.put("queueHandoffPrepared", ready);
        preview.put("pushHandoffPrepared", ready);
        preview.put("claimHandoffPrepared", ready);
        preview.put("snapshotDryRunReadinessPrepared", ready);
        preview.put("patchDryRunExecutionObservationPrepared", ready);
        preview.put("sourceLocalAgentClaimReadinessSchema", provided ? claimReadiness.get("schema") : null);
        preview.put("sourceLocalAgentClaimReadinessStatus", provided ? claimReadiness.get("status") : null);
        preview.put("toolName", provided ? claimReadiness.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? claimReadiness.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? claimReadiness.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? claimReadiness.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(claimReadiness.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(claimReadiness.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(claimReadiness.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(claimReadiness.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(claimReadiness.get("userApprovalRequired")));
        preview.put("approvalActions", provided ? claimReadiness.getOrDefault("approvalActions", List.of()) : List.of());
        preview.put("localAgentSnapshotDryRunObservation", localAgentSnapshotDryRunObservationEnvelope(ready, claimReadiness));
        preview.put("localAgentClaimReadinessEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview");
        preview.put("localAgentSnapshotDryRunEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("patchDryRunExecuted", false);
        preview.put("patchDryRunObservationRecorded", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent claim-readiness preview was supplied; snapshot dry-run observation cannot be modeled."
                : ready
                ? "Snapshot-writing dry-run observation can be modeled, but no claim, snapshot, dry-run execution, observation recording, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Local Agent claim-readiness preview is present but incomplete; no snapshot dry-run observation preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentSnapshotDryRunObservationEnvelope(boolean ready, Map<String, Object> claimReadiness) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-observation.v1");
        envelope.put("status", ready ? "READY_DISABLED" : "NOT_READY");
        envelope.put("toolName", "patch.apply");
        envelope.put("executionTarget", "USER_LOCAL_AGENT");
        envelope.put("approvalKind", "SNAPSHOT_WRITING_DRY_RUN");
        envelope.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "REQUIRES_CLAIM_READINESS");
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("targetFiles", ready ? claimReadiness.get("targetFiles") : List.of());
        envelope.put("snapshotDryRunReadinessPrepared", ready);
        envelope.put("patchDryRunExecutionObservationPrepared", ready);
        envelope.put("requestCreationEnabled", false);
        envelope.put("localAgentToolRequestCreated", false);
        envelope.put("durableLocalAgentRequestCreated", false);
        envelope.put("enqueueEnabled", false);
        envelope.put("pushEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("claimable", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("patchDryRunExecuted", false);
        envelope.put("patchDryRunObservationRecorded", false);
        envelope.put("mutationEnabled", false);
        envelope.put("testExecutionEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentDryRunResultPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> snapshotDryRun
    ) {
        boolean provided = snapshotDryRun != null && !snapshotDryRun.isEmpty();
        boolean ready = provided
                && "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED".equals(String.valueOf(snapshotDryRun.get("status")))
                && Boolean.TRUE.equals(snapshotDryRun.get("snapshotDryRunReadinessPrepared"))
                && Boolean.TRUE.equals(snapshotDryRun.get("patchDryRunExecutionObservationPrepared"))
                && Boolean.TRUE.equals(snapshotDryRun.get("diffValidationPassed"))
                && Boolean.TRUE.equals(snapshotDryRun.get("requestEnvelopePrepared"))
                && Boolean.TRUE.equals(snapshotDryRun.get("nonWritingPreflightPassed"))
                && Boolean.TRUE.equals(snapshotDryRun.get("browserReviewReady"))
                && Boolean.TRUE.equals(snapshotDryRun.get("userApprovalRequired"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_SNAPSHOT_DRY_RUN_NOT_PROVIDED"
                : ready ? "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED" : "LOCAL_AGENT_SNAPSHOT_DRY_RUN_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentSnapshotDryRunProvided", provided);
        preview.put("snapshotDryRunReadinessPrepared", ready);
        preview.put("patchDryRunExecutionObservationPrepared", ready);
        preview.put("dryRunResultAnalysisPrepared", ready);
        preview.put("failureLogAnalysisPrepared", ready);
        preview.put("retryDecisionPrepared", ready);
        preview.put("sourceLocalAgentSnapshotDryRunSchema", provided ? snapshotDryRun.get("schema") : null);
        preview.put("sourceLocalAgentSnapshotDryRunStatus", provided ? snapshotDryRun.get("status") : null);
        preview.put("toolName", provided ? snapshotDryRun.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? snapshotDryRun.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? snapshotDryRun.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? snapshotDryRun.get("targetFiles") : List.of());
        preview.put("diffValidationPassed", provided && Boolean.TRUE.equals(snapshotDryRun.get("diffValidationPassed")));
        preview.put("requestEnvelopePrepared", provided && Boolean.TRUE.equals(snapshotDryRun.get("requestEnvelopePrepared")));
        preview.put("nonWritingPreflightPassed", provided && Boolean.TRUE.equals(snapshotDryRun.get("nonWritingPreflightPassed")));
        preview.put("browserReviewReady", provided && Boolean.TRUE.equals(snapshotDryRun.get("browserReviewReady")));
        preview.put("userApprovalRequired", provided && Boolean.TRUE.equals(snapshotDryRun.get("userApprovalRequired")));
        preview.put("dryRunResultStatus", ready ? "NOT_EXECUTED_PREVIEW" : "UNAVAILABLE");
        preview.put("dryRunFailureCode", ready ? "NOT_EXECUTED" : "NO_RESULT");
        preview.put("dryRunSucceeded", false);
        preview.put("dryRunFailed", false);
        preview.put("contextMismatchDetected", false);
        preview.put("unsafePatchDetected", false);
        preview.put("retryRecommended", ready);
        preview.put("retryDecision", ready ? "WAIT_FOR_ACTUAL_DRY_RUN_RESULT" : "WAIT_FOR_SNAPSHOT_DRY_RUN_PREVIEW");
        preview.put("replanRequired", false);
        preview.put("userReviewRequired", ready);
        preview.put("localAgentDryRunResult", localAgentDryRunResultEnvelope(ready, snapshotDryRun));
        preview.put("failureLogAnalysis", localAgentDryRunFailureLogAnalysisEnvelope(ready));
        preview.put("retryDecisionPreview", localAgentDryRunRetryDecisionEnvelope(ready));
        preview.put("localAgentSnapshotDryRunEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview");
        preview.put("localAgentDryRunResultEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("patchDryRunExecuted", false);
        preview.put("patchDryRunObservationRecorded", false);
        preview.put("dryRunResultRecorded", false);
        preview.put("failureLogAnalysisRecorded", false);
        preview.put("retryDecisionRecorded", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent snapshot dry-run preview was supplied; dry-run result analysis cannot be modeled."
                : ready
                ? "Dry-run result, failure-log analysis, and retry decision can be modeled, but no dry-run execution, result recording, retry execution, mutation, tests, rollback, final publication, or partial reindex work is enabled."
                : "Local Agent snapshot dry-run preview is present but incomplete; no dry-run result or retry-decision preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentDryRunResultEnvelope(boolean ready, Map<String, Object> snapshotDryRun) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result.v1");
        envelope.put("status", ready ? "NOT_EXECUTED_PREVIEW" : "NOT_READY");
        envelope.put("toolName", "patch.apply");
        envelope.put("executionTarget", "USER_LOCAL_AGENT");
        envelope.put("targetFiles", ready ? snapshotDryRun.get("targetFiles") : List.of());
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("dryRunSucceeded", false);
        envelope.put("dryRunFailed", false);
        envelope.put("failureCode", ready ? "NOT_EXECUTED" : "NO_RESULT");
        envelope.put("requestCreationEnabled", false);
        envelope.put("claimEnabled", false);
        envelope.put("snapshotCreationEnabled", false);
        envelope.put("patchDryRunExecutionEnabled", false);
        envelope.put("patchDryRunExecuted", false);
        envelope.put("dryRunResultRecorded", false);
        envelope.put("mutationEnabled", false);
        envelope.put("testExecutionEnabled", false);
        return envelope;
    }

    private Map<String, Object> localAgentDryRunFailureLogAnalysisEnvelope(boolean ready) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-failure-log-analysis.v1");
        envelope.put("status", ready ? "WAITING_FOR_RESULT_DISABLED" : "NOT_READY");
        envelope.put("analysisPrepared", ready);
        envelope.put("failureCode", ready ? "NOT_EXECUTED" : "NO_RESULT");
        envelope.put("contextMismatchDetected", false);
        envelope.put("unsafePatchDetected", false);
        envelope.put("missingFileDetected", false);
        envelope.put("analysisRecorded", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    private Map<String, Object> localAgentDryRunRetryDecisionEnvelope(boolean ready) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-decision.v1");
        envelope.put("status", ready ? "WAITING_FOR_RESULT_DISABLED" : "NOT_READY");
        envelope.put("retryDecisionPrepared", ready);
        envelope.put("retryRecommended", ready);
        envelope.put("decision", ready ? "WAIT_FOR_ACTUAL_DRY_RUN_RESULT" : "WAIT_FOR_SNAPSHOT_DRY_RUN_PREVIEW");
        envelope.put("replanRequired", false);
        envelope.put("retryRequestCreationEnabled", false);
        envelope.put("retryExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        envelope.put("partialReindexEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentRetryInputPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> dryRunResult
    ) {
        boolean provided = dryRunResult != null && !dryRunResult.isEmpty();
        boolean ready = provided
                && "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED".equals(String.valueOf(dryRunResult.get("status")))
                && Boolean.TRUE.equals(dryRunResult.get("dryRunResultAnalysisPrepared"))
                && Boolean.TRUE.equals(dryRunResult.get("failureLogAnalysisPrepared"))
                && Boolean.TRUE.equals(dryRunResult.get("retryDecisionPrepared"));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_DRY_RUN_RESULT_NOT_PROVIDED"
                : ready ? "READY_RETRY_INPUT_REPLAN_DISABLED" : "LOCAL_AGENT_DRY_RUN_RESULT_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentDryRunResultProvided", provided);
        preview.put("retryInputPrepared", ready);
        preview.put("boundedRetryPatchInputPrepared", ready);
        preview.put("replanDecisionPrepared", ready);
        preview.put("sourceLocalAgentDryRunResultSchema", provided ? dryRunResult.get("schema") : null);
        preview.put("sourceLocalAgentDryRunResultStatus", provided ? dryRunResult.get("status") : null);
        preview.put("toolName", provided ? dryRunResult.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? dryRunResult.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? dryRunResult.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? dryRunResult.get("targetFiles") : List.of());
        preview.put("dryRunResultStatus", provided ? dryRunResult.getOrDefault("dryRunResultStatus", "UNAVAILABLE") : "UNAVAILABLE");
        preview.put("dryRunFailureCode", provided ? dryRunResult.getOrDefault("dryRunFailureCode", "NO_RESULT") : "NO_RESULT");
        preview.put("contextMismatchDetected", provided && Boolean.TRUE.equals(dryRunResult.get("contextMismatchDetected")));
        preview.put("unsafePatchDetected", provided && Boolean.TRUE.equals(dryRunResult.get("unsafePatchDetected")));
        preview.put("retryRecommended", provided && Boolean.TRUE.equals(dryRunResult.get("retryRecommended")));
        preview.put("sourceRetryDecision", provided ? dryRunResult.getOrDefault("retryDecision", "WAIT_FOR_SNAPSHOT_DRY_RUN_PREVIEW") : "WAIT_FOR_SNAPSHOT_DRY_RUN_PREVIEW");
        preview.put("retryInputDecision", ready ? "WAIT_FOR_ACTUAL_DRY_RUN_RESULT" : "WAIT_FOR_DRY_RUN_RESULT_PREVIEW");
        preview.put("replanRequired", provided && Boolean.TRUE.equals(dryRunResult.get("replanRequired")));
        preview.put("userVisibleDecision", ready ? "WAIT_FOR_DRY_RUN_RESULT_BEFORE_RETRY_OR_REPLAN" : "WAIT_FOR_DRY_RUN_RESULT_PREVIEW");
        preview.put("localAgentRetryInput", localAgentRetryInputEnvelope(ready, dryRunResult));
        preview.put("replanPreview", localAgentReplanEnvelope(ready, dryRunResult));
        preview.put("localAgentDryRunResultEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview");
        preview.put("localAgentRetryInputEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("patchDryRunExecuted", false);
        preview.put("patchDryRunObservationRecorded", false);
        preview.put("dryRunResultRecorded", false);
        preview.put("failureLogAnalysisRecorded", false);
        preview.put("retryDecisionRecorded", false);
        preview.put("retryPatchGenerated", false);
        preview.put("retryRequestCreationEnabled", false);
        preview.put("retryExecutionEnabled", false);
        preview.put("replanExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent dry-run result preview was supplied; retry input and replan preview cannot be modeled."
                : ready
                ? "Retry input and replan decisions can be modeled, but retry patch generation, request creation, retry execution, mutation, tests, rollback, final publication, and partial reindex remain disabled."
                : "Local Agent dry-run result preview is present but incomplete; no retry input or replan preview can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentRetryInputEnvelope(boolean ready, Map<String, Object> dryRunResult) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input.v1");
        envelope.put("status", ready ? "WAITING_FOR_RESULT_DISABLED" : "NOT_READY");
        envelope.put("targetFiles", ready ? dryRunResult.get("targetFiles") : List.of());
        envelope.put("failureCode", ready ? dryRunResult.getOrDefault("dryRunFailureCode", "NOT_EXECUTED") : "NO_RESULT");
        envelope.put("boundedRetryPatchInputPrepared", ready);
        envelope.put("dryRunOnly", true);
        envelope.put("allowMutation", false);
        envelope.put("retryPatchGenerated", false);
        envelope.put("retryRequestCreationEnabled", false);
        envelope.put("retryExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    private Map<String, Object> localAgentReplanEnvelope(boolean ready, Map<String, Object> dryRunResult) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-replan-preview.v1");
        envelope.put("status", ready ? "WAITING_FOR_RESULT_DISABLED" : "NOT_READY");
        envelope.put("replanDecisionPrepared", ready);
        envelope.put("sourceRetryDecision", ready ? dryRunResult.getOrDefault("retryDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT") : "WAIT_FOR_DRY_RUN_RESULT_PREVIEW");
        envelope.put("replanRequired", ready && Boolean.TRUE.equals(dryRunResult.get("replanRequired")));
        envelope.put("userVisibleDecision", ready ? "WAIT_FOR_DRY_RUN_RESULT_BEFORE_RETRY_OR_REPLAN" : "WAIT_FOR_DRY_RUN_RESULT_PREVIEW");
        envelope.put("replanExecutionEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    public Map<String, Object> localAgentRetryProposalPreview(
            UUID repositoryId,
            UUID spaceId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> retryInput
    ) {
        boolean provided = retryInput != null && !retryInput.isEmpty();
        boolean ready = provided
                && "READY_RETRY_INPUT_REPLAN_DISABLED".equals(String.valueOf(retryInput.get("status")))
                && Boolean.TRUE.equals(retryInput.get("retryInputPrepared"))
                && Boolean.TRUE.equals(retryInput.get("boundedRetryPatchInputPrepared"))
                && Boolean.TRUE.equals(retryInput.get("replanDecisionPrepared"));
        boolean replanRequired = provided && Boolean.TRUE.equals(retryInput.get("replanRequired"));
        boolean retryRecommended = provided && Boolean.TRUE.equals(retryInput.get("retryRecommended"));
        String finalStopDecision = ready && replanRequired
                ? "REPLAN_REQUIRED_BEFORE_RETRY"
                : ready && retryRecommended ? "WAIT_FOR_RETRY_PATCH_PROPOSAL"
                : ready ? "WAIT_FOR_SUCCESS_OR_USER_REVIEW" : "WAIT_FOR_RETRY_INPUT_PREVIEW";

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1");
        preview.put("status", !provided
                ? "LOCAL_AGENT_RETRY_INPUT_NOT_PROVIDED"
                : ready ? "READY_RETRY_PROPOSAL_FINAL_STOP_DISABLED" : "LOCAL_AGENT_RETRY_INPUT_NOT_READY");
        preview.put("repositoryId", repositoryId);
        preview.put("spaceId", spaceId);
        preview.put("agentId", agentId);
        preview.put("workspaceId", workspaceId);
        preview.put("localAgentRetryInputProvided", provided);
        preview.put("retryProposalPrepared", ready && retryRecommended && !replanRequired);
        preview.put("boundedRetryPatchProposalPrepared", ready && retryRecommended && !replanRequired);
        preview.put("finalStopDecisionPrepared", ready);
        preview.put("sourceLocalAgentRetryInputSchema", provided ? retryInput.get("schema") : null);
        preview.put("sourceLocalAgentRetryInputStatus", provided ? retryInput.get("status") : null);
        preview.put("toolName", provided ? retryInput.get("toolName") : "patch.apply");
        preview.put("executionTarget", provided ? retryInput.get("executionTarget") : "USER_LOCAL_AGENT");
        preview.put("approvalKind", provided ? retryInput.get("approvalKind") : "SNAPSHOT_WRITING_DRY_RUN");
        preview.put("approvalState", ready ? "APPROVED_HELD_PREVIEW" : "NOT_PREPARED");
        preview.put("targetFiles", provided ? retryInput.get("targetFiles") : List.of());
        preview.put("dryRunResultStatus", provided ? retryInput.getOrDefault("dryRunResultStatus", "UNAVAILABLE") : "UNAVAILABLE");
        preview.put("dryRunFailureCode", provided ? retryInput.getOrDefault("dryRunFailureCode", "NO_RESULT") : "NO_RESULT");
        preview.put("contextMismatchDetected", provided && Boolean.TRUE.equals(retryInput.get("contextMismatchDetected")));
        preview.put("unsafePatchDetected", provided && Boolean.TRUE.equals(retryInput.get("unsafePatchDetected")));
        preview.put("retryRecommended", retryRecommended);
        preview.put("sourceRetryInputDecision", provided ? retryInput.getOrDefault("retryInputDecision", "WAIT_FOR_RETRY_INPUT_PREVIEW") : "WAIT_FOR_RETRY_INPUT_PREVIEW");
        preview.put("replanRequired", replanRequired);
        preview.put("userVisibleDecision", ready
                ? (replanRequired ? "SHOW_REPLAN_REQUIRED_BEFORE_RETRY" : "WAIT_FOR_RETRY_PATCH_PROPOSAL")
                : "WAIT_FOR_RETRY_INPUT_PREVIEW");
        preview.put("finalStopDecision", finalStopDecision);
        preview.put("localAgentRetryPatchProposal", localAgentRetryPatchProposalEnvelope(ready, retryRecommended, replanRequired, retryInput));
        preview.put("finalStopDecisionPreview", localAgentFinalStopDecisionEnvelope(ready, retryRecommended, replanRequired, finalStopDecision));
        preview.put("localAgentRetryInputEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview");
        preview.put("localAgentRetryProposalEndpoint", "/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-proposal-preview");
        preview.put("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request");
        preview.put("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review");
        preview.put("approvalRecordCreationEnabled", false);
        preview.put("approvalActionPersistenceEnabled", false);
        preview.put("approvalActionEnabled", false);
        preview.put("heldRequestReviewEnabled", false);
        preview.put("heldRequestCreated", false);
        preview.put("approvalDecisionPersistenceEnabled", false);
        preview.put("approvalPersistenceEnabled", false);
        preview.put("approvalRequestCreationEnabled", false);
        preview.put("requestCreationEnabled", false);
        preview.put("serverApprovalRecordCreated", false);
        preview.put("approvalDecisionRecorded", false);
        preview.put("approvalDecisionPersisted", false);
        preview.put("approvalActionRecorded", false);
        preview.put("approvalActionPersisted", false);
        preview.put("localAgentToolRequestCreated", false);
        preview.put("durableLocalAgentRequestCreated", false);
        preview.put("enqueueEnabled", false);
        preview.put("pushEnabled", false);
        preview.put("claimEnabled", false);
        preview.put("claimable", false);
        preview.put("snapshotCreationEnabled", false);
        preview.put("patchDryRunExecutionEnabled", false);
        preview.put("patchDryRunExecuted", false);
        preview.put("patchDryRunObservationRecorded", false);
        preview.put("dryRunResultRecorded", false);
        preview.put("failureLogAnalysisRecorded", false);
        preview.put("retryDecisionRecorded", false);
        preview.put("retryPatchGenerated", false);
        preview.put("retryPatchProposalGenerated", false);
        preview.put("retryRequestCreationEnabled", false);
        preview.put("retryExecutionEnabled", false);
        preview.put("replanExecutionEnabled", false);
        preview.put("mutationEnabled", false);
        preview.put("testExecutionEnabled", false);
        preview.put("rollbackRestoreEnabled", false);
        preview.put("finalPublicationEnabled", false);
        preview.put("partialReindexEnabled", false);
        preview.put("approvalBypassAllowed", false);
        preview.put("reason", !provided
                ? "No Local Agent retry-input preview was supplied; retry proposal and final-stop decision cannot be modeled."
                : ready
                ? "Retry proposal or final-stop decision can be modeled, but retry patch generation, request creation, retry execution, replan execution, mutation, tests, rollback, final publication, and partial reindex remain disabled."
                : "Local Agent retry-input preview is present but incomplete; no retry proposal or final-stop decision can be prepared.");
        return preview;
    }

    private Map<String, Object> localAgentRetryPatchProposalEnvelope(
            boolean ready,
            boolean retryRecommended,
            boolean replanRequired,
            Map<String, Object> retryInput
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        boolean proposalReady = ready && retryRecommended && !replanRequired;
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-patch-proposal.v1");
        envelope.put("status", proposalReady ? "WAITING_FOR_RETRY_PATCH_DISABLED" : ready ? "NOT_SELECTED" : "NOT_READY");
        envelope.put("targetFiles", proposalReady ? retryInput.get("targetFiles") : List.of());
        envelope.put("failureCode", proposalReady ? retryInput.getOrDefault("dryRunFailureCode", "NOT_EXECUTED") : "NO_RESULT");
        envelope.put("boundedRetryPatchProposalPrepared", proposalReady);
        envelope.put("unifiedDiffRequired", true);
        envelope.put("retryPatchGenerated", false);
        envelope.put("retryPatchProposalGenerated", false);
        envelope.put("retryRequestCreationEnabled", false);
        envelope.put("retryExecutionEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
    }

    private Map<String, Object> localAgentFinalStopDecisionEnvelope(
            boolean ready,
            boolean retryRecommended,
            boolean replanRequired,
            String finalStopDecision
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-final-stop-decision-preview.v1");
        envelope.put("status", ready ? "WAITING_FOR_ACTUAL_RESULT_DISABLED" : "NOT_READY");
        envelope.put("finalStopDecisionPrepared", ready);
        envelope.put("retryRecommended", retryRecommended);
        envelope.put("replanRequired", replanRequired);
        envelope.put("decision", finalStopDecision);
        envelope.put("userVisibleDecision", ready
                ? (replanRequired ? "SHOW_REPLAN_REQUIRED_BEFORE_RETRY" : "WAIT_FOR_RETRY_PATCH_PROPOSAL")
                : "WAIT_FOR_RETRY_INPUT_PREVIEW");
        envelope.put("replanExecutionEnabled", false);
        envelope.put("retryExecutionEnabled", false);
        envelope.put("requestCreationEnabled", false);
        envelope.put("mutationEnabled", false);
        return envelope;
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
        Optional<CodeAgentLoopTimelineEventSummary> latestReadOnlyQueued = latestEvent(selected, "LOCAL_AGENT_READ_ONLY_REQUEST_QUEUED");
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
        if (latestReadOnlyQueued.isPresent()
                && isSameOrAfter(latestReadOnlyQueued.get(), latestDecision.orElse(null))
                && isSameOrAfter(latestReadOnlyQueued.get(), latestApproval.orElse(null))
                && isSameOrAfter(latestReadOnlyQueued.get(), latestApprovalRequest.orElse(null))
                && isSameOrAfter(latestReadOnlyQueued.get(), latestObservation.orElse(null))
                && isSameOrAfter(latestReadOnlyQueued.get(), latestStop.orElse(null))) {
            return nextAction(
                    selected.id(),
                    selected.repositoryId(),
                    stringDetail(latestReadOnlyQueued.get(), "status", "QUEUED"),
                    "WAIT_FOR_LOCAL_AGENT_OBSERVATION",
                    stringDetail(latestReadOnlyQueued.get(), "nextAction", "Wait for the Local Agent to complete the queued read-only observation before advancing again."),
                    latestReadOnlyQueued.get()
            );
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

    public void appendPatchProposalBlocked(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            String stopKey,
            String action,
            Map<String, Object> details
    ) {
        timelineRepository.appendStopOutcome(
                userId,
                repositoryId,
                loopId,
                stopKey == null || stopKey.isBlank() ? "PATCH_PROPOSAL_BLOCKED" : stopKey,
                "REPORT_PATCH_PROPOSAL_BLOCKED",
                action == null || action.isBlank() ? "Report that patch proposal could not produce an approval-ready diff." : action,
                details == null ? Map.of() : details
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
