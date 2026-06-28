package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentToolGatewayServiceTest {
    private final LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
    private final LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
    private final LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
    private final LocalAgentToolGatewayService service = new LocalAgentToolGatewayService(repository, gatewayService, toolPusher);

    @Test
    void enqueuePersistsReadOnlyRequestForConnectedApprovedWorkspace() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.FILE_READ, LocalAgentApprovalState.NOT_REQUIRED);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), eq(request))).thenAnswer(invocation -> execution(invocation.getArgument(0), request));

        var queued = service.enqueue(request);

        assertThat(queued.requestId()).isNotNull();
        assertThat(queued.request().toolName()).isEqualTo(LocalAgentToolName.FILE_READ);
        verify(repository).create(eq(queued.requestId()), eq(request));
        verify(toolPusher).sendToolRequest(queued);
    }

    @Test
    void enqueueStillSucceedsWhenWebSocketPushIsUnavailable() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.FILE_READ, LocalAgentApprovalState.NOT_REQUIRED);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), eq(request))).thenAnswer(invocation -> execution(invocation.getArgument(0), request));
        when(toolPusher.sendToolRequest(any())).thenReturn(false);

        var queued = service.enqueue(request);

        assertThat(queued.request().toolName()).isEqualTo(LocalAgentToolName.FILE_READ);
    }

    @Test
    void enqueueRejectsSideEffectfulRequestWithoutApproval() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        assertThatThrownBy(() -> service.enqueue(request(userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY, LocalAgentApprovalState.REQUIRED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved");
    }

    @Test
    void enqueueRejectsDisconnectedAgent() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.FILE_READ, LocalAgentApprovalState.NOT_REQUIRED);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(false);

        assertThatThrownBy(() -> service.enqueue(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    void enqueueRejectsUnapprovedWorkspace() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.FILE_READ, LocalAgentApprovalState.NOT_REQUIRED);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(false);

        assertThatThrownBy(() -> service.enqueue(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not approved");
    }

    @Test
    void enqueueReadOnlyAllowsOnlySafeReadTools() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.FILE_READ, LocalAgentApprovalState.NOT_REQUIRED);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), eq(request))).thenAnswer(invocation -> execution(invocation.getArgument(0), request));

        assertThat(service.enqueueReadOnly(request).request().toolName()).isEqualTo(LocalAgentToolName.FILE_READ);

        LocalAgentToolRequest diffRequest = request(userId, agentId, workspaceId, LocalAgentToolName.GIT_DIFF, LocalAgentApprovalState.NOT_REQUIRED);
        when(repository.create(any(UUID.class), eq(diffRequest))).thenAnswer(invocation -> execution(invocation.getArgument(0), diffRequest));
        assertThat(service.enqueueReadOnly(diffRequest).request().toolName()).isEqualTo(LocalAgentToolName.GIT_DIFF);

        assertThatThrownBy(() -> service.enqueueReadOnly(new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                null,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.WORKSPACE_LIST,
                Map.of(),
                LocalAgentApprovalState.NOT_REQUIRED,
                null,
                List.of()
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("git.diff");
    }

    @Test
    void createApprovalRequestPersistsSideEffectfulRequestWithoutPushingIt() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY, LocalAgentApprovalState.REQUIRED);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), eq(request))).thenAnswer(invocation -> new LocalAgentToolExecution(
                invocation.getArgument(0),
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
        ));

        var response = service.createApprovalRequest(request);

        assertThat(response.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(response.approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.APPROVAL_REQUIRED);
        verify(repository).create(eq(response.requestId()), eq(request));
        verify(toolPusher, org.mockito.Mockito.never()).sendToolRequest(any());
    }

    @Test
    void approveHeldMarksApprovalButDoesNotPushOrMakeRequestClaimable() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY, LocalAgentApprovalState.REQUIRED);
        LocalAgentToolExecution awaitingApproval = execution(
                requestId,
                request,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED
        );
        LocalAgentToolExecution approvedHeld = execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(awaitingApproval));
        when(repository.updateApprovalDecision(
                requestId,
                userId,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD,
                "Approved by user. Execution remains held until Local Agent patch execution is enabled."
        )).thenReturn(java.util.Optional.of(approvedHeld));

        var response = service.approveHeld(userId, requestId);

        assertThat(response.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.APPROVED_HELD);
        verify(toolPusher, org.mockito.Mockito.never()).sendToolRequest(any());
    }

    @Test
    void denyRejectsApprovalRequestWithoutPushingIt() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY, LocalAgentApprovalState.REQUIRED);
        LocalAgentToolExecution awaitingApproval = execution(
                requestId,
                request,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED
        );
        LocalAgentToolExecution denied = execution(
                requestId,
                request,
                LocalAgentApprovalState.DENIED,
                LocalAgentToolStatus.REJECTED
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(awaitingApproval));
        when(repository.updateApprovalDecision(
                requestId,
                userId,
                LocalAgentApprovalState.DENIED,
                LocalAgentToolStatus.REJECTED,
                "Denied by user before Local Agent execution."
        )).thenReturn(java.util.Optional.of(denied));

        var response = service.deny(userId, requestId);

        assertThat(response.approvalState()).isEqualTo(LocalAgentApprovalState.DENIED);
        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.REJECTED);
        verify(toolPusher, org.mockito.Mockito.never()).sendToolRequest(any());
    }

    @Test
    void approvalDecisionRejectsRequestsThatAreNotAwaitingApproval() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = request(
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        )));

        assertThatThrownBy(() -> service.approveHeld(userId, requestId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer awaiting approval");
    }

    @Test
    void patchReadinessExplainsWhyApprovedHeldRequestCannotBeReleasedYet() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = patchRequest(userId, agentId, workspaceId);
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        )));
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.CONNECTED,
                agentId,
                "0.1.0",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("file.read", "git.status", "git.diff"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.readyToRelease()).isFalse();
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("patchCapability");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("releaseGateEnabled");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.warnings()).contains(
                "The connected Local Agent must advertise patch.apply capability.",
                "Patch execution release remains disabled until Local Agent patch.apply and rollback safety tests are implemented."
        );
    }

    private LocalAgentToolRequest request(UUID userId, UUID agentId, UUID workspaceId, LocalAgentToolName toolName, LocalAgentApprovalState approvalState) {
        return new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                Map.of("path", "README.md"),
                approvalState,
                OffsetDateTime.now(),
                List.of()
        );
    }

    private LocalAgentToolRequest patchRequest(UUID userId, UUID agentId, UUID workspaceId) {
        return new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of(
                        "schemaVersion", 1,
                        "diff", "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new\n",
                        "targetFiles", List.of("README.md"),
                        "expectedFiles", List.of(Map.of("path", "README.md", "sha256", "abc123", "bytes", 12)),
                        "requiresSnapshot", true,
                        "staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH"
                ),
                LocalAgentApprovalState.REQUIRED,
                OffsetDateTime.now(),
                List.of()
        );
    }

    private LocalAgentToolExecution execution(UUID requestId, LocalAgentToolRequest request) {
        return execution(requestId, request, request.approvalState(), LocalAgentToolStatus.PENDING);
    }

    private LocalAgentToolExecution execution(
            UUID requestId,
            LocalAgentToolRequest request,
            LocalAgentApprovalState approvalState,
            LocalAgentToolStatus status
    ) {
        return new LocalAgentToolExecution(
                requestId,
                request.sessionId(),
                request.userId(),
                request.agentId(),
                request.workspaceId(),
                request.executionTarget(),
                request.toolName(),
                approvalState,
                status,
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
    }
}
