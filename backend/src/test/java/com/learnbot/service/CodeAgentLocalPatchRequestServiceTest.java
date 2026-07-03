package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.dto.PatchValidationResult;
import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentLocalPatchRequestServiceTest {
    private final CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
    private final PatchValidationService validationService = mock(PatchValidationService.class);
    private final LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
    private final CodeRepository codeRepository = mock(CodeRepository.class);
    private final LocalAgentGatewayService localAgentGatewayService = mock(LocalAgentGatewayService.class);
    private final CodeAgentLocalPatchRequestService service = new CodeAgentLocalPatchRequestService(
            fileLoader,
            validationService,
            toolGatewayService,
            codeRepository,
            localAgentGatewayService
    );

    @Test
    void prepareBuildsApprovalRequiredPatchApplyEnvelopeWithoutExecutingIt() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String path = "src/App.java";
        String content = "class App {}\n";
        String diff = """
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App {}
                +class App { /* ok */ }
                """;
        when(fileLoader.normalizeRequestedPaths(org.mockito.ArgumentMatchers.eq(List.of(path)), org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(path));
        when(validationService.validate(diff, List.of(path))).thenReturn(new PatchValidationResult(true, List.of("validated")));
        when(fileLoader.load(repositoryId, List.of(path))).thenReturn(new CodePatchFileLoader.LoadResult(
                List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), path, "java", content)),
                List.of("loaded")
        ));
        when(codeRepository.findRepository(repositoryId)).thenReturn(java.util.Optional.of(new CodeRepositoryRecord(
                repositoryId,
                spaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));
        when(localAgentGatewayService.approvedWorkspace(userId, workspaceId)).thenReturn(java.util.Optional.of(
                new LocalAgentWorkspaceSummary(workspaceId, "learnbot", "C:/work/learnbot", true)
        ));
        when(toolGatewayService.createApprovalRequest(org.mockito.ArgumentMatchers.any())).thenReturn(new LocalAgentToolExecutionResponse(
                requestId,
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                Map.of(),
                Map.of(),
                null,
                null,
                List.of("validated", "loaded"),
                List.of(),
                null,
                null,
                null
        ));

        LocalAgentToolExecutionResponse response = service.prepare(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                "fix",
                diff,
                List.of(path)
        );

        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.APPROVAL_REQUIRED);
        ArgumentCaptor<LocalAgentToolRequest> captor = ArgumentCaptor.forClass(LocalAgentToolRequest.class);
        verify(toolGatewayService).createApprovalRequest(captor.capture());
        LocalAgentToolRequest request = captor.getValue();
        assertThat(request.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(request.approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(request.input()).containsEntry("schemaVersion", 1);
        assertThat(request.input()).containsEntry("repositoryId", repositoryId.toString());
        assertThat(request.input()).containsEntry("spaceId", spaceId.toString());
        assertThat(request.input()).containsEntry("loopId", loopId.toString());
        assertThat(request.input()).containsEntry("approvalPersistenceRequired", true)
                .containsEntry("approvalPersisted", true);
        assertThat(request.input().get("approvalRequestId")).asString().startsWith("apr-");
        assertThat(request.input()).containsEntry("requiresSnapshot", true);
        assertThat(request.input()).containsEntry("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH");
        assertThat(request.input().get("snapshotPolicy").toString()).contains("TARGET_FILES", "LOCAL_AGENT_MANAGED", "createBeforeMutation=true");
        assertThat(request.input().get("rollbackPolicy").toString()).contains("rollback.restore", "SNAPSHOT_TARGET_FILES", "requiresUserApproval=true");
        assertThat(request.input().get("sourceRepository").toString()).contains("learnbot", "abc123", "main");
        assertThat(request.input().get("localWorkspace").toString()).contains(workspaceId.toString(), "C:/work/learnbot");
        assertThat(request.input().get("workspaceVerification").toString()).contains("UNVERIFIED", "blocking=true");
        assertThat(request.input().get("expectedFiles").toString()).contains(path, sha256(content));
        assertThat(request.warnings()).anyMatch(warning -> warning.contains("identity is not verified"));
    }

    @Test
    void prepareUsesLocalAgentFileReadExpectedFilesWhenTargetIsNotIndexed() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String path = "readme.txt";
        String content = "리드미 나를 읽어";
        String diff = """
                --- a/readme.txt
                +++ b/readme.txt
                @@ -1,1 +1,4 @@
                 리드미 나를 읽어
                +작은 빛이 머문 자리
                +한 줄의 바람이 쉬어 가고
                +오늘의 마음이 조용히 빛난다
                """;
        when(fileLoader.normalizeRequestedPaths(org.mockito.ArgumentMatchers.eq(List.of(path)), org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(path));
        when(fileLoader.rejectionReason(path)).thenReturn(null);
        when(validationService.validate(diff, List.of(path))).thenReturn(new PatchValidationResult(true, List.of("validated")));
        when(codeRepository.findRepository(repositoryId)).thenReturn(java.util.Optional.of(new CodeRepositoryRecord(
                repositoryId,
                spaceId,
                "local-test",
                "LOCAL",
                "C:/Users/honeybadger/Desktop/test",
                null,
                null,
                "NONE",
                "NONE",
                "C:/Users/honeybadger/Desktop/test",
                "LOCAL",
                null
        )));
        when(localAgentGatewayService.approvedWorkspace(userId, workspaceId)).thenReturn(java.util.Optional.of(
                new LocalAgentWorkspaceSummary(workspaceId, "test", "C:/Users/honeybadger/Desktop/test", true)
        ));
        when(toolGatewayService.createApprovalRequest(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            LocalAgentToolRequest request = invocation.getArgument(0);
            return new LocalAgentToolExecutionResponse(
                    requestId,
                    UUID.randomUUID(),
                    userId,
                    agentId,
                    workspaceId,
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
                    null,
                    null,
                    null
            );
        });

        LocalAgentToolExecutionResponse response = service.prepare(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                "README파일 끝에 짧은 시를 추가해줘",
                diff,
                List.of(path),
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "text", content))
        );

        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.APPROVAL_REQUIRED);
        ArgumentCaptor<LocalAgentToolRequest> captor = ArgumentCaptor.forClass(LocalAgentToolRequest.class);
        verify(toolGatewayService).createApprovalRequest(captor.capture());
        LocalAgentToolRequest request = captor.getValue();
        assertThat(request.input()).containsEntry("expectedFileSource", "local-agent-file-read");
        assertThat(request.input().get("expectedFiles").toString()).contains(path, sha256(content));
        assertThat(request.warnings()).contains("Expected file hashes came from completed Local Agent file.read observations.");
        verify(fileLoader, never()).load(repositoryId, List.of(path));
    }

    @Test
    void previewValidatedDryRunRequestModelsWouldBeQueueRowWithoutPersistingIt() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        String path = "src/App.java";
        String content = "class App {}\n";
        String diff = """
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App {}
                +class App { /* ok */ }
                """;
        Map<String, Object> handoff = Map.of(
                "schema", "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
                "status", "READY_DRY_RUN_QUEUE_DISABLED",
                "patchApplyInput", Map.of(
                        "diff", diff,
                        "targetFiles", List.of(path),
                        "dryRunOnly", true,
                        "mutationAllowed", false,
                        "sourceRequestId", "source-request-1"
                )
        );
        when(fileLoader.normalizeRequestedPaths(org.mockito.ArgumentMatchers.eq(List.of(path)), org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(path));
        when(validationService.validate(diff, List.of(path))).thenReturn(new PatchValidationResult(true, List.of("validated")));
        when(fileLoader.load(repositoryId, List.of(path))).thenReturn(new CodePatchFileLoader.LoadResult(
                List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), path, "java", content)),
                List.of("loaded")
        ));
        when(codeRepository.findRepository(repositoryId)).thenReturn(java.util.Optional.of(new CodeRepositoryRecord(
                repositoryId,
                spaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));
        when(localAgentGatewayService.approvedWorkspace(userId, workspaceId)).thenReturn(java.util.Optional.of(
                new LocalAgentWorkspaceSummary(workspaceId, "learnbot", "C:/work/learnbot", true)
        ));

        Map<String, Object> preview = service.previewValidatedDryRunRequest(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                handoff
        );

        assertThat(preview).containsEntry("schema", "learnbot.server.validated-revised-patch-dry-run-preview.v1")
                .containsEntry("status", "READY_QUEUE_PREVIEW_DISABLED")
                .containsEntry("ready", true)
                .containsEntry("queueEnabled", false)
                .containsEntry("requestPersisted", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        assertThat(preview.get("blockers")).asList().isEmpty();
        assertThat(preview.get("warnings")).asList().contains("validated", "loaded");
        assertThat(preview.get("approvalPrerequisites").toString())
                .contains("explicitUserApprovalRequiredBeforeMutation=true", "freshDryRunRequired=true", "releaseGateEnabled=false");
        @SuppressWarnings("unchecked")
        Map<String, Object> wouldBeRequest = (Map<String, Object>) preview.get("wouldBeRequest");
        assertThat(wouldBeRequest).containsEntry("requestPersisted", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("toolName", "patch.apply")
                .containsEntry("status", "APPROVAL_REQUIRED_PREVIEW")
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        assertThat(wouldBeRequest.get("input").toString())
                .contains("source-request-1", path, sha256(content), "READY_DRY_RUN_QUEUE_DISABLED", "consumed=true");
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void previewValidatedDryRunRequestSurfacesBlockersForUnsafeHandoff() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> handoff = Map.of(
                "schema", "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
                "status", "BLOCKED_OUTPUT_NOT_READY",
                "patchApplyInput", Map.of(
                        "diff", "",
                        "targetFiles", List.of(),
                        "dryRunOnly", false,
                        "mutationAllowed", true
                )
        );
        when(codeRepository.findRepository(repositoryId)).thenReturn(java.util.Optional.of(new CodeRepositoryRecord(
                repositoryId,
                spaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));

        Map<String, Object> preview = service.previewValidatedDryRunRequest(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                null,
                handoff
        );

        assertThat(preview).containsEntry("status", "BLOCKED")
                .containsEntry("ready", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("requestPersisted", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(preview.get("blockers").toString())
                .contains("not ready", "dryRunOnly=true", "mutationAllowed=false", "non-empty unified diff", "targetFiles");
        assertThat(preview.get("wouldBeRequest").toString())
                .contains("APPROVAL_REQUIRED_PREVIEW", "claimable=false", "mutationAllowed=false");
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistValidatedDryRunIntentCreatesApprovalRequiredNonClaimableDryRunRequest() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String path = "src/App.java";
        String content = "class App {}\n";
        String diff = """
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App {}
                +class App { /* ok */ }
                """;
        Map<String, Object> handoff = Map.of(
                "schema", "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
                "status", "READY_DRY_RUN_QUEUE_DISABLED",
                "patchApplyInput", Map.of(
                        "diff", diff,
                        "targetFiles", List.of(path),
                        "dryRunOnly", true,
                        "mutationAllowed", false,
                        "sourceRequestId", "source-request-1"
                )
        );
        when(fileLoader.normalizeRequestedPaths(org.mockito.ArgumentMatchers.eq(List.of(path)), org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(path));
        when(validationService.validate(diff, List.of(path))).thenReturn(new PatchValidationResult(true, List.of("validated")));
        when(fileLoader.load(repositoryId, List.of(path))).thenReturn(new CodePatchFileLoader.LoadResult(
                List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), path, "java", content)),
                List.of("loaded")
        ));
        when(codeRepository.findRepository(repositoryId)).thenReturn(java.util.Optional.of(new CodeRepositoryRecord(
                repositoryId,
                spaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));
        when(localAgentGatewayService.approvedWorkspace(userId, workspaceId)).thenReturn(java.util.Optional.of(
                new LocalAgentWorkspaceSummary(workspaceId, "learnbot", "C:/work/learnbot", true)
        ));
        when(toolGatewayService.createApprovalRequest(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            LocalAgentToolRequest request = invocation.getArgument(0);
            return new LocalAgentToolExecutionResponse(
                    requestId,
                    sessionId,
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
                    null,
                    null,
                    null
            );
        });

        Map<String, Object> intent = service.persistValidatedDryRunIntent(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                handoff
        );

        assertThat(intent).containsEntry("schema", "learnbot.server.validated-revised-patch-dry-run-intent.v1")
                .containsEntry("status", "PERSISTED_APPROVAL_REQUIRED_NON_CLAIMABLE")
                .containsEntry("intentPersisted", true)
                .containsEntry("requestPersisted", true)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        ArgumentCaptor<LocalAgentToolRequest> captor = ArgumentCaptor.forClass(LocalAgentToolRequest.class);
        verify(toolGatewayService).createApprovalRequest(captor.capture());
        LocalAgentToolRequest request = captor.getValue();
        assertThat(request.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(request.executionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(request.approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(request.input()).containsEntry("validatedDryRunIntent", true)
                .containsEntry("dryRunIntentPersisted", true)
                .containsEntry("approvalPersistenceRequired", true)
                .containsEntry("approvalPersisted", true)
                .containsEntry("requestPersisted", true)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        assertThat(request.input().get("approvalRequestId")).asString().startsWith("apr-");
        assertThat(request.input().get("expectedFiles").toString()).contains(path, sha256(content));
        assertThat(intent.get("persistedRequest").toString())
                .contains(requestId.toString(), sessionId.toString(), "APPROVAL_REQUIRED", "claimable=false", "mutationAllowed=false");
    }

    @Test
    void persistValidatedDryRunIntentRefusesBlockedPreviewWithoutPersisting() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> handoff = Map.of(
                "schema", "learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1",
                "status", "BLOCKED_OUTPUT_NOT_READY",
                "patchApplyInput", Map.of(
                        "diff", "",
                        "targetFiles", List.of(),
                        "dryRunOnly", false,
                        "mutationAllowed", true
                )
        );
        when(codeRepository.findRepository(repositoryId)).thenReturn(java.util.Optional.of(new CodeRepositoryRecord(
                repositoryId,
                spaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));

        Map<String, Object> intent = service.persistValidatedDryRunIntent(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                null,
                handoff
        );

        assertThat(intent).containsEntry("schema", "learnbot.server.validated-revised-patch-dry-run-intent.v1")
                .containsEntry("status", "BLOCKED_PREVIEW_NOT_READY")
                .containsEntry("intentPersisted", false)
                .containsEntry("requestPersisted", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(intent.get("blockers").toString()).contains("not ready", "dryRunOnly=true", "mutationAllowed=false");
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void inspectValidatedDryRunIntentEligibilityReportsReadyButReleaseDisabled() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> input = Map.of(
                "validatedDryRunIntent", true,
                "dryRunIntentPersisted", true,
                "dryRunOnly", true,
                "mutationAllowed", false,
                "diff", "--- a/src/App.java\n+++ b/src/App.java\n",
                "targetFiles", List.of("src/App.java")
        );
        when(toolGatewayService.findForUser(userId, requestId)).thenReturn(Optional.of(new LocalAgentToolExecutionResponse(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                input,
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        )));

        Map<String, Object> eligibility = service.inspectValidatedDryRunIntentEligibility(userId, requestId);

        assertThat(eligibility).containsEntry("schema", "learnbot.server.validated-revised-patch-dry-run-eligibility.v1")
                .containsEntry("status", "READY_DRY_RUN_RELEASE_DISABLED")
                .containsEntry("requestId", requestId.toString())
                .containsEntry("validatedDryRunIntent", true)
                .containsEntry("dryRunIntentPersisted", true)
                .containsEntry("requestPersisted", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("approvalBypassAllowed", false)
                .containsEntry("prerequisitesPassed", true);
        assertThat(eligibility.get("blockingKeys")).asList().isEmpty();
        assertThat(eligibility.get("futureDryRunReleaseGate").toString())
                .contains("READY_RELEASE_DISABLED", "requestCreationEnabled=false", "claimable=false", "mutationAllowed=false");
        assertThat(eligibility.get("checks").toString())
                .contains("patchApplyTool", "validatedDryRunIntent", "dryRunOnly", "mutationDisabled");
    }

    @Test
    void inspectValidatedDryRunIntentEligibilityBlocksUnsafePersistedRequestWithoutRelease() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> input = Map.of(
                "validatedDryRunIntent", false,
                "dryRunIntentPersisted", false,
                "dryRunOnly", false,
                "mutationAllowed", true,
                "diff", "",
                "targetFiles", List.of()
        );
        when(toolGatewayService.findForUser(userId, requestId)).thenReturn(Optional.of(new LocalAgentToolExecutionResponse(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                input,
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        )));

        Map<String, Object> eligibility = service.inspectValidatedDryRunIntentEligibility(userId, requestId);

        assertThat(eligibility).containsEntry("status", "BLOCKED_DRY_RUN_RELEASE_DISABLED")
                .containsEntry("prerequisitesPassed", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false);
        assertThat(eligibility.get("blockingKeys").toString())
                .contains("validatedDryRunIntent", "intentPersisted", "dryRunOnly", "mutationDisabled", "targetFilesPresent", "diffPresent");
        assertThat(eligibility.get("futureDryRunReleaseGate").toString())
                .contains("BLOCKED_RELEASE_DISABLED", "claimable=false", "approvalBypassAllowed=false");
    }

    @Test
    void previewValidatedDryRunIntentClaimableDryRunModelsFutureRequestWithoutCreatingIt() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        String path = "src/App.java";
        Map<String, Object> input = Map.ofEntries(
                Map.entry("repositoryId", repositoryId.toString()),
                Map.entry("spaceId", UUID.randomUUID().toString()),
                Map.entry("loopId", UUID.randomUUID().toString()),
                Map.entry("sourceRepository", Map.of("id", repositoryId.toString(), "name", "learnbot")),
                Map.entry("localWorkspace", Map.of("workspaceId", workspaceId.toString(), "approved", true)),
                Map.entry("expectedFiles", List.of(Map.of("path", path, "sha256", "abc123", "bytes", 12))),
                Map.entry("sourceRequestId", "source-request-1"),
                Map.entry("validatedDryRunIntent", true),
                Map.entry("dryRunIntentPersisted", true),
                Map.entry("dryRunOnly", true),
                Map.entry("mutationAllowed", false),
                Map.entry("diff", "--- a/src/App.java\n+++ b/src/App.java\n"),
                Map.entry("targetFiles", List.of(path))
        );
        when(toolGatewayService.findForUser(userId, requestId)).thenReturn(Optional.of(new LocalAgentToolExecutionResponse(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                input,
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        )));

        Map<String, Object> preview = service.previewValidatedDryRunIntentClaimableDryRun(userId, requestId);

        assertThat(preview).containsEntry("schema", "learnbot.server.validated-revised-patch-dry-run-transition-preview.v1")
                .containsEntry("status", "READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED")
                .containsEntry("sourceIntentRequestId", requestId.toString())
                .containsEntry("sourceIntentSessionId", sessionId.toString())
                .containsEntry("prerequisitesPassed", true)
                .containsEntry("requestPersisted", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(preview.get("blockingKeys")).asList().isEmpty();
        assertThat(preview.get("eligibility").toString())
                .contains("READY_DRY_RUN_RELEASE_DISABLED", "prerequisitesPassed=true");
        assertThat(preview.get("transitionGate").toString())
                .contains("READY_TRANSITION_DISABLED", "requestCreationEnabled=false", "claimable=false", "approvalBypassAllowed=false");
        assertThat(preview.get("wouldBeClaimableDryRunRequest").toString())
                .contains(
                        "learnbot.server.validated-revised-patch-claimable-dry-run-request-preview.v1",
                        "READY_REQUEST_CREATION_DISABLED",
                        "requestPersisted=false",
                        "claimable=false",
                        "dryRunOnly=true",
                        "mutationAllowed=false",
                        "sourceIntentRequestId=" + requestId,
                        path,
                        "abc123"
                );
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void previewValidatedDryRunIntentClaimableDryRunBlocksUnsafeIntentWithoutCreatingRequest() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> input = Map.of(
                "validatedDryRunIntent", false,
                "dryRunIntentPersisted", false,
                "dryRunOnly", false,
                "mutationAllowed", true,
                "diff", "",
                "targetFiles", List.of()
        );
        when(toolGatewayService.findForUser(userId, requestId)).thenReturn(Optional.of(new LocalAgentToolExecutionResponse(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                input,
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        )));

        Map<String, Object> preview = service.previewValidatedDryRunIntentClaimableDryRun(userId, requestId);

        assertThat(preview).containsEntry("status", "BLOCKED_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED")
                .containsEntry("prerequisitesPassed", false)
                .containsEntry("requestPersisted", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(preview.get("blockingKeys").toString())
                .contains("validatedDryRunIntent", "intentPersisted", "dryRunOnly", "mutationDisabled", "targetFilesPresent", "diffPresent");
        assertThat(preview.get("transitionGate").toString())
                .contains("BLOCKED_TRANSITION_DISABLED", "claimable=false", "approvalBypassAllowed=false");
        assertThat(preview.get("wouldBeClaimableDryRunRequest").toString())
                .contains("BLOCKED_PREREQUISITES", "requestCreationEnabled=false", "claimable=false", "mutationAllowed=false");
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void releaseValidatedDryRunIntentClaimableDryRunRefusesCreationWhileGateDisabled() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        String path = "src/App.java";
        Map<String, Object> input = Map.ofEntries(
                Map.entry("repositoryId", repositoryId.toString()),
                Map.entry("sourceRepository", Map.of("id", repositoryId.toString(), "name", "learnbot")),
                Map.entry("localWorkspace", Map.of("workspaceId", workspaceId.toString(), "approved", true)),
                Map.entry("expectedFiles", List.of(Map.of("path", path, "sha256", "abc123", "bytes", 12))),
                Map.entry("sourceRequestId", "source-request-1"),
                Map.entry("validatedDryRunIntent", true),
                Map.entry("dryRunIntentPersisted", true),
                Map.entry("dryRunOnly", true),
                Map.entry("mutationAllowed", false),
                Map.entry("diff", "--- a/src/App.java\n+++ b/src/App.java\n"),
                Map.entry("targetFiles", List.of(path))
        );
        when(toolGatewayService.findForUser(userId, requestId)).thenReturn(Optional.of(new LocalAgentToolExecutionResponse(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                input,
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        )));

        Map<String, Object> release = service.releaseValidatedDryRunIntentClaimableDryRun(userId, requestId);

        assertThat(release)
                .containsEntry("schema", "learnbot.server.validated-revised-patch-claimable-dry-run-release.v1")
                .containsEntry("status", "REFUSED_CLAIMABLE_DRY_RUN_CREATION_DISABLED")
                .containsEntry("sourceIntentRequestId", requestId.toString())
                .containsEntry("prerequisitesPassed", true)
                .containsEntry("requestPersisted", false)
                .containsEntry("requestCreated", false)
                .containsEntry("queued", false)
                .containsEntry("pushed", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("queueEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("approvalBypassAllowed", false);
        assertThat(release.get("releaseGate").toString())
                .contains("learnbot.server.validated-revised-patch-claimable-dry-run-release-gate.v1",
                        "REFUSED_REQUEST_CREATION_DISABLED",
                        "requestCreationEnabled=false",
                        "claimable=false",
                        "mutationAllowed=false",
                        "approvalBypassAllowed=false");
        assertThat(release.get("blockingKeys").toString()).contains("requestCreationEnabled");
        assertThat(release.get("transitionPreview").toString()).contains("READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED");
        assertThat(release.get("wouldBeClaimableDryRunRequest").toString()).contains(path, "dryRunOnly=true", "mutationAllowed=false");
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void releaseValidatedDryRunIntentClaimableDryRunBlocksUnsafeIntentWithoutCreatingRequest() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> input = Map.of(
                "validatedDryRunIntent", false,
                "dryRunIntentPersisted", false,
                "dryRunOnly", false,
                "mutationAllowed", true,
                "diff", "",
                "targetFiles", List.of()
        );
        when(toolGatewayService.findForUser(userId, requestId)).thenReturn(Optional.of(new LocalAgentToolExecutionResponse(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                input,
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        )));

        Map<String, Object> release = service.releaseValidatedDryRunIntentClaimableDryRun(userId, requestId);

        assertThat(release)
                .containsEntry("status", "BLOCKED_CLAIMABLE_DRY_RUN_PREREQUISITES")
                .containsEntry("prerequisitesPassed", false)
                .containsEntry("requestCreated", false)
                .containsEntry("queued", false)
                .containsEntry("pushed", false)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);
        assertThat(release.get("releaseGate").toString())
                .contains("BLOCKED_PREREQUISITES", "requestCreationEnabled=false", "claimable=false");
        assertThat(release.get("blockingKeys").toString())
                .contains("validatedDryRunIntent", "intentPersisted", "dryRunOnly", "mutationDisabled", "targetFilesPresent", "diffPresent", "requestCreationEnabled");
        verify(toolGatewayService, never()).createApprovalRequest(org.mockito.ArgumentMatchers.any());
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
