package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolStatus;
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

    private LocalAgentToolExecution execution(UUID requestId, LocalAgentToolRequest request) {
        return new LocalAgentToolExecution(
                requestId,
                request.sessionId(),
                request.userId(),
                request.agentId(),
                request.workspaceId(),
                request.executionTarget(),
                request.toolName(),
                request.approvalState(),
                LocalAgentToolStatus.PENDING,
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
