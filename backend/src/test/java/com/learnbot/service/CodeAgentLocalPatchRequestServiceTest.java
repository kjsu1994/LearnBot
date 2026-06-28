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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
