package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentFailureCode;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import com.learnbot.repository.LocalAgentMutationObservationIntakeRepository;
import com.learnbot.repository.LocalAgentPatchReleaseAttemptRepository;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class LocalAgentToolGatewayServiceTest {
    private final LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
    private final LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
    private final LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
    private final CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
    private final LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
    private final LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
    private final LocalAgentToolGatewayService service = new LocalAgentToolGatewayService(repository, mutationObservationIntakeRepository, releaseAttemptRepository, loopTimelineRepository, gatewayService, toolPusher);

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
    void createApprovalRequestAppendsAuditOnlyStopOutcomeWhenAgentIsUnavailable() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString()),
                LocalAgentApprovalState.REQUIRED,
                null,
                List.of()
        );
        when(gatewayService.isConnected(userId, agentId)).thenReturn(false);

        assertThatThrownBy(() -> service.createApprovalRequest(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not connected");

        verify(loopTimelineRepository).appendAgentUnavailableStopOutcome(userId, repositoryId, loopId, request);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(toolPusher, never()).sendToolRequest(any());
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
    void approveHeldAppendsAuditOnlyLoopApprovalDecisionWhenRepositoryContextExists() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString()),
                LocalAgentApprovalState.REQUIRED,
                null,
                List.of()
        );
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

        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.APPROVED_HELD);
        verify(loopTimelineRepository).appendApprovalDecision(
                userId,
                repositoryId,
                requestId,
                request.sessionId(),
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.APPROVED.name(),
                LocalAgentToolStatus.APPROVED_HELD.name(),
                loopId,
                request.input()
        );
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).claimNext(any(), any());
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
    void denyAppendsAuditOnlyLoopApprovalDecisionWhenRepositoryContextExists() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString()),
                LocalAgentApprovalState.REQUIRED,
                null,
                List.of()
        );
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

        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.REJECTED);
        verify(loopTimelineRepository).appendApprovalDecision(
                userId,
                repositoryId,
                requestId,
                request.sessionId(),
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.DENIED.name(),
                LocalAgentToolStatus.REJECTED.name(),
                loopId,
                request.input()
        );
        verify(loopTimelineRepository).appendApprovalDeniedStopOutcome(
                userId,
                repositoryId,
                loopId,
                requestId,
                request.sessionId(),
                agentId,
                workspaceId,
                LocalAgentApprovalState.DENIED.name(),
                LocalAgentToolStatus.REJECTED.name(),
                request.input()
        );
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).claimNext(any(), any());
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
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.readyToRelease()).isFalse();
        assertThat(readiness.snapshotReadiness())
                .containsEntry("status", "CREATED")
                .containsEntry("snapshotCreated", true)
                .containsEntry("writesCompleted", true);
        assertThat(readiness.rollbackReadiness())
                .containsEntry("status", "RESTORE_VALIDATED")
                .containsEntry("blocking", false)
                .containsEntry("requiresUserApproval", true)
                .containsEntry("fileCount", 1);
        assertThat(readiness.patchReleaseReadiness())
                .containsEntry("status", "BLOCKED")
                .containsEntry("preconditionsPassed", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(readiness.patchExecutionGate())
                .containsEntry("status", "BLOCKED")
                .containsEntry("preconditionsPassed", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(readiness.repositoryVerification()).containsEntry("status", "MATCH");
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("patchCapability");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("rollbackCapability");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("snapshotPolicy");
            assertThat(check.passed()).isTrue();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("rollbackPolicy");
            assertThat(check.passed()).isTrue();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("snapshotManifestPreview");
            assertThat(check.passed()).isTrue();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("rollbackRestorePreconditions");
            assertThat(check.passed()).isTrue();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("workspaceRepositoryVerified");
            assertThat(check.passed()).isTrue();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("releaseGateEnabled");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.warnings()).contains(
                "The connected Local Agent must advertise patch.apply capability.",
                "The connected Local Agent must advertise rollback.restore capability before patch execution can be released.",
                "Patch execution release remains disabled until Local Agent patch.apply and rollback safety tests are implemented."
        );
    }

    @Test
    void patchReadinessKeepsRepositoryMismatchBlocking() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MISMATCH",
                "blocking", true,
                "message", "Local workspace identity does not match the indexed repository metadata.",
                "checks", List.of(Map.of("key", "remote", "status", "MISMATCH", "expected", "https://example.com/repo.git", "actual", "https://example.com/other.git"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.empty());

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.readyToRelease()).isFalse();
        assertThat(readiness.snapshotReadiness())
                .containsEntry("status", "MISSING")
                .containsEntry("blocking", true);
        assertThat(readiness.rollbackReadiness())
                .containsEntry("status", "MISSING")
                .containsEntry("blocking", true);
        assertThat(readiness.patchReleaseReadiness())
                .containsEntry("status", "BLOCKED")
                .containsEntry("preconditionsPassed", false)
                .containsEntry("releaseGateEnabled", false);
        assertThat(readiness.patchExecutionGate())
                .containsEntry("status", "BLOCKED")
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("sourceRequestRelationship", "NOT_OBSERVED");
        assertThat(readiness.repositoryVerification()).containsEntry("status", "MISMATCH");
        assertThat(readiness.workspaceVerification()).containsEntry("status", "UNVERIFIED");
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("workspaceRepositoryVerified");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("releaseGateEnabled");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("snapshotManifestPreview");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.warnings()).contains(
                "The selected Local Agent workspace must be verified against the indexed repository identity before release.",
                "Latest Local Agent dry-run must provide a managed snapshot manifest with schema, id, path, target files, and a matching snapshotCreated state.",
                "Patch execution release remains disabled until Local Agent patch.apply and rollback safety tests are implemented."
        );
    }

    @Test
    void patchExecutionGateStaysDisabledEvenWhenPreconditionsAreReady() {
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.readyToRelease()).isFalse();
        assertThat(readiness.patchReleaseReadiness())
                .containsEntry("status", "PRECONDITIONS_READY_RELEASE_DISABLED")
                .containsEntry("preconditionsPassed", true)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(readiness.patchExecutionGate())
                .containsEntry("status", "INTERNAL_PRECONDITIONS_READY_GATE_DISABLED")
                .containsEntry("preconditionsPassed", true)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("sourceRequestRelationship", "LINKED_DRY_RUN_OUTPUT_OBSERVED");
        Map<String, Object> revalidation = ((Map<?, ?>) readiness.patchExecutionGate().get("preReleaseRevalidation"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(revalidation)
                .containsEntry("status", "REQUIRED_BEFORE_RELEASE")
                .containsEntry("required", true)
                .containsEntry("passed", false)
                .containsEntry("requiresFreshDryRunAfterReleaseAttempt", true)
                .containsEntry("requiresFreshRepositoryVerificationAfterReleaseAttempt", true);
        Map<String, Object> releaseAttemptModel = ((Map<?, ?>) readiness.patchExecutionGate().get("releaseAttemptModel"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(releaseAttemptModel)
                .containsEntry("schema", "learnbot.local-agent.patch-release-attempt.v1")
                .containsEntry("status", "MODEL_ONLY_RELEASE_DISABLED")
                .containsEntry("created", false)
                .containsEntry("claimable", false)
                .containsEntry("staleWindowSeconds", 120);
        assertThat((Map<?, ?>) releaseAttemptModel.get("latestAttempt")).isEmpty();
        assertThat(readiness.releaseAttemptModel().schema()).isEqualTo("learnbot.local-agent.patch-release-attempt.v1");
        assertThat(readiness.releaseAttemptModel().status()).isEqualTo("MODEL_ONLY_RELEASE_DISABLED");
        assertThat(readiness.releaseAttemptModel().created()).isFalse();
        assertThat(readiness.releaseAttemptModel().claimable()).isFalse();
        assertThat(readiness.releaseAttemptModel().staleWindowSeconds()).isEqualTo(120);
        assertThat(readiness.releaseAttemptModel().latestAttempt()).isEmpty();
        assertThat(readiness.releaseAttemptModel().requiredEvidence())
                .extracting("key")
                .contains("releaseAttemptId", "repositoryVerification", "patchDryRun", "snapshotManifest", "rollbackManifest", "userReleaseApproval");
        assertThat(((List<?>) releaseAttemptModel.get("requiredEvidence")).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> String.valueOf(item.get("key")))
                .toList())
                .contains("releaseAttemptId", "repositoryVerification", "patchDryRun", "snapshotManifest", "rollbackManifest", "userReleaseApproval");
        assertThat(((List<?>) readiness.patchExecutionGate().get("requiredBeforeEnablement")).stream()
                .map(String::valueOf)
                .toList())
                .contains("Connect Local Agent patch.apply to the guarded write helper.");
    }

    @Test
    void patchReadinessRejectsSnapshotObservationIfDryRunMutatedFiles() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", false,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId))
                .thenReturn(java.util.Optional.of(patchDryRunOutputWithMutationApplied()));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of("repositoryVerificationRequestId", "repo-check-1"),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.readyToRelease()).isFalse();
        assertThat(readiness.snapshotReadiness())
                .containsEntry("status", "INVALID")
                .containsEntry("dryRun", true)
                .containsEntry("mutationApplied", true)
                .containsEntry("snapshotCreated", true)
                .containsEntry("blocking", true)
                .containsEntry("message", "Snapshot readiness requires a non-mutating Local Agent dry-run observation with mutationApplied=false.");
        assertThat(readiness.rollbackReadiness())
                .containsEntry("status", "INVALID")
                .containsEntry("blocking", true)
                .containsEntry("message", "Rollback validation requires a dry-run observation with mutationApplied=false.");
        assertThat(readiness.patchReleaseReadiness())
                .containsEntry("status", "BLOCKED")
                .containsEntry("preconditionsPassed", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(readiness.patchExecutionGate())
                .containsEntry("status", "BLOCKED")
                .containsEntry("preconditionsPassed", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("sourceRequestRelationship", "LINKED_DRY_RUN_OUTPUT_OBSERVED");
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("snapshotManifestPreview");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("rollbackRestorePreconditions");
            assertThat(check.passed()).isFalse();
        });
        assertThat(readiness.releaseAttemptModel().created()).isTrue();
        Map<String, Object> latestAttempt = readiness.releaseAttemptModel().latestAttempt();
        assertThat(latestAttempt)
                .containsEntry("id", attemptId)
                .containsEntry("sourceRequestId", requestId)
                .containsEntry("claimable", false);
        assertThat(latestAttempt.get("releaseAttemptFinalReadiness")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> finalReadiness = (Map<String, Object>) latestAttempt.get("releaseAttemptFinalReadiness");
        assertThat(finalReadiness)
                .containsEntry("status", "BLOCKED_RELEASE_DISABLED")
                .containsEntry("ready", false)
                .containsEntry("patchPreconditionsPassed", false)
                .containsEntry("patchReleaseStatus", "BLOCKED")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationAllowed", false);
        assertThat(finalReadiness.get("blockingReasons")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> finalBlockingReasons = (List<String>) finalReadiness.get("blockingReasons");
        assertThat(finalBlockingReasons)
                .contains("patch release prerequisites are incomplete", "release gate is disabled", "held patch request remains non-claimable");
        assertThat(latestAttempt.get("releaseEnablementChecklist")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> checklist = (Map<String, Object>) latestAttempt.get("releaseEnablementChecklist");
        assertThat(checklist)
                .containsEntry("status", "BLOCKED_ENABLEMENT_DISABLED")
                .containsEntry("prerequisitesPassed", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationAllowed", false);
        assertThat(checklist.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> checklistBlockingKeys = (List<String>) checklist.get("blockingKeys");
        assertThat(checklistBlockingKeys).containsExactly("finalReadiness", "rollbackReadiness");
        assertThat(latestAttempt.get("releaseAttemptDisplaySummary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> displaySummary = (Map<String, Object>) latestAttempt.get("releaseAttemptDisplaySummary");
        assertThat(displaySummary)
                .containsEntry("status", "BLOCKED_DISABLED_DISPLAY")
                .containsEntry("releaseReadyButDisabled", false)
                .containsEntry("patchPreconditionsPassed", false);
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void patchReadinessSurfacesLatestDisabledReleaseAttemptWithoutEnablingClaim() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));
        when(repository.findLatestAcceptedMutationObservationForReleaseAttempt(userId, requestId, attemptId)).thenReturn(java.util.Optional.of(Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.accepted-mutation-observation.v1"),
                Map.entry("status", "ACCEPTED"),
                Map.entry("accepted", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("sourceRequestId", requestId.toString()),
                Map.entry("releaseAttemptId", attemptId.toString()),
                Map.entry("acceptedObservationPersistenceEnabled", false),
                Map.entry("resultAggregationEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementSaveEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false)
        )));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of("repositoryVerificationRequestId", "repo-check-1"),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.releaseAttemptModel().status()).isEqualTo(LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS);
        assertThat(readiness.releaseAttemptModel().created()).isTrue();
        assertThat(readiness.releaseAttemptModel().claimable()).isFalse();
        assertThat(readiness.releaseAttemptModel().latestAttempt())
                .containsEntry("id", attemptId)
                .containsEntry("sourceRequestId", requestId)
                .containsEntry("status", LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS)
                .containsEntry("claimable", false)
                .containsEntry("freshnessStatus", "FRESH")
                .containsEntry("stale", false)
                .containsEntry("expiresAt", attemptCreatedAt.plusSeconds(120));
        assertFreshObservationRequirements(readiness.releaseAttemptModel().latestAttempt(), attemptCreatedAt);
        assertFreshObservationRequestPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, attemptCreatedAt);
        assertFreshObservationEvidenceStatus(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "SOURCE_ONLY_FALLBACK",
                "SOURCE_ONLY_FALLBACK"
        );
        assertFreshObservationEvidenceCompleteness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "INCOMPLETE_RELEASE_DISABLED",
                false,
                0,
                0,
                2
        );
        assertReleaseAttemptFinalReadiness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_RELEASE_DISABLED",
                false,
                "FRESH",
                false,
                false,
                true
        );
        assertLocalAgentMutationExecutionSequencePlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertPostMutationResultContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertMutationDispatchEnvelopeContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "READY_DISPATCH_DISABLED", true);
        assertMutationDispatchPreflightBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "READY_PREFLIGHT_DISABLED", true);
        assertMutationDispatchDecisionModel(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_DISPATCH_DISABLED", true);
        assertMutationRequestBlueprint(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_REQUEST_CREATION_DISABLED", true);
        assertMutationRequestCreationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_CREATION_DISABLED", true, 4);
        assertMutationRequestPushGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_PUSH_DISABLED", true, 4);
        assertMutationRequestClaimGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_CLAIM_DISABLED", true, 4);
        assertMutationExecutionGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_EXECUTION_DISABLED", true, 4);
        assertMutationWriteHelperSafetyGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_WRITE_HELPER_DISABLED", true, 4);
        assertMutationPostExecutionObservationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED", true, 4);
        assertMutationObservationAcceptanceGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED", true, 4);
        assertMutationResultIntakePersistenceGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "REFUSED_INTAKE_PERSISTENCE_DISABLED",
                true,
                4
        );
        assertMutationRollbackFallbackGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_ROLLBACK_FALLBACK_DISABLED", true, 4);
        assertMutationRagFreshnessGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_RAG_FRESHNESS_DISABLED", true, 4);
        assertMutationResultAggregationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_RESULT_AGGREGATION_DISABLED", true, 4);
        assertMutationPublicationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_PUBLICATION_DISABLED", true, 4);
        assertMutationFinalAnswerGenerationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_GENERATION_DISABLED", true, 4);
        assertMutationFinalAnswerCompletionGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_COMPLETION_DISABLED", true, 4);
        assertMutationFinalAnswerPersistenceGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED", true, 4);
        assertMutationFinalAnswerConversationSaveGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED", true, 4);
        assertMutationFinalAnswerUserVisibleCompletionGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED", true, 4);
        assertMutationFinalResponseHandoffGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_RESPONSE_HANDOFF_DISABLED", true, 4);
        assertMutationFinalAnswerDeliveryGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_DELIVERY_DISABLED", true, 4);
        assertMutationFinalAnswerDeliveryReceiptGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED", true, 4);
        assertMutationResultIntakeBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "RESTORE_VALIDATED");
        assertMutationResultAggregationPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportDraft(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportFinalizationBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_FINALIZATION_DISABLED",
                false,
                "releaseAttemptReady"
        );
        assertFinalAnswerPublicationBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_PUBLICATION_DISABLED",
                false,
                "releaseAttemptReady"
        );
        assertReleaseEnablementChecklist(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_ENABLEMENT_DISABLED",
                false,
                "finalReadiness"
        );
        assertMutationCompletionSummary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_COMPLETION_DISABLED",
                false,
                "releaseAttemptReadiness",
                "finalMutationReportFinalizationBoundary",
                "finalAnswerPublicationBoundary",
                "releaseEnablementChecklist"
        );
        assertObservationLinkage(readiness.repositoryVerification(), requestId, attemptId, "SOURCE_ONLY_FALLBACK");
        assertObservationLinkage(readiness.snapshotReadiness(), requestId, attemptId, "SOURCE_ONLY_FALLBACK");
        assertThat((Long) readiness.releaseAttemptModel().latestAttempt().get("ageSeconds")).isBetween(0L, 30L);
        assertThat(readiness.patchExecutionGate())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).complete(any(LocalAgentToolResponse.class));
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void patchReadinessPrefersReleaseAttemptLinkedObservationsWithoutEnablingClaim() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of(),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MISMATCH",
                "blocking", true,
                "message", "Source-only fallback should not be preferred when linked evidence exists.",
                "checks", List.of(Map.of("key", "remote", "status", "MISMATCH", "expected", "a", "actual", "b"))
        )));
        when(repository.findLatestRepositoryVerificationForReleaseAttempt(userId, requestId, attemptId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Fresh linked repository observation matched.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(false)));
        when(repository.findLatestPatchDryRunOutputForReleaseAttempt(userId, requestId, attemptId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.repositoryVerification())
                .containsEntry("status", "MATCH")
                .containsEntry("message", "Fresh linked repository observation matched.");
        assertObservationLinkage(readiness.repositoryVerification(), requestId, attemptId, "RELEASE_ATTEMPT_LINKED");
        assertThat(readiness.snapshotReadiness())
                .containsEntry("status", "CREATED")
                .containsEntry("snapshotCreated", true);
        assertObservationLinkage(readiness.snapshotReadiness(), requestId, attemptId, "RELEASE_ATTEMPT_LINKED");
        assertFreshObservationEvidenceStatus(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "RELEASE_ATTEMPT_LINKED",
                "RELEASE_ATTEMPT_LINKED"
        );
        assertFreshObservationEvidenceCompleteness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "ALL_LINKED_RELEASE_DISABLED",
                true,
                2,
                0,
                0
        );
        assertReleaseAttemptFinalReadiness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "READY_RELEASE_DISABLED",
                true,
                "FRESH",
                false,
                true,
                true
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> evidenceCompleteness = (Map<String, Object>) readiness.releaseAttemptModel().latestAttempt()
                .get("freshObservationEvidenceCompleteness");
        assertThat(evidenceCompleteness.get("linkedKeys")).asList()
                .containsExactly("repositoryVerification", "patchDryRun");
        assertThat(evidenceCompleteness.get("missingKeys")).asList().isEmpty();
        assertThat(evidenceCompleteness.get("sourceOnlyFallbackKeys")).asList().isEmpty();
        assertThat(evidenceCompleteness.get("blockingKeys")).asList().isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> finalReadiness = (Map<String, Object>) readiness.releaseAttemptModel().latestAttempt()
                .get("releaseAttemptFinalReadiness");
        assertThat(finalReadiness)
                .containsEntry("evidenceCompletenessStatus", "ALL_LINKED_RELEASE_DISABLED")
                .containsEntry("patchReleaseStatus", "PRECONDITIONS_READY_RELEASE_DISABLED")
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(finalReadiness.get("blockingReasons")).asList()
                .containsExactly("release gate is disabled", "held patch request remains non-claimable");
        assertReleaseAttemptDisplaySummary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertLocalAgentMutationExecutionSequencePlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertPostMutationResultContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertMutationDispatchEnvelopeContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "READY_DISPATCH_DISABLED", true);
        assertMutationDispatchPreflightBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "READY_PREFLIGHT_DISABLED", true);
        assertMutationDispatchDecisionModel(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_DISPATCH_DISABLED", true);
        assertMutationRequestBlueprint(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_REQUEST_CREATION_DISABLED", true);
        assertMutationRequestCreationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_CREATION_DISABLED", true, 4);
        assertMutationRequestPushGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_PUSH_DISABLED", true, 4);
        assertMutationRequestClaimGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_CLAIM_DISABLED", true, 4);
        assertMutationExecutionGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_EXECUTION_DISABLED", true, 4);
        assertMutationWriteHelperSafetyGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_WRITE_HELPER_DISABLED", true, 4);
        assertMutationPostExecutionObservationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED", true, 4);
        assertMutationObservationAcceptanceGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED", true, 4);
        assertMutationResultIntakePersistenceGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "REFUSED_INTAKE_PERSISTENCE_DISABLED",
                true,
                4
        );
        assertMutationRollbackFallbackGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_ROLLBACK_FALLBACK_DISABLED", true, 4);
        assertMutationRagFreshnessGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_RAG_FRESHNESS_DISABLED", true, 4);
        assertMutationResultAggregationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_RESULT_AGGREGATION_DISABLED", true, 4);
        assertMutationPublicationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_PUBLICATION_DISABLED", true, 4);
        assertMutationFinalAnswerGenerationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_GENERATION_DISABLED", true, 4);
        assertMutationFinalAnswerCompletionGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_COMPLETION_DISABLED", true, 4);
        assertMutationFinalAnswerPersistenceGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED", true, 4);
        assertMutationFinalAnswerConversationSaveGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED", true, 4);
        assertMutationFinalAnswerUserVisibleCompletionGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED", true, 4);
        assertMutationFinalResponseHandoffGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_RESPONSE_HANDOFF_DISABLED", true, 4);
        assertMutationFinalAnswerDeliveryGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_DELIVERY_DISABLED", true, 4);
        assertMutationFinalAnswerDeliveryReceiptGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, request.sessionId(), userId, agentId, workspaceId, "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED", true, 4);
        assertMutationResultIntakeBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "RESTORE_VALIDATED");
        assertMutationResultAggregationPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportDraft(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportFinalizationBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "READY_FINALIZATION_DISABLED",
                true
        );
        assertFinalAnswerPublicationBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "READY_PUBLICATION_DISABLED",
                true
        );
        assertReleaseEnablementChecklist(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "READY_ENABLEMENT_DISABLED",
                true
        );
        assertMutationCompletionSummary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "READY_COMPLETION_DISABLED",
                true
        );
        assertMutationHandoffSummary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "READY_HANDOFF_DISABLED",
                true,
                "releaseGateEnabled",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "mutationAllowed"
        );
        assertMutationExecutionReadinessBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "REFUSED_EXECUTION_READINESS_DISABLED",
                true,
                "runtimeExecutionSwitch",
                "sideEffectTransport",
                "releaseGateEnabled",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "executionEnabled",
                "writeHelperEnabled",
                "applyEnabled",
                "testEnabled",
                "rollbackRestoreEnabled",
                "resultIntakeEnabled",
                "mutationAllowed"
        );
        assertMutationToolRunnerBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "REFUSED_TOOL_RUNNER_DISABLED",
                true,
                "toolRunnerPolicy",
                "requestRunningTransition",
                "resultCompletionTransition",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "runningTransitionEnabled",
                "toolRunnerEnabled",
                "writeHelperEnabled",
                "applyEnabled",
                "testEnabled",
                "rollbackRestoreEnabled",
                "resultIntakeEnabled",
                "mutationAllowed"
        );
        assertMutationResultCompletionBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "REFUSED_RESULT_COMPLETION_DISABLED",
                true,
                "completedResultTransition",
                "resultEnvelopePersistence",
                "observationCapture",
                "toolRunnerEnabled",
                "completedResultTransitionEnabled",
                "completedResultPersistenceEnabled",
                "postExecutionObservationEnabled",
                "resultIntakeEnabled",
                "mutationAllowed"
        );
        assertThat(readiness.patchExecutionGate())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(repository).findLatestRepositoryVerificationForReleaseAttempt(userId, requestId, attemptId);
        verify(repository, never()).findLatestRepositoryVerificationForSourceRequest(userId, requestId);
        verify(repository).findLatestPatchDryRunOutputForReleaseAttempt(userId, requestId, attemptId);
        verify(repository, never()).findLatestPatchDryRunOutputForSourceRequest(userId, requestId);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).complete(any(LocalAgentToolResponse.class));
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void patchReadinessMarksMissingReleaseAttemptLinkedEvidenceWithoutCreatingRequests() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of(),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertFreshObservationEvidenceStatus(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "MISSING",
                "MISSING"
        );
        assertFreshObservationEvidenceCompleteness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "INCOMPLETE_RELEASE_DISABLED",
                false,
                0,
                2,
                0
        );
        assertReleaseAttemptFinalReadiness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_RELEASE_DISABLED",
                false,
                "FRESH",
                false,
                false,
                false
        );
        assertLocalAgentMutationExecutionSequencePlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertPostMutationResultContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertMutationDispatchEnvelopeContract(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_DISPATCH_DISABLED",
                false,
                "rollbackReadiness"
        );
        assertMutationDispatchPreflightBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_PREFLIGHT_DISABLED",
                false,
                "workspaceIdentityVerified",
                "mutationDispatchEnvelopeContract"
        );
        assertMutationDispatchDecisionModel(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_DISPATCH_DISABLED",
                false,
                "mutationDispatchEnvelopeContract",
                "mutationDispatchPreflightBoundary"
        );
        assertMutationRequestBlueprint(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_REQUEST_BLUEPRINT_DISABLED",
                false
        );
        assertMutationRequestCreationGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_CREATION_DISABLED",
                false,
                4
        );
        assertMutationRequestPushGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_PUSH_DISABLED",
                false,
                4
        );
        assertMutationRequestClaimGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_CLAIM_DISABLED",
                false,
                4
        );
        assertMutationExecutionGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_EXECUTION_DISABLED",
                false,
                4
        );
        assertMutationWriteHelperSafetyGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_WRITE_HELPER_DISABLED",
                false,
                4
        );
        assertMutationPostExecutionObservationGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED",
                false,
                4
        );
        assertMutationObservationAcceptanceGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED",
                false,
                4
        );
        assertMutationResultIntakePersistenceGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_INTAKE_PERSISTENCE_DISABLED",
                false,
                4
        );
        assertMutationRollbackFallbackGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_ROLLBACK_FALLBACK_DISABLED",
                false,
                4
        );
        assertMutationRagFreshnessGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_RAG_FRESHNESS_DISABLED",
                false,
                4
        );
        assertMutationResultAggregationGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_RESULT_AGGREGATION_DISABLED",
                false,
                4
        );
        assertMutationPublicationGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_PUBLICATION_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerGenerationGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_GENERATION_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerCompletionGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_COMPLETION_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerPersistenceGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_PERSISTENCE_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerConversationSaveGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerUserVisibleCompletionGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED",
                false,
                4
        );
        assertMutationFinalResponseHandoffGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_RESPONSE_HANDOFF_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerDeliveryGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_DELIVERY_DISABLED",
                false,
                4
        );
        assertMutationFinalAnswerDeliveryReceiptGate(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED",
                false,
                4
        );
        assertMutationResultIntakeBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "MISSING");
        assertMutationResultAggregationPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportDraft(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportFinalizationBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_FINALIZATION_DISABLED",
                false,
                "releaseAttemptReady"
        );
        assertFinalAnswerPublicationBoundary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_PUBLICATION_DISABLED",
                false,
                "releaseAttemptReady"
        );
        assertReleaseEnablementChecklist(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_ENABLEMENT_DISABLED",
                false,
                "finalReadiness",
                "rollbackReadiness"
        );
        assertMutationCompletionSummary(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                "BLOCKED_COMPLETION_DISABLED",
                false,
                "releaseAttemptReadiness",
                "finalMutationReportFinalizationBoundary",
                "finalAnswerPublicationBoundary",
                "releaseEnablementChecklist",
                "mutationDispatchEnvelopeContract",
                "mutationDispatchPreflightBoundary",
                "mutationDispatchDecisionModel",
                "mutationRequestBlueprint",
                "mutationRequestCreationGate",
                "mutationRequestPushGate",
                "mutationRequestClaimGate",
                "mutationExecutionGate",
                "mutationWriteHelperSafetyGate",
                "mutationPostExecutionObservationGate",
                "mutationObservationAcceptanceGate",
                "mutationResultIntakePersistenceGate",
                "mutationRollbackFallbackGate",
                "mutationRagFreshnessGate",
                "mutationResultAggregationGate",
                "mutationPublicationGate",
                "mutationFinalAnswerGenerationGate",
                "mutationFinalAnswerCompletionGate",
                "mutationFinalAnswerPersistenceGate",
                "mutationFinalAnswerConversationSaveGate",
                "mutationFinalAnswerUserVisibleCompletionGate",
                "mutationFinalResponseHandoffGate",
                "mutationFinalAnswerDeliveryGate",
                "mutationFinalAnswerDeliveryReceiptGate",
                "rollbackReadiness"
        );
        assertThat(readiness.repositoryVerification()).isNull();
        assertThat(readiness.snapshotReadiness()).containsEntry("status", "MISSING");
        assertThat(readiness.patchExecutionGate())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).complete(any(LocalAgentToolResponse.class));
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void patchReadinessMarksOldDisabledReleaseAttemptAsStaleWithoutEnablingClaim() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(180);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));
        when(repository.findLatestAcceptedMutationObservationForReleaseAttempt(userId, requestId, attemptId)).thenReturn(java.util.Optional.of(Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.accepted-mutation-observation.v1"),
                Map.entry("status", "ACCEPTED"),
                Map.entry("accepted", true),
                Map.entry("toolName", "patch.apply"),
                Map.entry("sourceRequestId", requestId.toString()),
                Map.entry("releaseAttemptId", attemptId.toString()),
                Map.entry("acceptedObservationPersistenceEnabled", false),
                Map.entry("resultAggregationEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementSaveEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false)
        )));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of(),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));

        var readiness = service.inspectPatchExecutionReadiness(userId, requestId);

        assertThat(readiness.releaseAttemptModel().latestAttempt())
                .containsEntry("freshnessStatus", "STALE")
                .containsEntry("stale", true)
                .containsEntry("expiresAt", attemptCreatedAt.plusSeconds(120));
        assertFreshObservationRequirements(readiness.releaseAttemptModel().latestAttempt(), attemptCreatedAt);
        assertFreshObservationRequestPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, attemptCreatedAt);
        assertThat(readiness.releaseAttemptModel().latestAttempt().get("acceptedMutationObservationReadiness")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> observationReadiness = (Map<String, Object>) readiness.releaseAttemptModel().latestAttempt()
                .get("acceptedMutationObservationReadiness");
        assertThat(observationReadiness)
                .containsEntry("schema", "learnbot.local-agent.accepted-mutation-observation-readiness.v1")
                .containsEntry("status", "OBSERVED_INTAKE_DISABLED")
                .containsEntry("observed", true)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("resultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationAllowed", false);
        assertThat(observationReadiness.get("latestObservation")).isInstanceOf(Map.class);
        assertReleaseAttemptFinalReadiness(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_RELEASE_DISABLED",
                false,
                "STALE",
                true,
                false,
                true
        );
        assertReleaseEnablementChecklist(
                readiness.releaseAttemptModel().latestAttempt(),
                attemptId,
                requestId,
                "BLOCKED_ENABLEMENT_DISABLED",
                false,
                "finalReadiness"
        );
        assertThat((Long) readiness.releaseAttemptModel().latestAttempt().get("ageSeconds")).isGreaterThanOrEqualTo(180L);
        assertThat(readiness.releaseAttemptModel().claimable()).isFalse();
        assertThat(readiness.patchExecutionGate())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void releaseHeldPatchForExecutionCreatesDisabledAttemptAndRefusesWithoutFreshLinkedEvidence() {
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));

        assertThatThrownBy(() -> service.releaseHeldPatchForExecution(userId, requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires fresh release-attempt-linked evidence before claim");

        var sourceCaptor = forClass(LocalAgentToolExecution.class);
        var attemptIdCaptor = forClass(UUID.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> evidenceCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> reasonsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(releaseAttemptRepository).createDisabled(
                attemptIdCaptor.capture(),
                sourceCaptor.capture(),
                eq(120),
                evidenceCaptor.capture(),
                reasonsCaptor.capture()
        );
        UUID attemptId = attemptIdCaptor.getValue();
        assertThat(sourceCaptor.getValue().id()).isEqualTo(requestId);
        assertThat(evidenceCaptor.getValue())
                .containsEntry("sourceRequestId", requestId)
                .containsEntry("freshObservationEnqueueEnabled", false)
                .containsEntry("claimable", false);
        assertDisabledFreshObservationRequestTemplates(evidenceCaptor.getValue(), attemptId, requestId);
        assertDisabledFreshObservationEnqueueBoundary(evidenceCaptor.getValue(), attemptId, requestId);
        Map<String, Object> attemptGate = ((Map<?, ?>) evidenceCaptor.getValue().get("patchExecutionGate"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(attemptGate)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        assertThat(reasonsCaptor.getValue()).contains("Patch execution release is disabled; attempt remains non-claimable.");
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void releaseHeldPatchForExecutionRefusesOnlyOnReleaseFlagWhenFreshLinkedEvidenceIsReady() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                requestId,
                request.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of(),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));
        when(repository.findLatestRepositoryVerificationForReleaseAttempt(userId, requestId, attemptId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Fresh linked repository observation matched.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForReleaseAttempt(userId, requestId, attemptId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));

        assertThatThrownBy(() -> service.releaseHeldPatchForExecution(userId, requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release is disabled");

        verify(releaseAttemptRepository, never()).createDisabled(any(), any(), anyInt(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void releaseBoundaryCreatesDisabledAttemptAndReturnsRefusalDiagnosticsWithoutClaiming() {
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, requestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));
        AtomicReference<LocalAgentPatchReleaseAttempt> attempt = new AtomicReference<>();
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId)).thenAnswer(invocation ->
                java.util.Optional.ofNullable(attempt.get()));
        doAnswer(invocation -> {
            UUID attemptId = invocation.getArgument(0);
            LocalAgentToolExecution source = invocation.getArgument(1);
            Integer staleWindowSeconds = invocation.getArgument(2);
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = invocation.getArgument(3);
            @SuppressWarnings("unchecked")
            List<String> failureReasons = invocation.getArgument(4);
            attempt.set(new LocalAgentPatchReleaseAttempt(
                    attemptId,
                    source.id(),
                    source.sessionId(),
                    source.userId(),
                    source.agentId(),
                    source.workspaceId(),
                    LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                    false,
                    staleWindowSeconds,
                    evidence,
                    failureReasons,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                    null
            ));
            return null;
        }).when(releaseAttemptRepository).createDisabled(any(), any(), anyInt(), any(), any());

        var boundary = service.inspectPatchReleaseBoundary(userId, requestId);

        assertThat(boundary.status()).isEqualTo("RELEASE_REFUSED_GATE_DISABLED");
        assertThat(boundary.actionMode()).isEqualTo("REFUSAL_ONLY");
        assertThat(boundary.releaseGateEnabled()).isFalse();
        assertThat(boundary.claimEnabled()).isFalse();
        assertThat(boundary.writeHelperEnabled()).isFalse();
        assertThat(boundary.requestCreationEnabled()).isFalse();
        assertThat(boundary.pushEnabled()).isFalse();
        assertThat(boundary.claimable()).isFalse();
        assertThat(boundary.mutationAllowed()).isFalse();
        assertThat(boundary.applyEnabled()).isFalse();
        assertThat(boundary.testEnabled()).isFalse();
        assertThat(boundary.rollbackRestoreEnabled()).isFalse();
        assertThat(boundary.ragFreshnessUpdateEnabled()).isFalse();
        assertThat(boundary.releaseAttemptModel().created()).isTrue();
        assertThat(boundary.releaseAttemptModel().claimable()).isFalse();
        assertThat(boundary.releaseEnablementChecklist())
                .containsEntry("status", "BLOCKED_ENABLEMENT_DISABLED")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(boundary.blockingReasons())
                .contains(
                        "fresh release-attempt-linked evidence is required before claim",
                        "release gate is disabled",
                        "held patch request remains non-claimable",
                        "Local Agent request creation and push remain disabled"
                );
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void releaseBoundaryRefusesBlockedReadinessWithoutCreatingAttempt() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = patchRequest(userId, UUID.randomUUID(), UUID.randomUUID());
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        )));
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.DISCONNECTED,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is disconnected."
        ));

        var boundary = service.inspectPatchReleaseBoundary(userId, requestId);

        assertThat(boundary.status()).isEqualTo("RELEASE_REFUSED_PRECONDITIONS_BLOCKED");
        assertThat(boundary.actionMode()).isEqualTo("REFUSAL_ONLY");
        assertThat(boundary.releaseGateEnabled()).isFalse();
        assertThat(boundary.claimable()).isFalse();
        assertThat(boundary.mutationAllowed()).isFalse();
        assertThat(boundary.releaseAttemptModel().created()).isFalse();
        assertThat(boundary.blockingReasons())
                .contains("patch execution preconditions are incomplete", "no disabled release attempt envelope exists yet", "release gate is disabled");
        verify(releaseAttemptRepository, never()).createDisabled(any(), any(), anyInt(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void enqueuePatchDryRunClonesApprovedHeldRequestWithoutChangingSource() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        LocalAgentToolRequest sourceRequest = patchRequest(userId, agentId, workspaceId);
        LocalAgentToolExecution source = execution(
                sourceRequestId,
                sourceRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        when(repository.find(sourceRequestId)).thenReturn(java.util.Optional.of(source));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(invocation.getArgument(0), invocation.getArgument(1)));

        var queued = service.enqueuePatchDryRun(userId, sourceRequestId);

        var requestCaptor = forClass(LocalAgentToolRequest.class);
        verify(repository).create(eq(queued.requestId()), requestCaptor.capture());
        LocalAgentToolRequest dryRunRequest = requestCaptor.getValue();
        assertThat(queued.requestId()).isNotEqualTo(sourceRequestId);
        assertThat(dryRunRequest.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(dryRunRequest.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
        assertThat(dryRunRequest.input()).containsEntry("dryRunOnly", true);
        assertThat(dryRunRequest.input()).containsEntry("mutationAllowed", false);
        assertThat(dryRunRequest.input()).containsEntry("sourceRequestId", sourceRequestId.toString());
        assertThat(dryRunRequest.warnings()).anyMatch(warning -> warning.contains("Mutation remains disabled"));
        verify(toolPusher).sendToolRequest(queued);
        verify(repository, org.mockito.Mockito.never()).complete(any());
        verify(repository, org.mockito.Mockito.never()).updateApprovalDecision(
                eq(sourceRequestId),
                eq(userId),
                any(LocalAgentApprovalState.class),
                any(LocalAgentToolStatus.class),
                any(String.class)
        );
    }

    @Test
    void enqueuePatchDryRunRejectsRequestsThatAreNotApprovedHeld() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = patchRequest(userId, UUID.randomUUID(), UUID.randomUUID());
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED
        )));

        assertThatThrownBy(() -> service.enqueuePatchDryRun(userId, requestId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved-held");
        verify(toolPusher, org.mockito.Mockito.never()).sendToolRequest(any());
    }

    @Test
    void enqueueReleaseAttemptFreshObservationsQueuesLinkedReadOnlyAndDryRunWithoutReleasingSource() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime attemptCreatedAt = OffsetDateTime.now().minusSeconds(5);
        LocalAgentToolRequest sourceRequest = patchRequest(userId, agentId, workspaceId);
        LocalAgentToolExecution source = execution(
                sourceRequestId,
                sourceRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        when(repository.find(sourceRequestId)).thenReturn(java.util.Optional.of(source));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, sourceRequestId)).thenReturn(java.util.Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                sourceRequestId,
                sourceRequest.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of(),
                List.of("release gate disabled"),
                attemptCreatedAt,
                attemptCreatedAt.plusSeconds(1),
                null
        )));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(invocation.getArgument(0), invocation.getArgument(1)));

        List<LocalAgentQueuedToolRequest> queued = service.enqueueReleaseAttemptFreshObservations(userId, sourceRequestId);

        assertThat(queued).hasSize(2);
        assertThat(queued)
                .extracting(item -> item.request().toolName())
                .containsExactly(LocalAgentToolName.GIT_STATUS, LocalAgentToolName.PATCH_APPLY);
        assertThat(queued)
                .extracting(LocalAgentQueuedToolRequest::requestId)
                .doesNotContain(sourceRequestId)
                .doesNotHaveDuplicates();

        var requestCaptor = forClass(LocalAgentToolRequest.class);
        var requestIdCaptor = forClass(UUID.class);
        verify(repository, org.mockito.Mockito.times(2)).create(requestIdCaptor.capture(), requestCaptor.capture());
        assertThat(requestIdCaptor.getAllValues())
                .doesNotContain(sourceRequestId)
                .doesNotHaveDuplicates();
        List<LocalAgentToolRequest> createdRequests = requestCaptor.getAllValues();
        LocalAgentToolRequest repositoryObservation = createdRequests.get(0);
        assertThat(repositoryObservation.toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(repositoryObservation.approvalState()).isEqualTo(LocalAgentApprovalState.NOT_REQUIRED);
        assertThat(repositoryObservation.input())
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", attemptId.toString())
                .containsEntry("freshObservationOnly", true);
        assertThat(repositoryObservation.input()).containsKey("sourceRepository");
        assertThat(repositoryObservation.warnings()).anyMatch(warning -> warning.contains("Read-only"));

        LocalAgentToolRequest patchDryRun = createdRequests.get(1);
        assertThat(patchDryRun.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(patchDryRun.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
        assertThat(patchDryRun.input())
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", attemptId.toString())
                .containsEntry("freshObservationOnly", true);
        assertThat(patchDryRun.warnings()).anyMatch(warning -> warning.contains("source request stays held"));
        assertThat(repository.find(sourceRequestId).orElseThrow().status()).isEqualTo(LocalAgentToolStatus.APPROVED_HELD);
        assertThat(repository.find(sourceRequestId).orElseThrow().approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);

        verify(toolPusher, org.mockito.Mockito.times(2)).sendToolRequest(any(LocalAgentQueuedToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).complete(any(LocalAgentToolResponse.class));
        verify(repository, never()).updateApprovalDecision(
                eq(sourceRequestId),
                eq(userId),
                any(LocalAgentApprovalState.class),
                any(LocalAgentToolStatus.class),
                any(String.class)
        );
    }

    @Test
    void enqueueReleaseAttemptFreshObservationsCreatesDisabledAttemptThenQueuesLinkedRequests() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        LocalAgentToolRequest sourceRequest = patchRequest(userId, agentId, workspaceId);
        LocalAgentToolExecution source = execution(
                sourceRequestId,
                sourceRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        AtomicReference<LocalAgentPatchReleaseAttempt> attempt = new AtomicReference<>();
        when(repository.find(sourceRequestId)).thenReturn(java.util.Optional.of(source));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, sourceRequestId)).thenAnswer(invocation ->
                java.util.Optional.ofNullable(attempt.get()));
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.CONNECTED,
                agentId,
                "0.1.0",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("file.read", "git.status", "git.diff", "patch.apply", "command.runAllowed", "rollback.restore"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.findLatestRepositoryVerificationForSourceRequest(userId, sourceRequestId)).thenReturn(java.util.Optional.of(Map.of(
                "status", "MATCH",
                "blocking", true,
                "message", "Observed local repository identity matches available indexed metadata.",
                "checks", List.of(Map.of("key", "branch", "status", "MATCH", "expected", "main", "actual", "main"))
        )));
        when(repository.findLatestPatchDryRunOutputForSourceRequest(userId, sourceRequestId)).thenReturn(java.util.Optional.of(patchDryRunOutput(true)));
        doAnswer(invocation -> {
            UUID attemptId = invocation.getArgument(0);
            LocalAgentToolExecution attemptSource = invocation.getArgument(1);
            Integer staleWindowSeconds = invocation.getArgument(2);
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = invocation.getArgument(3);
            @SuppressWarnings("unchecked")
            List<String> failureReasons = invocation.getArgument(4);
            attempt.set(new LocalAgentPatchReleaseAttempt(
                    attemptId,
                    attemptSource.id(),
                    attemptSource.sessionId(),
                    attemptSource.userId(),
                    attemptSource.agentId(),
                    attemptSource.workspaceId(),
                    LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                    false,
                    staleWindowSeconds,
                    evidence,
                    failureReasons,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                    null
            ));
            return null;
        }).when(releaseAttemptRepository).createDisabled(any(), any(), anyInt(), any(), any());
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(invocation.getArgument(0), invocation.getArgument(1)));

        List<LocalAgentQueuedToolRequest> queued = service.enqueueReleaseAttemptFreshObservations(userId, sourceRequestId);

        assertThat(attempt.get()).isNotNull();
        assertThat(attempt.get().claimable()).isFalse();
        assertThat(attempt.get().status()).isEqualTo(LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS);
        assertThat(queued).hasSize(2);
        assertThat(queued)
                .extracting(item -> item.request().toolName())
                .containsExactly(LocalAgentToolName.GIT_STATUS, LocalAgentToolName.PATCH_APPLY);

        var requestCaptor = forClass(LocalAgentToolRequest.class);
        verify(repository, org.mockito.Mockito.times(2)).create(any(UUID.class), requestCaptor.capture());
        List<LocalAgentToolRequest> createdRequests = requestCaptor.getAllValues();
        assertThat(createdRequests).allSatisfy(item -> assertThat(item.input())
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", attempt.get().id().toString())
                .containsEntry("freshObservationOnly", true));
        assertThat(createdRequests.get(0).toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(createdRequests.get(1).toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(createdRequests.get(1).input())
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        verify(releaseAttemptRepository).createDisabled(any(), eq(source), eq(120), any(), any());
        verify(toolPusher, org.mockito.Mockito.times(2)).sendToolRequest(any(LocalAgentQueuedToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).complete(any(LocalAgentToolResponse.class));
    }

    @Test
    void enqueueReleaseAttemptFreshObservationsRequiresDisabledNonClaimableAttempt() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        LocalAgentToolRequest sourceRequest = patchRequest(userId, agentId, workspaceId);
        when(repository.find(sourceRequestId)).thenReturn(java.util.Optional.of(execution(
                sourceRequestId,
                sourceRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        )));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, sourceRequestId)).thenReturn(java.util.Optional.empty());
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.DISCONNECTED,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is not connected."
        ));

        assertThatThrownBy(() -> service.enqueueReleaseAttemptFreshObservations(userId, sourceRequestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled non-claimable release attempt");
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
    }

    @Test
    void completeAddsRepositoryVerificationToGitStatusObservationOnly() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                Map.of("sourceRepository", Map.of(
                        "branch", "main",
                        "lastIndexedCommit", "abcdef123456",
                        "gitUrl", "https://example.com/acme/learnbot.git"
                )),
                LocalAgentApprovalState.NOT_REQUIRED,
                null,
                List.of()
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.NOT_REQUIRED,
                LocalAgentToolStatus.SUCCEEDED
        )));
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                request.sessionId(),
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("repositoryIdentity", Map.of(
                        "branch", "main",
                        "headCommit", "abcdef123456",
                        "remoteUrl", "git@example.com:acme/learnbot.git"
                )),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );

        service.complete(response);

        var responseCaptor = forClass(LocalAgentToolResponse.class);
        verify(repository).complete(responseCaptor.capture());
        Object verification = responseCaptor.getValue().output().get("repositoryVerification");
        assertThat(verification).isInstanceOf(Map.class);
        assertThat(verification.toString()).contains("MATCH", "blocking=true", "branch", "head", "remote");
        verify(repository, org.mockito.Mockito.never()).updateApprovalDecision(
                any(UUID.class),
                any(UUID.class),
                any(LocalAgentApprovalState.class),
                any(LocalAgentToolStatus.class),
                any(String.class)
        );
    }

    @Test
    void completePatchDryRunObservationDoesNotReleaseHeldSourceOrCreateMutationWork() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID dryRunRequestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                dryRunRequestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "dryRun", true,
                        "mutationApplied", false,
                        "sourceRequestId", sourceRequestId.toString(),
                        "files", List.of(Map.of(
                                "path", "src/App.jsx",
                                "contextMatched", true,
                                "wouldChange", true
                        ))
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("dry-run completed without mutation")
        );

        service.complete(response);

        var responseCaptor = forClass(LocalAgentToolResponse.class);
        verify(repository).complete(responseCaptor.capture());
        assertThat(responseCaptor.getValue().requestId()).isEqualTo(dryRunRequestId);
        assertThat(responseCaptor.getValue().toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(responseCaptor.getValue().output())
                .containsEntry("dryRun", true)
                .containsEntry("mutationApplied", false)
                .containsEntry("sourceRequestId", sourceRequestId.toString());
        verify(mutationObservationIntakeRepository, never()).saveAcceptedObservation(any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).updateApprovalDecision(
                eq(sourceRequestId),
                eq(userId),
                any(LocalAgentApprovalState.class),
                any(LocalAgentToolStatus.class),
                any(String.class)
        );
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).claimNext(any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void completeMutationPatchResultAddsAuditOnlyIntakeCandidateWithoutOpeningFollowupWork() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of(
                        "sourceRequestId", sourceRequestId.toString(),
                        "releaseAttemptId", releaseAttemptId.toString(),
                        "dryRunOnly", false,
                        "mutationAllowed", true
                ),
                LocalAgentApprovalState.APPROVED,
                null,
                List.of("mutation execution")
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.SUCCEEDED
        )));
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                request.sessionId(),
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "mutationApplied", true,
                        "snapshotManifestId", "snap-123",
                        "rollbackAvailable", true
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("mutation applied")
        );

        service.complete(response);

        var responseCaptor = forClass(LocalAgentToolResponse.class);
        verify(repository).complete(responseCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = (Map<String, Object>) responseCaptor.getValue().output()
                .get("mutationResultIntakeCandidate");
        assertThat(candidate)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-intake-candidate.v1")
                .containsEntry("status", "OBSERVED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("mutationApplied", true)
                .containsEntry("snapshotManifestId", "snap-123")
                .containsEntry("acceptanceStatus", "ACCEPTED")
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted = (Map<String, Object>) responseCaptor.getValue().output()
                .get("acceptedMutationObservation");
        assertThat(accepted)
                .containsEntry("schema", "learnbot.local-agent.accepted-mutation-observation.v1")
                .containsEntry("status", "ACCEPTED")
                .containsEntry("accepted", true)
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("resultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false);
        verify(mutationObservationIntakeRepository).saveAcceptedObservation(eq(responseCaptor.getValue()), eq(request.input()));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).claimNext(any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void claimNextExpiresTimedOutLeasesBeforeClaimingNextRequest() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID expiredRequestId = UUID.randomUUID();
        UUID nextRequestId = UUID.randomUUID();
        LocalAgentToolRequest expiredRequest = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of(
                        "repositoryId", repositoryId.toString(),
                        "loopId", loopId.toString(),
                        "dryRunOnly", true,
                        "mutationAllowed", false
                ),
                LocalAgentApprovalState.APPROVED,
                OffsetDateTime.now().minusMinutes(10),
                List.of("lease candidate")
        );
        LocalAgentToolRequest nextRequest = request(
                userId,
                agentId,
                workspaceId,
                LocalAgentToolName.FILE_READ,
                LocalAgentApprovalState.NOT_REQUIRED
        );
        LocalAgentToolExecution expired = new LocalAgentToolExecution(
                expiredRequestId,
                expiredRequest.sessionId(),
                expiredRequest.userId(),
                expiredRequest.agentId(),
                expiredRequest.workspaceId(),
                expiredRequest.executionTarget(),
                expiredRequest.toolName(),
                expiredRequest.approvalState(),
                LocalAgentToolStatus.TIMED_OUT,
                expiredRequest.input(),
                Map.of(),
                LocalAgentFailureCode.TIMEOUT,
                "Local Agent tool execution lease timed out before completion.",
                expiredRequest.warnings(),
                List.of("Local Agent tool execution lease timed out before completion."),
                expiredRequest.createdAt(),
                OffsetDateTime.now().minusMinutes(9),
                OffsetDateTime.now().minusMinutes(4)
        );
        when(repository.expireTimedOutLeases()).thenReturn(List.of(expired));
        when(repository.claimNext(userId, agentId)).thenReturn(java.util.Optional.of(execution(
                nextRequestId,
                nextRequest,
                LocalAgentApprovalState.NOT_REQUIRED,
                LocalAgentToolStatus.RUNNING
        )));

        var queued = service.claimNext(userId, agentId).orElseThrow();

        assertThat(queued.requestId()).isEqualTo(nextRequestId);
        verify(repository).expireTimedOutLeases();
        verify(repository).claimNext(userId, agentId);
        verify(loopTimelineRepository).appendTimedOutStopOutcome(
                eq(userId),
                eq(repositoryId),
                eq(loopId),
                any(LocalAgentToolResponse.class),
                eq(expiredRequest.input())
        );
        verify(repository, never()).complete(any(LocalAgentToolResponse.class));
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void completeAppendsAuditOnlyLoopObservationEventWhenRepositoryContextExists() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID dryRunRequestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of(
                        "repositoryId", repositoryId.toString(),
                        "loopId", loopId.toString(),
                        "sourceRequestId", sourceRequestId.toString(),
                        "releaseAttemptId", UUID.randomUUID().toString(),
                        "freshObservationOnly", true,
                        "dryRunOnly", true,
                        "mutationAllowed", false
                ),
                LocalAgentApprovalState.APPROVED,
                null,
                List.of("dry-run observation only")
        );
        when(repository.find(dryRunRequestId)).thenReturn(java.util.Optional.of(execution(
                dryRunRequestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.SUCCEEDED
        )));
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                request.sessionId(),
                dryRunRequestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "dryRun", true,
                        "mutationApplied", false,
                        "snapshotCreated", true
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("dry-run completed without mutation")
        );

        service.complete(response);

        verify(repository).complete(response);
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).claimNext(any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void completeAppendsAuditOnlyStopOutcomeWhenObservationFails() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of(
                        "repositoryId", repositoryId.toString(),
                        "loopId", loopId.toString(),
                        "dryRunOnly", true,
                        "mutationAllowed", false
                ),
                LocalAgentApprovalState.APPROVED,
                null,
                List.of("dry-run observation only")
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.FAILED
        )));
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                request.sessionId(),
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.FAILED,
                Map.of(
                        "dryRun", true,
                        "mutationApplied", false
                ),
                LocalAgentFailureCode.TOOL_FAILED,
                "Local Agent reported failure.",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("dry-run failed without mutation")
        );

        service.complete(response);

        verify(repository).complete(response);
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(loopTimelineRepository).appendToolFailedStopOutcome(
                userId,
                repositoryId,
                loopId,
                response,
                request.input()
        );
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).claimNext(any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void completeAppendsAuditOnlyTimeoutStopOutcomeWhenObservationTimesOut() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString()),
                LocalAgentApprovalState.APPROVED,
                null,
                List.of("dry-run observation only")
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.TIMED_OUT
        )));
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                request.sessionId(),
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.TIMED_OUT,
                Map.of("dryRun", true, "mutationApplied", false),
                LocalAgentFailureCode.TIMEOUT,
                "Local Agent tool timed out.",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("timed out without mutation")
        );

        service.complete(response);

        verify(repository).complete(response);
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(loopTimelineRepository).appendTimedOutStopOutcome(userId, repositoryId, loopId, response, request.input());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).claimNext(any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void completeAppendsAuditOnlyCancellationStopOutcomeWhenObservationIsCancelled() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString()),
                LocalAgentApprovalState.APPROVED,
                null,
                List.of("dry-run observation only")
        );
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(
                requestId,
                request,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.CANCELLED
        )));
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                request.sessionId(),
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.CANCELLED,
                Map.of("dryRun", true, "mutationApplied", false),
                null,
                "Local Agent tool was cancelled.",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("cancelled without mutation")
        );

        service.complete(response);

        verify(repository).complete(response);
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(loopTimelineRepository).appendCancellationStopOutcome(userId, repositoryId, loopId, response, request.input());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).claimNext(any(), any());
        verify(toolPusher, never()).sendToolRequest(any());
    }

    @Test
    void completeSkipsLoopObservationEventWhenRepositoryContextIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.FILE_READ,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("content", "ok"),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );
        LocalAgentToolRequest request = request(userId, agentId, workspaceId, LocalAgentToolName.FILE_READ, LocalAgentApprovalState.NOT_REQUIRED);
        when(repository.find(requestId)).thenReturn(java.util.Optional.of(execution(requestId, request)));

        service.complete(response);

        verify(repository).complete(response);
        verify(loopTimelineRepository, never()).appendObservationResult(any(), any(), any(), any(), any());
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

    private void assertFreshObservationRequirements(Map<String, Object> latestAttempt, OffsetDateTime requiredAfter) {
        assertThat(latestAttempt.get("freshObservationRequirements")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requirements = (List<Map<String, Object>>) latestAttempt.get("freshObservationRequirements");
        assertThat(requirements)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "repositoryVerificationAfterAttempt",
                        "patchDryRunAfterAttempt",
                        "snapshotCreatedAfterFreshDryRun",
                        "rollbackValidatedAfterFreshSnapshot",
                        "userReleaseApprovalAfterFreshEvidence"
                );
        assertThat(requirements).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "REQUIRED_AFTER_RELEASE_ATTEMPT")
                .containsEntry("required", true)
                .containsEntry("passed", false)
                .containsEntry("requiredAfter", requiredAfter));
    }

    private void assertFreshObservationRequestPlan(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            OffsetDateTime requiredAfter
    ) {
        assertThat(latestAttempt.get("freshObservationRequestPlan")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plan = (List<Map<String, Object>>) latestAttempt.get("freshObservationRequestPlan");
        assertThat(plan)
                .extracting(item -> item.get("key"))
                .containsExactly("repositoryVerification", "patchDryRun");
        assertThat(plan).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "PLANNED_DISABLED")
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimableAfterEnqueue", false)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("requiredAfter", requiredAfter));

        Map<String, Object> repositoryVerification = plan.stream()
                .filter(item -> "repositoryVerification".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(repositoryVerification)
                .containsEntry("toolName", LocalAgentToolName.GIT_STATUS.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.NOT_REQUIRED.name())
                .containsEntry("dryRunOnly", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("requiresSnapshot", false);

        Map<String, Object> patchDryRun = plan.stream()
                .filter(item -> "patchDryRun".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(patchDryRun)
                .containsEntry("toolName", LocalAgentToolName.PATCH_APPLY.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.APPROVED.name())
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("requiresSnapshot", true);
    }

    private void assertFreshObservationEvidenceStatus(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String repositoryStatus,
            String dryRunStatus
    ) {
        assertThat(latestAttempt.get("freshObservationEvidenceStatus")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statuses = (List<Map<String, Object>>) latestAttempt.get("freshObservationEvidenceStatus");
        assertThat(statuses)
                .extracting(item -> item.get("key"))
                .containsExactly("repositoryVerification", "patchDryRun");
        Map<String, Object> repositoryVerification = statuses.stream()
                .filter(item -> "repositoryVerification".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> patchDryRun = statuses.stream()
                .filter(item -> "patchDryRun".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        assertFreshObservationEvidenceItem(repositoryVerification, attemptId, sourceRequestId, repositoryStatus);
        assertFreshObservationEvidenceItem(patchDryRun, attemptId, sourceRequestId, dryRunStatus);
    }

    private void assertFreshObservationEvidenceItem(
            Map<String, Object> item,
            UUID attemptId,
            UUID sourceRequestId,
            String status
    ) {
        assertThat(item)
                .containsEntry("status", status)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("linked", "RELEASE_ATTEMPT_LINKED".equals(status))
                .containsEntry("sourceOnlyFallback", "SOURCE_ONLY_FALLBACK".equals(status))
                .containsEntry("blocking", !"RELEASE_ATTEMPT_LINKED".equals(status));
    }

    private void assertFreshObservationEvidenceCompleteness(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean complete,
            int linkedCount,
            int missingCount,
            int sourceOnlyFallbackCount
    ) {
        assertThat(latestAttempt.get("freshObservationEvidenceCompleteness")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> completeness = (Map<String, Object>) latestAttempt.get("freshObservationEvidenceCompleteness");
        assertThat(completeness)
                .containsEntry("status", status)
                .containsEntry("complete", complete)
                .containsEntry("requiredCount", 2)
                .containsEntry("linkedCount", linkedCount)
                .containsEntry("missingCount", missingCount)
                .containsEntry("sourceOnlyFallbackCount", sourceOnlyFallbackCount)
                .containsEntry("blockingCount", complete ? 0 : 2)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(completeness.get("linkedKeys")).isInstanceOf(List.class);
        assertThat(completeness.get("missingKeys")).isInstanceOf(List.class);
        assertThat(completeness.get("sourceOnlyFallbackKeys")).isInstanceOf(List.class);
        assertThat(completeness.get("blockingKeys")).isInstanceOf(List.class);
    }

    private void assertReleaseAttemptFinalReadiness(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean ready,
            String freshnessStatus,
            boolean stale,
            boolean evidenceComplete,
            boolean patchPreconditionsPassed
    ) {
        assertThat(latestAttempt.get("releaseAttemptFinalReadiness")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> finalReadiness = (Map<String, Object>) latestAttempt.get("releaseAttemptFinalReadiness");
        assertThat(finalReadiness)
                .containsEntry("status", status)
                .containsEntry("ready", ready)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("freshnessStatus", freshnessStatus)
                .containsEntry("stale", stale)
                .containsEntry("evidenceComplete", evidenceComplete)
                .containsEntry("patchPreconditionsPassed", patchPreconditionsPassed)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false);
        assertThat(finalReadiness.get("blockingReasons")).isInstanceOf(List.class);
        assertThat(finalReadiness.get("blockingReasons").toString())
                .contains("release gate is disabled", "non-claimable");
    }

    private void assertReleaseAttemptDisplaySummary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(latestAttempt.get("releaseAttemptDisplaySummary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) latestAttempt.get("releaseAttemptDisplaySummary");
        assertThat(summary)
                .containsEntry("status", "READY_BUT_DISABLED_DISPLAY")
                .containsEntry("show", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("linkedEvidenceComplete", true)
                .containsEntry("releaseReadyButDisabled", true)
                .containsEntry("evidenceStatus", "ALL_LINKED_RELEASE_DISABLED")
                .containsEntry("releaseReadinessStatus", "READY_RELEASE_DISABLED")
                .containsEntry("patchPreconditionsPassed", true)
                .containsEntry("evidenceComplete", true)
                .containsEntry("linkedCount", 2)
                .containsEntry("missingCount", 0)
                .containsEntry("sourceOnlyFallbackCount", 0)
                .containsEntry("blockingCount", 0);
        assertThat(summary.get("blockingReasons")).asList()
                .containsExactly("release gate is disabled", "held patch request remains non-claimable");
        assertThat(summary.get("message").toString())
                .contains("Linked release evidence is complete", "remains disabled");

        assertThat(summary.get("disabledFlags")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> disabledFlags = (Map<String, Object>) summary.get("disabledFlags");
        assertThat(disabledFlags)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("mutationAllowed", false);
    }

    private void assertLocalAgentMutationExecutionSequencePlan(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(latestAttempt.get("localAgentMutationExecutionSequencePlan")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plan = (List<Map<String, Object>>) latestAttempt.get("localAgentMutationExecutionSequencePlan");
        assertThat(plan)
                .extracting(item -> item.get("key"))
                .containsExactly("patchApply", "allowlistedVerification", "postWriteObservation", "rollbackFallback");
        assertThat(plan).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "PLANNED_DISABLED")
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimableAfterRelease", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false));

        assertThat(plan.get(0))
                .containsEntry("order", 1)
                .containsEntry("toolName", LocalAgentToolName.PATCH_APPLY.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.APPROVED.name())
                .containsEntry("sideEffectful", true)
                .containsEntry("rollbackFallback", false);
        assertThat(plan.get(1))
                .containsEntry("order", 2)
                .containsEntry("toolName", LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.APPROVED.name())
                .containsEntry("sideEffectful", true)
                .containsEntry("rollbackFallback", false);
        assertThat(plan.get(2))
                .containsEntry("order", 3)
                .containsEntry("toolName", LocalAgentToolName.GIT_STATUS.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.NOT_REQUIRED.name())
                .containsEntry("sideEffectful", false)
                .containsEntry("rollbackFallback", false);
        assertThat(plan.get(3))
                .containsEntry("order", 4)
                .containsEntry("toolName", LocalAgentToolName.ROLLBACK_RESTORE.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.REQUIRED.name())
                .containsEntry("sideEffectful", true)
                .containsEntry("rollbackFallback", true);
    }

    private void assertPostMutationResultContract(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(latestAttempt.get("postMutationResultContract")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) latestAttempt.get("postMutationResultContract");
        assertThat(contract)
                .containsEntry("schema", "learnbot.local-agent.post-mutation-result.v1")
                .containsEntry("status", "CONTRACT_DISABLED")
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("ragFreshnessUpdateEnabled", false);
        assertThat(contract.get("expectedOutcomes")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) contract.get("expectedOutcomes");
        assertThat(outcomes)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "patchApplyOutcome",
                        "allowlistedVerificationOutcome",
                        "postWriteRepositoryObservation",
                        "rollbackFallbackOutcome",
                        "ragFreshnessMarker"
                );
        assertThat(outcomes).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "EXPECTED_DISABLED")
                .containsEntry("resultRequired", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false));
        assertThat(outcomes.get(0))
                .containsEntry("toolName", LocalAgentToolName.PATCH_APPLY.wireName())
                .containsEntry("sideEffectful", true)
                .containsEntry("rollbackFallback", false)
                .containsEntry("requiredForSuccess", true);
        assertThat(outcomes.get(1))
                .containsEntry("toolName", LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName())
                .containsEntry("sideEffectful", true)
                .containsEntry("rollbackFallback", false)
                .containsEntry("requiredForSuccess", true);
        assertThat(outcomes.get(2))
                .containsEntry("toolName", LocalAgentToolName.GIT_STATUS.wireName())
                .containsEntry("sideEffectful", false)
                .containsEntry("rollbackFallback", false)
                .containsEntry("requiredForSuccess", true);
        assertThat(outcomes.get(3))
                .containsEntry("toolName", LocalAgentToolName.ROLLBACK_RESTORE.wireName())
                .containsEntry("sideEffectful", true)
                .containsEntry("rollbackFallback", true)
                .containsEntry("requiredForSuccess", false);
        assertThat(outcomes.get(4))
                .containsEntry("sideEffectful", false)
                .containsEntry("rollbackFallback", false)
                .containsEntry("requiredForSuccess", true);
        assertThat(outcomes.get(4)).doesNotContainKey("toolName");
    }

    private void assertMutationDispatchEnvelopeContract(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationDispatchEnvelopeContract")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) latestAttempt.get("mutationDispatchEnvelopeContract");
        assertThat(contract)
                .containsEntry("schema", "learnbot.local-agent.mutation-dispatch-envelope.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("dispatchMode", "LOCAL_AGENT_TOOL_SEQUENCE")
                .containsEntry("postMutationResultSchema", "learnbot.local-agent.post-mutation-result.v1")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(contract.get("expectedOutcomeKeys")).isInstanceOf(List.class);
        assertThat(contract.get("expectedOutcomeKeys")).asList().containsExactly(
                "patchApplyOutcome",
                "allowlistedVerificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackFallbackOutcome",
                "ragFreshnessMarker"
        );
        assertThat(contract.get("orderedToolSequence")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderedToolSequence = (List<Map<String, Object>>) contract.get("orderedToolSequence");
        assertThat(orderedToolSequence)
                .extracting(item -> item.get("key"))
                .containsExactly("patchApply", "allowlistedVerification", "postWriteObservation", "rollbackFallback");
        assertThat(orderedToolSequence)
                .extracting(item -> item.get("toolName"))
                .containsExactly(
                        LocalAgentToolName.PATCH_APPLY.wireName(),
                        LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                        LocalAgentToolName.GIT_STATUS.wireName(),
                        LocalAgentToolName.ROLLBACK_RESTORE.wireName()
                );
        assertThat(contract.get("requiredApprovals")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvals = (List<Map<String, Object>>) contract.get("requiredApprovals");
        assertThat(approvals)
                .extracting(item -> item.get("approvalState"))
                .containsExactly(
                        LocalAgentApprovalState.APPROVED.name(),
                        LocalAgentApprovalState.APPROVED.name(),
                        LocalAgentApprovalState.NOT_REQUIRED.name(),
                        LocalAgentApprovalState.REQUIRED.name()
                );
        assertThat(contract.get("rollbackObligation")).isInstanceOf(Map.class);
        assertThat(contract.get("ragFreshnessObligation")).isInstanceOf(Map.class);
        assertThat(contract.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) contract.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
    }

    private void assertMutationDispatchPreflightBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationDispatchPreflightBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("mutationDispatchPreflightBoundary");
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.mutation-dispatch-preflight-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("connectionState", LocalAgentConnectionState.CONNECTED.name())
                .containsEntry("agentConnected", true)
                .containsEntry("agentMatches", true)
                .containsEntry("approvedWorkspaceReady", true)
                .containsEntry("workspaceApproved", true)
                .containsEntry("capabilitiesCovered", true)
                .containsEntry("dispatchPreflightEnabled", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(boundary.get("requiredCapabilities")).isInstanceOf(List.class);
        assertThat(boundary.get("requiredCapabilities")).asList().containsExactly(
                LocalAgentToolName.PATCH_APPLY.wireName(),
                LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                LocalAgentToolName.GIT_STATUS.wireName(),
                LocalAgentToolName.ROLLBACK_RESTORE.wireName()
        );
        assertThat(boundary.get("capabilityChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> capabilityChecks = (List<Map<String, Object>>) boundary.get("capabilityChecks");
        assertThat(capabilityChecks)
                .extracting(item -> item.get("toolName"))
                .containsExactly(
                        LocalAgentToolName.PATCH_APPLY.wireName(),
                        LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                        LocalAgentToolName.GIT_STATUS.wireName(),
                        LocalAgentToolName.ROLLBACK_RESTORE.wireName()
                );
        assertThat(capabilityChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("available", true)
                .containsEntry("passed", true)
                .containsEntry("blocking", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
        assertThat(boundary.get("missingCapabilities")).isInstanceOf(List.class);
        assertThat(boundary.get("missingCapabilities")).asList().isEmpty();
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
    }

    private void assertMutationDispatchDecisionModel(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean readinessInputsPassed,
            String... expectedInputBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationDispatchDecisionModel")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) latestAttempt.get("mutationDispatchDecisionModel");
        assertThat(model)
                .containsEntry("schema", "learnbot.local-agent.mutation-dispatch-decision.v1")
                .containsEntry("status", status)
                .containsEntry("decision", "REFUSE_DISPATCH")
                .containsEntry("readinessInputsPassed", readinessInputsPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("dispatchDecisionEnabled", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(model.get("readinessInputs")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) model.get("readinessInputs");
        assertThat(inputs)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationDispatchEnvelopeContract",
                        "mutationDispatchPreflightBoundary",
                        "releaseGateEnabled",
                        "dispatchDecisionEnabled"
                );
        assertThat(inputs).allSatisfy(item -> assertThat(item)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("dispatchDecisionEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
        List<String> expectedBlockingKeys = new ArrayList<>(List.of(expectedInputBlockingKeys));
        expectedBlockingKeys.add("releaseGateEnabled");
        expectedBlockingKeys.add("dispatchDecisionEnabled");
        assertThat(model.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) model.get("blockingKeys");
        assertThat(blockingKeys).containsExactlyElementsOf(expectedBlockingKeys);
        assertThat(String.valueOf(model.get("userVisibleRefusalMessage")))
                .contains(readinessInputsPassed ? "preflight-ready" : "readiness inputs are incomplete");
    }

    private void assertMutationRequestBlueprint(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean prerequisitesPassed
    ) {
        assertThat(latestAttempt.get("mutationRequestBlueprint")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> blueprint = (Map<String, Object>) latestAttempt.get("mutationRequestBlueprint");
        assertThat(blueprint)
                .containsEntry("schema", "learnbot.local-agent.mutation-request-blueprint.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("requestCreationMode", "BLUEPRINT_ONLY_DISABLED")
                .containsEntry("sourceDecision", "REFUSE_DISPATCH")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("dispatchDecisionEnabled", false)
                .containsEntry("requestBlueprintEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(blueprint.get("orderedToolRequests")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderedToolRequests = (List<Map<String, Object>>) blueprint.get("orderedToolRequests");
        assertThat(orderedToolRequests)
                .extracting(item -> item.get("key"))
                .containsExactly("patchApply", "allowlistedVerification", "postWriteObservation", "rollbackFallback");
        assertThat(orderedToolRequests)
                .extracting(item -> item.get("toolName"))
                .containsExactly(
                        LocalAgentToolName.PATCH_APPLY.wireName(),
                        LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                        LocalAgentToolName.GIT_STATUS.wireName(),
                        LocalAgentToolName.ROLLBACK_RESTORE.wireName()
                );
        assertThat(orderedToolRequests).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "REQUEST_BLUEPRINT_DISABLED")
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
        assertThat(orderedToolRequests).allSatisfy(item -> assertThat(item.get("expectedInput")).isInstanceOf(Map.class));
        assertThat(orderedToolRequests).allSatisfy(item -> assertThat(item.get("expectedOutputKeys")).isInstanceOf(List.class));
        assertThat(blueprint.get("expectedInputKeys")).isInstanceOf(List.class);
        assertThat(blueprint.get("expectedInputKeys")).asList().contains(
                "sourceRequestId",
                "releaseAttemptId",
                "sessionId",
                "userId",
                "agentId",
                "workspaceId",
                "toolName",
                "approvalState",
                "input"
        );
        assertThat(blueprint.get("expectedOutputKeys")).isInstanceOf(List.class);
        assertThat(blueprint.get("expectedOutputKeys")).asList().containsExactly(
                "patchApplyOutcome",
                "allowlistedVerificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackFallbackOutcome",
                "ragFreshnessMarker"
        );
        assertThat(blueprint.get("approvalStates")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvalStates = (List<Map<String, Object>>) blueprint.get("approvalStates");
        assertThat(approvalStates)
                .extracting(item -> item.get("approvalState"))
                .containsExactly(
                        LocalAgentApprovalState.APPROVED.name(),
                        LocalAgentApprovalState.APPROVED.name(),
                        LocalAgentApprovalState.NOT_REQUIRED.name(),
                        LocalAgentApprovalState.REQUIRED.name()
                );
        assertThat(blueprint.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(blueprint.get("blockingKeys")).asList().contains(
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationRequestCreationGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean blueprintReady,
            int expectedRequestCount
    ) {
        assertThat(latestAttempt.get("mutationRequestCreationGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationRequestCreationGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-request-creation-gate.v1")
                .containsEntry("status", status)
                .containsEntry("blueprintReady", blueprintReady)
                .containsEntry("prerequisitesPassed", blueprintReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("releaseGateState", "DISABLED")
                .containsEntry("requestCreationPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("expectedRequestCount", expectedRequestCount)
                .containsEntry("persistedRequestCount", 0)
                .containsEntry("pushedRequestCount", 0)
                .containsEntry("claimableRequestCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationRequestBlueprint",
                        "releaseGateEnabled",
                        "requestCreationPolicy",
                        "requestPersistence"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "releaseGateEnabled",
                "requestCreationPolicy",
                "requestPersistence",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationRequestPushGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean creationGateReady,
            int expectedRequestCount
    ) {
        assertThat(latestAttempt.get("mutationRequestPushGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationRequestPushGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-request-push-gate.v1")
                .containsEntry("status", status)
                .containsEntry("creationGateReady", creationGateReady)
                .containsEntry("prerequisitesPassed", creationGateReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("transportPushPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("pusherInvocationEnabled", false)
                .containsEntry("expectedRequestCount", expectedRequestCount)
                .containsEntry("persistedRequestCount", 0)
                .containsEntry("pushedRequestCount", 0)
                .containsEntry("claimableRequestCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushGateEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationRequestCreationGate",
                        "transportPushPolicy",
                        "pusherInvocation",
                        "claimableTransition"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "transportPushPolicy",
                "pusherInvocation",
                "claimableTransition",
                "pushEnabled",
                "requestCreationEnabled",
                "claimEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationRequestClaimGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean pushGateReady,
            int expectedRequestCount
    ) {
        assertThat(latestAttempt.get("mutationRequestClaimGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationRequestClaimGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-request-claim-gate.v1")
                .containsEntry("status", status)
                .containsEntry("pushGateReady", pushGateReady)
                .containsEntry("prerequisitesPassed", pushGateReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("claimPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("claimNextInvocationEnabled", false)
                .containsEntry("expectedRequestCount", expectedRequestCount)
                .containsEntry("persistedRequestCount", 0)
                .containsEntry("pushedRequestCount", 0)
                .containsEntry("claimableRequestCount", 0)
                .containsEntry("runningRequestCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationRequestPushGate",
                        "claimPolicy",
                        "claimNextInvocation",
                        "runningTransition"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("running", false)
                .containsEntry("mutationAllowed", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "claimPolicy",
                "claimNextInvocation",
                "runningTransition",
                "claimEnabled",
                "pushEnabled",
                "requestCreationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationExecutionGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean claimGateReady,
            int expectedRequestCount
    ) {
        assertThat(latestAttempt.get("mutationExecutionGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationExecutionGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-execution-gate.v1")
                .containsEntry("status", status)
                .containsEntry("claimGateReady", claimGateReady)
                .containsEntry("prerequisitesPassed", claimGateReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("executionPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("toolRunnerInvocationEnabled", false)
                .containsEntry("writeHelperInvocationEnabled", false)
                .containsEntry("expectedRequestCount", expectedRequestCount)
                .containsEntry("persistedRequestCount", 0)
                .containsEntry("pushedRequestCount", 0)
                .containsEntry("claimableRequestCount", 0)
                .containsEntry("runningRequestCount", 0)
                .containsEntry("completedRequestCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionGateEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationRequestClaimGate",
                        "executionPolicy",
                        "toolRunnerInvocation",
                        "writeHelperInvocation",
                        "completionTransition"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("running", false)
                .containsEntry("completed", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "executionPolicy",
                "toolRunnerInvocation",
                "writeHelperInvocation",
                "completionTransition",
                "executionEnabled",
                "writeHelperEnabled",
                "applyEnabled",
                "testEnabled",
                "rollbackRestoreEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationPostExecutionObservationGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean executionGateReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationPostExecutionObservationGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationPostExecutionObservationGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-post-execution-observation-gate.v1")
                .containsEntry("status", status)
                .containsEntry("executionGateReady", executionGateReady)
                .containsEntry("prerequisitesPassed", executionGateReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceExecutionGateSchema", "learnbot.local-agent.mutation-execution-gate.v1")
                .containsEntry("sourceExecutionGateStatus", executionGateReady ? "REFUSED_EXECUTION_DISABLED" : "BLOCKED_EXECUTION_DISABLED")
                .containsEntry("sourceExecutionGateSessionId", sessionId)
                .containsEntry("sourceExecutionGateUserId", userId)
                .containsEntry("sourceExecutionGateAgentId", agentId)
                .containsEntry("sourceExecutionGateWorkspaceId", workspaceId)
                .containsEntry("observationPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("message", executionGateReady
                        ? "Local Agent post-execution mutation observation is explicitly refused: no completed-result capture, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                        : "Local Agent post-execution mutation observation is blocked because the disabled mutation execution gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationExecutionGate",
                        "observationPolicy",
                        "completedResultPersistence",
                        "rollbackFallbackExecution",
                        "ragFreshnessUpdate",
                        "resultAggregation",
                        "publication"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "observationPolicy",
                "completedResultPersistence",
                "rollbackFallbackExecution",
                "ragFreshnessUpdate",
                "resultAggregation",
                "publication",
                "postExecutionObservationEnabled",
                "completedResultPersistenceEnabled",
                "rollbackFallbackExecutionEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationWriteHelperSafetyGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean executionGateReady,
            int expectedRequestCount
    ) {
        assertThat(latestAttempt.get("mutationWriteHelperSafetyGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationWriteHelperSafetyGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-write-helper-safety-gate.v1")
                .containsEntry("status", status)
                .containsEntry("executionGateReady", executionGateReady)
                .containsEntry("prerequisitesPassed", executionGateReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("writeHelperPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("expectedRequestCount", expectedRequestCount)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationExecutionGate",
                        "writeHelperPolicy",
                        "workspaceContainment",
                        "snapshotManifest",
                        "hashRecheck",
                        "atomicRewrite",
                        "rollbackContract"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "writeHelperPolicy",
                "workspaceContainment",
                "snapshotManifest",
                "hashRecheck",
                "atomicRewrite",
                "rollbackContract",
                "writeHelperEnabled",
                "applyEnabled",
                "mutationAllowed",
                "rollbackRestoreEnabled",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled"
        );
    }

    private void assertMutationObservationAcceptanceGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean postExecutionObservationReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationObservationAcceptanceGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationObservationAcceptanceGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-observation-acceptance-gate.v1")
                .containsEntry("status", status)
                .containsEntry("postExecutionObservationReady", postExecutionObservationReady)
                .containsEntry("prerequisitesPassed", postExecutionObservationReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourcePostExecutionObservationGateSchema", "learnbot.local-agent.mutation-post-execution-observation-gate.v1")
                .containsEntry("sourcePostExecutionObservationGateStatus", postExecutionObservationReady ? "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED" : "BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED")
                .containsEntry("sourcePostExecutionObservationGateSessionId", sessionId)
                .containsEntry("sourcePostExecutionObservationGateUserId", userId)
                .containsEntry("sourcePostExecutionObservationGateAgentId", agentId)
                .containsEntry("sourcePostExecutionObservationGateWorkspaceId", workspaceId)
                .containsEntry("acceptancePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("message", postExecutionObservationReady
                        ? "Local Agent mutation observation acceptance is explicitly refused: no accepted observation intake, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                        : "Local Agent mutation observation acceptance is blocked because the disabled post-execution observation gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationPostExecutionObservationGate",
                        "acceptancePolicy",
                        "intakePersistence",
                        "rollbackFallbackExecution",
                        "ragFreshnessUpdate",
                        "resultAggregation",
                        "publication",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "acceptancePolicy",
                "intakePersistence",
                "rollbackFallbackExecution",
                "ragFreshnessUpdate",
                "resultAggregation",
                "publication",
                "finalAnswerGeneration",
                "observationAcceptanceEnabled",
                "intakePersistenceEnabled",
                "rollbackFallbackExecutionEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationResultIntakePersistenceGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean observationAcceptanceReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationResultIntakePersistenceGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationResultIntakePersistenceGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> acceptedReadiness = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationReadiness");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        boolean acceptedObservationObserved = Boolean.TRUE.equals(acceptedReadiness.get("observed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> latestAcceptedObservation = acceptedReadiness.get("latestObservation") instanceof Map<?, ?>
                ? (Map<String, Object>) acceptedReadiness.get("latestObservation")
                : Map.of();
        String acceptedObservationStatus = acceptedObservationObserved
                ? String.valueOf(latestAcceptedObservation.getOrDefault("status", "UNKNOWN"))
                : "MISSING";
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-intake-persistence-gate.v1")
                .containsEntry("status", status)
                .containsEntry("observationAcceptanceReady", observationAcceptanceReady)
                .containsEntry("prerequisitesPassed", observationAcceptanceReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceObservationAcceptanceGateSchema", "learnbot.local-agent.mutation-observation-acceptance-gate.v1")
                .containsEntry("sourceObservationAcceptanceGateStatus", observationAcceptanceReady ? "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED" : "BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED")
                .containsEntry("sourceObservationAcceptanceGateSessionId", sessionId)
                .containsEntry("sourceObservationAcceptanceGateUserId", userId)
                .containsEntry("sourceObservationAcceptanceGateAgentId", agentId)
                .containsEntry("sourceObservationAcceptanceGateWorkspaceId", workspaceId)
                .containsEntry("sourceAcceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("sourceAcceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("sourceAcceptedMutationObservationSummaryObservationCount", observationSummary.get("observationCount"))
                .containsEntry("sourceAcceptedMutationObservationSummaryAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("sourceAcceptedMutationObservationSummaryRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("sourceAcceptedMutationObservationSummaryTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("sourceAcceptedMutationObservationSummaryMissingMutationResultRiskVisible", ((Number) observationSummary.get("observationCount")).intValue() == 0)
                .containsEntry("sourceAcceptedMutationObservationSummaryStaleIndexRiskVisible", ((Number) observationSummary.get("acceptedCount")).intValue() > 0)
                .containsEntry("sourceAcceptedMutationObservationPublicationGateSchema", observationSummary.get("publicationGateSchema"))
                .containsEntry("sourceAcceptedMutationObservationPublicationGateStatus", observationSummary.get("publicationGateStatus"))
                .containsEntry("sourceAcceptedMutationObservationPublicationGateSessionId", observationSummary.get("publicationGateSessionId"))
                .containsEntry("sourceAcceptedMutationObservationPublicationGateUserId", observationSummary.get("publicationGateUserId"))
                .containsEntry("sourceAcceptedMutationObservationPublicationGateAgentId", observationSummary.get("publicationGateAgentId"))
                .containsEntry("sourceAcceptedMutationObservationPublicationGateWorkspaceId", observationSummary.get("publicationGateWorkspaceId"))
                .containsEntry("sourceAcceptedMutationObservationRollbackSummaryStatus", observationSummary.get("status"))
                .containsEntry("sourceAcceptedMutationObservationRollbackSummaryObservationCount", observationSummary.get("observationCount"))
                .containsEntry("sourceAcceptedMutationObservationRollbackSummaryAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("sourceAcceptedMutationObservationRollbackSummaryRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("sourceAcceptedMutationObservationRollbackSummaryMissingMutationResultRiskVisible", ((Number) observationSummary.get("observationCount")).intValue() == 0)
                .containsEntry("sourceAcceptedMutationObservationRollbackSummaryStaleIndexRiskVisible", ((Number) observationSummary.get("acceptedCount")).intValue() > 0)
                .containsEntry("sourceAcceptedMutationObservationReadinessSchema", "learnbot.local-agent.accepted-mutation-observation-readiness.v1")
                .containsEntry("sourceAcceptedMutationObservationReadinessStatus", acceptedReadiness.get("status"))
                .containsEntry("sourceAcceptedMutationObservationObserved", acceptedObservationObserved)
                .containsEntry("sourceAcceptedMutationObservationReadinessSessionId", sessionId)
                .containsEntry("sourceAcceptedMutationObservationReadinessUserId", userId)
                .containsEntry("sourceAcceptedMutationObservationReadinessAgentId", agentId)
                .containsEntry("sourceAcceptedMutationObservationReadinessWorkspaceId", workspaceId)
                .containsEntry("intakePersistencePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("acceptedMutationObservationAuditStatus", acceptedObservationObserved ? "OBSERVED" : "MISSING")
                .containsEntry("latestAcceptedMutationObservationStatus", acceptedObservationStatus)
                .containsEntry("latestAcceptedMutationObservationAccepted", Boolean.TRUE.equals(latestAcceptedObservation.get("accepted")))
                .containsEntry("latestAcceptedMutationObservationRejected", acceptedObservationStatus.startsWith("REJECTED_"))
                .containsEntry("latestAcceptedMutationObservationTerminalFailureAccepted", "ACCEPTED_TERMINAL_FAILURE".equals(acceptedObservationStatus))
                .containsEntry("latestAcceptedMutationObservationToolName", latestAcceptedObservation.get("toolName"))
                .containsEntry("latestAcceptedMutationObservationVerificationStatus", latestAcceptedObservation.get("verificationStatus"))
                .containsEntry("latestAcceptedMutationObservation", latestAcceptedObservation)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", observationAcceptanceReady
                        ? "Local Agent mutation result intake persistence is explicitly refused: no accepted observation persistence, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                        : "Local Agent mutation result intake persistence is blocked because the disabled observation acceptance gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        assertThat(gate.get("acceptedObservationAudit")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> acceptedObservationAudit = (List<Map<String, Object>>) gate.get("acceptedObservationAudit");
        assertThat(acceptedObservationAudit)
                .extracting(item -> item.get("key"))
                .containsExactly("acceptedMutationObservationReadiness", "acceptedMutationObservationStatus");
        assertThat(acceptedObservationAudit).allSatisfy(item -> assertThat(item)
                .containsEntry("blocking", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationAllowed", false));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationObservationAcceptanceGate",
                        "intakePersistencePolicy",
                        "acceptedObservationPersistence",
                        "rollbackFallbackExecution",
                        "ragFreshnessUpdate",
                        "resultAggregation",
                        "publication",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "intakePersistencePolicy",
                "acceptedObservationPersistence",
                "rollbackFallbackExecution",
                "ragFreshnessUpdate",
                "resultAggregation",
                "publication",
                "finalAnswerGeneration",
                "intakePersistenceEnabled",
                "acceptedObservationPersistenceEnabled",
                "rollbackFallbackExecutionEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationRollbackFallbackGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean intakePersistenceReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationRollbackFallbackGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationRollbackFallbackGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> intakeGate = (Map<String, Object>) latestAttempt.get("mutationResultIntakePersistenceGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-rollback-fallback-gate.v1")
                .containsEntry("status", status)
                .containsEntry("intakePersistenceReady", intakePersistenceReady)
                .containsEntry("prerequisitesPassed", intakePersistenceReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceResultIntakePersistenceGateSchema", "learnbot.local-agent.mutation-result-intake-persistence-gate.v1")
                .containsEntry("sourceResultIntakePersistenceGateStatus", intakePersistenceReady ? "REFUSED_INTAKE_PERSISTENCE_DISABLED" : "BLOCKED_INTAKE_PERSISTENCE_DISABLED")
                .containsEntry("sourceResultIntakePersistenceGateSessionId", sessionId)
                .containsEntry("sourceResultIntakePersistenceGateUserId", userId)
                .containsEntry("sourceResultIntakePersistenceGateAgentId", agentId)
                .containsEntry("sourceResultIntakePersistenceGateWorkspaceId", workspaceId)
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationAuditStatus", intakeGate.get("acceptedMutationObservationAuditStatus"))
                .containsEntry("sourceResultIntakePersistenceGateLatestAcceptedObservationStatus", intakeGate.get("latestAcceptedMutationObservationStatus"))
                .containsEntry("sourceResultIntakePersistenceGateLatestAcceptedObservationAccepted", intakeGate.get("latestAcceptedMutationObservationAccepted"))
                .containsEntry("sourceResultIntakePersistenceGateLatestAcceptedObservationRejected", intakeGate.get("latestAcceptedMutationObservationRejected"))
                .containsEntry("sourceResultIntakePersistenceGateLatestAcceptedObservationTerminalFailureAccepted", intakeGate.get("latestAcceptedMutationObservationTerminalFailureAccepted"))
                .containsEntry("sourceResultIntakePersistenceGateLatestAcceptedObservationToolName", intakeGate.get("latestAcceptedMutationObservationToolName"))
                .containsEntry("sourceResultIntakePersistenceGateLatestAcceptedObservationVerificationStatus", intakeGate.get("latestAcceptedMutationObservationVerificationStatus"))
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationSummaryStatus", intakeGate.get("sourceAcceptedMutationObservationSummaryStatus"))
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationSummaryObservationCount", intakeGate.get("sourceAcceptedMutationObservationSummaryObservationCount"))
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationSummaryAcceptedCount", intakeGate.get("sourceAcceptedMutationObservationSummaryAcceptedCount"))
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationSummaryRejectedCount", intakeGate.get("sourceAcceptedMutationObservationSummaryRejectedCount"))
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationSummaryMissingMutationResultRiskVisible", intakeGate.get("sourceAcceptedMutationObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceResultIntakePersistenceGateAcceptedObservationSummaryStaleIndexRiskVisible", intakeGate.get("sourceAcceptedMutationObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourceResultIntakePersistenceGatePublicationGateSchema", intakeGate.get("sourceAcceptedMutationObservationPublicationGateSchema"))
                .containsEntry("sourceResultIntakePersistenceGatePublicationGateStatus", intakeGate.get("sourceAcceptedMutationObservationPublicationGateStatus"))
                .containsEntry("sourceResultIntakePersistenceGatePublicationGateSessionId", intakeGate.get("sourceAcceptedMutationObservationPublicationGateSessionId"))
                .containsEntry("sourceResultIntakePersistenceGatePublicationGateUserId", intakeGate.get("sourceAcceptedMutationObservationPublicationGateUserId"))
                .containsEntry("sourceResultIntakePersistenceGatePublicationGateAgentId", intakeGate.get("sourceAcceptedMutationObservationPublicationGateAgentId"))
                .containsEntry("sourceResultIntakePersistenceGatePublicationGateWorkspaceId", intakeGate.get("sourceAcceptedMutationObservationPublicationGateWorkspaceId"))
                .containsEntry("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStatus", intakeGate.get("sourceAcceptedMutationObservationRollbackSummaryStatus"))
                .containsEntry("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryObservationCount", intakeGate.get("sourceAcceptedMutationObservationRollbackSummaryObservationCount"))
                .containsEntry("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryAcceptedCount", intakeGate.get("sourceAcceptedMutationObservationRollbackSummaryAcceptedCount"))
                .containsEntry("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryRejectedCount", intakeGate.get("sourceAcceptedMutationObservationRollbackSummaryRejectedCount"))
                .containsEntry("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", intakeGate.get("sourceAcceptedMutationObservationRollbackSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", intakeGate.get("sourceAcceptedMutationObservationRollbackSummaryStaleIndexRiskVisible"))
                .containsEntry("rollbackFallbackPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("rollbackFallbackInvocationEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", intakePersistenceReady
                        ? "Local Agent mutation rollback fallback is explicitly refused: no rollback fallback execution, RAG freshness update, aggregation, publication, or final answer is enabled."
                        : "Local Agent mutation rollback fallback is blocked because the disabled intake persistence gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationResultIntakePersistenceGate",
                        "rollbackFallbackPolicy",
                        "rollbackFallbackExecution",
                        "ragFreshnessUpdate",
                        "resultAggregation",
                        "publication",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "rollbackFallbackPolicy",
                "rollbackFallbackExecution",
                "ragFreshnessUpdate",
                "resultAggregation",
                "publication",
                "finalAnswerGeneration",
                "rollbackFallbackExecutionEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationRagFreshnessGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean rollbackFallbackReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationRagFreshnessGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationRagFreshnessGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> rollbackFallbackGate = (Map<String, Object>) latestAttempt.get("mutationRollbackFallbackGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-rag-freshness-gate.v1")
                .containsEntry("status", status)
                .containsEntry("rollbackFallbackReady", rollbackFallbackReady)
                .containsEntry("prerequisitesPassed", rollbackFallbackReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceRollbackFallbackGateSchema", "learnbot.local-agent.mutation-rollback-fallback-gate.v1")
                .containsEntry("sourceRollbackFallbackGateStatus", rollbackFallbackReady ? "REFUSED_ROLLBACK_FALLBACK_DISABLED" : "BLOCKED_ROLLBACK_FALLBACK_DISABLED")
                .containsEntry("sourceRollbackFallbackGateSessionId", sessionId)
                .containsEntry("sourceRollbackFallbackGateUserId", userId)
                .containsEntry("sourceRollbackFallbackGateAgentId", agentId)
                .containsEntry("sourceRollbackFallbackGateWorkspaceId", workspaceId)
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationAuditStatus", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationAuditStatus"))
                .containsEntry("sourceRollbackFallbackGateLatestAcceptedObservationStatus", rollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationStatus"))
                .containsEntry("sourceRollbackFallbackGateLatestAcceptedObservationAccepted", rollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationAccepted"))
                .containsEntry("sourceRollbackFallbackGateLatestAcceptedObservationRejected", rollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationRejected"))
                .containsEntry("sourceRollbackFallbackGateLatestAcceptedObservationTerminalFailureAccepted", rollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationTerminalFailureAccepted"))
                .containsEntry("sourceRollbackFallbackGateLatestAcceptedObservationToolName", rollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationToolName"))
                .containsEntry("sourceRollbackFallbackGateLatestAcceptedObservationVerificationStatus", rollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationSummaryStatus", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationSummaryObservationCount", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationSummaryAcceptedCount", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationSummaryRejectedCount", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationSummaryMissingMutationResultRiskVisible", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceRollbackFallbackGateAcceptedObservationSummaryStaleIndexRiskVisible", rollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourceRollbackFallbackGatePublicationGateSchema", rollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateSchema"))
                .containsEntry("sourceRollbackFallbackGatePublicationGateStatus", rollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateStatus"))
                .containsEntry("sourceRollbackFallbackGatePublicationGateSessionId", rollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateSessionId"))
                .containsEntry("sourceRollbackFallbackGatePublicationGateUserId", rollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateUserId"))
                .containsEntry("sourceRollbackFallbackGatePublicationGateAgentId", rollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateAgentId"))
                .containsEntry("sourceRollbackFallbackGatePublicationGateWorkspaceId", rollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateWorkspaceId"))
                .containsEntry("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStatus", rollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryObservationCount", rollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryAcceptedCount", rollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryRejectedCount", rollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", rollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", rollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("ragFreshnessPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("ragFreshnessUpdateInvocationEnabled", false)
                .containsEntry("acceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("acceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("acceptedMutationObservationCount", observationSummary.get("observationCount"))
                .containsEntry("acceptedMutationObservationAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("acceptedMutationObservationRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("acceptedMutationObservationTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("acceptedMutationObservationToolCounts", observationSummary.get("toolObservationCounts"))
                .containsEntry("acceptedMutationObservationStatusCounts", observationSummary.get("statusObservationCounts"))
                .containsEntry("missingMutationResultRiskVisible", ((Number) observationSummary.get("observationCount")).intValue() == 0)
                .containsEntry("staleIndexRiskVisible", ((Number) observationSummary.get("acceptedCount")).intValue() > 0)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", rollbackFallbackReady
                        ? "Local Agent mutation RAG freshness is explicitly refused: no freshness update, aggregation, publication, or final answer is enabled."
                        : "Local Agent mutation RAG freshness is blocked because the disabled rollback fallback gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationRollbackFallbackGate",
                        "ragFreshnessPolicy",
                        "ragFreshnessUpdate",
                        "resultAggregation",
                        "publication",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "ragFreshnessPolicy",
                "ragFreshnessUpdate",
                "resultAggregation",
                "publication",
                "finalAnswerGeneration",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationResultAggregationGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean ragFreshnessReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationResultAggregationGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationResultAggregationGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> ragFreshnessGate = (Map<String, Object>) latestAttempt.get("mutationRagFreshnessGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> acceptedReadiness = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationReadiness");
        boolean acceptedObservationObserved = Boolean.TRUE.equals(acceptedReadiness.get("observed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> latestAcceptedObservation = acceptedReadiness.get("latestObservation") instanceof Map<?, ?>
                ? (Map<String, Object>) acceptedReadiness.get("latestObservation")
                : Map.of();
        String acceptedObservationStatus = acceptedObservationObserved
                ? String.valueOf(latestAcceptedObservation.getOrDefault("status", "UNKNOWN"))
                : "MISSING";
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-aggregation-gate.v1")
                .containsEntry("status", status)
                .containsEntry("ragFreshnessReady", ragFreshnessReady)
                .containsEntry("prerequisitesPassed", ragFreshnessReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceRagFreshnessGateSchema", "learnbot.local-agent.mutation-rag-freshness-gate.v1")
                .containsEntry("sourceRagFreshnessGateStatus", ragFreshnessReady ? "REFUSED_RAG_FRESHNESS_DISABLED" : "BLOCKED_RAG_FRESHNESS_DISABLED")
                .containsEntry("sourceRagFreshnessGateSessionId", sessionId)
                .containsEntry("sourceRagFreshnessGateUserId", userId)
                .containsEntry("sourceRagFreshnessGateAgentId", agentId)
                .containsEntry("sourceRagFreshnessGateWorkspaceId", workspaceId)
                .containsEntry("sourceRagFreshnessGateAcceptedObservationSummaryStatus", ragFreshnessGate.get("acceptedMutationObservationSummaryStatus"))
                .containsEntry("sourceRagFreshnessGateAcceptedObservationCount", ragFreshnessGate.get("acceptedMutationObservationCount"))
                .containsEntry("sourceRagFreshnessGateAcceptedObservationAcceptedCount", ragFreshnessGate.get("acceptedMutationObservationAcceptedCount"))
                .containsEntry("sourceRagFreshnessGateAcceptedObservationRejectedCount", ragFreshnessGate.get("acceptedMutationObservationRejectedCount"))
                .containsEntry("sourceRagFreshnessGateMissingMutationResultRiskVisible", ragFreshnessGate.get("missingMutationResultRiskVisible"))
                .containsEntry("sourceRagFreshnessGateStaleIndexRiskVisible", ragFreshnessGate.get("staleIndexRiskVisible"))
                .containsEntry("sourceRagFreshnessGatePublicationGateSchema", ragFreshnessGate.get("sourceRollbackFallbackGatePublicationGateSchema"))
                .containsEntry("sourceRagFreshnessGatePublicationGateStatus", ragFreshnessGate.get("sourceRollbackFallbackGatePublicationGateStatus"))
                .containsEntry("sourceRagFreshnessGatePublicationGateSessionId", ragFreshnessGate.get("sourceRollbackFallbackGatePublicationGateSessionId"))
                .containsEntry("sourceRagFreshnessGatePublicationGateUserId", ragFreshnessGate.get("sourceRollbackFallbackGatePublicationGateUserId"))
                .containsEntry("sourceRagFreshnessGatePublicationGateAgentId", ragFreshnessGate.get("sourceRollbackFallbackGatePublicationGateAgentId"))
                .containsEntry("sourceRagFreshnessGatePublicationGateWorkspaceId", ragFreshnessGate.get("sourceRollbackFallbackGatePublicationGateWorkspaceId"))
                .containsEntry("sourceRagFreshnessGateLatestAcceptedObservationStatus", ragFreshnessGate.get("sourceRollbackFallbackGateLatestAcceptedObservationStatus"))
                .containsEntry("sourceRagFreshnessGateLatestAcceptedObservationToolName", ragFreshnessGate.get("sourceRollbackFallbackGateLatestAcceptedObservationToolName"))
                .containsEntry("sourceRagFreshnessGateLatestAcceptedObservationVerificationStatus", ragFreshnessGate.get("sourceRollbackFallbackGateLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceRagFreshnessGateRollbackAcceptedObservationSummaryStatus", ragFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceRagFreshnessGateRollbackAcceptedObservationSummaryObservationCount", ragFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceRagFreshnessGateRollbackAcceptedObservationSummaryAcceptedCount", ragFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceRagFreshnessGateRollbackAcceptedObservationSummaryRejectedCount", ragFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceRagFreshnessGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", ragFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceRagFreshnessGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", ragFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourceAcceptedMutationObservationReadinessSchema", "learnbot.local-agent.accepted-mutation-observation-readiness.v1")
                .containsEntry("sourceAcceptedMutationObservationReadinessStatus", acceptedReadiness.get("status"))
                .containsEntry("sourceAcceptedMutationObservationObserved", acceptedObservationObserved)
                .containsEntry("sourceAcceptedMutationObservationReadinessSessionId", sessionId)
                .containsEntry("sourceAcceptedMutationObservationReadinessUserId", userId)
                .containsEntry("sourceAcceptedMutationObservationReadinessAgentId", agentId)
                .containsEntry("sourceAcceptedMutationObservationReadinessWorkspaceId", workspaceId)
                .containsEntry("resultAggregationPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("resultAggregationInvocationEnabled", false)
                .containsEntry("acceptedMutationObservationAuditStatus", acceptedObservationObserved ? "OBSERVED" : "MISSING")
                .containsEntry("latestAcceptedMutationObservationStatus", acceptedObservationStatus)
                .containsEntry("latestAcceptedMutationObservationAccepted", Boolean.TRUE.equals(latestAcceptedObservation.get("accepted")))
                .containsEntry("latestAcceptedMutationObservationRejected", acceptedObservationStatus.startsWith("REJECTED_"))
                .containsEntry("latestAcceptedMutationObservationTerminalFailureAccepted", "ACCEPTED_TERMINAL_FAILURE".equals(acceptedObservationStatus))
                .containsEntry("latestAcceptedMutationObservationToolName", latestAcceptedObservation.get("toolName"))
                .containsEntry("latestAcceptedMutationObservationVerificationStatus", latestAcceptedObservation.get("verificationStatus"))
                .containsEntry("latestAcceptedMutationObservation", latestAcceptedObservation)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", ragFreshnessReady
                        ? "Local Agent mutation result aggregation is explicitly refused: no aggregation, publication, or final answer is enabled."
                        : "Local Agent mutation result aggregation is blocked because the disabled RAG freshness gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        assertThat(gate.get("acceptedObservationAudit")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> acceptedObservationAudit = (List<Map<String, Object>>) gate.get("acceptedObservationAudit");
        assertThat(acceptedObservationAudit)
                .extracting(item -> item.get("key"))
                .containsExactly("acceptedMutationObservationReadiness", "acceptedMutationObservationStatus");
        assertThat(acceptedObservationAudit).allSatisfy(item -> assertThat(item)
                .containsEntry("blocking", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationAllowed", false));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationRagFreshnessGate",
                        "resultAggregationPolicy",
                        "resultAggregation",
                        "publication",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "resultAggregationPolicy",
                "resultAggregation",
                "publication",
                "finalAnswerGeneration",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationPublicationGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean resultAggregationReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationPublicationGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationPublicationGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultAggregationGate = (Map<String, Object>) latestAttempt.get("mutationResultAggregationGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-publication-gate.v1")
                .containsEntry("status", status)
                .containsEntry("resultAggregationReady", resultAggregationReady)
                .containsEntry("prerequisitesPassed", resultAggregationReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceResultAggregationGateSchema", "learnbot.local-agent.mutation-result-aggregation-gate.v1")
                .containsEntry("sourceResultAggregationGateStatus", resultAggregationReady
                        ? "REFUSED_RESULT_AGGREGATION_DISABLED"
                        : "BLOCKED_RESULT_AGGREGATION_DISABLED")
                .containsEntry("sourceResultAggregationGateSessionId", sessionId)
                .containsEntry("sourceResultAggregationGateUserId", userId)
                .containsEntry("sourceResultAggregationGateAgentId", agentId)
                .containsEntry("sourceResultAggregationGateWorkspaceId", workspaceId)
                .containsEntry("sourceResultAggregationGateAcceptedObservationSummaryStatus", resultAggregationGate.get("sourceRagFreshnessGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceResultAggregationGateAcceptedObservationCount", resultAggregationGate.get("sourceRagFreshnessGateAcceptedObservationCount"))
                .containsEntry("sourceResultAggregationGateAcceptedObservationAcceptedCount", resultAggregationGate.get("sourceRagFreshnessGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceResultAggregationGateAcceptedObservationRejectedCount", resultAggregationGate.get("sourceRagFreshnessGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceResultAggregationGateMissingMutationResultRiskVisible", resultAggregationGate.get("sourceRagFreshnessGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceResultAggregationGateStaleIndexRiskVisible", resultAggregationGate.get("sourceRagFreshnessGateStaleIndexRiskVisible"))
                .containsEntry("sourceResultAggregationGatePublicationGateSchema", resultAggregationGate.get("sourceRagFreshnessGatePublicationGateSchema"))
                .containsEntry("sourceResultAggregationGatePublicationGateStatus", resultAggregationGate.get("sourceRagFreshnessGatePublicationGateStatus"))
                .containsEntry("sourceResultAggregationGatePublicationGateSessionId", resultAggregationGate.get("sourceRagFreshnessGatePublicationGateSessionId"))
                .containsEntry("sourceResultAggregationGatePublicationGateUserId", resultAggregationGate.get("sourceRagFreshnessGatePublicationGateUserId"))
                .containsEntry("sourceResultAggregationGatePublicationGateAgentId", resultAggregationGate.get("sourceRagFreshnessGatePublicationGateAgentId"))
                .containsEntry("sourceResultAggregationGatePublicationGateWorkspaceId", resultAggregationGate.get("sourceRagFreshnessGatePublicationGateWorkspaceId"))
                .containsEntry("sourceResultAggregationGateLatestAcceptedObservationStatus", resultAggregationGate.get("sourceRagFreshnessGateLatestAcceptedObservationStatus"))
                .containsEntry("sourceResultAggregationGateLatestAcceptedObservationToolName", resultAggregationGate.get("sourceRagFreshnessGateLatestAcceptedObservationToolName"))
                .containsEntry("sourceResultAggregationGateLatestAcceptedObservationVerificationStatus", resultAggregationGate.get("sourceRagFreshnessGateLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceResultAggregationGateRollbackAcceptedObservationSummaryStatus", resultAggregationGate.get("sourceRagFreshnessGateRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceResultAggregationGateRollbackAcceptedObservationSummaryObservationCount", resultAggregationGate.get("sourceRagFreshnessGateRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceResultAggregationGateRollbackAcceptedObservationSummaryAcceptedCount", resultAggregationGate.get("sourceRagFreshnessGateRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceResultAggregationGateRollbackAcceptedObservationSummaryRejectedCount", resultAggregationGate.get("sourceRagFreshnessGateRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceResultAggregationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", resultAggregationGate.get("sourceRagFreshnessGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceResultAggregationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", resultAggregationGate.get("sourceRagFreshnessGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("publicationPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("publicationInvocationEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", resultAggregationReady
                        ? "Local Agent mutation publication is explicitly refused: no publication or final answer is enabled."
                        : "Local Agent mutation publication is blocked because the disabled result aggregation gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationResultAggregationGate",
                        "publicationPolicy",
                        "publication",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "publicationPolicy",
                "publication",
                "finalAnswerGeneration",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerGenerationGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean publicationReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerGenerationGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerGenerationGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> publicationGate = (Map<String, Object>) latestAttempt.get("mutationPublicationGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> publicationBoundary = (Map<String, Object>) latestAttempt.get("finalAnswerPublicationBoundary");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-generation-gate.v1")
                .containsEntry("status", status)
                .containsEntry("publicationReady", publicationReady)
                .containsEntry("prerequisitesPassed", publicationReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourcePublicationGateSchema", "learnbot.local-agent.mutation-publication-gate.v1")
                .containsEntry("sourcePublicationGateStatus", publicationReady
                        ? "REFUSED_PUBLICATION_DISABLED"
                        : "BLOCKED_PUBLICATION_DISABLED")
                .containsEntry("sourcePublicationGateSessionId", sessionId)
                .containsEntry("sourcePublicationGateUserId", userId)
                .containsEntry("sourcePublicationGateAgentId", agentId)
                .containsEntry("sourcePublicationGateWorkspaceId", workspaceId)
                .containsEntry("sourcePublicationGateAcceptedObservationSummaryStatus", publicationGate.get("sourceResultAggregationGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourcePublicationGateAcceptedObservationCount", publicationGate.get("sourceResultAggregationGateAcceptedObservationCount"))
                .containsEntry("sourcePublicationGateAcceptedObservationAcceptedCount", publicationGate.get("sourceResultAggregationGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourcePublicationGateAcceptedObservationRejectedCount", publicationGate.get("sourceResultAggregationGateAcceptedObservationRejectedCount"))
                .containsEntry("sourcePublicationGateMissingMutationResultRiskVisible", publicationGate.get("sourceResultAggregationGateMissingMutationResultRiskVisible"))
                .containsEntry("sourcePublicationGateStaleIndexRiskVisible", publicationGate.get("sourceResultAggregationGateStaleIndexRiskVisible"))
                .containsEntry("sourcePublicationGateLatestAcceptedObservationStatus", publicationGate.get("sourceResultAggregationGateLatestAcceptedObservationStatus"))
                .containsEntry("sourcePublicationGateLatestAcceptedObservationToolName", publicationGate.get("sourceResultAggregationGateLatestAcceptedObservationToolName"))
                .containsEntry("sourcePublicationGateLatestAcceptedObservationVerificationStatus", publicationGate.get("sourceResultAggregationGateLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourcePublicationGateRollbackAcceptedObservationSummaryStatus", publicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourcePublicationGateRollbackAcceptedObservationSummaryObservationCount", publicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourcePublicationGateRollbackAcceptedObservationSummaryAcceptedCount", publicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourcePublicationGateRollbackAcceptedObservationSummaryRejectedCount", publicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourcePublicationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", publicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourcePublicationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", publicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerPublicationBoundarySchema", "learnbot.local-agent.final-answer-publication-boundary.v1")
                .containsEntry("sourceFinalAnswerPublicationBoundaryStatus", publicationBoundary.get("status"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryPrerequisitesPassed", publicationBoundary.get("prerequisitesPassed"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryDraftStatus", publicationBoundary.get("finalMutationReportDraftStatus"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryAcceptedObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("sourceFinalAnswerPublicationBoundaryAcceptedObservationSummaryStatus", publicationBoundary.get("acceptedMutationObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryAcceptedObservationCount", publicationBoundary.get("acceptedMutationObservationCount"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryAcceptedObservationAcceptedCount", publicationBoundary.get("acceptedMutationObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryAcceptedObservationRejectedCount", publicationBoundary.get("acceptedMutationObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryMissingMutationResultRiskVisible", publicationBoundary.get("missingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerPublicationBoundaryStaleIndexRiskVisible", publicationBoundary.get("staleIndexRiskVisible"))
                .containsEntry("finalAnswerGenerationPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("finalAnswerGenerationInvocationEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", publicationReady
                        ? "Local Agent mutation final-answer generation is explicitly refused: no final answer is generated."
                        : "Local Agent mutation final-answer generation is blocked because the disabled publication gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationPublicationGate",
                        "finalAnswerPublicationBoundary",
                        "finalAnswerGenerationPolicy",
                        "finalAnswerGeneration"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "finalAnswerGenerationPolicy",
                "finalAnswerGeneration",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerCompletionGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean finalAnswerGenerationReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerCompletionGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerCompletionGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswerGenerationGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerGenerationGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-completion-gate.v1")
                .containsEntry("status", status)
                .containsEntry("finalAnswerGenerationReady", finalAnswerGenerationReady)
                .containsEntry("prerequisitesPassed", finalAnswerGenerationReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerGenerationGateSchema", "learnbot.local-agent.mutation-final-answer-generation-gate.v1")
                .containsEntry("sourceFinalAnswerGenerationGateStatus", finalAnswerGenerationReady
                        ? "REFUSED_FINAL_ANSWER_GENERATION_DISABLED"
                        : "BLOCKED_FINAL_ANSWER_GENERATION_DISABLED")
                .containsEntry("sourceFinalAnswerGenerationGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerGenerationGateUserId", userId)
                .containsEntry("sourceFinalAnswerGenerationGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerGenerationGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerGenerationGatePublicationGateSchema", finalAnswerGenerationGate.get("sourcePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationGateStatus", finalAnswerGenerationGate.get("sourcePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationGateSessionId", finalAnswerGenerationGate.get("sourcePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationGateUserId", finalAnswerGenerationGate.get("sourcePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationGateAgentId", finalAnswerGenerationGate.get("sourcePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationGateWorkspaceId", finalAnswerGenerationGate.get("sourcePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationBoundaryStatus", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationBoundaryPrerequisitesPassed", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationBoundaryDraftStatus", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationBoundaryDraftSections", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalAnswerGenerationGateAcceptedObservationSummaryStatus", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerGenerationGateAcceptedObservationCount", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerGenerationGateAcceptedObservationAcceptedCount", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerGenerationGateAcceptedObservationRejectedCount", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerGenerationGateMissingMutationResultRiskVisible", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerGenerationGateStaleIndexRiskVisible", finalAnswerGenerationGate.get("sourceFinalAnswerPublicationBoundaryStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationAcceptedObservationSummaryStatus", finalAnswerGenerationGate.get("sourcePublicationGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationAcceptedObservationCount", finalAnswerGenerationGate.get("sourcePublicationGateAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationAcceptedObservationAcceptedCount", finalAnswerGenerationGate.get("sourcePublicationGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationAcceptedObservationRejectedCount", finalAnswerGenerationGate.get("sourcePublicationGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationMissingMutationResultRiskVisible", finalAnswerGenerationGate.get("sourcePublicationGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationStaleIndexRiskVisible", finalAnswerGenerationGate.get("sourcePublicationGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationStatus", finalAnswerGenerationGate.get("sourcePublicationGateLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationToolName", finalAnswerGenerationGate.get("sourcePublicationGateLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationVerificationStatus", finalAnswerGenerationGate.get("sourcePublicationGateLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStatus", finalAnswerGenerationGate.get("sourcePublicationGateRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalAnswerGenerationGate.get("sourcePublicationGateRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalAnswerGenerationGate.get("sourcePublicationGateRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalAnswerGenerationGate.get("sourcePublicationGateRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalAnswerGenerationGate.get("sourcePublicationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalAnswerGenerationGate.get("sourcePublicationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("finalAnswerCompletionPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("finalAnswerCompletionInvocationEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", finalAnswerGenerationReady
                        ? "Local Agent mutation final-answer completion is explicitly refused: no final answer is completed or delivered."
                        : "Local Agent mutation final-answer completion is blocked because the disabled final-answer generation gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalAnswerGenerationGate",
                        "finalAnswerCompletionPolicy",
                        "finalAnswerCompletion",
                        "finalAnswerDelivery"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "finalAnswerCompletionPolicy",
                "finalAnswerCompletion",
                "finalAnswerDelivery",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerPersistenceGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean finalAnswerCompletionReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerPersistenceGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerPersistenceGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswerCompletionGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerCompletionGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-persistence-gate.v1")
                .containsEntry("status", status)
                .containsEntry("finalAnswerCompletionReady", finalAnswerCompletionReady)
                .containsEntry("prerequisitesPassed", finalAnswerCompletionReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerCompletionGateSchema", "learnbot.local-agent.mutation-final-answer-completion-gate.v1")
                .containsEntry("sourceFinalAnswerCompletionGateStatus", finalAnswerCompletionReady
                        ? "REFUSED_FINAL_ANSWER_COMPLETION_DISABLED"
                        : "BLOCKED_FINAL_ANSWER_COMPLETION_DISABLED")
                .containsEntry("sourceFinalAnswerCompletionGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerCompletionGateUserId", userId)
                .containsEntry("sourceFinalAnswerCompletionGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerCompletionGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerCompletionGatePublicationGateSchema", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationGateStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationGateSessionId", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationGateUserId", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationGateAgentId", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationGateWorkspaceId", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationBoundaryStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationBoundaryStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationBoundaryPrerequisitesPassed", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationBoundaryDraftStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationBoundaryDraftSections", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalAnswerCompletionGateAcceptedObservationSummaryStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerCompletionGateAcceptedObservationCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGateAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerCompletionGateAcceptedObservationAcceptedCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerCompletionGateAcceptedObservationRejectedCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerCompletionGateMissingMutationResultRiskVisible", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerCompletionGateStaleIndexRiskVisible", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationAcceptedObservationSummaryStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationAcceptedObservationCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationAcceptedObservationAcceptedCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationAcceptedObservationRejectedCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationMissingMutationResultRiskVisible", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationStaleIndexRiskVisible", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationToolName", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationVerificationStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStatus", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalAnswerCompletionGate.get("sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("finalAnswerPersistencePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("finalAnswerPersistenceInvocationEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", finalAnswerCompletionReady
                        ? "Local Agent mutation final-answer persistence is explicitly refused: no final answer is persisted and no conversation turn is saved."
                        : "Local Agent mutation final-answer persistence is blocked because the disabled final-answer completion gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalAnswerCompletionGate",
                        "finalAnswerPersistencePolicy",
                        "finalAnswerPersistence",
                        "conversationTurnSave",
                        "finalAnswerDelivery"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "finalAnswerPersistencePolicy",
                "finalAnswerPersistence",
                "conversationTurnSave",
                "finalAnswerDelivery",
                "finalAnswerPersistenceEnabled",
                "conversationTurnSaveEnabled",
                "userVisibleCompletionEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerConversationSaveGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean finalAnswerPersistenceReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerConversationSaveGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerConversationSaveGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswerPersistenceGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerPersistenceGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-conversation-save-gate.v1")
                .containsEntry("status", status)
                .containsEntry("finalAnswerPersistenceReady", finalAnswerPersistenceReady)
                .containsEntry("prerequisitesPassed", finalAnswerPersistenceReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerPersistenceGateSchema", "learnbot.local-agent.mutation-final-answer-persistence-gate.v1")
                .containsEntry("sourceFinalAnswerPersistenceGateStatus", finalAnswerPersistenceReady
                        ? "REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED"
                        : "BLOCKED_FINAL_ANSWER_PERSISTENCE_DISABLED")
                .containsEntry("sourceFinalAnswerPersistenceGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerPersistenceGateUserId", userId)
                .containsEntry("sourceFinalAnswerPersistenceGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerPersistenceGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationGateSchema", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationGateStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationGateSessionId", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationGateUserId", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationGateAgentId", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationGateWorkspaceId", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationBoundaryStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationBoundaryStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationBoundaryPrerequisitesPassed", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationBoundaryDraftStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationBoundaryDraftSections", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalAnswerPersistenceGateAcceptedObservationSummaryStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGateAcceptedObservationCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGateAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerPersistenceGateAcceptedObservationAcceptedCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerPersistenceGateAcceptedObservationRejectedCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerPersistenceGateMissingMutationResultRiskVisible", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerPersistenceGateStaleIndexRiskVisible", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationSummaryStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationAcceptedCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationRejectedCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationMissingMutationResultRiskVisible", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationStaleIndexRiskVisible", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationToolName", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationVerificationStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStatus", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalAnswerPersistenceGate.get("sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("finalAnswerConversationSavePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("conversationTurnSaveInvocationEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", finalAnswerPersistenceReady
                        ? "Local Agent mutation final-answer conversation save is explicitly refused: no conversation turn is saved and no user-visible completion is marked."
                        : "Local Agent mutation final-answer conversation save is blocked because the disabled final-answer persistence gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalAnswerPersistenceGate",
                        "finalAnswerConversationSavePolicy",
                        "conversationTurnSave",
                        "userVisibleCompletion",
                        "finalAnswerDelivery"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "finalAnswerConversationSavePolicy",
                "conversationTurnSave",
                "userVisibleCompletion",
                "finalAnswerDelivery",
                "conversationTurnSaveEnabled",
                "userVisibleCompletionEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerDeliveryEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerUserVisibleCompletionGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean finalAnswerConversationSaveReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerUserVisibleCompletionGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerUserVisibleCompletionGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswerConversationSaveGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerConversationSaveGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-user-visible-completion-gate.v1")
                .containsEntry("status", status)
                .containsEntry("finalAnswerConversationSaveReady", finalAnswerConversationSaveReady)
                .containsEntry("prerequisitesPassed", finalAnswerConversationSaveReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerConversationSaveGateSchema", "learnbot.local-agent.mutation-final-answer-conversation-save-gate.v1")
                .containsEntry("sourceFinalAnswerConversationSaveGateStatus", finalAnswerConversationSaveReady
                        ? "REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED"
                        : "BLOCKED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED")
                .containsEntry("sourceFinalAnswerConversationSaveGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerConversationSaveGateUserId", userId)
                .containsEntry("sourceFinalAnswerConversationSaveGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerConversationSaveGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationGateSchema", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationGateStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationGateSessionId", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationGateUserId", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationGateAgentId", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationGateWorkspaceId", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationBoundaryStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationBoundaryStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationBoundaryPrerequisitesPassed", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftSections", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalAnswerConversationSaveGateAcceptedObservationSummaryStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGateAcceptedObservationCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGateAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGateAcceptedObservationAcceptedCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGateAcceptedObservationRejectedCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGateMissingMutationResultRiskVisible", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerConversationSaveGateStaleIndexRiskVisible", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationSummaryStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationAcceptedCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationRejectedCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationMissingMutationResultRiskVisible", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationStaleIndexRiskVisible", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationToolName", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationVerificationStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStatus", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalAnswerConversationSaveGate.get("sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("userVisibleCompletionPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", finalAnswerConversationSaveReady
                        ? "Local Agent mutation final-answer user-visible completion is explicitly refused: no user-visible completion is marked and no final response is handed off."
                        : "Local Agent mutation final-answer user-visible completion is blocked because the disabled final-answer conversation-save gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalAnswerConversationSaveGate",
                        "userVisibleCompletionPolicy",
                        "userVisibleCompletion",
                        "finalResponseHandoff",
                        "conversationTurnSave"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "userVisibleCompletionPolicy",
                "userVisibleCompletion",
                "finalResponseHandoff",
                "conversationTurnSave",
                "userVisibleCompletionEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "conversationTurnSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerDeliveryEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalResponseHandoffGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean userVisibleCompletionReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalResponseHandoffGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalResponseHandoffGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswerUserVisibleCompletionGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerUserVisibleCompletionGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-response-handoff-gate.v1")
                .containsEntry("status", status)
                .containsEntry("userVisibleCompletionReady", userVisibleCompletionReady)
                .containsEntry("prerequisitesPassed", userVisibleCompletionReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateSchema", "learnbot.local-agent.mutation-final-answer-user-visible-completion-gate.v1")
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateStatus", userVisibleCompletionReady
                        ? "REFUSED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED"
                        : "BLOCKED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED")
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateUserId", userId)
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryPrerequisitesPassed", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftSections", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationAcceptedCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationRejectedCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateMissingMutationResultRiskVisible", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGateStaleIndexRiskVisible", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationSummaryStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationAcceptedCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationRejectedCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationMissingMutationResultRiskVisible", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationStaleIndexRiskVisible", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationToolName", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationVerificationStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStatus", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("finalResponseHandoffPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", userVisibleCompletionReady
                        ? "Local Agent mutation final-response handoff is explicitly refused: no final response is handed off and no final answer is delivered."
                        : "Local Agent mutation final-response handoff is blocked because the disabled final-answer user-visible completion gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalAnswerUserVisibleCompletionGate",
                        "finalResponseHandoffPolicy",
                        "finalResponseHandoff",
                        "finalAnswerDelivery",
                        "userVisibleCompletion"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "finalResponseHandoffPolicy",
                "finalResponseHandoff",
                "finalAnswerDelivery",
                "userVisibleCompletion",
                "finalResponseHandoffEnabled",
                "deliveryHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerDeliveryEnabled",
                "userVisibleCompletionEnabled",
                "conversationTurnSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerDeliveryGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean finalResponseHandoffReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerDeliveryGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerDeliveryGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalResponseHandoffGate = (Map<String, Object>) latestAttempt.get("mutationFinalResponseHandoffGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-delivery-gate.v1")
                .containsEntry("status", status)
                .containsEntry("finalResponseHandoffReady", finalResponseHandoffReady)
                .containsEntry("prerequisitesPassed", finalResponseHandoffReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalResponseHandoffGateSchema", "learnbot.local-agent.mutation-final-response-handoff-gate.v1")
                .containsEntry("sourceFinalResponseHandoffGateStatus", finalResponseHandoffReady
                        ? "REFUSED_FINAL_RESPONSE_HANDOFF_DISABLED"
                        : "BLOCKED_FINAL_RESPONSE_HANDOFF_DISABLED")
                .containsEntry("sourceFinalResponseHandoffGateSessionId", sessionId)
                .containsEntry("sourceFinalResponseHandoffGateUserId", userId)
                .containsEntry("sourceFinalResponseHandoffGateAgentId", agentId)
                .containsEntry("sourceFinalResponseHandoffGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalResponseHandoffGatePublicationGateSchema", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationGateStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationGateSessionId", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationGateUserId", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationGateAgentId", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationGateWorkspaceId", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationBoundaryStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationBoundaryPrerequisitesPassed", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationBoundaryDraftStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationBoundaryDraftSections", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalResponseHandoffGateAcceptedObservationSummaryStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalResponseHandoffGateAcceptedObservationCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationCount"))
                .containsEntry("sourceFinalResponseHandoffGateAcceptedObservationAcceptedCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalResponseHandoffGateAcceptedObservationRejectedCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalResponseHandoffGateMissingMutationResultRiskVisible", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalResponseHandoffGateStaleIndexRiskVisible", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationAcceptedObservationSummaryStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationAcceptedObservationCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationAcceptedObservationAcceptedCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationAcceptedObservationRejectedCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationMissingMutationResultRiskVisible", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationStaleIndexRiskVisible", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationToolName", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationVerificationStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStatus", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("finalAnswerDeliveryPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("message", finalResponseHandoffReady
                        ? "Local Agent mutation final-answer delivery is explicitly refused: no final answer is delivered and no delivery handoff runs."
                        : "Local Agent mutation final-answer delivery is blocked because the disabled final-response handoff gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalResponseHandoffGate",
                        "finalAnswerDeliveryPolicy",
                        "finalAnswerDelivery",
                        "deliveryHandoff",
                        "finalResponseHandoff"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "finalAnswerDeliveryPolicy",
                "finalAnswerDelivery",
                "deliveryHandoff",
                "finalResponseHandoff",
                "finalAnswerDeliveryEnabled",
                "deliveryHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalResponseHandoffEnabled",
                "userVisibleCompletionEnabled",
                "conversationTurnSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationFinalAnswerDeliveryReceiptGate(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean finalAnswerDeliveryReady,
            int expectedResultCount
    ) {
        assertThat(latestAttempt.get("mutationFinalAnswerDeliveryReceiptGate")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerDeliveryReceiptGate");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalAnswerDeliveryGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerDeliveryGate");
        assertThat(gate)
                .containsEntry("schema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1")
                .containsEntry("status", status)
                .containsEntry("finalAnswerDeliveryReady", finalAnswerDeliveryReady)
                .containsEntry("prerequisitesPassed", finalAnswerDeliveryReady)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerDeliveryGateSchema", "learnbot.local-agent.mutation-final-answer-delivery-gate.v1")
                .containsEntry("sourceFinalAnswerDeliveryGateStatus", finalAnswerDeliveryReady
                        ? "REFUSED_FINAL_ANSWER_DELIVERY_DISABLED"
                        : "BLOCKED_FINAL_ANSWER_DELIVERY_DISABLED")
                .containsEntry("sourceFinalAnswerDeliveryGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerDeliveryGateUserId", userId)
                .containsEntry("sourceFinalAnswerDeliveryGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerDeliveryGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationGateSchema", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationGateStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationGateSessionId", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationGateUserId", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationGateAgentId", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationGateWorkspaceId", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationBoundaryStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationBoundaryStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationBoundaryPrerequisitesPassed", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationBoundaryPrerequisitesPassed"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationBoundaryDraftStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationBoundaryDraftStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationBoundaryDraftSections", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationBoundaryDraftSections"))
                .containsEntry("sourceFinalAnswerDeliveryGateAcceptedObservationSummaryStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGateAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGateAcceptedObservationCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGateAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerDeliveryGateAcceptedObservationAcceptedCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGateAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerDeliveryGateAcceptedObservationRejectedCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGateAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerDeliveryGateMissingMutationResultRiskVisible", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGateMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryGateStaleIndexRiskVisible", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGateStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationSummaryStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationAcceptedCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationRejectedCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationMissingMutationResultRiskVisible", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationStaleIndexRiskVisible", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationToolName", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationVerificationStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStatus", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryObservationCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryRejectedCount", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", finalAnswerDeliveryGate.get("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("deliveryReceiptPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("acknowledgementSavePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("acknowledgementSaveReady", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("expectedResultCount", expectedResultCount)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("intakePersistedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("observationAcceptanceEnabled", false)
                .containsEntry("intakePersistenceEnabled", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("rollbackFallbackExecutionEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("message", finalAnswerDeliveryReady
                        ? "Local Agent mutation final-answer delivery receipt is explicitly refused: no delivery receipt is recorded and no acknowledgement is saved."
                        : "Local Agent mutation final-answer delivery receipt is blocked because the disabled final-answer delivery gate is incomplete.");
        assertThat(gate.get("policyChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyChecks = (List<Map<String, Object>>) gate.get("policyChecks");
        assertThat(policyChecks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationFinalAnswerDeliveryGate",
                        "deliveryReceiptPolicy",
                        "deliveryReceipt",
                        "acknowledgementSave",
                        "finalAnswerDelivery",
                        "deliveryHandoff"
                );
        assertThat(policyChecks).allSatisfy(item -> assertThat(item)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false));
        assertThat(gate.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(gate.get("blockingKeys")).asList().contains(
                "deliveryReceiptPolicy",
                "deliveryReceipt",
                "acknowledgementSave",
                "finalAnswerDelivery",
                "deliveryHandoff",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerDeliveryEnabled",
                "deliveryHandoffEnabled",
                "finalResponseHandoffEnabled",
                "userVisibleCompletionEnabled",
                "conversationTurnSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        );
    }

    private void assertMutationResultIntakeBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(latestAttempt.get("mutationResultIntakeBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("mutationResultIntakeBoundary");
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-intake-boundary.v1")
                .containsEntry("status", "READY_INTAKE_DISABLED")
                .containsEntry("prerequisitesPassed", true)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("postMutationResultSchema", "learnbot.local-agent.post-mutation-result.v1")
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(boundary.get("requiredOutcomeKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> requiredOutcomeKeys = (List<String>) boundary.get("requiredOutcomeKeys");
        assertThat(requiredOutcomeKeys).containsExactly(
                "patchApplyOutcome",
                "allowlistedVerificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackFallbackOutcome",
                "ragFreshnessMarker"
        );
        assertThat(boundary.get("acceptedTerminalStatuses")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> acceptedTerminalStatuses = (List<String>) boundary.get("acceptedTerminalStatuses");
        assertThat(acceptedTerminalStatuses).containsExactly(
                LocalAgentToolStatus.SUCCEEDED.name(),
                LocalAgentToolStatus.FAILED.name(),
                LocalAgentToolStatus.REJECTED.name(),
                LocalAgentToolStatus.TIMED_OUT.name(),
                LocalAgentToolStatus.DISCONNECTED.name()
        );
        assertThat(boundary.get("requirements")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requirements = (List<Map<String, Object>>) boundary.get("requirements");
        assertThat(requirements)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "sourceRequestLink",
                        "releaseAttemptLink",
                        "expectedOutcomeKeys",
                        "mutationAppliedProof",
                        "verificationAndRollbackDisclosure",
                        "ragFreshnessDisclosure"
                );
        assertThat(requirements).allSatisfy(item -> assertThat(item)
                .containsEntry("passed", true)
                .containsEntry("blocking", false)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).isEmpty();
    }

    private void assertFinalMutationReportContract(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String rollbackReadinessStatus
    ) {
        assertThat(latestAttempt.get("finalMutationReportContract")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) latestAttempt.get("finalMutationReportContract");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        assertThat(contract)
                .containsEntry("schema", "learnbot.local-agent.final-mutation-report.v1")
                .containsEntry("status", "CONTRACT_DISABLED")
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("postMutationResultSchema", "learnbot.local-agent.post-mutation-result.v1")
                .containsEntry("acceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("acceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("acceptedMutationObservationCount", observationSummary.get("observationCount"))
                .containsEntry("acceptedMutationObservationAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("acceptedMutationObservationRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("acceptedMutationObservationTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("acceptedMutationObservationToolCounts", observationSummary.get("toolObservationCounts"))
                .containsEntry("acceptedMutationObservationStatusCounts", observationSummary.get("statusObservationCounts"))
                .containsEntry("rollbackReadinessStatus", rollbackReadinessStatus)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("acceptedObservationAggregationEnabled", false);
        assertThat(contract.get("expectedOutcomeKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> expectedOutcomeKeys = (List<String>) contract.get("expectedOutcomeKeys");
        assertThat(expectedOutcomeKeys).containsExactly(
                "patchApplyOutcome",
                "allowlistedVerificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackFallbackOutcome",
                "ragFreshnessMarker"
        );
        assertThat(contract.get("requiredSections")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) contract.get("requiredSections");
        assertThat(sections)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "changedFiles",
                        "verificationOutcome",
                        "postWriteRepositoryObservation",
                        "rollbackState",
                        "ragFreshnessState",
                        "residualRisks",
                        "evidenceAndCitations"
                );
        assertThat(sections).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "REQUIRED_DISABLED")
                .containsEntry("required", true)
                .containsEntry("resultRequired", true)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false));
        assertThat(sections.get(0)).containsEntry("sourceOutcomeKey", "patchApplyOutcome");
        assertThat(sections.get(1)).containsEntry("sourceOutcomeKey", "allowlistedVerificationOutcome");
        assertThat(sections.get(2)).containsEntry("sourceOutcomeKey", "postWriteRepositoryObservation");
        assertThat(sections.get(3)).containsEntry("sourceOutcomeKey", "rollbackFallbackOutcome");
        assertThat(sections.get(4)).containsEntry("sourceOutcomeKey", "ragFreshnessMarker");
        assertThat(sections.get(5)).doesNotContainKey("sourceOutcomeKey");
        assertThat(sections.get(6)).doesNotContainKey("sourceOutcomeKey");
        assertThat(contract.get("answerQualityGuardrails")).isInstanceOf(List.class);
        assertThat(contract.get("answerQualityGuardrails").toString())
                .contains("must not claim files changed", "failed or skipped verification", "RAG freshness", "rollback state");
    }

    private void assertMutationResultAggregationPlan(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(latestAttempt.get("mutationResultAggregationPlan")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) latestAttempt.get("mutationResultAggregationPlan");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        assertThat(plan)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-aggregation-plan.v1")
                .containsEntry("status", "READY_AGGREGATION_DISABLED")
                .containsEntry("prerequisitesPassed", true)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("postMutationResultSchema", "learnbot.local-agent.post-mutation-result.v1")
                .containsEntry("finalMutationReportSchema", "learnbot.local-agent.final-mutation-report.v1")
                .containsEntry("acceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("acceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("acceptedMutationObservationCount", observationSummary.get("observationCount"))
                .containsEntry("acceptedMutationObservationAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("acceptedMutationObservationRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("acceptedMutationObservationTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("acceptedMutationObservationToolCounts", observationSummary.get("toolObservationCounts"))
                .containsEntry("acceptedMutationObservationStatusCounts", observationSummary.get("statusObservationCounts"))
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("acceptedObservationAggregationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(plan.get("sourceOutcomeKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> sourceOutcomeKeys = (List<String>) plan.get("sourceOutcomeKeys");
        assertThat(sourceOutcomeKeys).containsExactly(
                "patchApplyOutcome",
                "allowlistedVerificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackFallbackOutcome",
                "ragFreshnessMarker"
        );
        assertThat(plan.get("targetReportSections")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> targetReportSections = (List<String>) plan.get("targetReportSections");
        assertThat(targetReportSections).containsExactly(
                "changedFiles",
                "verificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackState",
                "ragFreshnessState",
                "residualRisks",
                "evidenceAndCitations"
        );
        assertThat(plan.get("steps")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");
        assertThat(steps)
                .extracting(item -> item.get("targetSectionKey"))
                .containsExactly(
                        "changedFiles",
                        "verificationOutcome",
                        "postWriteRepositoryObservation",
                        "rollbackState",
                        "ragFreshnessState",
                        "residualRisks",
                        "evidenceAndCitations"
                );
        assertThat(steps).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "PLANNED_DISABLED")
                .containsEntry("required", true)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(steps.get(0)).containsEntry("sourceOutcomeKey", "patchApplyOutcome");
        assertThat(steps.get(1)).containsEntry("sourceOutcomeKey", "allowlistedVerificationOutcome");
        assertThat(steps.get(2)).containsEntry("sourceOutcomeKey", "postWriteRepositoryObservation");
        assertThat(steps.get(3)).containsEntry("sourceOutcomeKey", "rollbackFallbackOutcome");
        assertThat(steps.get(4)).containsEntry("sourceOutcomeKey", "ragFreshnessMarker");
        assertThat(steps.get(5)).doesNotContainKey("sourceOutcomeKey");
        assertThat(steps.get(6)).doesNotContainKey("sourceOutcomeKey");
        assertThat(plan.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) plan.get("blockingKeys");
        assertThat(blockingKeys).isEmpty();
    }

    private void assertFinalMutationReportDraft(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(latestAttempt.get("finalMutationReportDraft")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) latestAttempt.get("finalMutationReportDraft");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        assertThat(draft)
                .containsEntry("schema", "learnbot.local-agent.final-mutation-report-draft.v1")
                .containsEntry("status", "READY_DRAFT_DISABLED")
                .containsEntry("prerequisitesPassed", true)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("aggregationPlanSchema", "learnbot.local-agent.mutation-result-aggregation-plan.v1")
                .containsEntry("aggregationPlanStatus", "READY_AGGREGATION_DISABLED")
                .containsEntry("finalMutationReportSchema", "learnbot.local-agent.final-mutation-report.v1")
                .containsEntry("finalMutationReportStatus", "CONTRACT_DISABLED")
                .containsEntry("acceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("acceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("acceptedMutationObservationCount", observationSummary.get("observationCount"))
                .containsEntry("acceptedMutationObservationAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("acceptedMutationObservationRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("acceptedMutationObservationTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("acceptedMutationObservationToolCounts", observationSummary.get("toolObservationCounts"))
                .containsEntry("acceptedMutationObservationStatusCounts", observationSummary.get("statusObservationCounts"))
                .containsEntry("missingMutationResultRiskVisible", ((Number) observationSummary.get("observationCount")).intValue() == 0)
                .containsEntry("staleIndexRiskVisible", ((Number) observationSummary.get("acceptedCount")).intValue() > 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("acceptedObservationAggregationEnabled", false)
                .containsEntry("finalMutationReportDraftEnabled", false)
                .containsEntry("finalReportGenerationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("publicationEnabled", false);
        assertThat(draft.get("sections")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) draft.get("sections");
        assertThat(sections)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "changedFiles",
                        "verificationOutcome",
                        "postWriteRepositoryObservation",
                        "rollbackState",
                        "ragFreshnessState",
                        "residualRisks",
                        "evidenceAndCitations"
                );
        assertThat(sections).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "PENDING_RESULT_DISABLED")
                .containsEntry("sourceOutcomeModeled", true)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("finalReportGenerationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(sections.get(0))
                .containsEntry("sourceOutcomeKey", "patchApplyOutcome")
                .containsEntry("aggregationSourceOutcomeKey", "patchApplyOutcome")
                .containsEntry("aggregationStepStatus", "PLANNED_DISABLED");
        assertThat(sections.get(5))
                .containsEntry("sourceOutcomeKey", null)
                .containsEntry("aggregationSourceOutcomeKey", null);
        assertThat(draft.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) draft.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(
                "mutationResultAggregationEnabled",
                "finalReportGenerationEnabled",
                "publicationEnabled"
        );
        assertThat(draft.get("message").toString())
                .contains("final mutation report draft", "aggregation", "publication");
    }

    private void assertFinalMutationReportFinalizationBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("finalMutationReportFinalizationBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("finalMutationReportFinalizationBoundary");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.finalization-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("acceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("acceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("acceptedMutationObservationCount", observationSummary.get("observationCount"))
                .containsEntry("acceptedMutationObservationAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("acceptedMutationObservationRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("acceptedMutationObservationTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("acceptedMutationObservationToolCounts", observationSummary.get("toolObservationCounts"))
                .containsEntry("acceptedMutationObservationStatusCounts", observationSummary.get("statusObservationCounts"))
                .containsEntry("missingMutationResultRiskVisible", ((Number) observationSummary.get("observationCount")).intValue() == 0)
                .containsEntry("staleIndexRiskVisible", ((Number) observationSummary.get("acceptedCount")).intValue() > 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false);
        assertThat(boundary.get("requirements")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requirements = (List<Map<String, Object>>) boundary.get("requirements");
        assertThat(requirements)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "releaseAttemptReady",
                        "postMutationOutcomesModeled",
                        "finalReportSectionsModeled",
                        "answerQualityGuardrailsModeled"
                );
        assertThat(requirements).allSatisfy(item -> assertThat(item)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
    }

    private void assertFinalAnswerPublicationBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("finalAnswerPublicationBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("finalAnswerPublicationBoundary");
        @SuppressWarnings("unchecked")
        Map<String, Object> observationSummary = (Map<String, Object>) latestAttempt.get("acceptedMutationObservationSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) latestAttempt.get("finalMutationReportDraft");
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.final-answer-publication-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("finalMutationReportSchema", "learnbot.local-agent.final-mutation-report.v1")
                .containsEntry("aggregationPlanSchema", "learnbot.local-agent.mutation-result-aggregation-plan.v1")
                .containsEntry("finalMutationReportDraftSchema", "learnbot.local-agent.final-mutation-report-draft.v1")
                .containsEntry("finalMutationReportDraftStatus", draft.get("status"))
                .containsEntry("acceptedMutationObservationSummarySchema", "learnbot.local-agent.accepted-mutation-observation-summary.v1")
                .containsEntry("acceptedMutationObservationSummaryStatus", observationSummary.get("status"))
                .containsEntry("acceptedMutationObservationCount", observationSummary.get("observationCount"))
                .containsEntry("acceptedMutationObservationAcceptedCount", observationSummary.get("acceptedCount"))
                .containsEntry("acceptedMutationObservationRejectedCount", observationSummary.get("rejectedCount"))
                .containsEntry("acceptedMutationObservationTerminalFailureAcceptedCount", observationSummary.get("terminalFailureAcceptedCount"))
                .containsEntry("acceptedMutationObservationToolCounts", observationSummary.get("toolObservationCounts"))
                .containsEntry("acceptedMutationObservationStatusCounts", observationSummary.get("statusObservationCounts"))
                .containsEntry("missingMutationResultRiskVisible", ((Number) observationSummary.get("observationCount")).intValue() == 0)
                .containsEntry("staleIndexRiskVisible", ((Number) observationSummary.get("acceptedCount")).intValue() > 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("publicationEnabled", false);
        assertThat(boundary.get("requiredReportSections")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> sections = (List<String>) boundary.get("requiredReportSections");
        assertThat(sections).containsExactly(
                "changedFiles",
                "verificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackState",
                "ragFreshnessState",
                "residualRisks",
                "evidenceAndCitations"
        );
        assertThat(boundary.get("finalMutationReportDraftSections")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> draftSections = (List<String>) boundary.get("finalMutationReportDraftSections");
        assertThat(draftSections).containsExactly(
                "changedFiles",
                "verificationOutcome",
                "postWriteRepositoryObservation",
                "rollbackState",
                "ragFreshnessState",
                "residualRisks",
                "evidenceAndCitations"
        );
        assertThat(boundary.get("answerQualityGuardrails")).isInstanceOf(List.class);
        assertThat(boundary.get("answerQualityGuardrails").toString())
                .contains("must not claim files changed", "failed or skipped verification", "RAG freshness", "rollback state");
        assertThat(boundary.get("requirements")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requirements = (List<Map<String, Object>>) boundary.get("requirements");
        assertThat(requirements)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "releaseAttemptReady",
                        "aggregationPlanModeled",
                        "finalReportContractModeled",
                        "finalReportDraftModeled",
                        "answerQualityGuardrailsModeled"
                );
        assertThat(requirements).allSatisfy(item -> assertThat(item)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("publicationEnabled", false));
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
    }

    private void assertReleaseEnablementChecklist(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("releaseEnablementChecklist")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> checklist = (Map<String, Object>) latestAttempt.get("releaseEnablementChecklist");
        assertThat(checklist)
                .containsEntry("schema", "learnbot.local-agent.release-enablement-checklist.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false);
        assertThat(checklist.get("items")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) checklist.get("items");
        assertThat(items)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "finalReadiness",
                        "localAgentMutationExecutionSequence",
                        "postMutationResultContract",
                        "rollbackReadiness",
                        "ragFreshnessRequirement"
                );
        assertThat(items).allSatisfy(item -> assertThat(item)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
        assertThat(checklist.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) checklist.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
    }

    private void assertMutationCompletionSummary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationCompletionSummary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) latestAttempt.get("mutationCompletionSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> deliveryReceiptGate = (Map<String, Object>) latestAttempt.get("mutationFinalAnswerDeliveryReceiptGate");
        String sourceDeliveryReceiptGateStatus = List.of(expectedBlockingKeys).contains("mutationFinalAnswerDeliveryReceiptGate")
                ? "BLOCKED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED"
                : "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED";
        String expectedPublicationStatus = prerequisitesPassed ? "READY_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED";
        assertThat(summary)
                .containsEntry("schema", "learnbot.local-agent.mutation-completion-summary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateSchema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1")
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateStatus", sourceDeliveryReceiptGateStatus)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateSessionId", sessionId)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateUserId", userId)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateAgentId", agentId)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateWorkspaceId", workspaceId)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateAcknowledgementSavePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("sourceFinalAnswerDeliveryReceiptGateAcknowledgementSaveEnabled", false)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationGateSchema", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateSchema"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationGateStatus", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateStatus"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationGateSessionId", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateSessionId"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationGateUserId", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateUserId"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationGateAgentId", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateAgentId"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationGateWorkspaceId", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateWorkspaceId"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryStatus", expectedPublicationStatus)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", prerequisitesPassed)
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryDraftStatus", "READY_DRAFT_DISABLED")
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationCount", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationStaleIndexRiskVisible", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", deliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", prerequisitesPassed
                        ? "Local Agent mutation completion prerequisites are modeled, but execution, aggregation, publication, and final-answer generation remain disabled."
                        : "Local Agent mutation completion prerequisites are incomplete, and execution, aggregation, publication, and final-answer generation remain disabled.");
        assertThat(summary.get("items")).isInstanceOf(List.class);
        assertThat(summary).containsKeys(
                "sourceFinalAnswerDeliveryReceiptGateAcceptedObservationSummaryStatus",
                "sourceFinalAnswerDeliveryReceiptGateAcceptedObservationCount",
                "sourceFinalAnswerDeliveryReceiptGateAcceptedObservationAcceptedCount",
                "sourceFinalAnswerDeliveryReceiptGateAcceptedObservationRejectedCount",
                "sourceFinalAnswerDeliveryReceiptGateMissingMutationResultRiskVisible",
                "sourceFinalAnswerDeliveryReceiptGateStaleIndexRiskVisible",
                "sourceFinalAnswerDeliveryReceiptGatePublicationGateSchema",
                "sourceFinalAnswerDeliveryReceiptGatePublicationGateStatus",
                "sourceFinalAnswerDeliveryReceiptGatePublicationGateSessionId",
                "sourceFinalAnswerDeliveryReceiptGatePublicationGateUserId",
                "sourceFinalAnswerDeliveryReceiptGatePublicationGateAgentId",
                "sourceFinalAnswerDeliveryReceiptGatePublicationGateWorkspaceId",
                "sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus",
                "sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationCount",
                "sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount",
                "sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationRejectedCount",
                "sourceFinalAnswerDeliveryReceiptGatePublicationMissingMutationResultRiskVisible",
                "sourceFinalAnswerDeliveryReceiptGatePublicationStaleIndexRiskVisible",
                "sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationStatus",
                "sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationToolName",
                "sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus",
                "sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus",
                "sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount",
                "sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount",
                "sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount",
                "sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible",
                "sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) summary.get("items");
        assertThat(items)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "releaseAttemptReadiness",
                        "mutationExecutionSequencePlan",
                        "mutationResultIntakeBoundary",
                        "mutationResultAggregationPlan",
                        "finalMutationReportDraft",
                        "finalMutationReportContract",
                        "finalMutationReportFinalizationBoundary",
                        "finalAnswerPublicationBoundary",
                        "releaseEnablementChecklist",
                        "mutationDispatchEnvelopeContract",
                        "mutationDispatchPreflightBoundary",
                        "mutationDispatchDecisionModel",
                        "mutationRequestBlueprint",
                        "mutationRequestCreationGate",
                        "mutationRequestPushGate",
                        "mutationRequestClaimGate",
                        "mutationExecutionGate",
                        "mutationWriteHelperSafetyGate",
                        "mutationPostExecutionObservationGate",
                        "mutationObservationAcceptanceGate",
                        "mutationResultIntakePersistenceGate",
                        "mutationRollbackFallbackGate",
                        "mutationRagFreshnessGate",
                        "mutationResultAggregationGate",
                        "mutationPublicationGate",
                        "mutationFinalAnswerGenerationGate",
                        "mutationFinalAnswerCompletionGate",
                        "mutationFinalAnswerPersistenceGate",
                        "mutationFinalAnswerConversationSaveGate",
                        "mutationFinalAnswerUserVisibleCompletionGate",
                        "mutationFinalResponseHandoffGate",
                        "mutationFinalAnswerDeliveryGate",
                        "mutationFinalAnswerDeliveryReceiptGate",
                        "acknowledgementSaveRefusal",
                        "rollbackReadiness",
                        "ragFreshnessRequirement"
                );
        assertThat(items).allSatisfy(item -> assertThat(item)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
        assertThat(summary.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) summary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
    }

    private void assertMutationHandoffSummary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationHandoffSummary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) latestAttempt.get("mutationHandoffSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> completionSummary = (Map<String, Object>) latestAttempt.get("mutationCompletionSummary");
        String sourceDeliveryReceiptGateStatus = List.of(expectedBlockingKeys).contains("mutationFinalAnswerDeliveryReceiptGate")
                ? "BLOCKED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED"
                : "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED";
        String expectedPublicationStatus = prerequisitesPassed ? "READY_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED";
        assertThat(summary)
                .containsEntry("schema", "learnbot.local-agent.mutation-handoff-summary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceCompletionSummaryStatus", prerequisitesPassed ? "READY_COMPLETION_DISABLED" : "BLOCKED_COMPLETION_DISABLED")
                .containsEntry("sourceCompletionSummarySchema", "learnbot.local-agent.mutation-completion-summary.v1")
                .containsEntry("sourceCompletionPrerequisitesPassed", prerequisitesPassed)
                .containsEntry("sourceCompletionSummarySessionId", sessionId)
                .containsEntry("sourceCompletionSummaryUserId", userId)
                .containsEntry("sourceCompletionSummaryAgentId", agentId)
                .containsEntry("sourceCompletionSummaryWorkspaceId", workspaceId)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateSchema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1")
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateStatus", sourceDeliveryReceiptGateStatus)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateSessionId", sessionId)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateUserId", userId)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateAgentId", agentId)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateWorkspaceId", workspaceId)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSavePolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSaveEnabled", false)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationGateSchema", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateSchema"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationGateStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateStatus"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationGateSessionId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateSessionId"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationGateUserId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateUserId"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationGateAgentId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateAgentId"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationGateWorkspaceId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateWorkspaceId"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryStatus", expectedPublicationStatus)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", prerequisitesPassed)
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus", "READY_DRAFT_DISABLED")
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("message", prerequisitesPassed
                        ? "Local Agent mutation handoff prerequisites are modeled, but release, request creation, push, claim, execution, result handling, final response, delivery, and mutation remain disabled."
                        : "Local Agent mutation handoff is blocked by incomplete disabled readiness inputs, and all handoff controls remain disabled.");
        assertThat(summary.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(summary).containsKeys(
                "sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationSummaryStatus",
                "sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationCount",
                "sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount",
                "sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationRejectedCount",
                "sourceCompletionSummaryDeliveryReceiptGateMissingMutationResultRiskVisible",
                "sourceCompletionSummaryDeliveryReceiptGateStaleIndexRiskVisible",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationGateSchema",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationGateStatus",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationGateSessionId",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationGateUserId",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationGateAgentId",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationGateWorkspaceId",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationCount",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible",
                "sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"
        );
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) summary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);

        assertThat(summary.get("disabledControls")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> disabledControls = (Map<String, Object>) summary.get("disabledControls");
        assertThat(disabledControls)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalAnswerCompletionEnabled", false)
                .containsEntry("finalAnswerDeliveryEnabled", false)
                .containsEntry("finalAnswerPersistenceEnabled", false)
                .containsEntry("conversationTurnSaveEnabled", false)
                .containsEntry("userVisibleCompletionEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);

        assertThat(summary.get("handoffStages")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) summary.get("handoffStages");
        assertThat(stages)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "dispatchDecision",
                        "requestCreation",
                        "transportPush",
                        "agentClaim",
                        "toolExecution",
                        "resultIntake",
                        "finalResponse",
                        "deliveryReceipt",
                        "acknowledgementSave"
                );
        assertThat(stages).allSatisfy(stage -> assertThat(stage)
                .containsEntry("status", prerequisitesPassed ? "MODELED_DISABLED" : "BLOCKED_DISABLED")
                .containsEntry("passed", prerequisitesPassed)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
    }

    private void assertMutationExecutionReadinessBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationExecutionReadinessBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("mutationExecutionReadinessBoundary");
        @SuppressWarnings("unchecked")
        Map<String, Object> handoffSummary = (Map<String, Object>) latestAttempt.get("mutationHandoffSummary");
        String expectedPublicationStatus = prerequisitesPassed ? "READY_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED";
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.mutation-execution-readiness-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceHandoffSummarySchema", "learnbot.local-agent.mutation-handoff-summary.v1")
                .containsEntry("sourceHandoffSummaryStatus", prerequisitesPassed ? "READY_HANDOFF_DISABLED" : "BLOCKED_HANDOFF_DISABLED")
                .containsEntry("sourceHandoffSummarySessionId", sessionId)
                .containsEntry("sourceHandoffSummaryUserId", userId)
                .containsEntry("sourceHandoffSummaryAgentId", agentId)
                .containsEntry("sourceHandoffSummaryWorkspaceId", workspaceId)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGateSchema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1")
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGateStatus", "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED")
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGateSessionId", sessionId)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGateUserId", userId)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGateAgentId", agentId)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGateWorkspaceId", workspaceId)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationGateSchema", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationGateSchema"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationGateStatus", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationGateStatus"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationGateSessionId", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationGateSessionId"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationGateUserId", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationGateUserId"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationGateAgentId", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationGateAgentId"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationGateWorkspaceId", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationGateWorkspaceId"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryStatus", expectedPublicationStatus)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", prerequisitesPassed)
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus", "READY_DRAFT_DISABLED")
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationCount", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", handoffSummary.get("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourceExecutionGateSchema", "learnbot.local-agent.mutation-execution-gate.v1")
                .containsEntry("sourceExecutionGateStatus", "REFUSED_EXECUTION_DISABLED")
                .containsEntry("sourceExecutionGateSessionId", sessionId)
                .containsEntry("sourceExecutionGateUserId", userId)
                .containsEntry("sourceExecutionGateAgentId", agentId)
                .containsEntry("sourceExecutionGateWorkspaceId", workspaceId)
                .containsEntry("sourceWriteHelperSafetyGateSchema", "learnbot.local-agent.mutation-write-helper-safety-gate.v1")
                .containsEntry("sourceWriteHelperSafetyGateStatus", "REFUSED_WRITE_HELPER_DISABLED")
                .containsEntry("sourceWriteHelperSafetyGateSessionId", sessionId)
                .containsEntry("sourceWriteHelperSafetyGateUserId", userId)
                .containsEntry("sourceWriteHelperSafetyGateAgentId", agentId)
                .containsEntry("sourceWriteHelperSafetyGateWorkspaceId", workspaceId)
                .containsEntry("expectedRequestCount", 4)
                .containsEntry("completedRequestCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("toolRunnerEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", prerequisitesPassed
                        ? "Local Agent mutation execution inputs are modeled, but runtime execution, request creation, push, claim, write helper, apply, test, rollback restore, result intake, final response handoff, delivery receipt, and mutation remain disabled."
                        : "Local Agent mutation execution readiness is blocked by incomplete disabled handoff, execution, or write-helper inputs.");
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(boundary).containsKeys(
                "sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationSummaryStatus",
                "sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationCount",
                "sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount",
                "sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationRejectedCount",
                "sourceHandoffSummaryDeliveryReceiptGateMissingMutationResultRiskVisible",
                "sourceHandoffSummaryDeliveryReceiptGateStaleIndexRiskVisible",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationGateSchema",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationGateStatus",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationGateSessionId",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationGateUserId",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationGateAgentId",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationGateWorkspaceId",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationCount",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible",
                "sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"
        );
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);

        assertThat(boundary.get("readinessChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) boundary.get("readinessChecks");
        assertThat(checks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationHandoffSummary",
                        "mutationExecutionGate",
                        "mutationWriteHelperSafetyGate",
                        "runtimeExecutionSwitch",
                        "sideEffectTransport"
                );
        assertThat(checks).allSatisfy(check -> assertThat(check)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("toolRunnerEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
    }

    private void assertMutationToolRunnerBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationToolRunnerBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("mutationToolRunnerBoundary");
        @SuppressWarnings("unchecked")
        Map<String, Object> executionReadinessBoundary = (Map<String, Object>) latestAttempt.get("mutationExecutionReadinessBoundary");
        String expectedPublicationStatus = prerequisitesPassed ? "READY_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED";
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.mutation-tool-runner-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceExecutionReadinessBoundarySchema", "learnbot.local-agent.mutation-execution-readiness-boundary.v1")
                .containsEntry("sourceExecutionReadinessBoundaryStatus", prerequisitesPassed ? "REFUSED_EXECUTION_READINESS_DISABLED" : "BLOCKED_EXECUTION_READINESS_DISABLED")
                .containsEntry("sourceExecutionReadinessBoundarySessionId", sessionId)
                .containsEntry("sourceExecutionReadinessBoundaryUserId", userId)
                .containsEntry("sourceExecutionReadinessBoundaryAgentId", agentId)
                .containsEntry("sourceExecutionReadinessBoundaryWorkspaceId", workspaceId)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGateSchema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1")
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGateStatus", "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED")
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGateSessionId", sessionId)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGateUserId", userId)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGateAgentId", agentId)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGateWorkspaceId", workspaceId)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSchema", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationGateSchema"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateStatus", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationGateStatus"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSessionId", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationGateSessionId"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateUserId", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationGateUserId"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateAgentId", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationGateAgentId"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateWorkspaceId", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationGateWorkspaceId"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryStatus", expectedPublicationStatus)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", prerequisitesPassed)
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryDraftStatus", "READY_DRAFT_DISABLED")
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", executionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourceExecutionGateSchema", "learnbot.local-agent.mutation-execution-gate.v1")
                .containsEntry("sourceExecutionGateStatus", "REFUSED_EXECUTION_DISABLED")
                .containsEntry("sourceExecutionGateSessionId", sessionId)
                .containsEntry("sourceExecutionGateUserId", userId)
                .containsEntry("sourceExecutionGateAgentId", agentId)
                .containsEntry("sourceExecutionGateWorkspaceId", workspaceId)
                .containsEntry("toolRunnerPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("expectedRequestCount", 4)
                .containsEntry("runningRequestCount", 0)
                .containsEntry("completedRequestCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("runningTransitionEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("toolRunnerEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", prerequisitesPassed
                        ? "Local Agent mutation tool-runner inputs are modeled, but runner invocation, running transition, result completion, write helper, apply, test, rollback restore, result intake, and mutation remain disabled."
                        : "Local Agent mutation tool-runner boundary is blocked by incomplete disabled execution readiness or execution gate inputs.");
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(boundary).containsKeys(
                "sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationSummaryStatus",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationAcceptedCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationRejectedCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGateMissingMutationResultRiskVisible",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGateStaleIndexRiskVisible",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSchema",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateStatus",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSessionId",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateUserId",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateAgentId",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateWorkspaceId",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible",
                "sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"
        );
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);

        assertThat(boundary.get("runnerChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) boundary.get("runnerChecks");
        assertThat(checks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationExecutionReadinessBoundary",
                        "mutationExecutionGate",
                        "toolRunnerPolicy",
                        "requestRunningTransition",
                        "resultCompletionTransition"
                );
        assertThat(checks).allSatisfy(check -> assertThat(check)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("runningTransitionEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("toolRunnerEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false));
    }

    private void assertMutationResultCompletionBoundary(
            Map<String, Object> latestAttempt,
            UUID attemptId,
            UUID sourceRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationResultCompletionBoundary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> boundary = (Map<String, Object>) latestAttempt.get("mutationResultCompletionBoundary");
        @SuppressWarnings("unchecked")
        Map<String, Object> toolRunnerBoundary = (Map<String, Object>) latestAttempt.get("mutationToolRunnerBoundary");
        String expectedPublicationStatus = prerequisitesPassed ? "READY_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED";
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-completion-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("sessionId", sessionId)
                .containsEntry("userId", userId)
                .containsEntry("agentId", agentId)
                .containsEntry("workspaceId", workspaceId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceToolRunnerBoundarySchema", "learnbot.local-agent.mutation-tool-runner-boundary.v1")
                .containsEntry("sourceToolRunnerBoundaryStatus", prerequisitesPassed ? "REFUSED_TOOL_RUNNER_DISABLED" : "BLOCKED_TOOL_RUNNER_DISABLED")
                .containsEntry("sourceToolRunnerBoundarySessionId", sessionId)
                .containsEntry("sourceToolRunnerBoundaryUserId", userId)
                .containsEntry("sourceToolRunnerBoundaryAgentId", agentId)
                .containsEntry("sourceToolRunnerBoundaryWorkspaceId", workspaceId)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGateSchema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1")
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGateStatus", "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED")
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGateSessionId", sessionId)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGateUserId", userId)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGateAgentId", agentId)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGateWorkspaceId", workspaceId)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSchema", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSchema"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateStatus", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateStatus"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSessionId", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSessionId"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateUserId", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateUserId"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateAgentId", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateAgentId"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateWorkspaceId", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateWorkspaceId"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryStatus", expectedPublicationStatus)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", prerequisitesPassed)
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryDraftStatus", "READY_DRAFT_DISABLED")
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"))
                .containsEntry("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", toolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"))
                .containsEntry("sourcePostExecutionObservationGateSchema", "learnbot.local-agent.mutation-post-execution-observation-gate.v1")
                .containsEntry("sourcePostExecutionObservationGateStatus", "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED")
                .containsEntry("sourcePostExecutionObservationGateSessionId", sessionId)
                .containsEntry("sourcePostExecutionObservationGateUserId", userId)
                .containsEntry("sourcePostExecutionObservationGateAgentId", agentId)
                .containsEntry("sourcePostExecutionObservationGateWorkspaceId", workspaceId)
                .containsEntry("completionPolicy", "DISABLED_AUDIT_ONLY")
                .containsEntry("expectedResultCount", 4)
                .containsEntry("completedResultCount", 0)
                .containsEntry("acceptedResultCount", 0)
                .containsEntry("rejectedResultCount", 0)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("runningTransitionEnabled", false)
                .containsEntry("executionEnabled", false)
                .containsEntry("toolRunnerEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("completedResultTransitionEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationResultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("finalAnswerGenerationEnabled", false)
                .containsEntry("finalResponseHandoffEnabled", false)
                .containsEntry("deliveryReceiptEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("message", prerequisitesPassed
                        ? "Local Agent mutation result-completion inputs are modeled, but completed transition, result persistence, observation capture, result intake, and mutation remain disabled."
                        : "Local Agent mutation result completion is blocked by incomplete disabled tool-runner or post-execution observation inputs.");
        assertThat(boundary.get("blockingKeys")).isInstanceOf(List.class);
        assertThat(boundary).containsKeys(
                "sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationSummaryStatus",
                "sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationAcceptedCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationRejectedCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGateMissingMutationResultRiskVisible",
                "sourceToolRunnerBoundaryDeliveryReceiptGateStaleIndexRiskVisible",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSchema",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateStatus",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSessionId",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateUserId",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateAgentId",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateWorkspaceId",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible",
                "sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"
        );
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) boundary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);

        assertThat(boundary.get("resultChecks")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) boundary.get("resultChecks");
        assertThat(checks)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "mutationToolRunnerBoundary",
                        "mutationPostExecutionObservationGate",
                        "completedResultTransition",
                        "resultEnvelopePersistence",
                        "observationCapture"
                );
        assertThat(checks).allSatisfy(check -> assertThat(check)
                .containsEntry("toolRunnerEnabled", false)
                .containsEntry("completedResultTransitionEnabled", false)
                .containsEntry("completedResultPersistenceEnabled", false)
                .containsEntry("postExecutionObservationEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false));
    }

    private void assertDisabledFreshObservationRequestTemplates(
            Map<String, Object> evidence,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(evidence.get("freshObservationRequestTemplates")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> templates = (List<Map<String, Object>>) evidence.get("freshObservationRequestTemplates");
        assertThat(templates)
                .extracting(item -> item.get("key"))
                .containsExactly("repositoryVerification", "patchDryRun");
        assertThat(templates).allSatisfy(item -> assertThat(item)
                .containsEntry("status", "TEMPLATE_DISABLED")
                .containsEntry("enqueueEnabled", false)
                .containsEntry("claimableAfterEnqueue", false)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("releaseAttemptId", attemptId));

        Map<String, Object> repositoryVerification = templates.stream()
                .filter(item -> "repositoryVerification".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(repositoryVerification)
                .containsEntry("toolName", LocalAgentToolName.GIT_STATUS.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.NOT_REQUIRED.name());
        Map<String, Object> repositoryVerificationInput = ((Map<?, ?>) repositoryVerification.get("input"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(repositoryVerificationInput)
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", attemptId.toString())
                .containsEntry("freshObservationOnly", true);

        Map<String, Object> patchDryRun = templates.stream()
                .filter(item -> "patchDryRun".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(patchDryRun)
                .containsEntry("toolName", LocalAgentToolName.PATCH_APPLY.wireName())
                .containsEntry("approvalState", LocalAgentApprovalState.APPROVED.name());
        Map<String, Object> patchDryRunInput = ((Map<?, ?>) patchDryRun.get("input"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(patchDryRunInput)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", attemptId.toString())
                .containsEntry("freshObservationOnly", true);
    }

    private void assertDisabledFreshObservationEnqueueBoundary(
            Map<String, Object> evidence,
            UUID attemptId,
            UUID sourceRequestId
    ) {
        assertThat(evidence.get("freshObservationEnqueueBoundary")).isInstanceOf(Map.class);
        Map<String, Object> boundary = ((Map<?, ?>) evidence.get("freshObservationEnqueueBoundary"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(boundary)
                .containsEntry("status", "DISABLED_RELEASE_GATE")
                .containsEntry("enqueueEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimableAfterEnqueue", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        assertThat(boundary.get("plannedRequests")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plannedRequests = (List<Map<String, Object>>) boundary.get("plannedRequests");
        assertThat(plannedRequests)
                .extracting(item -> item.get("key"))
                .containsExactly("repositoryVerification", "patchDryRun");
        assertThat(boundary.get("requiredBeforeEnablement").toString())
                .contains("release gate", "non-claimable", "Never enqueue patch mutation");
    }

    private void assertObservationLinkage(
            Map<String, Object> observation,
            UUID sourceRequestId,
            UUID attemptId,
            String status
    ) {
        assertThat(observation.get("observationLinkage")).isInstanceOf(Map.class);
        Map<String, Object> linkage = ((Map<?, ?>) observation.get("observationLinkage"))
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        assertThat(linkage)
                .containsEntry("status", status)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("releaseAttemptLinked", "RELEASE_ATTEMPT_LINKED".equals(status))
                .containsEntry("sourceOnlyFallback", "SOURCE_ONLY_FALLBACK".equals(status));
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
                        "snapshotPolicy", Map.of(
                                "required", true,
                                "scope", "TARGET_FILES",
                                "location", "LOCAL_AGENT_MANAGED",
                                "createBeforeMutation", true,
                                "includeExpectedHashes", true
                        ),
                        "rollbackPolicy", Map.of(
                                "required", true,
                                "tool", "rollback.restore",
                                "restoreScope", "SNAPSHOT_TARGET_FILES",
                                "requiresUserApproval", true
                        ),
                        "staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH",
                        "sourceRepository", Map.of(
                                "name", "learnbot",
                                "branch", "main",
                                "lastIndexedCommit", "abcdef123456",
                                "gitUrl", "https://example.com/acme/learnbot.git"
                        ),
                        "workspaceVerification", Map.of(
                                "status", "UNVERIFIED",
                                "blocking", true,
                                "reason", "Repository/workspace identity has not been verified."
                        )
                ),
                LocalAgentApprovalState.REQUIRED,
                OffsetDateTime.now(),
                List.of()
        );
    }

    private Map<String, Object> patchDryRunOutput() {
        return patchDryRunOutput(false);
    }

    private Map<String, Object> patchDryRunOutput(boolean snapshotCreated) {
        return Map.of(
                "dryRun", true,
                "preflightPassed", true,
                "mutationApplied", false,
                "snapshotCreated", snapshotCreated,
                "snapshotObservation", Map.of(
                        "manifestPreview", Map.of(
                                "id", "snap-1234",
                                "version", 1,
                                "schema", "learnbot.local-agent.snapshot-manifest.v1",
                                "relativeManifestPath", "snap-1234/manifest.json",
                                "contentStrategy", "COPY_TARGET_FILES_BEFORE_MUTATION",
                                "created", snapshotCreated,
                                "writesPlanned", snapshotCreated,
                                "writesCompleted", snapshotCreated,
                                "files", List.of(Map.of(
                                        "path", "README.md",
                                        "snapshotRelativePath", "files/README.md",
                                        "actualSha256", "abc123"
                                ))
                        )
                ),
                "rollbackObservation", Map.of(
                        "restored", false,
                        "restorePreconditions", List.of(Map.of(
                                "key", "snapshotManifestExists",
                                "required", true,
                                "previewOnly", true
                        ), Map.of(
                                "key", "userApprovalRequired",
                                "required", true,
                                "previewOnly", true
                        ))
                )
        );
    }

    private Map<String, Object> patchDryRunOutputWithMutationApplied() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(patchDryRunOutput(true));
        output.put("mutationApplied", true);
        return output;
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
