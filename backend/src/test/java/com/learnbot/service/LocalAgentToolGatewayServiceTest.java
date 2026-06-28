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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "rollback.restore"),
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "rollback.restore"),
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
                OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now(),
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
                .containsEntry("claimable", false);
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
                List.of("file.read", "git.status", "git.diff", "patch.apply", "rollback.restore"),
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
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> evidenceCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> reasonsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(releaseAttemptRepository).createDisabled(
                any(UUID.class),
                sourceCaptor.capture(),
                eq(120),
                evidenceCaptor.capture(),
                reasonsCaptor.capture()
        );
        assertThat(sourceCaptor.getValue().id()).isEqualTo(requestId);
        assertThat(evidenceCaptor.getValue())
                .containsEntry("sourceRequestId", requestId)
                .containsEntry("claimable", false);
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
