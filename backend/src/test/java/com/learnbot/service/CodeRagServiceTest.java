package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.ConversationIntent;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.repository.CodeRepository;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

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
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("The"));

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
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("로그인은 LoginService 후보 메서드에서 인증과 토큰 발급을 처리합니다 [1]."));

        service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "로그인 어떻게 동작해?",
                "overview",
                16
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(anyString(), promptCaptor.capture());
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
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("AuthController에 있습니다."));

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

    @Test
    void ranksGraphCallFlowEvidenceAboveWeakTextMatch() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult noisy = result("backend/src/main/java/com/learnbot/service/ReportService.java", "method", "render", 0.95);
        CodeSearchResult graph = graphResult("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.42, "CALLS", 0.96, 1);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(noisy, graph));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "login call flow",
                "flow",
                4
        );

        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().get(0).filePath()).contains("AuthService");
        assertThat(response.evidence().get(0).metadata()).containsKeys("evidenceScore", "evidenceRankReason", "graphReliability");
        assertThat(response.evidence().get(0).metadata()).doesNotContainKey("evidenceScoreParts");
        assertThat(String.valueOf(response.evidence().get(0).metadata().get("evidenceRankReason"))).contains("graph CALLS");
    }

    @Test
    void evidenceRankingDebugExposesScorePartsAndGraphDiagnostics() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().getGraph().setEvidenceRankingDebug(true);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult graph = graphResult("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.42, "CALLS", 0.96, 1);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(graph));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "login call flow",
                "flow",
                4
        );

        assertThat(response.evidence().get(0).metadata()).containsKeys("evidenceScoreParts", "evidenceRankReason");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("Graph evidence:"));
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("Top graph edges: CALLS=1"));
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("Evidence ranking debug:"));
    }

    @Test
    void confidenceUsesGraphEvidenceScoreWhenRawSearchScoreIsLow() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult serviceResult = graphResult("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.12, "CALLS", 0.98, 1);
        CodeSearchResult repositoryResult = graphResult("backend/src/main/java/com/learnbot/repository/AuthRepository.java", "method", "findUser", 0.10, "USES_ENTITY", 0.92, 1);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(serviceResult, repositoryResult));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("AuthService calls repository evidence [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "login call flow",
                "flow",
                4
        );

        assertThat(response.confidence()).isIn("높음", "보통");
    }

    @Test
    void callFlowSelectionPrioritizesEvidenceScoreBeforeFlowRank() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult weakController = graphResult("backend/src/main/java/com/learnbot/web/AuthController.java", "method", "login", 0.15, "CALLS", 0.20, 2);
        CodeSearchResult strongService = graphResult("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.45, "CALLS", 0.99, 1);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(weakController, strongService));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "login call flow",
                "flow",
                4
        );

        assertThat(response.evidence().get(0).filePath()).contains("AuthService");
    }

    @Test
    void conversationalAskPinsPreviousEvidenceChunks() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagService service = new CodeRagService(
                searchService,
                codeRepository,
                referenceService,
                null,
                ollamaClient,
                properties,
                new RagPipelineService(ollamaClient, properties),
                new CodeEvidenceRanker(properties),
                null
        );
        UUID pinnedChunkId = UUID.randomUUID();
        CodeSearchResult pinned = resultWithId(
                pinnedChunkId,
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askConversational",
                0.25,
                "public CodeAskResponse askConversational(...) { return askPrioritized(...); }"
        );
        CodeSearchResult generic = result(
                "backend/src/main/java/com/learnbot/service/OtherService.java",
                "method",
                "call",
                0.40
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "CodeRagService askConversational call flow",
                List.of(),
                List.of(new CodeConversationAnchor(
                        pinnedChunkId,
                        pinned.filePath(),
                        pinned.symbolName(),
                        pinned.className(),
                        pinned.methodName(),
                        pinned.lineStart(),
                        pinned.lineEnd()
                )),
                true
        );

        when(codeRepository.findActiveChunksByIds(isNull(), anyList(), anyList(), isNull())).thenReturn(List.of(pinned));
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(generic));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.askConversational(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "그 호출 흐름도 알려줘",
                "flow",
                4,
                context
        );

        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().get(0).chunkId()).isEqualTo(pinnedChunkId);
        assertThat(response.evidence().get(0).metadata()).containsEntry("conversationPinned", true);
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("pinned"));
    }

    @Test
    void conversationalAutoModeInfersFlowAndKeepsPinnedEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagService service = new CodeRagService(
                searchService,
                codeRepository,
                referenceService,
                null,
                ollamaClient,
                properties,
                new RagPipelineService(ollamaClient, properties),
                new CodeEvidenceRanker(properties),
                null
        );
        UUID pinnedChunkId = UUID.randomUUID();
        CodeSearchResult pinned = resultWithId(
                pinnedChunkId,
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askConversational",
                0.25,
                "public CodeAskResponse askConversational(...) { return askPrioritized(...); }"
        );
        CodeSearchResult generic = result(
                "backend/src/main/java/com/learnbot/web/CodeController.java",
                "method",
                "ask",
                0.40
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "CodeRagService askConversational call flow",
                List.of(),
                List.of(new CodeConversationAnchor(
                        pinnedChunkId,
                        pinned.filePath(),
                        pinned.symbolName(),
                        pinned.className(),
                        pinned.methodName(),
                        pinned.lineStart(),
                        pinned.lineEnd()
                )),
                true
        );

        when(codeRepository.findActiveChunksByIds(isNull(), anyList(), anyList(), isNull())).thenReturn(List.of(pinned));
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(generic));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.askConversational(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "call flow",
                "auto",
                null,
                context
        );

        assertThat(response.mode()).isEqualTo("flow");
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().get(0).chunkId()).isEqualTo(pinnedChunkId);
        assertThat(response.evidence().get(0).metadata()).containsEntry("conversationPinned", true);
    }

    @Test
    void conversationalAutoModeInheritsPreviousModeWhenQuestionHasNoModeKeyword() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult result = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.72
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "more detail",
                List.of(new RagConversationTurnContext("What is affected?", "Impact answer [1]", "impact", new ObjectMapper().createArrayNode())),
                List.of(),
                List.of(),
                true
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.askConversational(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "more detail",
                "",
                null,
                context
        );

        assertThat(response.mode()).isEqualTo("impact");
    }

    @Test
    void previousAnswerExpansionKeepsRequiredCodeEvidenceAndStillSearches() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagService service = new CodeRagService(
                searchService,
                codeRepository,
                referenceService,
                null,
                ollamaClient,
                properties,
                new RagPipelineService(ollamaClient, properties),
                new CodeEvidenceRanker(properties),
                null
        );
        UUID requiredChunkId = UUID.randomUUID();
        CodeSearchResult required = resultWithId(
                requiredChunkId,
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.20,
                "private CodeAskResponse askPrioritized(...) { return fallbackAnswer(...); }"
        );
        CodeSearchResult searched = result(
                "backend/src/main/java/com/learnbot/web/CodeController.java",
                "method",
                "ask",
                0.35
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "more detail by item",
                List.of(),
                List.of(),
                List.of(),
                true,
                ConversationIntent.PREVIOUS_ANSWER_EXPANSION,
                List.of(new PreviousAnswerItem("Ask flow", "Ask flow [1]", List.of(1), List.of(requiredChunkId))),
                List.of(),
                List.of(requiredChunkId)
        );

        when(codeRepository.findActiveChunksByIds(isNull(), anyList(), anyList(), isNull())).thenReturn(List.of(required));
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(searched));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.askConversational(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "more detail by item",
                "auto",
                null,
                context
        );

        verify(searchService, atLeastOnce()).search(isNull(), anyString(), anyInt(), anyList(), isNull());
        assertThat(response.mode()).isEqualTo("overview");
        assertThat(response.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.chunkId()).isEqualTo(requiredChunkId);
            assertThat(evidence.metadata()).containsEntry("conversationRequired", true);
            assertThat(evidence.metadata()).containsEntry("previousAnswerItem", "Ask flow");
        });
    }

    private static OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }

    private CodeSearchResult result(String filePath, String chunkType, String methodName, double score, String content) {
        return resultWithId(UUID.randomUUID(), filePath, chunkType, methodName, score, content);
    }

    private CodeSearchResult resultWithId(UUID chunkId, String filePath, String chunkType, String methodName, double score, String content) {
        return new CodeSearchResult(
                chunkId,
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

    private CodeSearchResult graphResult(String filePath, String chunkType, String methodName, double score,
                                         String edgeType, double pathScore, int depth) {
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
                "com.learnbot.service",
                null,
                null,
                1,
                10,
                24,
                "File: " + filePath + "\npublic LoginResponse login(...) { return tokenService.issue(...); }",
                score,
                Map.of(
                        "language", "java",
                        "graphExpanded", true,
                        "graphEdgeType", edgeType,
                        "graphPathScore", pathScore,
                        "graphDepth", depth,
                        "graphPath", "AuthController -> AuthService",
                        "graphEdgeTypes", List.of(edgeType)
                )
        );
    }
}
