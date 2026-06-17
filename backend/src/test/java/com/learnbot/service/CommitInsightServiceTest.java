package com.learnbot.service;

import com.learnbot.dto.CodeAskResponse;
import com.learnbot.repository.CodeRepository;
import com.learnbot.repository.SecurityRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                "https://example.com/sample.git",
                "main",
                "NONE",
                tempDir.toString(),
                "INDEXED",
                second.getName()
        );

        when(repository.findRepository(repositoryId)).thenReturn(Optional.of(record));
        when(repository.findActiveFileIdsByPath(eq(repositoryId), anyList())).thenReturn(Map.of("src/App.java", fileId));
        when(ollamaClient.chat(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.answer(repositoryId, "최근 변경내용 설명해줘");

        assertThat(response.mode()).isEqualTo("commit");
        assertThat(response.answer()).contains("Add greeting");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().get(0).fileId()).isEqualTo(fileId);
        assertThat(response.evidence().get(0).metadata()).containsEntry("kind", "commit_diff");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("최신 인덱싱 커밋"));
    }
}
