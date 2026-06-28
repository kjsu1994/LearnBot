package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessCheck;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public LocalAgentToolExecutionResponse createApprovalRequest(LocalAgentToolRequest request) {
        if (request.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException("Only USER_LOCAL_AGENT tool requests can be prepared for Local Agent approval.");
        }
        if (!request.toolName().isSideEffectful()) {
            throw new IllegalArgumentException("Only side-effectful Local Agent tools require an approval request.");
        }
        if (request.approvalState() != LocalAgentApprovalState.REQUIRED) {
            throw new IllegalArgumentException("Approval requests must start with REQUIRED approval state.");
        }
        if (!gatewayService.isConnected(request.userId(), request.agentId())) {
            throw new IllegalStateException("Local Agent is not connected.");
        }
        if (request.workspaceId() != null && !gatewayService.hasApprovedWorkspace(request.userId(), request.workspaceId())) {
            throw new IllegalStateException("Workspace is not approved by the Local Agent.");
        }
        UUID requestId = UUID.randomUUID();
        return toResponse(repository.create(requestId, request));
    }

    @Transactional
    public LocalAgentToolExecutionResponse approveHeld(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = approvalCandidate(userId, requestId);
        return repository.updateApprovalDecision(
                        execution.id(),
                        userId,
                        LocalAgentApprovalState.APPROVED,
                        LocalAgentToolStatus.APPROVED_HELD,
                        "Approved by user. Execution remains held until Local Agent patch execution is enabled."
                )
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Local Agent approval request is no longer awaiting approval."));
    }

    @Transactional
    public LocalAgentToolExecutionResponse deny(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = approvalCandidate(userId, requestId);
        return repository.updateApprovalDecision(
                        execution.id(),
                        userId,
                        LocalAgentApprovalState.DENIED,
                        LocalAgentToolStatus.REJECTED,
                        "Denied by user before Local Agent execution."
                )
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Local Agent approval request is no longer awaiting approval."));
    }

    public LocalAgentPatchExecutionReadinessResponse inspectPatchExecutionReadiness(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent patch request was not found."));
        if (execution.toolName() != LocalAgentToolName.PATCH_APPLY) {
            throw new IllegalArgumentException("Execution readiness is available only for patch.apply requests.");
        }

        List<LocalAgentPatchExecutionReadinessCheck> checks = new ArrayList<>();
        checks.add(check(
                "approvedHeld",
                execution.approvalState() == LocalAgentApprovalState.APPROVED
                        && execution.status() == LocalAgentToolStatus.APPROVED_HELD,
                "Request must be approved and held before release."
        ));
        checks.add(check(
                "executionTarget",
                execution.executionTarget() == AgentExecutionTarget.USER_LOCAL_AGENT,
                "Patch must target USER_LOCAL_AGENT."
        ));

        LocalAgentStatusResponse status = gatewayService.status(userId);
        checks.add(check(
                "agentConnected",
                status.state() == LocalAgentConnectionState.CONNECTED
                        && execution.agentId() != null
                        && execution.agentId().equals(status.agentId()),
                "The selected Local Agent must be connected and match the request."
        ));
        checks.add(check(
                "workspaceApproved",
                execution.workspaceId() != null && gatewayService.hasApprovedWorkspace(userId, execution.workspaceId()),
                "The request workspace must still be approved by the Local Agent."
        ));
        checks.add(check(
                "patchCapability",
                status.capabilities().contains(LocalAgentToolName.PATCH_APPLY.wireName()),
                "The connected Local Agent must advertise patch.apply capability."
        ));

        Map<String, Object> input = execution.input();
        checks.add(check(
                "inputSchema",
                numberValue(input.get("schemaVersion")) == 1,
                "Patch request input schema must be version 1."
        ));
        checks.add(check(
                "diffPresent",
                hasText(input.get("diff")),
                "A validated unified diff must be present."
        ));
        checks.add(check(
                "targetFilesPresent",
                nonEmptyList(input.get("targetFiles")),
                "At least one target file must be present."
        ));
        checks.add(check(
                "expectedFilesPresent",
                hasExpectedFiles(input.get("expectedFiles")),
                "Expected file hashes must be present for context validation."
        ));
        checks.add(check(
                "snapshotRequired",
                Boolean.TRUE.equals(input.get("requiresSnapshot")),
                "A snapshot must be required before file writes."
        ));
        checks.add(check(
                "staleIndexPolicy",
                "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH".equals(input.get("staleIndexPolicy")),
                "Stale-index policy must require expected hash or context match."
        ));
        checks.add(check(
                "releaseGateEnabled",
                false,
                "Patch execution release remains disabled until Local Agent patch.apply and rollback safety tests are implemented."
        ));

        boolean ready = checks.stream().allMatch(LocalAgentPatchExecutionReadinessCheck::passed);
        List<String> warnings = ready
                ? List.of()
                : checks.stream()
                .filter(item -> !item.passed())
                .map(LocalAgentPatchExecutionReadinessCheck::message)
                .toList();
        return new LocalAgentPatchExecutionReadinessResponse(
                execution.id(),
                ready,
                List.copyOf(checks),
                warnings,
                ready
                        ? "Held patch request is ready to release."
                        : "Held patch request is not ready for Local Agent execution."
        );
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

    private LocalAgentPatchExecutionReadinessCheck check(String key, boolean passed, String message) {
        return new LocalAgentPatchExecutionReadinessCheck(key, passed, message);
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private boolean hasExpectedFiles(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return false;
        return list.stream().allMatch(item -> item instanceof Map<?, ?> file
                && hasText(file.get("path"))
                && hasText(file.get("sha256")));
    }

    private LocalAgentToolExecution approvalCandidate(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent approval request was not found."));
        if (execution.approvalState() != LocalAgentApprovalState.REQUIRED
                || execution.status() != LocalAgentToolStatus.APPROVAL_REQUIRED) {
            throw new IllegalArgumentException("Local Agent approval request is no longer awaiting approval.");
        }
        if (!execution.toolName().isSideEffectful()) {
            throw new IllegalArgumentException("Only side-effectful Local Agent tools can be approved or denied.");
        }
        return execution;
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
