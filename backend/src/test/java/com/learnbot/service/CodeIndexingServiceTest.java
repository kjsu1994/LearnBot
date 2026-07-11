package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIndexingServiceTest {

    @Test
    void localSourceNeverInvokesGitSynchronization() {
        CodeRepositoryRecord local = new CodeRepositoryRecord(
                UUID.randomUUID(), UUID.randomUUID(), "local", "LOCAL", "/host/local/project", null,
                "git@github.com:org/project.git", "HEAD", "NONE", "/host/local/project", "LOCAL_READY", "abc123"
        );

        assertThat(CodeIndexingService.sourceRevision(local, () -> {
            throw new AssertionError("LOCAL indexing must not synchronize Git");
        })).isEqualTo("abc123");
    }

    @Test
    void gitSourceStillInvokesGitSynchronization() {
        CodeRepositoryRecord git = new CodeRepositoryRecord(
                UUID.randomUUID(), UUID.randomUUID(), "git", "GIT", "https://example.test/project.git", null,
                "https://example.test/project.git", "main", "NONE", "/repos/project", "PENDING", null
        );

        assertThat(CodeIndexingService.sourceRevision(git, () -> "new-commit"))
                .isEqualTo("new-commit");
    }

    @Test
    void mapsConfiguredWindowsDrivePathToConfiguredDockerMount() {
        assertThat(CodeIndexingService.dockerLocalPath(
                "D:\\Users\\developer\\Project", "D", "/mnt/source"))
                .isEqualTo("/mnt/source/Users/developer/Project");
    }

    @Test
    void preservesContainerLocalPathForLocalAgentCompatibility() {
        assertThat(CodeIndexingService.dockerLocalPath(
                "/workspace/Project", "C", "/host/local"))
                .isEqualTo("/workspace/Project");
    }

    @Test
    void contentHashMatchDoesNotReuseLegacyParserChunks() {
        ActiveCodeFileSnapshot legacy = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "same-hash",
                12,
                "legacy",
                "legacy"
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(legacy, "same-hash")).isFalse();
    }

    @Test
    void contentHashAndCurrentParserSignatureReusePreviousChunks() {
        ActiveCodeFileSnapshot current = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "same-hash",
                12,
                CodeIndexingService.CODE_PARSER_SIGNATURE,
                CodeIndexingService.CODE_CHUNK_PROFILE
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(current, "same-hash")).isTrue();
    }

    @Test
    void contentHashMatchDoesNotReusePreviousParserVersionChunks() {
        ActiveCodeFileSnapshot previousParser = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "same-hash",
                12,
                "code-symbol-v4",
                CodeIndexingService.CODE_CHUNK_PROFILE
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(previousParser, "same-hash")).isFalse();
    }

    @Test
    void currentParserSignatureDoesNotReuseWhenContentChanged() {
        ActiveCodeFileSnapshot current = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "old-hash",
                12,
                CodeIndexingService.CODE_PARSER_SIGNATURE,
                CodeIndexingService.CODE_CHUNK_PROFILE
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(current, "new-hash")).isFalse();
    }
}
