package com.learnbot.service.agentloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopToolCandidate;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.LocalAgentToolGatewayService;
import com.learnbot.service.OllamaClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class CodeAgentLoopToolSelectionServiceTest {
    private final CodeAgentLoopRunnerService runnerService = mock(CodeAgentLoopRunnerService.class);
    private final LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
    private final CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
    private final OllamaClient ollamaClient = mock(OllamaClient.class);
    private final CodeAgentLoopToolSelectionService service = new CodeAgentLoopToolSelectionService(
            runnerService,
            toolGatewayService,
            localPatchRequestService,
            ollamaClient,
            new ObjectMapper()
    );

    @Test
    void modelCanSelectOnlyAllowedReadOnlyGitStatusCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Check current workspace state."}
                """));

        var result = service.selectNextToolPreview(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.selectionDecision()).isEqualTo("MODEL_SELECTED_READ_ONLY_CANDIDATE");
        assertThat(result.modelToolSelectionAttempted()).isTrue();
        assertThat(result.modelToolSelectionAccepted()).isTrue();
        assertThat(result.selectedByModel()).isTrue();
        assertThat(result.candidate()).isSameAs(candidate);
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.modelDecision()).containsEntry("toolName", "git.status")
                .containsEntry("mutationAllowed", false);
        assertThat(result.guardrails()).containsEntry("modelToolSelectionEnabled", true)
                .containsEntry("mutationAllowed", false);
    }

    @Test
    void unsafeModelToolSelectionFallsBackToReadOnlyCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":true,"reason":"Apply the patch."}
                """));

        var result = service.selectNextToolPreview(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.selectionDecision()).isEqualTo("MODEL_SELECTION_REJECTED_FALLBACK_READ_ONLY");
        assertThat(result.modelToolSelectionAttempted()).isTrue();
        assertThat(result.modelToolSelectionAccepted()).isFalse();
        assertThat(result.selectedByModel()).isFalse();
        assertThat(result.candidate()).isSameAs(candidate);
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.modelDecision()).containsEntry("toolName", "patch.apply")
                .containsEntry("mutationAllowed", true);
    }

    @Test
    void skipsModelCallWhenRunnerCannotPrepareCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "NO_REQUEST_PREPARED", null));

        var result = service.selectNextToolPreview(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.selectionDecision()).isEqualTo("NO_MODEL_SELECTION");
        assertThat(result.modelToolSelectionAttempted()).isFalse();
        assertThat(result.candidate()).isNull();
        assertThat(result.mutationEnabled()).isFalse();
        verify(ollamaClient, never()).chatResult(anyString(), anyString(), eq(400));
    }

    @Test
    void enqueueSelectedReadOnlyQueuesModelAcceptedCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Check state."}
                """));
        when(toolGatewayService.enqueueReadOnly(any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                new LocalAgentQueuedToolRequest(requestId, invocation.getArgument(0)));

        var result = service.enqueueSelectedReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.runnerDecision()).isEqualTo("ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION");
        assertThat(result.modelToolSelectionAccepted()).isTrue();
        assertThat(result.selectedByModel()).isTrue();
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.enqueueEnabled()).isTrue();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.queuedRequest().requestId()).isEqualTo(requestId);
        assertThat(result.queuedRequest().request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(result.queuedRequest().request().input()).containsEntry("mutationAllowed", false);
    }

    @Test
    void enqueueSelectedReadOnlyQueuesDeterministicFallbackWhenModelIsUnsafe() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":true,"reason":"Apply patch."}
                """));
        when(toolGatewayService.enqueueReadOnly(any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                new LocalAgentQueuedToolRequest(requestId, invocation.getArgument(0)));

        var result = service.enqueueSelectedReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.runnerDecision()).isEqualTo("ENQUEUED_FALLBACK_READ_ONLY_OBSERVATION");
        assertThat(result.modelToolSelectionAttempted()).isTrue();
        assertThat(result.modelToolSelectionAccepted()).isFalse();
        assertThat(result.selectedByModel()).isFalse();
        assertThat(result.queuedRequest().request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(result.queuedRequest().request().approvalState()).isEqualTo(LocalAgentApprovalState.NOT_REQUIRED);
        assertThat(result.mutationEnabled()).isFalse();
    }

    @Test
    void sideEffectBoundaryRequiresApprovalAndReleaseForPatchApplyWithoutQueueing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                """));

        var result = service.previewSideEffectBoundary(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.boundaryDecision()).isEqualTo("SIDE_EFFECTFUL_PATCH_REQUIRES_APPROVAL_RELEASE");
        assertThat(result.modelToolSelectionAttempted()).isTrue();
        assertThat(result.modelProposedSideEffectfulTool()).isTrue();
        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.releaseRequired()).isTrue();
        assertThat(result.releaseGateEnabled()).isFalse();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.modelDecision()).containsEntry("toolName", "patch.apply")
                .containsEntry("requiresApproval", true)
                .containsEntry("mutationAllowed", false);
        assertThat(result.guardrails()).containsEntry("releaseGateEnabled", false)
                .containsEntry("mutationAllowed", false);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void sideEffectBoundaryDoesNotRequireApprovalWhenModelKeepsReadOnlyTool() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Observe first."}
                """));

        var result = service.previewSideEffectBoundary(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.boundaryDecision()).isEqualTo("NO_SIDE_EFFECTFUL_TOOL_PROPOSED");
        assertThat(result.modelToolSelectionAttempted()).isTrue();
        assertThat(result.modelProposedSideEffectfulTool()).isFalse();
        assertThat(result.approvalRequired()).isFalse();
        assertThat(result.releaseRequired()).isFalse();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void patchApprovalPreviewPreparesRequiredMetadataWithoutCreatingRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                """));

        var result = service.previewPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.approvalDecision()).isEqualTo("PREPARED_PATCH_APPROVAL_REQUEST_PREVIEW");
        assertThat(result.approvalRequestPrepared()).isTrue();
        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.releaseRequired()).isTrue();
        assertThat(result.releaseEvidenceAvailable()).isFalse();
        assertThat(result.releaseGateEnabled()).isFalse();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.candidate().toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(result.candidate().approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(result.candidate().sideEffectful()).isTrue();
        assertThat(result.candidate().requiresApproval()).isTrue();
        assertThat(result.candidate().enqueueEnabled()).isFalse();
        assertThat(result.candidate().mutationAllowed()).isFalse();
        assertThat(result.candidate().input())
                .containsEntry("repositoryId", repositoryId.toString())
                .containsEntry("loopId", loopId.toString())
                .containsEntry("approvalRequired", true)
                .containsEntry("releaseRequired", true)
                .containsEntry("releaseEvidenceAvailable", false)
                .containsEntry("mutationAllowed", false);
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void patchApprovalPreviewWaitsWhenAgentWorkspaceIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(runnerService.previewNextStep(userId, repositoryId, loopId, null, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "WAIT_FOR_AGENT_WORKSPACE", null));

        var result = service.previewPatchApprovalRequest(userId, repositoryId, loopId, null, workspaceId);

        assertThat(result.approvalDecision()).isEqualTo("NO_APPROVAL_REQUEST_PREPARED");
        assertThat(result.approvalRequestPrepared()).isFalse();
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(ollamaClient, never()).chatResult(anyString(), anyString(), eq(400));
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void patchApprovalPreviewDoesNotPrepareWhenModelKeepsReadOnlyTool() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Observe first."}
                """));

        var result = service.previewPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.approvalDecision()).isEqualTo("NO_APPROVAL_REQUEST_PREPARED");
        assertThat(result.approvalRequestPrepared()).isFalse();
        assertThat(result.approvalRequired()).isFalse();
        assertThat(result.releaseRequired()).isFalse();
        assertThat(result.candidate()).isNull();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void createPatchApprovalRequestPersistsOnlyApprovalRequiredNonClaimableRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                """));
        when(toolGatewayService.createApprovalRequest(any(LocalAgentToolRequest.class))).thenAnswer(invocation -> {
            LocalAgentToolRequest request = invocation.getArgument(0);
            return new LocalAgentToolExecutionResponse(
                    requestId,
                    request.sessionId(),
                    request.userId(),
                    request.agentId(),
                    request.workspaceId(),
                    request.executionTarget(),
                    request.toolName(),
                    request.approvalState(),
                    LocalAgentToolStatus.APPROVAL_REQUIRED,
                    request.input(),
                    Map.of(),
                    null,
                    null,
                    request.warnings(),
                    List.of(),
                    request.createdAt(),
                    null,
                    null
            );
        });

        var result = service.createPatchApprovalRequest(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(result.approvalDecision()).isEqualTo("CREATED_PATCH_APPROVAL_REQUEST");
        assertThat(result.approvalRequestCreated()).isTrue();
        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.releaseRequired()).isTrue();
        assertThat(result.releaseGateEnabled()).isFalse();
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.enqueueEnabled()).isFalse();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.approvalRequest().requestId()).isEqualTo(requestId);
        assertThat(result.approvalRequest().toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(result.approvalRequest().approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(result.approvalRequest().status()).isEqualTo(LocalAgentToolStatus.APPROVAL_REQUIRED);
        assertThat(result.approvalRequest().input()).containsEntry("mutationAllowed", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("releaseGateEnabled", false);
        assertThat(result.guardrails()).containsEntry("createdStatus", "APPROVAL_REQUIRED")
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationAllowed", false);
        verify(toolGatewayService).createApprovalRequest(argThat(request ->
                request.toolName() == LocalAgentToolName.PATCH_APPLY
                        && request.approvalState() == LocalAgentApprovalState.REQUIRED
                        && request.executionTarget() == AgentExecutionTarget.USER_LOCAL_AGENT
                        && request.agentId().equals(agentId)
                        && request.workspaceId().equals(workspaceId)
        ));
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void createPatchApprovalRequestDoesNotPersistWithoutAgentWorkspaceCandidate() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        when(runnerService.previewNextStep(userId, repositoryId, loopId, null, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "WAIT_FOR_AGENT_WORKSPACE", null));

        var result = service.createPatchApprovalRequest(userId, repositoryId, loopId, null, workspaceId);

        assertThat(result.approvalDecision()).isEqualTo("NO_APPROVAL_REQUEST_CREATED");
        assertThat(result.approvalRequestCreated()).isFalse();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.approvalRequest()).isNull();
        assertThat(result.mutationEnabled()).isFalse();
        verify(toolGatewayService, never()).createApprovalRequest(any(LocalAgentToolRequest.class));
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void createValidatedPatchApprovalRequestDelegatesToValidatedPayloadServiceOnlyAfterPatchBoundary() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        List<String> targetFiles = List.of("src/App.java");
        String diff = "--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-old\n+new\n";
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                """));
        when(localPatchRequestService.prepare(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                "fix this",
                diff,
                targetFiles
        )).thenReturn(new LocalAgentToolExecutionResponse(
                requestId,
                loopId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                Map.of(
                        "repositoryId", repositoryId.toString(),
                        "spaceId", spaceId.toString(),
                        "loopId", loopId.toString(),
                        "diff", diff,
                        "targetFiles", targetFiles,
                        "expectedFiles", List.of(Map.of("path", "src/App.java", "sha256", "abc123", "bytes", 7)),
                        "requiresSnapshot", true,
                        "workspaceVerification", Map.of("status", "UNVERIFIED", "blocking", true)
                ),
                Map.of(),
                null,
                null,
                List.of("release blocked until fresh Local Agent evidence is available"),
                List.of(),
                java.time.OffsetDateTime.now(),
                null,
                null
        ));

        var result = service.createValidatedPatchApprovalRequest(
                userId,
                repositoryId,
                spaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this",
                diff,
                targetFiles
        );

        assertThat(result.approvalDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        assertThat(result.approvalRequestCreated()).isTrue();
        assertThat(result.requestCreationEnabled()).isTrue();
        assertThat(result.pushEnabled()).isFalse();
        assertThat(result.claimEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        assertThat(result.approvalRequest().input())
                .containsEntry("diff", diff)
                .containsEntry("targetFiles", targetFiles)
                .containsEntry("requiresSnapshot", true);
        assertThat(result.approvalRequest().input().get("expectedFiles")).asList().isNotEmpty();
        verify(localPatchRequestService).prepare(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                "fix this",
                diff,
                targetFiles
        );
        verify(toolGatewayService, never()).createApprovalRequest(any(LocalAgentToolRequest.class));
        verify(toolGatewayService, never()).enqueueReadOnly(any(LocalAgentToolRequest.class));
    }

    @Test
    void createValidatedPatchApprovalRequestDoesNotDelegateWhenModelKeepsReadOnlyTool() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopToolCandidate candidate = candidate(userId, repositoryId, loopId, agentId, workspaceId);
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId))
                .thenReturn(preview(repositoryId, loopId, "PREPARED_READ_ONLY_CANDIDATE", candidate));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(chat("""
                {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Observe first."}
                """));

        var result = service.createValidatedPatchApprovalRequest(
                userId,
                repositoryId,
                spaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this",
                "--- a/src/App.java\n+++ b/src/App.java\n",
                List.of("src/App.java")
        );

        assertThat(result.approvalDecision()).isEqualTo("NO_VALIDATED_PATCH_APPROVAL_REQUEST_CREATED");
        assertThat(result.approvalRequestCreated()).isFalse();
        assertThat(result.requestCreationEnabled()).isFalse();
        assertThat(result.mutationEnabled()).isFalse();
        verify(localPatchRequestService, never()).prepare(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(toolGatewayService, never()).createApprovalRequest(any(LocalAgentToolRequest.class));
    }

    private CodeAgentLoopToolCandidate candidate(
            UUID userId,
            UUID repositoryId,
            UUID loopId,
            UUID agentId,
            UUID workspaceId
    ) {
        return new CodeAgentLoopToolCandidate(
                loopId,
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
                Map.of(
                        "repositoryId", repositoryId.toString(),
                        "loopId", loopId.toString(),
                        "freshObservationOnly", true,
                        "mutationAllowed", false
                ),
                List.of()
        );
    }

    private CodeAgentLoopRunnerPreviewResponse preview(
            UUID repositoryId,
            UUID loopId,
            String runnerDecision,
            CodeAgentLoopToolCandidate candidate
    ) {
        return new CodeAgentLoopRunnerPreviewResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                runnerDecision,
                "Preview.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                candidate,
                Map.of("mutationAllowed", false)
        );
    }

    private OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://localhost:11434", "test", "PRIMARY", false);
    }
}
