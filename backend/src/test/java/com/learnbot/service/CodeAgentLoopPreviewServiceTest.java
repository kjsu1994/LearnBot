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
    void submissionPlanIsAuthoritativeButDisabledAndDoesNotCreateTimeline() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        var plan = service.submissionPlan(repositoryId, spaceId, agentId, workspaceId, "repair parser failure", 99, null);

        assertThat(plan.schema()).isEqualTo("learnbot.server.code-agent.loop-submission-plan.v1");
        assertThat(plan.repositoryId()).isEqualTo(repositoryId);
        assertThat(plan.spaceId()).isEqualTo(spaceId);
        assertThat(plan.agentId()).isEqualTo(agentId);
        assertThat(plan.workspaceId()).isEqualTo(workspaceId);
        assertThat(plan.instruction()).isEqualTo("repair parser failure");
        assertThat(plan.maxSteps()).isEqualTo(8);
        assertThat(plan.method()).isEqualTo("POST");
        assertThat(plan.endpoint()).isEqualTo("/api/code-agent/loop/preview");
        assertThat(plan.bodyPreview())
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("instruction", "repair parser failure")
                .containsEntry("maxSteps", 8)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId);
        assertThat(plan.patchDryRunApprovalHandoffPlan())
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1")
                .containsEntry("status", "HANDOFF_NOT_PROVIDED")
                .containsEntry("handoffProvided", false)
                .containsEntry("handoffPrepared", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(plan.patchDryRunApprovalReviewPreview())
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1")
                .containsEntry("status", "HANDOFF_NOT_PROVIDED")
                .containsEntry("reviewSurface", "CODE_WORKSPACE_LOOP_REVIEW")
                .containsEntry("approvalReviewPrepared", false)
                .containsEntry("browserReviewReady", false)
                .containsEntry("userApprovalRequired", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(plan.followUpEndpoints())
                .contains("POST /api/code-agent/loop/runner/preview",
                        "POST /api/code-agent/loop/runner/select-tool-preview",
                        "POST /api/code-agent/loop/runner/enqueue-selected-read-only",
                        "POST /api/code-agent/loop/runner/validated-patch-approval-request");
        assertThat(plan.readyForDisabledPlan()).isTrue();
        assertThat(plan.enabled()).isFalse();
        assertThat(plan.networkCallEnabled()).isFalse();
        assertThat(plan.requestCreationEnabled()).isFalse();
        assertThat(plan.serverConversationCreationEnabled()).isFalse();
        assertThat(plan.loopPreviewExecutionEnabled()).isFalse();
        assertThat(plan.mutationEnabled()).isFalse();
        assertThat(plan.testExecutionEnabled()).isFalse();
        assertThat(plan.rollbackExecutionEnabled()).isFalse();
        assertThat(plan.finalPublicationEnabled()).isFalse();
        assertThat(plan.partialReindexEnabled()).isFalse();
        assertThat(plan.requiresAuthenticatedWebSession()).isTrue();
        assertThat(plan.requiresRepositoryAuthorization()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void submissionPlanShapesReadyCliDryRunApprovalHandoffWithoutCreatingRequests() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> handoff = Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1"),
                Map.entry("status", "APPROVAL_HANDOFF_PREPARED"),
                Map.entry("approvalHandoffPrepared", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("diffValidationPassed", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java"))
        );

        var plan = service.submissionPlan(repositoryId, spaceId, agentId, workspaceId, "repair parser failure", 6, handoff);

        assertThat(plan.bodyPreview())
                .containsEntry("patchDryRunApprovalHandoffPreview", handoff);
        assertThat(plan.patchDryRunApprovalHandoffPlan())
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1")
                .containsEntry("status", "READY_APPROVAL_REQUEST_PREVIEW_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("handoffProvided", true)
                .containsEntry("handoffPrepared", true)
                .containsEntry("sourceSchema", "learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1")
                .containsEntry("sourceStatus", "APPROVAL_HANDOFF_PREPARED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "REQUIRED_BEFORE_SNAPSHOT_DRY_RUN")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request")
                .containsEntry("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(plan.patchDryRunApprovalHandoffPlan().get("targetFiles"))
                .isEqualTo(List.of("src/App.java"));
        assertThat(plan.patchDryRunApprovalReviewPreview())
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1")
                .containsEntry("status", "READY_BROWSER_REVIEW_DISABLED")
                .containsEntry("reviewSurface", "CODE_WORKSPACE_LOOP_REVIEW")
                .containsEntry("sourcePlanSchema", "learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1")
                .containsEntry("sourcePlanStatus", "READY_APPROVAL_REQUEST_PREVIEW_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "AWAITING_USER_REVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("approvalReviewPrepared", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request")
                .containsEntry("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(plan.patchDryRunApprovalReviewPreview().get("targetFiles"))
                .isEqualTo(List.of("src/App.java"));
        assertThat(plan.requestCreationEnabled()).isFalse();
        assertThat(plan.mutationEnabled()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalIntentPreviewShapesReadyBrowserReviewWithoutCreatingRequests() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> review = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1"),
                Map.entry("status", "READY_BROWSER_REVIEW_DISABLED"),
                Map.entry("reviewSurface", "CODE_WORKSPACE_LOOP_REVIEW"),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("approvalReviewPrepared", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true)
        );

        var preview = service.approvalIntentPreview(repositoryId, spaceId, agentId, workspaceId, review);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1")
                .containsEntry("status", "READY_APPROVAL_INTENT_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("reviewProvided", true)
                .containsEntry("approvalIntentPrepared", true)
                .containsEntry("sourceReviewSchema", "learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1")
                .containsEntry("sourceReviewStatus", "READY_BROWSER_REVIEW_DISABLED")
                .containsEntry("sourceReviewSurface", "CODE_WORKSPACE_LOOP_REVIEW")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "USER_REVIEW_REQUIRED")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request")
                .containsEntry("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review")
                .containsEntry("approvalIntentCreationEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("approvalIntent"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-intent.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("approvalAction", "APPROVE_SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalIntentPrepared", true)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalIntentPreviewBlocksMissingBrowserReview() {
        var preview = service.approvalIntentPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1")
                .containsEntry("status", "REVIEW_NOT_PROVIDED")
                .containsEntry("reviewProvided", false)
                .containsEntry("approvalIntentPrepared", false)
                .containsEntry("approvalIntentCreationEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalRequestCreationPreviewShapesReadyIntentWithoutPersistingOrCreatingRequests() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> intent = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1"),
                Map.entry("status", "READY_APPROVAL_INTENT_DISABLED"),
                Map.entry("approvalIntentPrepared", true),
                Map.entry("reviewProvided", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true)
        );

        var preview = service.approvalRequestCreationPreview(repositoryId, spaceId, agentId, workspaceId, intent);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1")
                .containsEntry("status", "READY_APPROVAL_REQUEST_CREATION_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalIntentProvided", true)
                .containsEntry("approvalRequestCreationPrepared", true)
                .containsEntry("approvalPersistencePrepared", true)
                .containsEntry("sourceIntentSchema", "learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1")
                .containsEntry("sourceIntentStatus", "READY_APPROVAL_INTENT_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVAL_REQUIRED_HELD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request")
                .containsEntry("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review")
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("approvalPersistencePreview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-persistence-preview.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("approvalPersistencePrepared", true)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalBypassAllowed", false)
                .containsEntry("mutationEnabled", false);
        assertThat((Map<String, Object>) preview.get("approvalRequestPreview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-request-preview.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("approvalRequestCreationPrepared", true)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalRequestCreationPreviewBlocksMissingIntent() {
        var preview = service.approvalRequestCreationPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1")
                .containsEntry("status", "APPROVAL_INTENT_NOT_PROVIDED")
                .containsEntry("approvalIntentProvided", false)
                .containsEntry("approvalRequestCreationPrepared", false)
                .containsEntry("approvalPersistencePrepared", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalDecisionPreviewShapesReadyRequestCreationWithoutRecordingDecision() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> requestCreation = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1"),
                Map.entry("status", "READY_APPROVAL_REQUEST_CREATION_DISABLED"),
                Map.entry("approvalRequestCreationPrepared", true),
                Map.entry("approvalPersistencePrepared", true),
                Map.entry("approvalIntentProvided", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true)
        );

        var preview = service.approvalDecisionPreview(repositoryId, spaceId, agentId, workspaceId, requestCreation);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1")
                .containsEntry("status", "READY_APPROVAL_DECISION_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalRequestCreationProvided", true)
                .containsEntry("approvalDecisionPrepared", true)
                .containsEntry("sourceRequestCreationSchema", "learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1")
                .containsEntry("sourceRequestCreationStatus", "READY_APPROVAL_REQUEST_CREATION_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "AWAITING_BROWSER_DECISION_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalDecisionEndpoint", "/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview")
                .containsEntry("approvalRequestEndpoint", "/api/code-agent/loop/runner/validated-patch-approval-request")
                .containsEntry("releaseReviewEndpoint", "/api/code-agent/loop/runner/release-review")
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((List<Map<String, Object>>) preview.get("decisionOptions"))
                .extracting("action")
                .containsExactly("APPROVE_SNAPSHOT_WRITING_DRY_RUN", "DENY_SNAPSHOT_WRITING_DRY_RUN");
        assertThat((List<Map<String, Object>>) preview.get("decisionOptions"))
                .allSatisfy(option -> {
                    assertThat(option).containsEntry("prepared", true);
                    assertThat(option).containsEntry("enabled", false);
                    assertThat(option).containsEntry("approvalDecisionPersistenceEnabled", false);
                    assertThat(option).containsEntry("requestCreationEnabled", false);
                    assertThat(option).containsEntry("mutationEnabled", false);
                });
        assertThat((Map<String, Object>) preview.get("heldRequestReview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1")
                .containsEntry("status", "READY_HELD_REQUEST_REVIEW_DISABLED")
                .containsEntry("heldRequestReviewPrepared", true)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalDecisionPreviewBlocksMissingRequestCreationPreview() {
        var preview = service.approvalDecisionPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1")
                .containsEntry("status", "APPROVAL_REQUEST_CREATION_NOT_PROVIDED")
                .containsEntry("approvalRequestCreationProvided", false)
                .containsEntry("approvalDecisionPrepared", false)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalDecisionPersistencePreviewShapesReadyDecisionWithoutPersisting() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> decision = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1"),
                Map.entry("status", "READY_APPROVAL_DECISION_DISABLED"),
                Map.entry("approvalDecisionPrepared", true),
                Map.entry("approvalRequestCreationProvided", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("decisionOptions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                )),
                Map.entry("heldRequestReview", Map.of("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1"))
        );

        var preview = service.approvalDecisionPersistencePreview(repositoryId, spaceId, agentId, workspaceId, decision);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1")
                .containsEntry("status", "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalDecisionProvided", true)
                .containsEntry("approvalDecisionPersistencePrepared", true)
                .containsEntry("heldRequestReviewPrepared", true)
                .containsEntry("sourceDecisionSchema", "learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1")
                .containsEntry("sourceDecisionStatus", "READY_APPROVAL_DECISION_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVAL_DECISION_HELD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("decisionPersistencePreview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("approvalDecisionPersistencePrepared", true)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("mutationEnabled", false);
        assertThat((Map<String, Object>) preview.get("heldRequestReview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1");
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalDecisionPersistencePreviewBlocksMissingDecision() {
        var preview = service.approvalDecisionPersistencePreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1")
                .containsEntry("status", "APPROVAL_DECISION_NOT_PROVIDED")
                .containsEntry("approvalDecisionProvided", false)
                .containsEntry("approvalDecisionPersistencePrepared", false)
                .containsEntry("heldRequestReviewPrepared", false)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void heldRequestReviewActionPreviewShapesReadyPersistenceWithoutEnablingReviewActions() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> persistence = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1"),
                Map.entry("status", "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED"),
                Map.entry("approvalDecisionPersistencePrepared", true),
                Map.entry("heldRequestReviewPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("heldRequestReview", Map.of(
                        "schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1",
                        "status", "READY_HELD_REQUEST_REVIEW_DISABLED"
                ))
        );

        var preview = service.heldRequestReviewActionPreview(repositoryId, spaceId, agentId, workspaceId, persistence);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1")
                .containsEntry("status", "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalDecisionPersistenceProvided", true)
                .containsEntry("heldRequestReviewActionPrepared", true)
                .containsEntry("heldRequestReviewPrepared", true)
                .containsEntry("sourceDecisionPersistenceSchema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1")
                .containsEntry("sourceDecisionPersistenceStatus", "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "HELD_REQUEST_BROWSER_REVIEW_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("heldRequestReviewEnabled", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("heldRequestReview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1")
                .containsEntry("status", "READY_HELD_REQUEST_REVIEW_DISABLED");
        assertThat((List<Map<String, Object>>) preview.get("reviewActions"))
                .allSatisfy(action -> assertThat(action)
                        .containsEntry("prepared", true)
                        .containsEntry("enabled", false)
                        .containsEntry("heldRequestReviewEnabled", false)
                        .containsEntry("requestCreationEnabled", false)
                        .containsEntry("mutationEnabled", false));
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void heldRequestReviewActionPreviewBlocksMissingDecisionPersistence() {
        var preview = service.heldRequestReviewActionPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1")
                .containsEntry("status", "DECISION_PERSISTENCE_NOT_PROVIDED")
                .containsEntry("approvalDecisionPersistenceProvided", false)
                .containsEntry("heldRequestReviewActionPrepared", false)
                .containsEntry("heldRequestReviewPrepared", false)
                .containsEntry("heldRequestReviewEnabled", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalActionPreviewShapesReadyHeldReviewWithoutPersistingAction() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> heldReview = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1"),
                Map.entry("status", "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED"),
                Map.entry("heldRequestReviewActionPrepared", true),
                Map.entry("heldRequestReviewPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("reviewActions", List.of(
                        Map.of("action", "REVIEW_HELD_APPROVAL", "enabled", false),
                        Map.of("action", "APPROVE_HELD_APPROVAL", "enabled", false),
                        Map.of("action", "DENY_HELD_APPROVAL", "enabled", false)
                ))
        );

        var preview = service.approvalActionPreview(repositoryId, spaceId, agentId, workspaceId, heldReview);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1")
                .containsEntry("status", "READY_APPROVAL_ACTION_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("heldRequestReviewProvided", true)
                .containsEntry("approvalActionPrepared", true)
                .containsEntry("heldRequestReviewPrepared", true)
                .containsEntry("sourceHeldRequestReviewSchema", "learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1")
                .containsEntry("sourceHeldRequestReviewStatus", "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "AWAITING_APPROVE_OR_DENY_ACTION_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalActionEnabled", false)
                .containsEntry("heldRequestReviewEnabled", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("approvalActionRecorded", false)
                .containsEntry("approvalActionPersisted", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((List<Map<String, Object>>) preview.get("approvalActions"))
                .allSatisfy(action -> assertThat(action)
                        .containsEntry("prepared", true)
                        .containsEntry("enabled", false)
                        .containsEntry("approvalActionEnabled", false)
                        .containsEntry("requestCreationEnabled", false)
                        .containsEntry("mutationEnabled", false));
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalActionPreviewBlocksMissingHeldReview() {
        var preview = service.approvalActionPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1")
                .containsEntry("status", "HELD_REQUEST_REVIEW_NOT_PROVIDED")
                .containsEntry("heldRequestReviewProvided", false)
                .containsEntry("approvalActionPrepared", false)
                .containsEntry("heldRequestReviewPrepared", false)
                .containsEntry("approvalActionEnabled", false)
                .containsEntry("heldRequestReviewEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalActionRecorded", false)
                .containsEntry("approvalActionPersisted", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalActionPersistencePreviewShapesReadyActionWithoutPersisting() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> action = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1"),
                Map.entry("status", "READY_APPROVAL_ACTION_DISABLED"),
                Map.entry("approvalActionPrepared", true),
                Map.entry("heldRequestReviewProvided", true),
                Map.entry("heldRequestReviewPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("approvalActions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                ))
        );

        var preview = service.approvalActionPersistencePreview(repositoryId, spaceId, agentId, workspaceId, action);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1")
                .containsEntry("status", "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalActionProvided", true)
                .containsEntry("approvalActionPersistencePrepared", true)
                .containsEntry("heldRequestReviewPrepared", true)
                .containsEntry("sourceApprovalActionSchema", "learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1")
                .containsEntry("sourceApprovalActionStatus", "READY_APPROVAL_ACTION_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVAL_ACTION_PERSISTENCE_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalActionPersistenceEnabled", false)
                .containsEntry("approvalActionEnabled", false)
                .containsEntry("heldRequestReviewEnabled", false)
                .containsEntry("heldRequestCreated", false)
                .containsEntry("approvalDecisionPersistenceEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalDecisionRecorded", false)
                .containsEntry("approvalDecisionPersisted", false)
                .containsEntry("approvalActionRecorded", false)
                .containsEntry("approvalActionPersisted", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("approvalActionPersistence"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("approvalActionPersistencePrepared", true)
                .containsEntry("approvalActionPersistenceEnabled", false)
                .containsEntry("approvalActionRecorded", false)
                .containsEntry("approvalActionPersisted", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalActionPersistencePreviewBlocksMissingAction() {
        var preview = service.approvalActionPersistencePreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1")
                .containsEntry("status", "APPROVAL_ACTION_NOT_PROVIDED")
                .containsEntry("approvalActionProvided", false)
                .containsEntry("approvalActionPersistencePrepared", false)
                .containsEntry("approvalActionPersistenceEnabled", false)
                .containsEntry("approvalActionEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("approvalActionRecorded", false)
                .containsEntry("approvalActionPersisted", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalRecordPreviewShapesReadyPersistenceWithoutCreatingRecordOrRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> actionPersistence = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1"),
                Map.entry("status", "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED"),
                Map.entry("approvalActionPersistencePrepared", true),
                Map.entry("approvalActionProvided", true),
                Map.entry("heldRequestReviewPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("approvalActions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                ))
        );

        var preview = service.approvalRecordPreview(repositoryId, spaceId, agentId, workspaceId, actionPersistence);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1")
                .containsEntry("status", "READY_APPROVAL_RECORD_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalActionPersistenceProvided", true)
                .containsEntry("approvalRecordPrepared", true)
                .containsEntry("localAgentRequestCreationPrepared", true)
                .containsEntry("sourceApprovalActionPersistenceSchema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1")
                .containsEntry("sourceApprovalActionPersistenceStatus", "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVAL_RECORD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalRecordCreationEnabled", false)
                .containsEntry("approvalActionPersistenceEnabled", false)
                .containsEntry("approvalActionEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalActionRecorded", false)
                .containsEntry("approvalActionPersisted", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("approvalRecord"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-record.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("approvalRecordPrepared", true)
                .containsEntry("approvalRecordCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void approvalRecordPreviewBlocksMissingPersistence() {
        var preview = service.approvalRecordPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1")
                .containsEntry("status", "APPROVAL_ACTION_PERSISTENCE_NOT_PROVIDED")
                .containsEntry("approvalActionPersistenceProvided", false)
                .containsEntry("approvalRecordPrepared", false)
                .containsEntry("localAgentRequestCreationPrepared", false)
                .containsEntry("approvalRecordCreationEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRequestEnvelopePreviewShapesReadyRecordWithoutCreatingRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> approvalRecord = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1"),
                Map.entry("status", "READY_APPROVAL_RECORD_DISABLED"),
                Map.entry("approvalRecordPrepared", true),
                Map.entry("localAgentRequestCreationPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("approvalActions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                ))
        );

        var preview = service.localAgentRequestEnvelopePreview(repositoryId, spaceId, agentId, workspaceId, approvalRecord);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1")
                .containsEntry("status", "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("approvalRecordProvided", true)
                .containsEntry("localAgentRequestEnvelopePrepared", true)
                .containsEntry("localAgentRequestCreationPrepared", true)
                .containsEntry("sourceApprovalRecordSchema", "learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1")
                .containsEntry("sourceApprovalRecordStatus", "READY_APPROVAL_RECORD_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVED_HELD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("approvalRecordCreationEnabled", false)
                .containsEntry("approvalPersistenceEnabled", false)
                .containsEntry("approvalRequestCreationEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("serverApprovalRecordCreated", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentRequestEnvelope"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("dryRunOnly", true)
                .containsEntry("allowMutation", false)
                .containsEntry("localAgentRequestEnvelopePrepared", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRequestEnvelopePreviewBlocksMissingApprovalRecord() {
        var preview = service.localAgentRequestEnvelopePreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1")
                .containsEntry("status", "APPROVAL_RECORD_NOT_PROVIDED")
                .containsEntry("approvalRecordProvided", false)
                .containsEntry("localAgentRequestEnvelopePrepared", false)
                .containsEntry("localAgentRequestCreationPrepared", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRequestCreationPreviewShapesReadyEnvelopeWithoutCreatingRequestOrQueue() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> requestEnvelope = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1"),
                Map.entry("status", "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED"),
                Map.entry("localAgentRequestEnvelopePrepared", true),
                Map.entry("localAgentRequestCreationPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("approvalActions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                ))
        );

        var preview = service.localAgentRequestCreationPreview(repositoryId, spaceId, agentId, workspaceId, requestEnvelope);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1")
                .containsEntry("status", "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentRequestEnvelopeProvided", true)
                .containsEntry("localAgentRequestEnvelopePrepared", true)
                .containsEntry("localAgentRequestCreationPrepared", true)
                .containsEntry("queueHandoffPrepared", true)
                .containsEntry("sourceLocalAgentRequestEnvelopeSchema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1")
                .containsEntry("sourceLocalAgentRequestEnvelopeStatus", "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVED_HELD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentRequestCreation"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("dryRunOnly", true)
                .containsEntry("allowMutation", false)
                .containsEntry("localAgentRequestCreationPrepared", true)
                .containsEntry("queueHandoffPrepared", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRequestCreationPreviewBlocksMissingEnvelope() {
        var preview = service.localAgentRequestCreationPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_REQUEST_ENVELOPE_NOT_PROVIDED")
                .containsEntry("localAgentRequestEnvelopeProvided", false)
                .containsEntry("localAgentRequestEnvelopePrepared", false)
                .containsEntry("localAgentRequestCreationPrepared", false)
                .containsEntry("queueHandoffPrepared", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentQueuePreviewShapesReadyCreationWithoutQueuePushOrClaim() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> requestCreation = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1"),
                Map.entry("status", "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED"),
                Map.entry("localAgentRequestCreationPrepared", true),
                Map.entry("queueHandoffPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("approvalActions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                ))
        );

        var preview = service.localAgentQueuePreview(repositoryId, spaceId, agentId, workspaceId, requestCreation);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1")
                .containsEntry("status", "READY_LOCAL_AGENT_QUEUE_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentRequestCreationProvided", true)
                .containsEntry("localAgentRequestCreationPrepared", true)
                .containsEntry("queueHandoffPrepared", true)
                .containsEntry("pushHandoffPrepared", true)
                .containsEntry("claimHandoffPrepared", true)
                .containsEntry("sourceLocalAgentRequestCreationSchema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1")
                .containsEntry("sourceLocalAgentRequestCreationStatus", "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVED_HELD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentQueue"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("queueHandoffPrepared", true)
                .containsEntry("pushHandoffPrepared", true)
                .containsEntry("claimHandoffPrepared", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentQueuePreviewBlocksMissingCreation() {
        var preview = service.localAgentQueuePreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_REQUEST_CREATION_NOT_PROVIDED")
                .containsEntry("localAgentRequestCreationProvided", false)
                .containsEntry("localAgentRequestCreationPrepared", false)
                .containsEntry("queueHandoffPrepared", false)
                .containsEntry("pushHandoffPrepared", false)
                .containsEntry("claimHandoffPrepared", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentClaimReadinessPreviewShapesReadyQueueWithoutClaimOrSnapshotDryRun() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> queue = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1"),
                Map.entry("status", "READY_LOCAL_AGENT_QUEUE_DISABLED"),
                Map.entry("queueHandoffPrepared", true),
                Map.entry("pushHandoffPrepared", true),
                Map.entry("claimHandoffPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true),
                Map.entry("approvalActions", List.of(
                        Map.of("action", "APPROVE_SNAPSHOT_WRITING_DRY_RUN", "enabled", false),
                        Map.of("action", "DENY_SNAPSHOT_WRITING_DRY_RUN", "enabled", false)
                ))
        );

        var preview = service.localAgentClaimReadinessPreview(repositoryId, spaceId, agentId, workspaceId, queue);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1")
                .containsEntry("status", "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentQueueProvided", true)
                .containsEntry("queueHandoffPrepared", true)
                .containsEntry("pushHandoffPrepared", true)
                .containsEntry("claimHandoffPrepared", true)
                .containsEntry("snapshotDryRunReadinessPrepared", true)
                .containsEntry("sourceLocalAgentQueueSchema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1")
                .containsEntry("sourceLocalAgentQueueStatus", "READY_LOCAL_AGENT_QUEUE_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVED_HELD_PREVIEW")
                .containsEntry("diffValidationPassed", true)
                .containsEntry("requestEnvelopePrepared", true)
                .containsEntry("nonWritingPreflightPassed", true)
                .containsEntry("browserReviewReady", true)
                .containsEntry("userApprovalRequired", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentClaimReadiness"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("snapshotDryRunReadinessPrepared", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentClaimReadinessPreviewBlocksMissingQueue() {
        var preview = service.localAgentClaimReadinessPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_QUEUE_NOT_PROVIDED")
                .containsEntry("localAgentQueueProvided", false)
                .containsEntry("queueHandoffPrepared", false)
                .containsEntry("pushHandoffPrepared", false)
                .containsEntry("claimHandoffPrepared", false)
                .containsEntry("snapshotDryRunReadinessPrepared", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false)
                .containsEntry("approvalBypassAllowed", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentSnapshotDryRunPreviewShapesReadyClaimReadinessWithoutExecution() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> claimReadiness = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1"),
                Map.entry("status", "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED"),
                Map.entry("queueHandoffPrepared", true),
                Map.entry("pushHandoffPrepared", true),
                Map.entry("claimHandoffPrepared", true),
                Map.entry("snapshotDryRunReadinessPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true)
        );

        var preview = service.localAgentSnapshotDryRunPreview(repositoryId, spaceId, agentId, workspaceId, claimReadiness);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1")
                .containsEntry("status", "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentClaimReadinessProvided", true)
                .containsEntry("snapshotDryRunReadinessPrepared", true)
                .containsEntry("patchDryRunExecutionObservationPrepared", true)
                .containsEntry("sourceLocalAgentClaimReadinessStatus", "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN")
                .containsEntry("approvalState", "APPROVED_HELD_PREVIEW")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("localAgentToolRequestCreated", false)
                .containsEntry("durableLocalAgentRequestCreated", false)
                .containsEntry("enqueueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("patchDryRunExecuted", false)
                .containsEntry("patchDryRunObservationRecorded", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentSnapshotDryRunObservation"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-observation.v1")
                .containsEntry("status", "READY_DISABLED")
                .containsEntry("patchDryRunExecutionObservationPrepared", true)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("patchDryRunExecuted", false)
                .containsEntry("patchDryRunObservationRecorded", false)
                .containsEntry("mutationEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentSnapshotDryRunPreviewBlocksMissingClaimReadiness() {
        var preview = service.localAgentSnapshotDryRunPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_CLAIM_READINESS_NOT_PROVIDED")
                .containsEntry("localAgentClaimReadinessProvided", false)
                .containsEntry("snapshotDryRunReadinessPrepared", false)
                .containsEntry("patchDryRunExecutionObservationPrepared", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("patchDryRunExecuted", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentDryRunResultPreviewShapesReadySnapshotDryRunWithoutRecordingResult() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> snapshotDryRun = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1"),
                Map.entry("status", "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED"),
                Map.entry("snapshotDryRunReadinessPrepared", true),
                Map.entry("patchDryRunExecutionObservationPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("diffValidationPassed", true),
                Map.entry("requestEnvelopePrepared", true),
                Map.entry("nonWritingPreflightPassed", true),
                Map.entry("browserReviewReady", true),
                Map.entry("userApprovalRequired", true)
        );

        var preview = service.localAgentDryRunResultPreview(repositoryId, spaceId, agentId, workspaceId, snapshotDryRun);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1")
                .containsEntry("status", "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentSnapshotDryRunProvided", true)
                .containsEntry("dryRunResultAnalysisPrepared", true)
                .containsEntry("failureLogAnalysisPrepared", true)
                .containsEntry("retryDecisionPrepared", true)
                .containsEntry("sourceLocalAgentSnapshotDryRunStatus", "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED")
                .containsEntry("dryRunResultStatus", "NOT_EXECUTED_PREVIEW")
                .containsEntry("dryRunFailureCode", "NOT_EXECUTED")
                .containsEntry("dryRunSucceeded", false)
                .containsEntry("dryRunFailed", false)
                .containsEntry("contextMismatchDetected", false)
                .containsEntry("unsafePatchDetected", false)
                .containsEntry("retryRecommended", true)
                .containsEntry("retryDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT")
                .containsEntry("replanRequired", false)
                .containsEntry("userReviewRequired", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("patchDryRunExecuted", false)
                .containsEntry("patchDryRunObservationRecorded", false)
                .containsEntry("dryRunResultRecorded", false)
                .containsEntry("failureLogAnalysisRecorded", false)
                .containsEntry("retryDecisionRecorded", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentDryRunResult"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result.v1")
                .containsEntry("status", "NOT_EXECUTED_PREVIEW")
                .containsEntry("dryRunResultRecorded", false)
                .containsEntry("mutationEnabled", false);
        assertThat((Map<String, Object>) preview.get("failureLogAnalysis"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-failure-log-analysis.v1")
                .containsEntry("status", "WAITING_FOR_RESULT_DISABLED")
                .containsEntry("analysisRecorded", false);
        assertThat((Map<String, Object>) preview.get("retryDecisionPreview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-decision.v1")
                .containsEntry("status", "WAITING_FOR_RESULT_DISABLED")
                .containsEntry("retryExecutionEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentDryRunResultPreviewBlocksMissingSnapshotDryRun() {
        var preview = service.localAgentDryRunResultPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_SNAPSHOT_DRY_RUN_NOT_PROVIDED")
                .containsEntry("localAgentSnapshotDryRunProvided", false)
                .containsEntry("dryRunResultAnalysisPrepared", false)
                .containsEntry("failureLogAnalysisPrepared", false)
                .containsEntry("retryDecisionPrepared", false)
                .containsEntry("dryRunResultStatus", "UNAVAILABLE")
                .containsEntry("dryRunFailureCode", "NO_RESULT")
                .containsEntry("retryRecommended", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("dryRunResultRecorded", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRetryInputPreviewShapesReadyDryRunResultWithoutCreatingRetry() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> dryRunResult = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1"),
                Map.entry("status", "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED"),
                Map.entry("dryRunResultAnalysisPrepared", true),
                Map.entry("failureLogAnalysisPrepared", true),
                Map.entry("retryDecisionPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("dryRunResultStatus", "NOT_EXECUTED_PREVIEW"),
                Map.entry("dryRunFailureCode", "NOT_EXECUTED"),
                Map.entry("retryRecommended", true),
                Map.entry("retryDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT"),
                Map.entry("replanRequired", false)
        );

        var preview = service.localAgentRetryInputPreview(repositoryId, spaceId, agentId, workspaceId, dryRunResult);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1")
                .containsEntry("status", "READY_RETRY_INPUT_REPLAN_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentDryRunResultProvided", true)
                .containsEntry("retryInputPrepared", true)
                .containsEntry("boundedRetryPatchInputPrepared", true)
                .containsEntry("replanDecisionPrepared", true)
                .containsEntry("sourceLocalAgentDryRunResultStatus", "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED")
                .containsEntry("dryRunResultStatus", "NOT_EXECUTED_PREVIEW")
                .containsEntry("dryRunFailureCode", "NOT_EXECUTED")
                .containsEntry("retryRecommended", true)
                .containsEntry("sourceRetryDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT")
                .containsEntry("retryInputDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT")
                .containsEntry("replanRequired", false)
                .containsEntry("userVisibleDecision", "WAIT_FOR_DRY_RUN_RESULT_BEFORE_RETRY_OR_REPLAN")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("dryRunResultRecorded", false)
                .containsEntry("failureLogAnalysisRecorded", false)
                .containsEntry("retryDecisionRecorded", false)
                .containsEntry("retryPatchGenerated", false)
                .containsEntry("retryRequestCreationEnabled", false)
                .containsEntry("retryExecutionEnabled", false)
                .containsEntry("replanExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentRetryInput"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input.v1")
                .containsEntry("status", "WAITING_FOR_RESULT_DISABLED")
                .containsEntry("retryPatchGenerated", false)
                .containsEntry("retryExecutionEnabled", false);
        assertThat((Map<String, Object>) preview.get("replanPreview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-replan-preview.v1")
                .containsEntry("status", "WAITING_FOR_RESULT_DISABLED")
                .containsEntry("replanExecutionEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRetryInputPreviewBlocksMissingDryRunResult() {
        var preview = service.localAgentRetryInputPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_DRY_RUN_RESULT_NOT_PROVIDED")
                .containsEntry("localAgentDryRunResultProvided", false)
                .containsEntry("retryInputPrepared", false)
                .containsEntry("boundedRetryPatchInputPrepared", false)
                .containsEntry("replanDecisionPrepared", false)
                .containsEntry("retryPatchGenerated", false)
                .containsEntry("retryRequestCreationEnabled", false)
                .containsEntry("retryExecutionEnabled", false)
                .containsEntry("replanExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRetryProposalPreviewShapesReadyRetryInputWithoutGeneratingRetryPatch() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> retryInput = Map.ofEntries(
                Map.entry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1"),
                Map.entry("status", "READY_RETRY_INPUT_REPLAN_DISABLED"),
                Map.entry("retryInputPrepared", true),
                Map.entry("boundedRetryPatchInputPrepared", true),
                Map.entry("replanDecisionPrepared", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("approvalKind", "SNAPSHOT_WRITING_DRY_RUN"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("dryRunResultStatus", "NOT_EXECUTED_PREVIEW"),
                Map.entry("dryRunFailureCode", "NOT_EXECUTED"),
                Map.entry("retryRecommended", true),
                Map.entry("retryInputDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT"),
                Map.entry("replanRequired", false)
        );

        var preview = service.localAgentRetryProposalPreview(repositoryId, spaceId, agentId, workspaceId, retryInput);

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1")
                .containsEntry("status", "READY_RETRY_PROPOSAL_FINAL_STOP_DISABLED")
                .containsEntry("repositoryId", repositoryId)
                .containsEntry("spaceId", spaceId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("localAgentRetryInputProvided", true)
                .containsEntry("retryProposalPrepared", true)
                .containsEntry("boundedRetryPatchProposalPrepared", true)
                .containsEntry("finalStopDecisionPrepared", true)
                .containsEntry("sourceLocalAgentRetryInputStatus", "READY_RETRY_INPUT_REPLAN_DISABLED")
                .containsEntry("dryRunResultStatus", "NOT_EXECUTED_PREVIEW")
                .containsEntry("dryRunFailureCode", "NOT_EXECUTED")
                .containsEntry("retryRecommended", true)
                .containsEntry("sourceRetryInputDecision", "WAIT_FOR_ACTUAL_DRY_RUN_RESULT")
                .containsEntry("replanRequired", false)
                .containsEntry("userVisibleDecision", "WAIT_FOR_RETRY_PATCH_PROPOSAL")
                .containsEntry("finalStopDecision", "WAIT_FOR_RETRY_PATCH_PROPOSAL")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("snapshotCreationEnabled", false)
                .containsEntry("patchDryRunExecutionEnabled", false)
                .containsEntry("dryRunResultRecorded", false)
                .containsEntry("failureLogAnalysisRecorded", false)
                .containsEntry("retryDecisionRecorded", false)
                .containsEntry("retryPatchGenerated", false)
                .containsEntry("retryPatchProposalGenerated", false)
                .containsEntry("retryRequestCreationEnabled", false)
                .containsEntry("retryExecutionEnabled", false)
                .containsEntry("replanExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("testExecutionEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("finalPublicationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        assertThat(preview.get("targetFiles")).isEqualTo(List.of("src/App.java"));
        assertThat((Map<String, Object>) preview.get("localAgentRetryPatchProposal"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-patch-proposal.v1")
                .containsEntry("status", "WAITING_FOR_RETRY_PATCH_DISABLED")
                .containsEntry("retryPatchGenerated", false)
                .containsEntry("retryExecutionEnabled", false);
        assertThat((Map<String, Object>) preview.get("finalStopDecisionPreview"))
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-final-stop-decision-preview.v1")
                .containsEntry("status", "WAITING_FOR_ACTUAL_RESULT_DISABLED")
                .containsEntry("replanExecutionEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
    }

    @Test
    void localAgentRetryProposalPreviewBlocksMissingRetryInput() {
        var preview = service.localAgentRetryProposalPreview(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        assertThat(preview)
                .containsEntry("schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1")
                .containsEntry("status", "LOCAL_AGENT_RETRY_INPUT_NOT_PROVIDED")
                .containsEntry("localAgentRetryInputProvided", false)
                .containsEntry("retryProposalPrepared", false)
                .containsEntry("boundedRetryPatchProposalPrepared", false)
                .containsEntry("finalStopDecisionPrepared", false)
                .containsEntry("retryPatchGenerated", false)
                .containsEntry("retryPatchProposalGenerated", false)
                .containsEntry("retryRequestCreationEnabled", false)
                .containsEntry("retryExecutionEnabled", false)
                .containsEntry("replanExecutionEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("partialReindexEnabled", false);
        org.mockito.Mockito.verifyNoInteractions(timelineRepository);
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
        assertThat(result.loopState()).isEqualTo("OBSERVATION_RECEIVED");
        assertThat(result.stateSnapshot()).containsEntry("state", "OBSERVATION_RECEIVED");
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
        assertThat(result.loopState()).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(result.stateSnapshot()).containsEntry("state", "WAITING_FOR_APPROVAL");
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
        assertThat(result.loopState()).isEqualTo("APPROVED_HELD");
        assertThat(result.stateSnapshot()).containsEntry("state", "APPROVED_HELD");
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
        assertThat(result.loopState()).isEqualTo("CREATED");
        assertThat(result.stateSnapshot()).containsEntry("state", "CREATED");
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
