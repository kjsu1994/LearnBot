package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import com.learnbot.repository.SecurityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentServiceTest {
    @Test
    void planDoesNotUseServerAuthoredTargetsWhenLlmPlanFails() {
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

        assertThat(response.needsMoreContext()).isTrue();
        assertThat(response.targetFiles()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("server-authored target selection fallback is disabled"));
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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(diff));

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
    void patchFromLoadedFilesAcceptsJsonProposalAndSendsStructuredContextEnvelope() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <main>
                </main>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "targetFiles": ["home.html"],
                  "diagnosis": "The page needs a visible entry button inside the current main section.",
                  "changeIntent": "Add the requested button without server-authored content.",
                  "unifiedDiff": "--- a/home.html\\n+++ b/home.html\\n@@ -1,2 +1,3 @@\\n <main>\\n+  <button type=\\"button\\">Start</button>\\n </main>\\n",
                  "verificationPlan": ["Inspect the resulting HTML"],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "html file에 시작 버튼을 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+  <button type=\"button\">Start</button>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("proposal action=propose_patch"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("diagnosis"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(anyString(), promptCaptor.capture(), eq(4096));
        assertThat(promptCaptor.getValue())
                .contains("PATCH_CONTEXT_ENVELOPE v1")
                .contains("CONTENT_AUTHORITY: exact_current_workspace_file")
                .doesNotContain("LINE_NUMBERED_VIEW")
                .doesNotContain("    1 | <main>")
                .contains("EXACT_CONTENT_START home.html")
                .contains("<main>\n</main>\n");
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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(diff));

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
    void patchFromLoadedFilesDoesNotUseServerAuthoredAppendFallbackWhenModelFails() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "readme.txt";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenThrow(new RuntimeException("model unavailable"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "README파일 끝에 짧은 시를 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "text", ""))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation failed"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(diff));

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
        verify(ollamaClient).chatResult(anyString(), anyString(), eq(4096));
    }

    @Test
    void patchFromLoadedFilesFallsBackToAuxiliaryWhenPrimaryStopsByLengthWithBlankOutput() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <button type="button" onclick="showMain()">Start</button>
                <section id="main-page" style="display:none;"></section>
                """;
        String diff = """
                --- a/home.html
                +++ b/home.html
                @@ -1,2 +1,2 @@
                -<button type="button" onclick="showMain()">Start</button>
                +<button type="button" onclick="document.getElementById('main-page').style.display='block'">Start</button>
                 <section id="main-page" style="display:none;"></section>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chatLengthBlank());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), eq(4096))).thenReturn(chat(diff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the HTML button navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff()).contains("document.getElementById('main-page')");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("primary output was blank after a length stop"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("auxiliary fallback produced non-empty output"));
        verify(ollamaClient).chatResult(anyString(), anyString(), eq(4096));
        verify(ollamaClient).chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), eq(4096));
    }

    @Test
    void patchFromLoadedFilesFallsBackToAuxiliaryWhenRepairStopsByLengthWithBlankOutput() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <button type="button" onclick="showMain()">Start</button>
                <section id="main-page" style="display:none;"></section>
                """;
        String invalidDiff = """
                --- a/other.html
                +++ b/other.html
                @@ -1 +1 @@
                -old
                +new
                """;
        String repairedDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -1,2 +1,2 @@
                -<button type="button" onclick="showMain()">Start</button>
                +<button type="button" onclick="document.getElementById('main-page').style.display='block'">Start</button>
                 <section id="main-page" style="display:none;"></section>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096)))
                .thenReturn(chat(invalidDiff), chatLengthBlank());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), eq(4096)))
                .thenReturn(chat(repairedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the HTML button navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff()).contains("document.getElementById('main-page')");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch repair attempted"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("repair primary output was blank after a length stop"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("repair auxiliary fallback produced non-empty output"));
        verify(ollamaClient, times(2)).chatResult(anyString(), anyString(), eq(4096));
        verify(ollamaClient).chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), eq(4096));
    }

    @Test
    void patchFromLoadedFilesNormalizesMissingNewFileHeaderInLlmDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <script>
                function switchTab(tabId) {
                  const tabs = document.querySelectorAll('.tab-content');
                  tabs.forEach(t => t.classList.remove('active'));
                  document.getElementById(tabId).classList
                }
                </script>
                """;
        String malformedDiff = """
                --- a/home.html
                @@ -2,6 +2,8 @@
                 function switchTab(tabId) {
                   const tabs = document.querySelectorAll('.tab-content');
                   tabs.forEach(t => t.classList.remove('active'));
                -  document.getElementById(tabId).classList
                +  document.getElementById(tabId).classList.add('active');
                +  document.getElementById('main-page').style.display = 'block';
                 }
                 </script>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(malformedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "버튼 클릭 후 메인페이지로 들어가지 않는 문제를 복구해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("--- a/home.html")
                .contains("+++ b/home.html")
                .contains("+  document.getElementById('main-page').style.display = 'block';");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("omitted +++ file header"));
    }

    @Test
    void patchFromLoadedFilesNormalizesUniqueExistingLineWhitespaceInLlmDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <script>
                    function switchTab(tabId) {
                        const tabs = document.querySelectorAll('.tab-content');
                        tabs.forEach(t => t.classList.remove('active'));
                        document.getElementById(tabId).classList
                    }
                """;
        String malformedDiff = """
                --- a/home.html
                @@ -203,5 +203,6 @@
                 function switchTab(tabId) {
                 const tabs = document.querySelectorAll('.tab-content');
                 tabs.forEach(t => t.classList.remove('active'));
                - document.getElementById(tabId).classList
                + document.getElementById(tabId).classList.add('active');
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(malformedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the broken tab button navigation in home.html",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("--- a/home.html")
                .contains("+++ b/home.html")
                .contains("     function switchTab(tabId) {")
                .contains("         const tabs = document.querySelectorAll('.tab-content');")
                .contains("-        document.getElementById(tabId).classList")
                .contains("+        document.getElementById(tabId).classList.add('active');");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("omitted +++ file header"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("existing-line whitespace was normalized"));
    }

    @Test
    void patchFromLoadedFilesRepairsInitialDiffWithNoActualMutationLines() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <script>
                  function switchTab(tabId) {
                    const tabs = document.querySelectorAll('.tab-content');
                    tabs.forEach(t => t.classList.remove('active'));
                    document.getElementById(tabId).classList
                """;
        String contextOnlyDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -170,3 +170,4 @@
                    document.getElementById(tabId).classList
                """;
        String repairedDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -2,4 +2,8 @@
                  function switchTab(tabId) {
                    const tabs = document.querySelectorAll('.tab-content');
                    tabs.forEach(t => t.classList.remove('active'));
                -   document.getElementById(tabId).classList
                +   document.getElementById(tabId).classList.add('active');
                + }
                +</script>
                +</body>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096)))
                .thenReturn(chat(contextOnlyDiff), chat(repairedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the broken HTML tab navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("-    document.getElementById(tabId).classList")
                .contains("+    document.getElementById(tabId).classList.add('active');");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("no added or removed lines"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch repair attempted"));
        verify(ollamaClient, times(2)).chatResult(anyString(), anyString(), eq(4096));
    }

    @Test
    void patchFromLoadedFilesReclassifiesAbsentTrailingContextLinesAsAdditions() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <script>
                  function switchTab(tabId) {
                    const tabs = document.querySelectorAll('.tab-content');
                    tabs.forEach(t => t.classList.remove('active'));
                    document.getElementById(tabId).classList
                """;
        String malformedDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -207,6 +207,8 @@
                 function switchTab(tabId) {
                 const tabs = document.querySelectorAll('.tab-content');
                 tabs.forEach(t => t.classList.remove('active'));
                - document.getElementById(tabId).classList
                + document.getElementById(tabId).classList.add('active');
                + }
                 </script>
                 </body>
                 </html>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(malformedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the broken HTML tab navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+</script>")
                .contains("+</body>")
                .contains("+</html>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("absent trailing context lines"));
    }

    @Test
    void patchFromLoadedFilesRejectsWhitespaceOnlyPatchForBehavioralRequest() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <section>
                  <script>
                    function showMain() {
                      document.getElementById('main-page').style.display = 'block';
                    }
                """;
        String whitespaceOnlyDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -1,5 +1,5 @@
                 <section>
                -  <script>
                +    <script>
                     function showMain() {
                       document.getElementById('main-page').style.display = 'block';
                     }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(whitespaceOnlyDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the button so it opens the main page",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("only whitespace changes"));
    }

    @Test
    void patchFromLoadedFilesRejectsHtmlPatchThatLeavesContentAfterClosingHtml() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <main>
                  <section id="main-page"></section>

                  <script>
                    function showMain() {
                      document.getElementById('main-page').style.display = 'block';
                    }
                """;
        String malformedDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -1,5 +1,7 @@
                 <main>
                   <section id="main-page"></section>

                +</main>
                +</html>
                   <script>
                     function showMain() {
                       document.getElementById('main-page').style.display = 'block';
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(malformedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the button so it opens the main page",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("content after </html>"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("PATCHED_RESULT_CONTEXT")
                .contains("</html>")
                .contains("<script>"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("structurally invalid"));
    }

    @Test
    void patchFromLoadedFilesRetriesRepairWhenModelDeclinesBecauseFileLooksTruncated() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <script>
                function showMain() {
                  document.getElementById('landing').style.display = 'none';
                  document.getElementById('main-page').style.display = 'block';
                }
                function switchTab(tabId) {
                  const tabs = document.querySelectorAll('.tab-content');
                  tabs.forEach(t => t.classList.remove('active'));
                  document.getElementById(tabId).classList
                """;
        String repairedDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -6,4 +6,10 @@
                 function switchTab(tabId) {
                   const tabs = document.querySelectorAll('.tab-content');
                   tabs.forEach(t => t.classList.remove('active'));
                -  document.getElementById(tabId).classList
                +  document.getElementById(tabId).classList.add('active');
                +}
                +</script>
                +</body>
                +</html>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096)))
                .thenReturn(chat("NO_PATCH\nreason: The target file content is truncated."), chat(repairedDiff));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "버튼클릭했는데 메인페이지로 안들어가지니 코드를 보고 정상적으로 복구해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+  document.getElementById(tabId).classList.add('active');")
                .contains("+</html>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation returned no patch"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch repair attempted"));
    }

    @Test
    void patchFromLoadedFilesRetriesRepairAgainstCurrentContentWhenFirstRepairAssumesFailedDiffWasApplied() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="UTF-8">
                </head>
                <body>
                  <main>
                    <script>
                      function switchTab(tabId) {
                        const tabs = document.querySelectorAll('.tab-content');
                        tabs.forEach(t => t.classList.remove('active'));
                        document.getElementById(tabId).classList
                """;
        String initialDiff = """
                --- a/home.html
                @@ -1,6 +1,7 @@
                 <!doctype html>
                 <html lang="ko">
                +<head>
                 <meta charset="UTF-8">
                 </head>
                 <body>
                """;
        String firstRepairStillStale = """
                --- a/home.html
                +++ b/home.html
                @@ -10,3 +10,5 @@
                         tabs.forEach(t => t.classList.remove('active'));
                         document.getElementById(tabId).classList.add('active');
                       }
                +    </script>
                +  </main>
                """;
        String secondRepairFromCurrentContent = """
                --- a/home.html
                +++ b/home.html
                @@ -9,4 +9,9 @@
                       function switchTab(tabId) {
                         const tabs = document.querySelectorAll('.tab-content');
                         tabs.forEach(t => t.classList.remove('active'));
                -        document.getElementById(tabId).classList
                +        document.getElementById(tabId).classList.add('active');
                +      }
                +    </script>
                +  </main>
                +</body>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096)))
                .thenReturn(chat(initialDiff), chat(firstRepairStillStale), chat(secondRepairFromCurrentContent));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the broken HTML tab navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("-        document.getElementById(tabId).classList")
                .contains("+        document.getElementById(tabId).classList.add('active');")
                .contains("+</body>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch repair attempted"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("repair retry attempted"));
        verify(ollamaClient, times(3)).chatResult(anyString(), anyString(), eq(4096));
    }

    @Test
    void patchFromLoadedFilesDoesNotUseEnglishPlaceholderOrServerFallbackWhenModelFails() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "testfile.md";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenThrow(new RuntimeException("model unavailable"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "test파일 끝에 한글로 아무거나 한마디를 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "markdown", ""))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation failed"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
    }

    @Test
    void patchFromLoadedFilesDoesNotUseServerAuthoredHtmlFallbackForEmptyHtmlFile() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat("NO_PATCH\nreason: model was unsure"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "html\uD30C\uC77C\uC5D0 \uAC04\uB2E8\uD55C \uC6F9\uD398\uC774\uC9C0 \uB9CC\uB4E4\uC5B4\uC918",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", ""))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation returned no patch"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
    }

    @Test
    void patchFromLoadedFilesDoesNotUseHtmlFallbackForNonEmptyHtmlFile() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat("NO_PATCH\nreason: model was unsure"));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "html\uD30C\uC77C\uC5D0 \uAC04\uB2E8\uD55C \uC6F9\uD398\uC774\uC9C0 \uB9CC\uB4E4\uC5B4\uC918",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, "home.html", "html", "<main>existing</main>\n"))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("LLM patch generation returned no patch"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(upgraded));

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
    void patchFromLoadedFilesConvertsFullHtmlOutputWithMalformedDiffHeaderToUnifiedDiff() {
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
                  <button type="button" onclick="showMain()">시작하기</button>
                  <section id="main-page" style="display:none;"></section>
                </body>
                </html>
                """;
        String upgraded = """
                --- a/home.html
                <!doctype html>
                <html lang="ko">
                <body>
                  <button type="button" onclick="document.getElementById('main-page').style.display='block'">시작하기</button>
                  <section id="main-page" style="display:none;"></section>
                </body>
                </html>
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(upgraded));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "버튼 클릭 시 메인페이지로 들어가도록 복구해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("--- a/home.html")
                .contains("+++ b/home.html")
                .contains("-  <button type=\"button\" onclick=\"showMain()\">시작하기</button>")
                .contains("+  <button type=\"button\" onclick=\"document.getElementById('main-page').style.display='block'\">시작하기</button>");
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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(upgraded));

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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat("""
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
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat("Here is the upgraded file. I changed the title."));

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
    void patchFromLoadedFilesMaterializesStructuredFullFileEditIntoUnifiedDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <main>
                  <section id="main-page" style="display:none;"></section>
                </main>
                </html>
                <script>
                function showMain() {
                  document.getElementById('main-page').style.display = 'block';
                }
                </script>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "full_file",
                  "targetFiles": ["home.html"],
                  "diagnosis": "The script is outside the html document, so the button handler may not run reliably.",
                  "changeIntent": "Move the script before the closing document tag without server-authored content.",
                  "edits": [
                    {
                      "path": "home.html",
                      "fullFileContent": "<main>\\n  <section id=\\"main-page\\" style=\\"display:none;\\"></section>\\n</main>\\n<script>\\nfunction showMain() {\\n  document.getElementById('main-page').style.display = 'block';\\n}\\n</script>\\n</html>\\n"
                    }
                  ],
                  "verificationPlan": ["Click the button and confirm the main page appears"],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the HTML button navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("--- a/home.html")
                .contains("+++ b/home.html")
                .contains("-</html>")
                .contains("+</script>")
                .contains("+</html>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("editFormat=full_file"));
        assertThat(response.warnings()).noneSatisfy(warning -> assertThat(warning).contains("legacy unifiedDiff"));
    }

    @Test
    void patchFromLoadedFilesRejectsMalformedJsonEnvelopeInsteadOfTreatingItAsFullFileContent() {
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
                  <main>
                    <button type="button" onclick="showMain()">시작하기</button>
                  </main>
                </body>
                </html>
                """;
        String malformedJsonEnvelope = """
                {"action":"propose_patch","editFormat":"full_file","targetFiles":["home.html"],"diagnosis":"script is outside html","edits":[{"path":"home.html","fullFileContent":"<!doctype html>\\n<html lang=\\"ko\\">\\n<body>\\n
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(malformedJsonEnvelope));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Fix the HTML button navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("JSON proposal parsing failed"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("raw diff/full-file fallback is blocked"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
    }

    @Test
    void patchFromLoadedFilesMaterializesStructuredSearchReplaceEditIntoUnifiedDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "src/App.java";
        String current = """
                class App {
                  String title() {
                    return "old";
                  }
                }
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "search_replace",
                  "targetFiles": ["src/App.java"],
                  "diagnosis": "The title method returns the old label.",
                  "changeIntent": "Replace only the exact return line.",
                  "edits": [
                    {
                      "path": "src/App.java",
                      "search": "    return \\"old\\";",
                      "replace": "    return \\"new\\";"
                    }
                  ],
                  "verificationPlan": ["Run the closest Java test"],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Update the Java title text",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "java", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("-    return \"old\";")
                .contains("+    return \"new\";");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("editFormat=search_replace"));
    }

    @Test
    void patchFromLoadedFilesMaterializesOperationEditIntoUnifiedDiff() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <nav>
                  <button>소개</button>
                  <button>기능</button>
                </nav>
                <main>
                  <section id="intro">소개 화면</section>
                  <section id="features">기능 화면</section>
                </main>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "diagnosis": "Buttons do not select separate screens.",
                  "changeIntent": "Add a small script after the main content.",
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_after_anchor",
                      "anchorBefore": "</main>\\n",
                      "newText": "<script>\\nfunction showScreen(id) {\\n  document.querySelectorAll('main section').forEach(section => section.hidden = section.id !== id);\\n}\\n</script>\\n",
                      "reason": "Add tab screen switching without rewriting the whole file."
                    }
                  ],
                  "verificationPlan": ["Click each button and confirm only one section is visible."],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Make each button show a different screen",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+<script>")
                .contains("+function showScreen(id)")
                .contains("+</script>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("editFormat=operation_edit"));
    }

    @Test
    void patchFromLoadedFilesRejectsOperationEditWhenAnchorDoesNotMatch() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = "<main>현재 화면</main>\n";
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "diagnosis": "Need an insertion.",
                  "changeIntent": "Insert after an anchor.",
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_after_anchor",
                      "anchorBefore": "<footer>",
                      "newText": "<script></script>\\n"
                    }
                  ],
                  "verificationPlan": [],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Add script",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("matched 0 anchors"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
    }

    @Test
    void patchFromLoadedFilesRejectsOperationEditWhenAnchorIsAmbiguous() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <button>열기</button>
                <button>열기</button>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "diagnosis": "Need to wire a button.",
                  "changeIntent": "Insert script after a button.",
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_after_anchor",
                      "anchorBefore": "<button>열기</button>",
                      "newText": "\\n<script></script>"
                    }
                  ],
                  "verificationPlan": [],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Add script",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isFalse();
        assertThat(response.files()).isEmpty();
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("matched 2 anchors"));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("No server-authored content fallback"));
    }

    @Test
    void patchFromLoadedFilesDisambiguatesRepeatedInsertAnchorWithBoundaryAnchor() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <nav>
                  <a href="#contact">연락처</a>
                </nav>
                <footer>
                  <a href="#contact">연락처</a>
                </footer>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_after_anchor",
                      "anchorBefore": "<a href=\\"#contact\\">연락처</a>",
                      "anchorAfter": "</nav>",
                      "newText": "\\n  <a href=\\"#orgchart\\">조직도</a>"
                    }
                  ],
                  "verificationPlan": [],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "조직도 탭을 nav에 추가해줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+  <a href=\"#orgchart\">조직도</a>")
                .contains("<nav>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("editFormat=operation_edit"));
    }

    @Test
    void patchFromLoadedFilesAcceptsInsertBeforeWhenModelUsesAnchorBeforeAsPrimaryAnchor() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <main>
                  <div id="contact" class="card">Contact</div>
                </main>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_before_anchor",
                      "anchorBefore": "</main>",
                      "newText": "  <div id=\\"orgchart\\" class=\\"card\\">Org chart</div>\\n"
                    }
                  ],
                  "verificationPlan": [],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Add an organization chart tab to the homepage",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+  <div id=\"orgchart\" class=\"card\">Org chart</div>")
                .contains("+</main>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("editFormat=operation_edit"));
    }

    @Test
    void patchFromLoadedFilesAcceptsInsertAfterWhenModelUsesAnchorAfterAsPrimaryAnchor() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <nav>
                  <a href="#contact">Contact</a>
                </nav>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_after_anchor",
                      "anchorAfter": "<a href=\\"#contact\\">Contact</a>",
                      "new_text": "\\n  <a href=\\"#orgchart\\">Org chart</a>"
                    }
                  ],
                  "verificationPlan": [],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Add org chart navigation",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("+  <a href=\"#orgchart\">Org chart</a>");
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("editFormat=operation_edit"));
    }

    @Test
    void patchFromLoadedFilesDisambiguatesReplaceBetweenWhenTrailingAnchorRepeats() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <style>
                footer { footer {
                  text-align: center;
                  color: white;
                }
                .card {
                  padding: 16px;
                }
                </style>
                """;
        String proposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "replace_between_anchors",
                      "anchorBefore": "footer { footer {",
                      "anchorAfter": "}",
                      "newText": "\\n  text-align: center;\\n  color: white;\\n  font-size: 14px;\\n"
                    }
                  ],
                  "verificationPlan": [],
                  "riskNotes": []
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096))).thenReturn(chat(proposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "footer css 오류를 고쳐줘",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).diff())
                .contains("-footer { footer {")
                .contains("+footer { footer {")
                .contains("+  font-size: 14px;");
    }

    @Test
    void patchFromLoadedFilesUsesCompactContextAndBoundsPreviousLengthStoppedOutputForRepair() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = new PatchValidationService(fileLoader);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentService service = new CodeAgentService(searchService, fileLoader, validationService, ollamaClient, new ObjectMapper());
        String path = "home.html";
        String current = """
                <html>
                <head>
                  <style>
                    body { background: white; }
                  </style>
                </head>
                <body>
                  <main>Home</main>
                </body>
                </html>
                """;
        String longTruncatedProposal = """
                {"action":"propose_patch","editFormat":"operation_edit","targetFiles":["home.html"],"operations":[{"path":"home.html","operation":"insert_before_anchor","anchorAfter":"</style>","newText":"
                """ + "x".repeat(2500);
        String repairedProposal = """
                {
                  "action": "propose_patch",
                  "editFormat": "operation_edit",
                  "targetFiles": ["home.html"],
                  "operations": [
                    {
                      "path": "home.html",
                      "operation": "insert_before_anchor",
                      "anchorAfter": "</style>",
                      "newText": "    main { border: 1px solid #1976d2; }\\n"
                    }
                  ]
                }
                """;

        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(4096)))
                .thenReturn(chatLength(longTruncatedProposal), chat(repairedProposal));

        CodeAgentPatchResponse response = service.patchFromLoadedFiles(
                "Make the page more polished",
                List.of(new CodePatchFileLoader.LoadedPatchFile(null, path, "html", current))
        );

        assertThat(response.valid()).isTrue();
        assertThat(response.files()).hasSize(1);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, times(2)).chatResult(anyString(), userPromptCaptor.capture(), eq(4096));
        String initialPrompt = userPromptCaptor.getAllValues().get(0);
        String repairPrompt = userPromptCaptor.getAllValues().get(1);
        assertThat(initialPrompt).doesNotContain("LINE_NUMBERED_VIEW");
        assertThat(initialPrompt).contains("EXACT_CONTENT_START home.html");
        assertThat(repairPrompt).contains("...<truncated>");
        assertThat(repairPrompt).doesNotContain("x".repeat(1500));
        assertThat(response.warnings()).anySatisfy(warning -> assertThat(warning).contains("stopped by length"));
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

    private static OllamaClient.ChatResult chatLength(String content) {
        return new OllamaClient.ChatResult(content, "length", true, 5900, 244, "http://ollama:11434", "ornith", "primary", false);
    }

    private static OllamaClient.ChatResult chatLengthBlank() {
        return new OllamaClient.ChatResult("", "length", true, 1200, 4096, "http://ollama:11434", "ornith", "primary", false);
    }
}
