package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeSearchServiceTest {
    private final CodeSearchService service = new CodeSearchService(null, null, new LearnBotProperties());

    @Test
    void expandsKoreanLoginQuestionWithCodeAliases() {
        List<String> expanded = service.expandedQueries("로그인 어떻게 동작해?");

        assertThat(expanded)
                .contains("로그인 어떻게 동작해?", "login", "auth", "session", "token", "credential");
    }

    @Test
    void extractsCodeIdentifiersFromNaturalLanguageQuestion() {
        List<String> identifiers = service.identifiersFrom("LoginController.startIndex 메서드 설명해줘");

        assertThat(identifiers).contains("LoginController.startIndex");
    }

    @Test
    void reranksLoginAuthFilesAboveGitNoise() {
        CodeRepository repository = mock(CodeRepository.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeSearchService searchService = new CodeSearchService(repository, ollamaClient, new LearnBotProperties());
        CodeSearchResult noisy = result(
                "backend/src/main/java/com/learnbot/service/GitWorkspaceService.java",
                "method",
                "cloneRepository",
                0.9
        );
        CodeSearchResult auth = result(
                "backend/src/main/java/com/learnbot/web/AuthController.java",
                "method",
                "login",
                0.55
        );

        when(repository.keywordSearch(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(noisy, auth));
        when(repository.relatedChunks(any(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(ollamaClient.embed(anyList())).thenThrow(new RuntimeException("embedding unavailable"));

        List<CodeSearchResult> results = searchService.search(null, "로그인 관련 파일 어디있어?", 2, List.of(UUID.randomUUID()), null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).filePath()).contains("AuthController");
    }

    private CodeSearchResult result(String filePath, String chunkType, String methodName, double score) {
        return new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                filePath,
                chunkType,
                methodName,
                "AuthController",
                methodName,
                "com.learnbot.web",
                null,
                null,
                1,
                10,
                24,
                "File: " + filePath + "\npublic LoginResponse login(...) { return authService.login(...); }",
                score,
                Map.of("language", "java")
        );
    }
}
