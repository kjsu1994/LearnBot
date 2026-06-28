package com.learnbot.service;

import com.learnbot.dto.PatchApplySnapshot;
import com.learnbot.repository.CodeAgentPatchSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeAgentApplyServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void applyRevalidatesAndWritesPatchWithRollbackSnapshot() throws Exception {
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        CodeAgentPatchSessionRepository sessionRepository = mock(CodeAgentPatchSessionRepository.class);
        CodeAgentApplyService service = new CodeAgentApplyService(fileLoader, new PatchValidationService(fileLoader), sessionRepository);
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Path file = tempDir.resolve("AuthService.java");
        Files.writeString(file, "class AuthService {\n  boolean login() { return false; }\n}\n");
        String path = "backend/src/main/java/AuthService.java";
        String diff = """
                --- a/backend/src/main/java/AuthService.java
                +++ b/backend/src/main/java/AuthService.java
                @@ -1,3 +1,3 @@
                 class AuthService {
                -  boolean login() { return false; }
                +  boolean login() { return true; }
                 }
                """;

        when(fileLoader.normalizeRequestedPaths(eq(List.of(path)), anyList())).thenReturn(List.of(path));
        when(fileLoader.isSensitiveOrUnsafe(any())).thenReturn(false);
        when(fileLoader.localTarget(repositoryId, path)).thenAnswer(invocation ->
                new CodePatchFileLoader.LocalTargetFile(path, file, Files.readString(file)));
        when(sessionRepository.createApplied(eq(repositoryId), eq(spaceId), eq(userId), any(), eq(diff), eq(List.of(path)), anyList(), any(), anyList()))
                .thenReturn(new CodeAgentPatchSession(sessionId, repositoryId, spaceId, userId, "fix", diff, List.of(path), List.of(), Map.of(path, "after"), "APPLIED", List.of(), List.of()));

        var response = service.apply(repositoryId, spaceId, userId, "fix", diff, List.of(path));

        assertThat(response.applied()).isTrue();
        assertThat(response.patchSessionId()).isEqualTo(sessionId);
        assertThat(Files.readString(file)).contains("return true");
    }

    @Test
    void applyRejectsContextMismatchWithoutWriting() throws Exception {
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        CodeAgentPatchSessionRepository sessionRepository = mock(CodeAgentPatchSessionRepository.class);
        CodeAgentApplyService service = new CodeAgentApplyService(fileLoader, new PatchValidationService(fileLoader), sessionRepository);
        UUID repositoryId = UUID.randomUUID();
        String path = "backend/src/main/java/AuthService.java";
        Path file = tempDir.resolve("AuthService.java");
        Files.writeString(file, "class AuthService {\n  boolean login() { return true; }\n}\n");
        String diff = """
                --- a/backend/src/main/java/AuthService.java
                +++ b/backend/src/main/java/AuthService.java
                @@ -1,3 +1,3 @@
                 class AuthService {
                -  boolean login() { return false; }
                +  boolean login() { return true; }
                 }
                """;

        when(fileLoader.normalizeRequestedPaths(eq(List.of(path)), anyList())).thenReturn(List.of(path));
        when(fileLoader.isSensitiveOrUnsafe(any())).thenReturn(false);
        when(fileLoader.localTarget(repositoryId, path)).thenReturn(new CodePatchFileLoader.LocalTargetFile(path, file, Files.readString(file)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.apply(repositoryId, UUID.randomUUID(), UUID.randomUUID(), "fix", diff, List.of(path)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context mismatch");
        assertThat(Files.readString(file)).contains("return true");
    }

    @Test
    void rollbackRestoresOnlyWhenCurrentHashMatchesAppliedSnapshot() throws Exception {
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        CodeAgentPatchSessionRepository sessionRepository = mock(CodeAgentPatchSessionRepository.class);
        CodeAgentApplyService service = new CodeAgentApplyService(fileLoader, new PatchValidationService(fileLoader), sessionRepository);
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String path = "backend/src/main/java/AuthService.java";
        Path file = tempDir.resolve("AuthService.java");
        String before = "class AuthService {\n  boolean login() { return false; }\n}\n";
        String after = "class AuthService {\n  boolean login() { return true; }\n}\n";
        Files.writeString(file, after);
        PatchApplySnapshot snapshot = new PatchApplySnapshot(path, sha256(before), sha256(after), before);
        when(sessionRepository.find(sessionId)).thenReturn(Optional.of(new CodeAgentPatchSession(
                sessionId, repositoryId, spaceId, userId, "fix", "diff", List.of(path), List.of(snapshot),
                Map.of(path, sha256(after)), "APPLIED", List.of(), List.of()
        )));
        when(fileLoader.localTarget(repositoryId, path)).thenAnswer(invocation ->
                new CodePatchFileLoader.LocalTargetFile(path, file, Files.readString(file)));

        var response = service.rollback(repositoryId, spaceId, userId, sessionId);

        assertThat(response.rolledBack()).isTrue();
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void nonAllowlistedTestCommandIsRejected() {
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        CodeAgentPatchSessionRepository sessionRepository = mock(CodeAgentPatchSessionRepository.class);
        CodeAgentApplyService service = new CodeAgentApplyService(fileLoader, new PatchValidationService(fileLoader), sessionRepository);
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.find(sessionId)).thenReturn(Optional.of(new CodeAgentPatchSession(
                sessionId, repositoryId, spaceId, userId, "fix", "diff", List.of(), List.of(), Map.of(), "APPLIED", List.of(), List.of()
        )));

        var response = service.runAllowedTest(repositoryId, spaceId, userId, sessionId, "rm-rf");

        assertThat(response.allowed()).isFalse();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("not allowlisted"));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
