package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
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
    private final LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
    private final LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
    private final LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
    private final LocalAgentToolGatewayService service = new LocalAgentToolGatewayService(repository, releaseAttemptRepository, gatewayService, toolPusher);

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
        assertMutationResultIntakeBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "RESTORE_VALIDATED");
        assertMutationResultAggregationPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
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
        assertLocalAgentMutationExecutionSequencePlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertPostMutationResultContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertMutationDispatchEnvelopeContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "READY_DISPATCH_DISABLED", true);
        assertMutationDispatchPreflightBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "READY_PREFLIGHT_DISABLED", true);
        assertMutationDispatchDecisionModel(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_DISPATCH_DISABLED", true);
        assertMutationRequestBlueprint(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_REQUEST_CREATION_DISABLED", true);
        assertMutationRequestCreationGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_CREATION_DISABLED", true, 4);
        assertMutationRequestPushGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_PUSH_DISABLED", true, 4);
        assertMutationRequestClaimGate(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "REFUSED_CLAIM_DISABLED", true, 4);
        assertMutationResultIntakeBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "RESTORE_VALIDATED");
        assertMutationResultAggregationPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
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
                "READY_COMPLETION_DISABLED",
                true
        );
        assertThat(readiness.patchExecutionGate())
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("writeHelperEnabled", false)
                .containsEntry("mutationEnabled", false);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
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
        assertMutationResultIntakeBoundary(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
        assertFinalMutationReportContract(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId, "MISSING");
        assertMutationResultAggregationPlan(readiness.releaseAttemptModel().latestAttempt(), attemptId, requestId);
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
                "BLOCKED_COMPLETION_DISABLED",
                false,
                "releaseAttemptReadiness",
                "finalMutationReportFinalizationBoundary",
                "finalAnswerPublicationBoundary",
                "releaseEnablementChecklist",
                "mutationDispatchEnvelopeContract",
                "mutationDispatchPreflightBoundary",
                "mutationDispatchDecisionModel",
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
    void releaseHeldPatchForExecutionCreatesDisabledAttemptAndRefusesWhileReleaseFlagIsDisabled() {
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
                .hasMessageContaining("release is disabled");

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
                .contains("release gate is disabled", "held patch request remains non-claimable", "Local Agent request creation and push remain disabled");
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
        assertThat(contract)
                .containsEntry("schema", "learnbot.local-agent.final-mutation-report.v1")
                .containsEntry("status", "CONTRACT_DISABLED")
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
                .containsEntry("postMutationResultSchema", "learnbot.local-agent.post-mutation-result.v1")
                .containsEntry("rollbackReadinessStatus", rollbackReadinessStatus)
                .containsEntry("releaseGateEnabled", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false)
                .containsEntry("applyEnabled", false)
                .containsEntry("testEnabled", false)
                .containsEntry("rollbackRestoreEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false);
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
        assertThat(boundary)
                .containsEntry("schema", "learnbot.local-agent.finalization-boundary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
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
            String status,
            boolean prerequisitesPassed,
            String... expectedBlockingKeys
    ) {
        assertThat(latestAttempt.get("mutationCompletionSummary")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) latestAttempt.get("mutationCompletionSummary");
        assertThat(summary)
                .containsEntry("schema", "learnbot.local-agent.mutation-completion-summary.v1")
                .containsEntry("status", status)
                .containsEntry("prerequisitesPassed", prerequisitesPassed)
                .containsEntry("blocking", true)
                .containsEntry("releaseAttemptId", attemptId)
                .containsEntry("sourceRequestId", sourceRequestId)
                .containsEntry("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name())
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
        assertThat(summary.get("items")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) summary.get("items");
        assertThat(items)
                .extracting(item -> item.get("key"))
                .containsExactly(
                        "releaseAttemptReadiness",
                        "mutationExecutionSequencePlan",
                        "mutationResultIntakeBoundary",
                        "mutationResultAggregationPlan",
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
                .containsEntry("finalAnswerGenerationEnabled", false));
        assertThat(summary.get("blockingKeys")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = (List<String>) summary.get("blockingKeys");
        assertThat(blockingKeys).containsExactly(expectedBlockingKeys);
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
