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
    void patchFromLoadedFilesFallsBackToSimpleHtmlPageForEmptyHtmlFile() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat("NO_PATCH\nreason: model was unsure"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "html\uD30C\uC77C\uC5D0 \uAC04\uB2E8\uD55C \uC6F9\uD398\uC774\uC9C0 \uB9CC\uB4E4\uC5B4\uC918",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", ""))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).path()).isEqualTo(path);
        assertThat(response.files().get(0).diff())
                .contains("--- a/home.html")
                .contains("+++ b/home.html")
                .contains("@@ -0,0 +1,")
                .contains("+<!doctype html>")
                .contains("+<html lang=\"ko\">")
                .contains("+  <title>\uAC04\uB2E8\uD55C \uC6F9\uD398\uC774\uC9C0</title>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation returned no patch"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("Deterministic empty HTML page fallback"));
    }

    @Test
    void patchFromLoadedFilesDoesNotUseHtmlFallbackForNonEmptyHtmlFile() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat("NO_PATCH\nreason: model was unsure"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "html\uD30C\uC77C\uC5D0 \uAC04\uB2E8\uD55C \uC6F9\uD398\uC774\uC9C0 \uB9CC\uB4E4\uC5B4\uC918",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, "home.html", "html", "<main>existing</main>\n"))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation returned no patch"));
        assertThat(response.warnings()).noneSatisfy(warning -> assertThat(warning).contains("Deterministic empty HTML page fallback"));
    }

    @Test
    void patchFromLoadedFilesConvertsFullHtmlModelOutputToUnifiedDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <!doctype html>
                <html lang="ko">
                <body>
                  <h1>안녕하세요</h1>
                </body>
                </html>
                """;
        String upgraded = """
                ```html
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="UTF-8">
                  <title>업그레이드된 웹페이지</title>
                </head>
                <body>
                  <main>
                    <h1>더 멋진 웹페이지</h1>
                    <p>보기 좋게 개선했습니다.</p>
                  </main>
                </body>
                </html>
                ```
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat(upgraded));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "html파일에 내 웹페이지를 좀더 업그레이드 해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("--- a/home.html")
                .contains("+++ b/home.html")
                .contains("-  <h1>안녕하세요</h1>")
                .contains("+  <title>업그레이드된 웹페이지</title>")
                .contains("+    <h1>더 멋진 웹페이지</h1>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("Model returned full-file content"));
    }

    @Test
    void patchFromLoadedFilesConvertsFullJavaModelOutputToUnifiedDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "src/App.java";
        String current = """
                package demo;

                class App {
                  String title() {
                    return "old";
                  }
                }
                """;
        String upgraded = """
                ```java
                package demo;

                class App {
                  String title() {
                    return "upgraded";
                  }
                }
                ```
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat(upgraded));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "App.java를 업그레이드해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "java", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("--- a/src/App.java")
                .contains("+++ b/src/App.java")
                .contains("-    return \"old\";")
                .contains("+    return \"upgraded\";");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("Model returned full-file content"));
    }

    @Test
    void patchFromLoadedFilesConvertsFullJsonModelOutputToUnifiedDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "config.json";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat("""
                {
                  "name": "learnbot",
                  "enabled": true
                }
                """));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "config.json 설정을 업그레이드해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "json", "{\"name\":\"learnbot\"}\n"))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files().get(0).diff())
                .contains("--- a/config.json")
                .contains("+++ b/config.json")
                .contains("-{\"name\":\"learnbot\"}")
                .contains("+  \"enabled\": true");
    }

    @Test
    void patchFromLoadedFilesDoesNotConvertChattyModelExplanationToPatch() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(1800))).thenReturn(chat("Here is the upgraded file. I changed the title."));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "README를 업그레이드해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, "README.md", "markdown", "# Old\n"))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("Patch output is not a unified diff"));
        assertThat(response.warnings()).noneSatisfy(warning -> assertThat(warning).contains("Model returned full-file content"));
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
