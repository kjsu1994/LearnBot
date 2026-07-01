package com.learnbot.web;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLocalPatchRequest;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopPreviewRequest;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopApprovalRequestPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalRequestResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalPayloadRequest;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopSelectedToolEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopSideEffectBoundaryResponse;
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
import com.learnbot.service.agentloop.CodeAgentLoopToolSelectionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

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
}
