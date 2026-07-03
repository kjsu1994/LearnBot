package com.learnbot.web;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLocalPatchRequest;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopPreviewRequest;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.CodeAgentValidatedPatchDryRunPreviewRequest;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopApprovalRequestPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopFinalResultPublicationPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopM8EntryReadinessResponse;
import com.learnbot.dto.loop.CodeAgentLoopObservationContinuationRequest;
import com.learnbot.dto.loop.CodeAgentLoopObservationContinuationResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalRequestResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalPayloadRequest;
import com.learnbot.dto.loop.CodeAgentLoopReleaseReviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunRequest;
import com.learnbot.dto.loop.CodeAgentLoopRunResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopSelectedToolEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopSideEffectBoundaryResponse;
import com.learnbot.dto.loop.CodeAgentLoopApprovalActionPersistencePreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopApprovalActionPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopApprovalRecordPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopApprovalDecisionPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopApprovalDecisionPersistencePreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopHeldRequestReviewPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopApprovalIntentPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopApprovalRequestCreationPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentClaimReadinessPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentDryRunResultPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentRequestEnvelopePreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentRequestCreationPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentQueuePreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentRetryInputPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentRetryProposalPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopLocalAgentSnapshotDryRunPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopSubmissionPlanRequest;
import com.learnbot.dto.loop.CodeAgentLoopSubmissionPlanResponse;
import com.learnbot.dto.loop.CodeAgentLoopToolSelectionResponse;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.AppUser;
import com.learnbot.service.CodeAgentApplyService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodeIndexingService;
import com.learnbot.service.agentloop.CodeAgentLoopRunnerService;
import com.learnbot.service.agentloop.CodeAgentLoopRunService;
import com.learnbot.service.agentloop.CodeAgentLoopToolSelectionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentControllerTest {
    @Test
    void mutationPolicyDefaultsToUserLocalAgentBoundaryWithoutEnablingMutationTools() {
        LearnBotProperties properties = new LearnBotProperties();
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                mock(CodeIndexingService.class),
                mock(AuthService.class),
                mock(CurrentUserProvider.class),
                properties
        );

        var policy = controller.mutationPolicy();

        assertThat(policy.intendedExecutionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(policy.localAgentMutationEnabled()).isFalse();
        assertThat(policy.serverLocalMutationEnabled()).isFalse();
        assertThat(policy.futureLocalAgentTools()).containsExactly(
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolName.COMMAND_RUN_ALLOWED,
                LocalAgentToolName.ROLLBACK_RESTORE
        );
        assertThat(policy.message()).contains("Patch proposals are available");
    }

    @Test
    void loopPreviewResolvesRepositorySpaceAndDelegatesWithoutStartingMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);

        controller.loopPreview(new CodeAgentLoopPreviewRequest(
                repositoryId,
                requestedSpaceId,
                "fix this bug",
                7
        ));

        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).preview(userId, repositoryId, repositorySpaceId, "fix this bug", 7);
    }

    @Test
    void startLoopRunResolvesRepositorySpaceAndDelegatesToRunService() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopRunService loopRunService = mock(CodeAgentLoopRunService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                loopRunService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopRunService.start(userId, repositoryId, repositorySpaceId, "fix this bug", 8, agentId, workspaceId))
                .thenReturn(new CodeAgentLoopRunResponse(
                        "learnbot.server.code-agent.loop-run.v1",
                        loopId,
                        repositoryId,
                        repositorySpaceId,
                        agentId,
                        workspaceId,
                        "fix this bug",
                        8,
                        "READ_ONLY_QUEUED",
                        true,
                        true,
                        true,
                        false,
                        true,
                        "Wait for the Local Agent read-only observation.",
                        null,
                        List.of()
                ));

        CodeAgentLoopRunResponse response = controller.startLoopRun(new CodeAgentLoopRunRequest(
                repositoryId,
                requestedSpaceId,
                "fix this bug",
                8,
                agentId,
                workspaceId
        ));

        assertThat(response.schema()).isEqualTo("learnbot.server.code-agent.loop-run.v1");
        assertThat(response.readOnlyQueued()).isTrue();
        assertThat(response.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopRunService).start(userId, repositoryId, repositorySpaceId, "fix this bug", 8, agentId, workspaceId);
    }

    @Test
    void loopSubmissionPlanResolvesRepositorySpaceAndDelegatesWithoutExecutingPreview() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopSubmissionPlanResponse expected = new CodeAgentLoopSubmissionPlanResponse(
                "learnbot.server.code-agent.loop-submission-plan.v1",
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                "fix this bug",
                6,
                "POST",
                "/api/code-agent/loop/preview",
                Map.of("repositoryId", repositoryId, "instruction", "fix this bug", "maxSteps", 6),
                Map.of("schema", "learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1"),
                Map.of("schema", "learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1"),
                List.of("POST /api/code-agent/loop/runner/preview"),
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
                "disabled"
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.submissionPlan(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                "fix this bug",
                6,
                Map.of("status", "APPROVAL_HANDOFF_PREPARED")
        )).thenReturn(expected);

        var result = controller.loopSubmissionPlan(new CodeAgentLoopSubmissionPlanRequest(
                repositoryId,
                requestedSpaceId,
                "fix this bug",
                6,
                agentId,
                workspaceId,
                Map.of("status", "APPROVAL_HANDOFF_PREPARED")
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.enabled()).isFalse();
        assertThat(result.networkCallEnabled()).isFalse();
        assertThat(result.loopPreviewExecutionEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).submissionPlan(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                "fix this bug",
                6,
                Map.of("status", "APPROVAL_HANDOFF_PREPARED")
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalIntentPreviewResolvesRepositorySpaceAndDelegatesWithoutCreatingRequests() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> review = Map.of("status", "READY_BROWSER_REVIEW_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1",
                "status", "READY_APPROVAL_INTENT_DISABLED",
                "requestCreationEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalIntentPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                review
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalIntentPreview(new CodeAgentLoopApprovalIntentPreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                review
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("requestCreationEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalIntentPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                review
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalRequestCreationPreviewResolvesRepositorySpaceAndDelegatesWithoutPersistingApproval() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> intent = Map.of("status", "READY_APPROVAL_INTENT_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1",
                "status", "READY_APPROVAL_REQUEST_CREATION_DISABLED",
                "approvalPersistenceEnabled", false,
                "requestCreationEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalRequestCreationPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                intent
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalRequestCreationPreview(new CodeAgentLoopApprovalRequestCreationPreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                intent
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("approvalPersistenceEnabled", false);
        assertThat(result).containsEntry("requestCreationEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalRequestCreationPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                intent
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalDecisionPreviewResolvesRepositorySpaceAndDelegatesWithoutRecordingDecision() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> requestCreation = Map.of("status", "READY_APPROVAL_REQUEST_CREATION_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1",
                "status", "READY_APPROVAL_DECISION_DISABLED",
                "approvalDecisionPersistenceEnabled", false,
                "requestCreationEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalDecisionPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                requestCreation
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalDecisionPreview(new CodeAgentLoopApprovalDecisionPreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                requestCreation
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("approvalDecisionPersistenceEnabled", false);
        assertThat(result).containsEntry("requestCreationEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalDecisionPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                requestCreation
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalDecisionPersistencePreviewResolvesRepositorySpaceAndDelegatesWithoutPersistingDecision() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> decision = Map.of("status", "READY_APPROVAL_DECISION_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1",
                "status", "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED",
                "approvalDecisionPersistenceEnabled", false,
                "approvalDecisionPersisted", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalDecisionPersistencePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                decision
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalDecisionPersistencePreview(new CodeAgentLoopApprovalDecisionPersistencePreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                decision
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("approvalDecisionPersistenceEnabled", false);
        assertThat(result).containsEntry("approvalDecisionPersisted", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalDecisionPersistencePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                decision
        );
    }

    @Test
    void loopRunnerPatchDryRunHeldRequestReviewPreviewResolvesRepositorySpaceAndDelegatesWithoutCreatingHeldRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> persistence = Map.of("status", "READY_APPROVAL_DECISION_PERSISTENCE_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1",
                "status", "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED",
                "heldRequestReviewEnabled", false,
                "heldRequestCreated", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.heldRequestReviewActionPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                persistence
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunHeldRequestReviewPreview(new CodeAgentLoopHeldRequestReviewPreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                persistence
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("heldRequestReviewEnabled", false);
        assertThat(result).containsEntry("heldRequestCreated", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).heldRequestReviewActionPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                persistence
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalActionPreviewResolvesRepositorySpaceAndDelegatesWithoutPersistingAction() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> heldReview = Map.of("status", "READY_HELD_REQUEST_REVIEW_ACTION_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1",
                "status", "READY_APPROVAL_ACTION_DISABLED",
                "approvalActionEnabled", false,
                "approvalActionPersisted", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalActionPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                heldReview
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalActionPreview(new CodeAgentLoopApprovalActionPreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                heldReview
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("approvalActionEnabled", false);
        assertThat(result).containsEntry("approvalActionPersisted", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalActionPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                heldReview
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalActionPersistencePreviewResolvesRepositorySpaceAndDelegatesWithoutPersistingAction() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> approvalAction = Map.of("status", "READY_APPROVAL_ACTION_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1",
                "status", "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED",
                "approvalActionPersistenceEnabled", false,
                "approvalActionPersisted", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalActionPersistencePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                approvalAction
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalActionPersistencePreview(new CodeAgentLoopApprovalActionPersistencePreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                approvalAction
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("approvalActionPersistenceEnabled", false);
        assertThat(result).containsEntry("approvalActionPersisted", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalActionPersistencePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                approvalAction
        );
    }

    @Test
    void loopRunnerPatchDryRunApprovalRecordPreviewResolvesRepositorySpaceAndDelegatesWithoutCreatingRecordOrRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> actionPersistence = Map.of("status", "READY_APPROVAL_ACTION_PERSISTENCE_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1",
                "status", "READY_APPROVAL_RECORD_DISABLED",
                "approvalRecordCreationEnabled", false,
                "requestCreationEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.approvalRecordPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                actionPersistence
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunApprovalRecordPreview(new CodeAgentLoopApprovalRecordPreviewRequest(
                repositoryId,
                requestedSpaceId,
                agentId,
                workspaceId,
                actionPersistence
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("approvalRecordCreationEnabled", false);
        assertThat(result).containsEntry("requestCreationEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).approvalRecordPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                actionPersistence
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentRequestEnvelopePreviewResolvesRepositorySpaceAndDelegatesWithoutCreatingRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> approvalRecord = Map.of("status", "READY_APPROVAL_RECORD_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1",
                "status", "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED",
                "requestCreationEnabled", false,
                "localAgentToolRequestCreated", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentRequestEnvelopePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                approvalRecord
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentRequestEnvelopePreview(
                new CodeAgentLoopLocalAgentRequestEnvelopePreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        approvalRecord
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("requestCreationEnabled", false);
        assertThat(result).containsEntry("localAgentToolRequestCreated", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentRequestEnvelopePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                approvalRecord
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentRequestCreationPreviewResolvesRepositorySpaceAndDelegatesWithoutCreatingRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> requestEnvelope = Map.of("status", "READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1",
                "status", "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED",
                "requestCreationEnabled", false,
                "durableLocalAgentRequestCreated", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentRequestCreationPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                requestEnvelope
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentRequestCreationPreview(
                new CodeAgentLoopLocalAgentRequestCreationPreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        requestEnvelope
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("requestCreationEnabled", false);
        assertThat(result).containsEntry("durableLocalAgentRequestCreated", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentRequestCreationPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                requestEnvelope
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentQueuePreviewResolvesRepositorySpaceAndDelegatesWithoutQueuePushOrClaim() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> requestCreation = Map.of("status", "READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1",
                "status", "READY_LOCAL_AGENT_QUEUE_DISABLED",
                "enqueueEnabled", false,
                "pushEnabled", false,
                "claimEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentQueuePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                requestCreation
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentQueuePreview(
                new CodeAgentLoopLocalAgentQueuePreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        requestCreation
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("enqueueEnabled", false);
        assertThat(result).containsEntry("pushEnabled", false);
        assertThat(result).containsEntry("claimEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentQueuePreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                requestCreation
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentClaimReadinessPreviewResolvesRepositorySpaceAndDelegatesWithoutClaimOrSnapshot() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> queue = Map.of("status", "READY_LOCAL_AGENT_QUEUE_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1",
                "status", "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED",
                "claimEnabled", false,
                "snapshotCreationEnabled", false,
                "patchDryRunExecutionEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentClaimReadinessPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                queue
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentClaimReadinessPreview(
                new CodeAgentLoopLocalAgentClaimReadinessPreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        queue
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("claimEnabled", false);
        assertThat(result).containsEntry("snapshotCreationEnabled", false);
        assertThat(result).containsEntry("patchDryRunExecutionEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentClaimReadinessPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                queue
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentSnapshotDryRunPreviewResolvesRepositorySpaceAndDelegatesWithoutExecution() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> claimReadiness = Map.of("status", "READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1",
                "status", "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED",
                "snapshotCreationEnabled", false,
                "patchDryRunExecutionEnabled", false,
                "patchDryRunExecuted", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentSnapshotDryRunPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                claimReadiness
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentSnapshotDryRunPreview(
                new CodeAgentLoopLocalAgentSnapshotDryRunPreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        claimReadiness
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("snapshotCreationEnabled", false);
        assertThat(result).containsEntry("patchDryRunExecutionEnabled", false);
        assertThat(result).containsEntry("patchDryRunExecuted", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentSnapshotDryRunPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                claimReadiness
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentDryRunResultPreviewResolvesRepositorySpaceAndDelegatesWithoutResultRecording() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> snapshotDryRun = Map.of("status", "READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1",
                "status", "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED",
                "dryRunResultRecorded", false,
                "retryDecisionRecorded", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentDryRunResultPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                snapshotDryRun
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentDryRunResultPreview(
                new CodeAgentLoopLocalAgentDryRunResultPreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        snapshotDryRun
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("dryRunResultRecorded", false);
        assertThat(result).containsEntry("retryDecisionRecorded", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentDryRunResultPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                snapshotDryRun
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentRetryInputPreviewResolvesRepositorySpaceAndDelegatesWithoutRetryCreation() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> dryRunResult = Map.of("status", "READY_DRY_RUN_RESULT_ANALYSIS_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1",
                "status", "READY_RETRY_INPUT_REPLAN_DISABLED",
                "retryPatchGenerated", false,
                "retryExecutionEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentRetryInputPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                dryRunResult
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentRetryInputPreview(
                new CodeAgentLoopLocalAgentRetryInputPreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        dryRunResult
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("retryPatchGenerated", false);
        assertThat(result).containsEntry("retryExecutionEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentRetryInputPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                dryRunResult
        );
    }

    @Test
    void loopRunnerPatchDryRunLocalAgentRetryProposalPreviewResolvesRepositorySpaceAndDelegatesWithoutRetryPatchGeneration() {
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> retryInput = Map.of("status", "READY_RETRY_INPUT_REPLAN_DISABLED");
        Map<String, Object> expected = Map.of(
                "schema", "learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1",
                "status", "READY_RETRY_PROPOSAL_FINAL_STOP_DISABLED",
                "retryPatchGenerated", false,
                "retryPatchProposalGenerated", false,
                "retryExecutionEnabled", false,
                "mutationEnabled", false
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.localAgentRetryProposalPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                retryInput
        )).thenReturn(expected);

        var result = controller.loopRunnerPatchDryRunLocalAgentRetryProposalPreview(
                new CodeAgentLoopLocalAgentRetryProposalPreviewRequest(
                        repositoryId,
                        requestedSpaceId,
                        agentId,
                        workspaceId,
                        retryInput
                )
        );

        assertThat(result).isSameAs(expected);
        assertThat(result).containsEntry("retryPatchGenerated", false);
        assertThat(result).containsEntry("retryPatchProposalGenerated", false);
        assertThat(result).containsEntry("retryExecutionEnabled", false);
        assertThat(result).containsEntry("mutationEnabled", false);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).localAgentRetryProposalPreview(
                repositoryId,
                repositorySpaceId,
                agentId,
                workspaceId,
                retryInput
        );
    }

    @Test
    void loopTimelinesResolveRepositorySpaceAndReturnAuditOnlyHistory() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        List<CodeAgentLoopTimelineSummary> expected = List.of();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.recentTimelines(userId, repositoryId, 3)).thenReturn(expected);

        var result = controller.loopTimelines(repositoryId, 3);

        assertThat(result).isSameAs(expected);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).recentTimelines(userId, repositoryId, 3);
    }

    @Test
    void loopNextActionResolvesRepositorySpaceAndReturnsReadOnlyDecision() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopNextActionResponse expected = new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                java.util.Map.of()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.nextAction(userId, repositoryId, loopId)).thenReturn(expected);

        var result = controller.loopNextAction(repositoryId, loopId);

        assertThat(result).isSameAs(expected);
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).nextAction(userId, repositoryId, loopId);
    }

    @Test
    void loopRunnerPreviewResolvesRepositorySpaceAndDelegatesWithoutCreatingRequests() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopRunnerService loopRunnerService = mock(CodeAgentLoopRunnerService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                loopRunnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopRunnerPreviewResponse expected = new CodeAgentLoopRunnerPreviewResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "PREPARED_READ_ONLY_CANDIDATE",
                "Prepared.",
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
                java.util.Map.of("requestCreationEnabled", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopRunnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerPreview(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopRunnerService).previewNextStep(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerFinalResultPublicationPreviewResolvesRepositorySpaceAndDelegatesWithoutPublishing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopRunnerService loopRunnerService = mock(CodeAgentLoopRunnerService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                loopRunnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopFinalResultPublicationPreviewResponse expected = new CodeAgentLoopFinalResultPublicationPreviewResponse(
                loopId,
                repositoryId,
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "READY_FINAL_RESULT_PUBLICATION_DISABLED",
                "Final-result handoff is audit-only.",
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
                java.util.Map.of("schema", "learnbot.code-agent.approved-execution-flow-completed-handoff.v1"),
                java.util.Map.of("schema", "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1"),
                null
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopRunnerService.previewFinalResultPublication(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerFinalResultPublicationPreview(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.finalResultReady()).isTrue();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementSaveEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopRunnerService).previewFinalResultPublication(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerM8EntryReadinessResolvesRepositorySpaceAndDelegatesWithoutEnablingM8Work() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopRunnerService loopRunnerService = mock(CodeAgentLoopRunnerService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                loopRunnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopM8EntryReadinessResponse expected = new CodeAgentLoopM8EntryReadinessResponse(
                loopId,
                repositoryId,
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED",
                "M7_CLOSURE_READY",
                "M8_ENTRY_READY",
                "M8 can start, but controls remain disabled.",
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                null,
                null,
                null
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopRunnerService.previewM8EntryReadiness(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerM8EntryReadiness(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result.m8EntryDecision()).isEqualTo("M8_ENTRY_READY");
        assertThat(result.m8WorkEnabled()).isFalse();
        assertThat(result.publicationEnabled()).isFalse();
        assertThat(result.acknowledgementSaveEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopRunnerService).previewM8EntryReadiness(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerEnqueueReadOnlyResolvesRepositorySpaceAndDelegatesToRunner() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopRunnerService loopRunnerService = mock(CodeAgentLoopRunnerService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                loopRunnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopRunnerEnqueueResponse expected = new CodeAgentLoopRunnerEnqueueResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "ENQUEUED_READ_ONLY_OBSERVATION",
                "Queued.",
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                null,
                null
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopRunnerService.enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerEnqueueReadOnly(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopRunnerService).enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerReleaseReviewResolvesRepositorySpaceAndDelegatesWithoutEnablingMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopRunnerService loopRunnerService = mock(CodeAgentLoopRunnerService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                loopRunnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopRunnerPreviewResponse preview = new CodeAgentLoopRunnerPreviewResponse(
                loopId,
                repositoryId,
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "WAIT_RELEASE_GATE_READINESS_REFRESHED",
                "Readiness refreshed.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                java.util.Map.of("sourceRequestId", sourceRequestId.toString()),
                null,
                null,
                java.util.Map.of("mutationAllowed", false)
        );
        LocalAgentPatchReleaseBoundaryResponse boundary = new LocalAgentPatchReleaseBoundaryResponse(
                sourceRequestId,
                "RELEASE_REFUSED_GATE_DISABLED",
                "REFUSAL_ONLY",
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
                false,
                List.of("release gate is disabled"),
                "Release gate is disabled.",
                java.util.Map.of("status", "BLOCKED_RELEASE_DISABLED"),
                java.util.Map.of("releaseGateEnabled", false),
                null
        );
        CodeAgentLoopReleaseReviewResponse expected = new CodeAgentLoopReleaseReviewResponse(
                loopId,
                repositoryId,
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "RELEASE_READINESS_REFRESHED_RELEASE_GATED",
                "RELEASE_REVIEW_REFUSED_GATE_DISABLED",
                "Release review recorded the disabled release boundary.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                java.util.Map.of("sourceRequestId", sourceRequestId.toString()),
                preview,
                boundary
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopRunnerService.reviewReleaseGate(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerReleaseReview(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.runnerDecision()).isEqualTo("RELEASE_REVIEW_REFUSED_GATE_DISABLED");
        assertThat(result.boundary().releaseGateEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopRunnerService).reviewReleaseGate(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerSelectToolPreviewResolvesRepositorySpaceAndDelegatesWithoutExecuting() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopToolSelectionResponse expected = new CodeAgentLoopToolSelectionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "MODEL_SELECTED_READ_ONLY_CANDIDATE",
                "Selected.",
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                java.util.Map.of("toolName", "git.status"),
                java.util.Map.of("mutationAllowed", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.selectNextToolPreview(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerSelectToolPreview(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).selectNextToolPreview(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerEnqueueSelectedReadOnlyResolvesRepositorySpaceAndDelegatesToSelectionRunner() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopSelectedToolEnqueueResponse expected = new CodeAgentLoopSelectedToolEnqueueResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION",
                "Queued.",
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
                false,
                null,
                null
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.enqueueSelectedReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerEnqueueSelectedReadOnly(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).enqueueSelectedReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerContinueAfterObservationResolvesRepositorySpaceAndDelegatesWithoutEnablingMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopObservationContinuationResponse expected = new CodeAgentLoopObservationContinuationResponse(
                loopId,
                repositoryId,
                requestId,
                "SUCCEEDED",
                "NEXT_MODEL_TOOL_PREVIEW_READY",
                "Continued.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                1,
                6,
                5,
                false,
                null,
                null,
                null
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.continueAfterReadOnlyObservation(userId, repositoryId, loopId, agentId, workspaceId, requestId))
                .thenReturn(expected);

        var result = controller.loopRunnerContinueAfterObservation(new CodeAgentLoopObservationContinuationRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId,
                requestId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.continuationDecision()).isEqualTo("NEXT_MODEL_TOOL_PREVIEW_READY");
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).continueAfterReadOnlyObservation(userId, repositoryId, loopId, agentId, workspaceId, requestId);
    }

    @Test
    void loopRunnerSideEffectBoundaryPreviewResolvesRepositorySpaceAndDelegatesWithoutQueueing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopSideEffectBoundaryResponse expected = new CodeAgentLoopSideEffectBoundaryResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "SIDE_EFFECTFUL_PATCH_REQUIRES_APPROVAL_RELEASE",
                "Requires approval.",
                true,
                true,
                true,
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
                null,
                java.util.Map.of("toolName", "patch.apply"),
                java.util.Map.of("releaseGateEnabled", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.previewSideEffectBoundary(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerSideEffectBoundaryPreview(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.releaseGateEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).previewSideEffectBoundary(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerPatchApprovalPreviewResolvesRepositorySpaceAndDelegatesWithoutQueueing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopApprovalRequestPreviewResponse expected = new CodeAgentLoopApprovalRequestPreviewResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "PREPARED_PATCH_APPROVAL_REQUEST_PREVIEW",
                "Prepared.",
                true,
                true,
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
                null,
                null,
                java.util.Map.of("mutationAllowed", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.previewPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerPatchApprovalPreview(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.approvalRequestPrepared()).isTrue();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).previewPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerPatchApprovalRequestResolvesRepositorySpaceAndCreatesNonClaimableApprovalRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopPatchApprovalRequestResponse expected = new CodeAgentLoopPatchApprovalRequestResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "CREATED_PATCH_APPROVAL_REQUEST",
                "Created.",
                true,
                true,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                java.util.Map.of("claimEnabled", false, "mutationAllowed", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.createPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(expected);

        var result = controller.loopRunnerPatchApprovalRequest(new CodeAgentLoopRunnerPreviewRequest(
                repositoryId,
                loopId,
                agentId,
                workspaceId
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.approvalRequestCreated()).isTrue();
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).createPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId);
    }

    @Test
    void loopRunnerValidatedPatchApprovalRequestResolvesRepositorySpaceAndDelegatesValidatedPayload() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                toolSelectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        CodeAgentLoopPatchApprovalRequestResponse expected = new CodeAgentLoopPatchApprovalRequestResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "CREATED_VALIDATED_PATCH_APPROVAL_REQUEST",
                "Created.",
                true,
                true,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                java.util.Map.of("claimEnabled", false, "mutationAllowed", false)
        );
        List<String> targetFiles = List.of("src/App.java");
        String diff = "--- a/src/App.java\n+++ b/src/App.java\n";
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(toolSelectionService.createValidatedPatchApprovalRequest(
                userId,
                repositoryId,
                repositorySpaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this",
                diff,
                targetFiles
        )).thenReturn(expected);

        var result = controller.loopRunnerValidatedPatchApprovalRequest(new CodeAgentLoopPatchApprovalPayloadRequest(
                repositoryId,
                requestedSpaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this",
                diff,
                targetFiles
        ));

        assertThat(result).isSameAs(expected);
        assertThat(result.approvalRequestCreated()).isTrue();
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolSelectionService).createValidatedPatchApprovalRequest(
                userId,
                repositoryId,
                repositorySpaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this",
                diff,
                targetFiles
        );
    }

    @Test
    void localPatchRequestCarriesLoopIdIntoPreparedApprovalRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);

        controller.localPatchRequest(new CodeAgentLocalPatchRequest(
                repositoryId,
                requestedSpaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this bug",
                "--- a/src/App.java\n+++ b/src/App.java\n",
                List.of("src/App.java")
        ));

        verify(authService).requireSpace(user, repositorySpaceId);
        verify(localPatchRequestService).prepare(
                repositoryId,
                repositorySpaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                "fix this bug",
                "--- a/src/App.java\n+++ b/src/App.java\n",
                List.of("src/App.java")
        );
    }

    @Test
    void localPatchDryRunPreviewResolvesRepositorySpaceAndDelegatesValidatedHandoff() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> handoff = Map.of(
                "schema", "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
                "status", "READY_DRY_RUN_QUEUE_DISABLED",
                "patchApplyInput", Map.of("dryRunOnly", true, "mutationAllowed", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(localPatchRequestService.previewValidatedDryRunRequest(
                repositoryId,
                repositorySpaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                handoff
        )).thenReturn(Map.of("status", "READY_QUEUE_PREVIEW_DISABLED"));

        Map<String, Object> response = controller.localPatchDryRunPreview(new CodeAgentValidatedPatchDryRunPreviewRequest(
                repositoryId,
                requestedSpaceId,
                loopId,
                agentId,
                workspaceId,
                handoff
        ));

        assertThat(response).containsEntry("status", "READY_QUEUE_PREVIEW_DISABLED");
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(localPatchRequestService).previewValidatedDryRunRequest(
                repositoryId,
                repositorySpaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                handoff
        );
    }

    @Test
    void localPatchDryRunIntentResolvesRepositorySpaceAndPersistsValidatedHandoffIntent() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        Map<String, Object> handoff = Map.of(
                "schema", "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
                "status", "READY_DRY_RUN_QUEUE_DISABLED",
                "patchApplyInput", Map.of("dryRunOnly", true, "mutationAllowed", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(localPatchRequestService.persistValidatedDryRunIntent(
                repositoryId,
                repositorySpaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                handoff
        )).thenReturn(Map.of("status", "PERSISTED_APPROVAL_REQUIRED_NON_CLAIMABLE"));

        Map<String, Object> response = controller.localPatchDryRunIntent(new CodeAgentValidatedPatchDryRunPreviewRequest(
                repositoryId,
                requestedSpaceId,
                loopId,
                agentId,
                workspaceId,
                handoff
        ));

        assertThat(response).containsEntry("status", "PERSISTED_APPROVAL_REQUIRED_NON_CLAIMABLE");
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(localPatchRequestService).persistValidatedDryRunIntent(
                repositoryId,
                repositorySpaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                handoff
        );
    }

    @Test
    void localPatchDryRunIntentEligibilityDelegatesForCurrentUser() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                mock(CodeIndexingService.class),
                mock(AuthService.class),
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(localPatchRequestService.inspectValidatedDryRunIntentEligibility(userId, requestId))
                .thenReturn(Map.of("status", "READY_DRY_RUN_RELEASE_DISABLED"));

        Map<String, Object> response = controller.localPatchDryRunIntentEligibility(requestId);

        assertThat(response).containsEntry("status", "READY_DRY_RUN_RELEASE_DISABLED");
        verify(localPatchRequestService).inspectValidatedDryRunIntentEligibility(userId, requestId);
    }

    @Test
    void localPatchDryRunIntentClaimableDryRunPreviewDelegatesForCurrentUser() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                mock(CodeIndexingService.class),
                mock(AuthService.class),
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(localPatchRequestService.previewValidatedDryRunIntentClaimableDryRun(userId, requestId))
                .thenReturn(Map.of("status", "READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED"));

        Map<String, Object> response = controller.localPatchDryRunIntentClaimableDryRunPreview(requestId);

        assertThat(response).containsEntry("status", "READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED");
        verify(localPatchRequestService).previewValidatedDryRunIntentClaimableDryRun(userId, requestId);
    }

    @Test
    void localPatchDryRunIntentClaimableDryRunReleaseDelegatesForCurrentUser() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeAgentLoopRunnerService.class),
                mock(CodeAgentLoopToolSelectionService.class),
                mock(CodeIndexingService.class),
                mock(AuthService.class),
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(localPatchRequestService.releaseValidatedDryRunIntentClaimableDryRun(userId, requestId))
                .thenReturn(Map.of("status", "REFUSED_CLAIMABLE_DRY_RUN_CREATION_DISABLED"));

        Map<String, Object> response = controller.localPatchDryRunIntentClaimableDryRunRelease(requestId);

        assertThat(response).containsEntry("status", "REFUSED_CLAIMABLE_DRY_RUN_CREATION_DISABLED");
        verify(localPatchRequestService).releaseValidatedDryRunIntentClaimableDryRun(userId, requestId);
    }
}
