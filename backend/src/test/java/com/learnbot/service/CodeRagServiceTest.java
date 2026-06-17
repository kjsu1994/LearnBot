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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class CodeRagServiceTest {
    @Test
    void commitQuestionsBypassNormalSearchFlow() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        CommitInsightService commitInsightService = mock(CommitInsightService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, commitInsightService, ollamaClient, new LearnBotProperties());
        CodeAskResponse commitResponse = new CodeAskResponse("commit", "commit answer [1]", List.of(), "높음", List.of());

        when(commitInsightService.isCommitQuestion("latest changes")).thenReturn(true);
        when(commitInsightService.answer(null, "latest changes")).thenReturn(commitResponse);

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "latest changes",
                "overview",
                4
        );

        assertThat(response).isSameAs(commitResponse);
        verifyNoInteractions(searchService, referenceService, ollamaClient);
    }

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
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("검색 근거 기반 답변으로 대체"));
    }

    @Test
    void compressesLongCodeContextBeforeCallingLlm() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        List<CodeSearchResult> results = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> result(
                        "backend/src/main/java/com/learnbot/service/LoginService" + index + ".java",
                        "method",
                        "login" + index,
                        0.9 - (index * 0.01),
                        ("public LoginResponse login" + index + "() { authenticate(); issueToken(); }\n"
                                + "unrelated implementation detail ".repeat(300))
                ))
                .toList();

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(results);
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chat(anyString(), anyString())).thenReturn("로그인은 LoginService 후보 메서드에서 인증과 토큰 발급을 처리합니다 [1].");

        service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "로그인 어떻게 동작해?",
                "overview",
                16
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chat(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("login0");
        assertThat(promptCaptor.getValue()).doesNotContain("[9]");
        assertThat(promptCaptor.getValue().length()).isLessThan(6500);
    }

    @Test
    void locateRewritesUncitedModelAnswerIntoActionableFallback() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult controller = result("backend/src/main/java/com/learnbot/web/AuthController.java", "method", "login", 0.82);
        CodeSearchResult serviceResult = result("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.76);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(controller, serviceResult));
        when(ollamaClient.chat(anyString(), anyString())).thenReturn("AuthController에 있습니다.");

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "로그인 관련 파일 어디있어?",
                "locate",
                10
        );

        assertThat(response.answer()).contains("후보 위치");
        assertThat(response.answer()).contains("AuthController.java");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("검색 근거 기반 답변으로 대체"));
    }

    private CodeSearchResult result(String filePath, String chunkType, String methodName, double score) {
        return result(
                filePath,
                chunkType,
                methodName,
                score,
                "File: " + filePath + "\nLines: 10-24\npublic LoginResponse login(...) { return authService.login(...); }"
        );
    }

    private CodeSearchResult result(String filePath, String chunkType, String methodName, double score, String content) {
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
                content,
                score,
                Map.of("language", "java")
        );
    }
}
