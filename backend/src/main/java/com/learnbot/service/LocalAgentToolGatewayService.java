package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class LocalAgentToolGatewayService {
    private final LocalAgentToolExecutionRepository repository;
    private final LocalAgentGatewayService gatewayService;
    private final LocalAgentToolPusher toolPusher;

    public LocalAgentToolGatewayService(
            LocalAgentToolExecutionRepository repository,
            LocalAgentGatewayService gatewayService,
            LocalAgentToolPusher toolPusher
    ) {
        this.repository = repository;
        this.gatewayService = gatewayService;
        this.toolPusher = toolPusher;
    }

    @Transactional
    public LocalAgentQueuedToolRequest enqueue(LocalAgentToolRequest request) {
        if (request.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException("Only USER_LOCAL_AGENT tool requests can be routed through the Local Agent gateway.");
        }
        if (request.toolName().isSideEffectful() && request.approvalState() != LocalAgentApprovalState.APPROVED) {
            throw new IllegalArgumentException("Side-effectful Local Agent tools must be approved before routing.");
        }
        if (!gatewayService.isConnected(request.userId(), request.agentId())) {
            throw new IllegalStateException("Local Agent is not connected.");
        }
        if (request.workspaceId() != null && !gatewayService.hasApprovedWorkspace(request.userId(), request.workspaceId())) {
            throw new IllegalStateException("Workspace is not approved by the Local Agent.");
        }
        UUID requestId = UUID.randomUUID();
        LocalAgentToolExecution execution = repository.create(requestId, request);
        LocalAgentQueuedToolRequest queued = toQueuedRequest(execution);
        toolPusher.sendToolRequest(queued);
        return queued;
    }

    @Transactional
    public LocalAgentQueuedToolRequest enqueueReadOnly(LocalAgentToolRequest request) {
        if (request.toolName() != LocalAgentToolName.FILE_READ
                && request.toolName() != LocalAgentToolName.GIT_STATUS
                && request.toolName() != LocalAgentToolName.GIT_DIFF) {
            throw new IllegalArgumentException("Only file.read, git.status, and git.diff can be queued through this read-only path.");
        }
        return enqueue(request);
    }

    @Transactional
    public Optional<LocalAgentQueuedToolRequest> claimNext(UUID userId, UUID agentId) {
        return repository.claimNext(userId, agentId)
                .map(this::toQueuedRequest);
    }

    @Transactional
    public void complete(LocalAgentToolResponse response) {
        repository.complete(response);
    }

    public Optional<LocalAgentToolExecutionResponse> findForUser(UUID userId, UUID requestId) {
        return repository.find(requestId)
                .filter(execution -> execution.userId().equals(userId))
                .map(this::toResponse);
    }

    private LocalAgentQueuedToolRequest toQueuedRequest(LocalAgentToolExecution execution) {
        return new LocalAgentQueuedToolRequest(
                execution.id(),
                new LocalAgentToolRequest(
                        execution.sessionId(),
                        execution.userId(),
                        execution.agentId(),
                        execution.workspaceId(),
                        execution.executionTarget(),
                        execution.toolName(),
                        execution.input(),
                        execution.approvalState(),
                        execution.createdAt(),
                        execution.requestWarnings()
                )
        );
    }

    private LocalAgentToolExecutionResponse toResponse(LocalAgentToolExecution execution) {
        return new LocalAgentToolExecutionResponse(
                execution.id(),
                execution.sessionId(),
                execution.userId(),
                execution.agentId(),
                execution.workspaceId(),
                execution.executionTarget(),
                execution.toolName(),
                execution.approvalState(),
                execution.status(),
                execution.input(),
                execution.output(),
                execution.failureCode(),
                execution.error(),
                execution.requestWarnings(),
                execution.responseWarnings(),
                execution.createdAt(),
                execution.startedAt(),
                execution.finishedAt()
        );
    }
}
