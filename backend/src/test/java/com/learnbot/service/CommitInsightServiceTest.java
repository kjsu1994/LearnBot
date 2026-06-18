package com.learnbot.service;

import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeChunkSummary;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.repository.CodeRepository;
import com.learnbot.repository.SecurityRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommitInsightServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsRecentChangeQuestionsWithoutCommitKeyword() {
        CommitInsightService service = new CommitInsightService(mock(CodeRepository.class), mock(OllamaClient.class));

        assertThat(service.isCommitQuestion("최근 변경내용 설명해줘")).isTrue();
        assertThat(service.isCommitQuestion("최신 바뀐 내용 알려줘")).isTrue();
        assertThat(service.isCommitQuestion("abc1234에서 바뀐 내용 설명해줘")).isTrue();
        assertThat(service.isCommitQuestion("로그인 로직 설명해줘")).isFalse();
    }

    @Test
    void answersLatestIndexedCommitFromLocalGitWhenModelFails() throws Exception {
        Path source = tempDir.resolve("src").resolve("App.java");
        Files.createDirectories(source.getParent());
        UUID repositoryId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        RevCommit second;

        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.writeString(source, "class App {\n}\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Initial app")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();

            Files.writeString(source, "class App {\n    String greeting() { return \"hello\"; }\n}\n");
            git.add().addFilepattern(".").call();
            second = git.commit()
                    .setMessage("Add greeting")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
        }

        CodeRepository repository = mock(CodeRepository.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CommitInsightService service = new CommitInsightService(repository, ollamaClient);
        CodeRepositoryRecord record = new CodeRepositoryRecord(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                "sample",
                "GIT",
                "https://example.com/sample.git",
                null,
                "https://example.com/sample.git",
                "main",
                "NONE",
                tempDir.toString(),
                "INDEXED",
                second.getName()
        );

        when(repository.findRepository(repositoryId)).thenReturn(Optional.of(record));
        when(repository.findActiveFileIdsByPath(eq(repositoryId), anyList())).thenReturn(Map.of("src/App.java", fileId));
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.answer(repositoryId, "최근 변경내용 설명해줘");

        assertThat(response.mode()).isEqualTo("commit");
        assertThat(response.answer()).contains("Add greeting");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().get(0).fileId()).isEqualTo(fileId);
        assertThat(response.evidence().get(0).metadata()).containsEntry("kind", "commit_diff");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("최신 인덱싱 커밋"));
    }

    @Test
    void fallsBackWhenCommitAnswerEndsIncomplete() throws Exception {
        Path source = tempDir.resolve("src").resolve("App.java");
        Files.createDirectories(source.getParent());
        UUID repositoryId = UUID.randomUUID();
        RevCommit second;

        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Files.writeString(source, "class App {\n}\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Initial app")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();

            Files.writeString(source, "class App {\n    void run() { }\n}\n");
            git.add().addFilepattern(".").call();
            second = git.commit()
                    .setMessage("Add run method")
                    .setAuthor("Tester", "tester@example.com")
                    .setCommitter("Tester", "tester@example.com")
                    .call();
        }

        CodeRepository repository = mock(CodeRepository.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CommitInsightService service = new CommitInsightService(repository, ollamaClient);
        CodeRepositoryRecord record = new CodeRepositoryRecord(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                "sample",
                "GIT",
                "https://example.com/sample.git",
                null,
                "https://example.com/sample.git",
                "main",
                "NONE",
                tempDir.toString(),
                "INDEXED",
                second.getName()
        );

        when(repository.findRepository(repositoryId)).thenReturn(Optional.of(record));
        when(repository.findActiveFileIdsByPath(eq(repositoryId), anyList())).thenReturn(Map.of());
        when(ollamaClient.chatResult(anyString(), anyString()))
                .thenReturn(new OllamaClient.ChatResult("변경 요약은 App.java에 run 메서드를 추가했다는 정", "stop", true, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false));

        CodeAskResponse response = service.answer(repositoryId, "최신 바뀐 내용 알려줘");

        assertThat(response.answer()).contains("Add run method");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("완성되지 않아"));
    }

    @Test
    void importedRepositoryFallsBackToIndexedSnapshotWhenGitDirectoryIsMissing() {
        UUID repositoryId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        CodeRepository repository = mock(CodeRepository.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CommitInsightService service = new CommitInsightService(repository, ollamaClient);
        CodeRepositoryRecord record = new CodeRepositoryRecord(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                "imported-sample",
                "GIT",
                "https://example.com/sample.git",
                null,
                "https://example.com/sample.git",
                "main",
                "NONE",
                "imported://" + repositoryId,
                "INDEXED",
                "abc1234567890"
        );

        when(repository.findRepository(repositoryId)).thenReturn(Optional.of(record));
        when(repository.listActiveFiles(eq(repositoryId), isNull(), anyInt())).thenReturn(List.of(
                new CodeFileSummary(fileId, repositoryId, "src/App.java", "java", "hash", 2, OffsetDateTime.now())
        ));
        when(repository.listActiveChunksForFile(fileId)).thenReturn(List.of(
                new CodeChunkSummary(chunkId, 0, "method", "run", "App", "run", null, null, 10, 20, "void run() { start(); }", Map.of())
        ));

        CodeAskResponse response = service.answer(repositoryId, "latest changes");

        assertThat(response.mode()).isEqualTo("commit");
        assertThat(response.confidence()).isEqualTo("낮음");
        assertThat(response.answer()).contains("import", "abc1234567890", "[1]", "src/App.java");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().get(0).metadata()).containsEntry("kind", "imported_code_snapshot");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("localPath=imported:"));
    }
}
