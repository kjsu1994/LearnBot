package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import com.learnbot.repository.SecurityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentServiceTest {
    @Test
    void planFallsBackToDeterministicTargetsWhenLlmPlanFails() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = mock(PatchValidationService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult controller = result(repositoryId, "backend/src/main/java/AuthController.java", 0.9);
        CodeSearchResult serviceResult = result(repositoryId, "backend/src/main/java/AuthService.java", 0.8);

        when(searchService.search(eq(repositoryId), anyString(), anyInt(), anyList(), eq(SecurityRepository.DEFAULT_SPACE_ID)))
                .thenReturn(List.of(controller, serviceResult));
        when(fileLoader.rejectionReason(anyString())).thenReturn(null);
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(700))).thenThrow(new RuntimeException("model unavailable"));

        CodeAgentPlanResponse response = service.plan(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Fix login error",
                null
        );

        assertThat(response.needsMoreContext()).isFalse();
        assertThat(response.targetFiles()).extracting("path")
                .containsExactly("backend/src/main/java/AuthController.java", "backend/src/main/java/AuthService.java");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("deterministic target selection"));
        assertThat(response.evidence()).hasSize(2);
    }

    @Test
    void patchReturnsValidatedUnifiedDiffWithoutApplyingIt() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        UUID repositoryId = UUID.randomUUID();
        String path = "backend/src/main/java/AuthService.java";
        String diff = """
                --- a/backend/src/main/java/AuthService.java
                +++ b/backend/src/main/java/AuthService.java
                @@ -1,3 +1,4 @@
                 class AuthService {
                +  void fixed() {}
                 }
                """;

        when(fileLoader.load(eq(repositoryId), eq(List.of(path))))
                .thenReturn(new CodePatchFileLoader.LoadResult(
                        List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), path, "java", "class AuthService {\n}\n")),
                        List.of()
                ));
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat(diff));

        CodeAgentPatchResponse response = service.patch(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Fix login error",
                List.of(path)
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).path()).isEqualTo(path);
        assertThat(response.files().get(0).diff()).contains("+++ b/backend/src/main/java/AuthService.java");
        verify(fileLoader).load(eq(repositoryId), eq(List.of(path)));
    }

    @Test
    void patchRejectsDiffOutsideTargetFiles() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        UUID repositoryId = UUID.randomUUID();
        String target = "backend/src/main/java/AuthService.java";
        String diff = """
                --- a/backend/src/main/java/OtherService.java
                +++ b/backend/src/main/java/OtherService.java
                @@ -1,2 +1,3 @@
                 class OtherService {
                +  void unsafe() {}
                 }
                """;

        when(fileLoader.load(eq(repositoryId), eq(List.of(target))))
                .thenReturn(new CodePatchFileLoader.LoadResult(
                        List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), target, "java", "class AuthService {}\n")),
                        List.of()
                ));
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat(diff));

        CodeAgentPatchResponse response = service.patch(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Fix login error",
                List.of(target)
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("outside targetFiles"));
    }

    @Test
    void patchFromLoadedFilesFallsBackToDeterministicAppendForSimpleEndAppendRequest() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "readme.txt";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenThrow(new RuntimeException("model unavailable"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "README파일 끝에 짧은 시를 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "text", ""))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).path()).isEqualTo(path);
        assertThat(response.files().get(0).diff())
                .contains("--- a/readme.txt")
                .contains("+++ b/readme.txt")
                .contains("@@ -0,0 +1,3 @@")
                .contains("+작은 빛이 머문 자리");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("Deterministic append fallback"));
    }

    @Test
    void patchFromLoadedFilesUsesLlmBeforeAppendFallback() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "testfile.md";
        String diff = """
                --- a/testfile.md
                +++ b/testfile.md
                @@ -0,0 +1,1 @@
                +오늘도 좋은 하루입니다.
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat(diff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "test파일 끝에 한글로 아무거나 한마디를 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "markdown", ""))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+오늘도 좋은 하루입니다.")
                .doesNotContain("Added by LearnBot");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation attempted"));
        verify(ollamaClient).chatResult(anyString(), anyString(), eq(1800));
    }

    @Test
    void patchFromLoadedFilesKoreanFallbackDoesNotUseEnglishPlaceholder() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "testfile.md";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenThrow(new RuntimeException("model unavailable"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "test파일 끝에 한글로 아무거나 한마디를 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "markdown", ""))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files().get(0).diff())
                .contains("+오늘도 한 걸음 나아갑니다.")
                .doesNotContain("Added by LearnBot");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation failed"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("Deterministic append fallback"));
    }

    @Test
    void fileLoaderRejectsUnsafePatchTargetsBeforeReadingContent() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeContentReader contentReader = mock(CodeContentReader.class);
        CodePatchFileLoader loader = new CodePatchFileLoader(repository, contentReader);
        UUID repositoryId = UUID.randomUUID();

        when(repository.findRepository(repositoryId)).thenReturn(Optional.of(new CodeRepositoryRecord(
                repositoryId,
                SecurityRepository.DEFAULT_SPACE_ID,
                "repo",
                "ZIP",
                "repo.zip",
                "hash",
                null,
                "SNAPSHOT",
                "NONE",
                "imported://repo",
                "INDEXED",
                "hash"
        )));

        CodePatchFileLoader.LoadResult result = loader.load(repositoryId, List.of("../.env", "application-prod.yml"));

        assertThat(result.files()).isEmpty();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("Path traversal"));
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("Sensitive"));
    }

    private CodeSearchResult result(UUID repositoryId, String path, double score) {
        return new CodeSearchResult(
                UUID.randomUUID(),
                repositoryId,
                UUID.randomUUID(),
                "repo",
                path,
                "method",
                "login",
                "AuthService",
                "login",
                "com.example",
                null,
                null,
                1,
                1,
                12,
                "class AuthService { void login() {} }",
                score,
                Map.of()
        );
    }

    private static OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }
}
