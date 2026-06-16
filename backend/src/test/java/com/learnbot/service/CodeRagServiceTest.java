package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.SecurityRepository;
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

class CodeRagServiceTest {
    @Test
    void overviewKeepsEvidenceWhenChatModelFails() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult result = result("backend/src/main/java/com/learnbot/web/AuthController.java", "method", "login", 0.82);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chat(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "로그인 어떻게 동작해?",
                "overview",
                4
        );

        assertThat(response.mode()).isEqualTo("overview");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.confidence()).isIn("높음", "보통");
        assertThat(response.answer()).contains("검색된 코드 근거");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("LLM 호출이 실패"));
    }

    @Test
    void avoidsAnswerWhenNoEvidenceIsFound() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of());

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "없는 기능 설명해줘",
                "overview",
                4
        );

        assertThat(response.confidence()).isEqualTo("낮음");
        assertThat(response.evidence()).isEmpty();
        assertThat(response.answer()).contains("코드 근거가 부족");
    }

    @Test
    void overviewRewritesTooShortModelAnswerIntoNaturalSummary() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult controller = result("backend/src/main/java/com/learnbot/web/CodeController.java", "method", "ask", 0.72);
        CodeSearchResult rag = result("backend/src/main/java/com/learnbot/service/CodeRagService.java", "method", "ask", 0.68);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(controller, rag));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chat(anyString(), anyString())).thenReturn("The");

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "전체적으로 뭐에 대한 코드야?",
                "overview",
                6
        );

        assertThat(response.answer()).contains("검색된 코드 근거 기준");
        assertThat(response.answer()).contains("주요 구성");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("자연어 요약으로 대체"));
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
                "File: " + filePath + "\nLines: 10-24\npublic LoginResponse login(...) { return authService.login(...); }",
                score,
                Map.of("language", "java")
        );
    }
}
