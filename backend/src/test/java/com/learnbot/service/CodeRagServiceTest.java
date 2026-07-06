package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.ConversationIntent;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.repository.CodeRepository;
import com.learnbot.repository.SecurityRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class CodeRagServiceTest {
    @Test
    void commitQuestionsUseModelRouteInsteadOfServerRegexBypass() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        CommitInsightService commitInsightService = mock(CommitInsightService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, commitInsightService, ollamaClient, new LearnBotProperties());
        CodeAskResponse commitResponse = new CodeAskResponse("commit", "commit answer [1]", List.of(new CodeEvidence(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "commit_diff",
                null,
                null,
                null,
                null,
                null,
                1,
                1,
                "commit diff",
                0.9,
                Map.of("kind", "commit_diff")
        )), "높음", List.of());

        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(chat("{\"route\":\"COMMIT_DIFF\",\"mode\":\"overview\",\"confidence\":0.92,\"queries\":[],\"commitRef\":\"\",\"reason\":\"user asked for latest changes\"}"));
        when(commitInsightService.answer(null, "latest changes")).thenReturn(commitResponse);

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "latest changes",
                "overview",
                4
        );

        assertThat(response.mode()).isEqualTo("commit");
        assertThat(response.answer()).isEqualTo("commit answer [1]");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("Agentic RAG route: route=COMMIT_DIFF"));
        verify(commitInsightService, never()).isCommitQuestion(anyString());
        verifyNoInteractions(searchService, referenceService);
    }

    @Test
    void numericFollowupDoesNotRouteToCommitInsightWhenModelSelectsPreviousAnswerContext() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        CommitInsightService commitInsightService = mock(CommitInsightService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(
                searchService,
                codeRepository,
                referenceService,
                commitInsightService,
                ollamaClient,
                properties,
                new RagPipelineService(ollamaClient, properties),
                new CodeEvidenceRanker(properties),
                null
        );
        CodeSearchResult result = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.86,
                "askPrioritized retrieves evidence, builds context, and generates the code RAG answer"
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "2",
                List.of(new RagConversationTurnContext(
                        "Explain indexing to RAG answer flow",
                        "1. Indexing [1]\n2. Code RAG answer generation [2]",
                        "overview",
                        new ObjectMapper().createArrayNode()
                )),
                List.of(),
                List.of(),
                true
        );

        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(chat("{\"route\":\"EXPAND_PREVIOUS_ANSWER\",\"mode\":\"overview\",\"confidence\":0.88,\"queries\":[\"Code RAG answer generation flow\"],\"reason\":\"numeric follow-up refers to previous answer item\"}"));
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Code RAG answer generation is handled by askPrioritized [1]."));

        CodeAskResponse response = service.askConversational(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "2",
                "auto",
                4,
                context
        );

        verify(commitInsightService, never()).isCommitQuestion(anyString());
        verify(commitInsightService, never()).answer(any(), anyString());
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("Agentic RAG route: route=EXPAND_PREVIOUS_ANSWER"));
    }

    @Test
    void routerFailureFallsBackToCodeSearchAndRestoresPrimaryRequestSlot() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult result = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.86,
                "askPrioritized retrieves evidence, builds context, and generates the answer"
        );

        when(ollamaClient.hasPrimaryRequestInFlight()).thenReturn(true);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenThrow(new IllegalStateException("auxiliary busy"));
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Code RAG answer generation is handled by askPrioritized [1]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain the Code RAG flow",
                "overview",
                4
        );

        assertThat(response.evidence()).hasSize(1);
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note)
                        .contains("Agentic RAG route: route=CODE_SEARCH")
                        .contains("fallback=true")
                        .contains("router failed: auxiliary busy"));
        verify(ollamaClient, times(2)).beginPrimaryRequest();
        verify(ollamaClient, times(2)).finishPrimaryRequest();
    }

    @Test
    void llmPlannedFollowUpSearchMergesAdditionalEvidenceBeforeAnswering() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult indexing = result(
                "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                "method",
                "runIndex",
                0.84,
                "runIndex scans files, parses chunks, embeds content, and stores chunks"
        );
        CodeSearchResult answering = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.80,
                "askPrioritized retrieves evidence, builds context, and calls the LLM"
        );

        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"overview\",\"confidence\":0.9,\"queries\":[\"indexing to code rag answer flow\"],\"reason\":\"broad flow question\"}"),
                        chat("{\"enough\":false,\"missingAreas\":[\"answer generation\"],\"followUpQueries\":[\"runtime RAG retrieval context construction model answer generation\"],\"queryAreas\":[\"answer generation\"],\"reason\":\"initial evidence lacks answer generation\"}")
                );
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    if (query.contains("runtime RAG retrieval context construction model answer generation")) {
                        return List.of(answering);
                    }
                    return List.of(indexing);
                });
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Indexing is handled by CodeIndexingService and answer generation by CodeRagService [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "내 서비스의 인덱싱부터 RAG 답변까지 흐름을 설명해줘",
                "overview",
                4
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::filePath)
                .contains(
                        "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                        "backend/src/main/java/com/learnbot/service/CodeRagService.java"
                );
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("LLM-planned follow-up retrieval"));
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("followUpQueriesUsed=1").contains("answer generation"));
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("queryAreas=[answer generation]"));
        verify(searchService, atLeastOnce()).search(isNull(), argThat(query -> query.contains("runtime RAG retrieval context construction model answer generation")), anyInt(), anyList(), isNull());
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
    void conversationalAutoModeDoesNotInheritBroadPreviousModeWhenQuestionHasNoModeKeyword() {
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
        CodeSearchResult result = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.72
        );
        CodeSearchResult pinned = resultWithId(
                pinnedChunkId,
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.25,
                "private CodeAskResponse askPrioritized(...) { return fallbackAnswer(...); }"
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "more detail",
                List.of(new RagConversationTurnContext("What is affected?", "Impact answer [1]", "impact", new ObjectMapper().createArrayNode())),
                List.of(new CodeConversationAnchor(
                        pinnedChunkId,
                        pinned.filePath(),
                        pinned.symbolName(),
                        pinned.className(),
                        pinned.methodName(),
                        pinned.lineStart(),
                        pinned.lineEnd()
                )),
                List.of(),
                true
        );

        when(codeRepository.findActiveChunksByIds(isNull(), anyList(), anyList(), isNull())).thenReturn(List.of(pinned));
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

        assertThat(response.mode()).isEqualTo("method");
    }

    @Test
    void conversationalAutoModeInheritsNarrowPreviousModeWhenQuestionHasNoModeKeyword() {
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
                List.of(new RagConversationTurnContext("Explain this method", "Method answer [1]", "method", new ObjectMapper().createArrayNode())),
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

        assertThat(response.mode()).isEqualTo("method");
    }

    @Test
    void conversationalAutoModeUsesLocateOnlyWhenLocationKeywordIsExplicit() {
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
                "line",
                List.of(new RagConversationTurnContext("Explain this method", "Method answer [1]", "overview", new ObjectMapper().createArrayNode())),
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
                "line?",
                "",
                null,
                context
        );

        assertThat(response.mode()).isEqualTo("locate");
    }

    @Test
    void conversationalAutoFallbackForClassAnchorAvoidsLocateWithoutLocationKeyword() {
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
                "class",
                "",
                0.25,
                "public class CodeRagService { }"
        );
        CodeSearchResult result = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "class",
                "",
                0.72
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "why",
                List.of(new RagConversationTurnContext("Overview", "Overview answer [1]", "overview", new ObjectMapper().createArrayNode())),
                List.of(new CodeConversationAnchor(
                        pinnedChunkId,
                        pinned.filePath(),
                        pinned.symbolName(),
                        pinned.className(),
                        "",
                        pinned.lineStart(),
                        pinned.lineEnd()
                )),
                List.of(),
                true
        );

        when(codeRepository.findActiveChunksByIds(isNull(), anyList(), anyList(), isNull())).thenReturn(List.of(pinned));
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.askConversational(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "why?",
                "",
                null,
                context
        );

        assertThat(response.mode()).isEqualTo("reasoning");
    }

    @Test
    void implementationReasonQuestionUsesReasoningModeAndPromptGuidance() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult controller = result("backend/src/main/java/com/learnbot/web/AuthController.java", "method", "login", 0.72);
        CodeSearchResult serviceResult = result("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.68);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(controller, serviceResult));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("구현 의도는 컨트롤러와 서비스 책임을 분리하려는 구조로 보입니다 [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "로그인 로직은 왜 컨트롤러가 아니라 서비스에서 처리해?",
                "auto",
                null
        );

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(systemPrompt.capture(), anyString());
        assertThat(response.mode()).isEqualTo("reasoning");
        assertThat(systemPrompt.getValue()).contains("inferred design intent");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("REASONING"));
    }

    @Test
    void reasoningModeFallsBackWithCitationsWhenModelAnswerIsUncited() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult result = result("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.72);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("서비스에 있어서 좋아 보입니다."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "이 구현 의도가 뭐야?",
                "auto",
                null
        );

        assertThat(response.mode()).isEqualTo("reasoning");
        assertThat(response.answer()).contains("구현 의도");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("검색 근거 기반 답변으로 대체"));
    }

    @Test
    void explicitLocateStillWinsForLocationQuestions() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, new LearnBotProperties());
        CodeSearchResult result = result("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.72);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "로그인 구현 파일 어디 있어?",
                "auto",
                null
        );

        assertThat(response.mode()).isEqualTo("locate");
        assertThat(response.answer()).contains("후보 위치");
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

    @Test
    void streamingReplacesVisibleAnswerWhenSelfCheckWouldFallback() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult result = result("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.72);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(
                        streamDelta("The streamed code answer is useful but lacks a citation.", false),
                        streamDelta("", true)
                ));

        StringBuilder visible = new StringBuilder();
        CodeAskResponse response = service.askStreaming(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does login work?",
                "overview",
                4,
                new CodeRagService.CodeAnswerStreamSink() {
                    @Override
                    public void onEvidence(List<com.learnbot.dto.CodeEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                        visible.append(text);
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                        visible.setLength(0);
                        visible.append(answer);
                    }
                }
        );

        assertThat(visible.toString()).contains("[1]");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.answer()).doesNotContain("lacks a citation");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("fallback=true"));
    }

    @Test
    void diagnosticsIncludeOriginalQualityFailureReasonWhenAnswerFallsBack() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult result = result("backend/src/main/java/com/learnbot/service/AuthService.java", "method", "login", 0.72);

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString()))
                .thenReturn(chat("This answer has enough words but no citation."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does login work?",
                "overview",
                4
        );

        assertThat(response.answer()).contains("[1]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("fallback=true"));
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note)
                .contains("LLM answer quality trace")
                .contains("initialFailureReason=missing citation")
                .contains("initialCitedReferences=0")
                .contains("initialPreview=\"This answer has enough words but no citation.\""));
    }

    @Test
    void streamingUsesStatusEventsCompactContextAndNoDefaultOutputLimit() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        List<CodeSearchResult> results = List.of(
                result("backend/AuthController.java", "method", "login", 0.90, "public LoginResponse login() { return authService.login(); }"),
                result("backend/AuthService.java", "method", "login", 0.85, "public LoginResponse login() { authenticate(); issueToken(); }"),
                result("backend/SessionService.java", "method", "issue", 0.80, "public Token issue() { return tokenService.issue(); }"),
                result("backend/AuditService.java", "method", "record", 0.70, "public void record() { auditRepository.save(); }"),
                result("backend/NotificationService.java", "method", "notifyLogin", 0.60, "public void notifyLogin() { publisher.publish(); }"),
                result("backend/MetricsService.java", "method", "recordLogin", 0.50, "public void recordLogin() { meter.increment(); }")
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(results);
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.streamChat(anyString(), anyString(), eq(0)))
                .thenReturn(Flux.just(streamDelta("Login calls the controller and service path [1][2].", false), streamDelta("", true)));

        StringBuilder statuses = new StringBuilder();
        CodeAskResponse response = service.askStreaming(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does login call flow work?",
                "flow",
                6,
                new CodeRagService.CodeAnswerStreamSink() {
                    @Override
                    public void onStatus(String stage, String message) {
                        statuses.append(stage).append("|");
                    }

                    @Override
                    public void onEvidence(List<com.learnbot.dto.CodeEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                    }
                }
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).streamChat(anyString(), promptCaptor.capture(), eq(0));
        assertThat(promptCaptor.getValue()).contains("Key excerpt:");
        assertThat(statuses.toString()).contains("retrieval_started|", "evidence_ready|", "llm_started|");
        assertThat(response.evidence()).hasSize(6);
    }

    @Test
    void streamingContinuesWhenModelStopsByLength() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult result = result(
                "backend/AuthService.java",
                "method",
                "login",
                0.82,
                "public LoginResponse login() { authenticate(); issueToken(); audit(); }"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.streamChat(anyString(), anyString(), eq(0)))
                .thenReturn(Flux.just(
                        streamDelta("Login first authenticates the user and starts token issuance [1].", false),
                        streamDelta("", "length", true)
                ));
        when(ollamaClient.streamChat(anyString(), anyString(), eq(900)))
                .thenReturn(Flux.just(
                        streamDelta("It then records audit information ", false),
                        streamDelta("and returns the login response, completing the flow [1].", false),
                        streamDelta("", "stop", true)
                ));

        StringBuilder visible = new StringBuilder();
        StringBuilder replacements = new StringBuilder();
        StringBuilder statuses = new StringBuilder();
        CodeAskResponse response = service.askStreaming(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does login call flow work?",
                "flow",
                4,
                new CodeRagService.CodeAnswerStreamSink() {
                    @Override
                    public void onStatus(String stage, String message) {
                        statuses.append(stage).append("|");
                    }

                    @Override
                    public void onEvidence(List<com.learnbot.dto.CodeEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                        visible.append(text);
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                        replacements.append(reason).append("|");
                    }
                }
        );

        assertThat(response.answer()).contains("starts token issuance [1]", "completing the flow [1]");
        assertThat(visible.toString()).isEqualTo(response.answer());
        assertThat(replacements).isEmpty();
        assertThat(statuses.toString()).contains("continuation_started|");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("automatically continued"));
    }

    @Test
    void diagnosticsReportInvalidCodeCitationReference() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult result = result(
                "backend/AuthService.java",
                "method",
                "login",
                0.82,
                "public LoginResponse login() { authenticate(); issueToken(); }"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(
                        streamDelta("Login authenticates the user and issues a token [2].", false),
                        streamDelta("", true)
                ));

        CodeAskResponse response = service.askStreaming(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does login work?",
                "method",
                4,
                new CodeRagService.CodeAnswerStreamSink() {
                    @Override
                    public void onEvidence(List<com.learnbot.dto.CodeEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                    }
                }
        );

        assertThat(response.answer()).contains("[1]");
        assertThat(response.answer()).doesNotContain("[2]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("RAG quality trace").contains("invalidCitationRefs=0"));
    }

    @Test
    void diagnosticsReportCodeEvidenceSelectionSummary() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        List<CodeSearchResult> results = List.of(
                result("backend/AuthController.java", "method", "login", 0.90, "public LoginResponse login() { return authService.login(); }"),
                result("backend/AuthService.java", "method", "login", 0.85, "public LoginResponse login() { authenticate(); issueToken(); }")
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(results);
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Login starts in the controller and calls the auth service [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does login work?",
                "flow",
                4
        );

        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("Evidence selection").contains("selected=2").contains("chunkTypes={method=2}"));
    }

    @Test
    void deterministicCodePlannerAddsPatchIntentQueries() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult controller = result(
                "backend/AuthController.java",
                "method",
                "login",
                0.90,
                "public LoginResponse login() { return authService.login(); }"
        );
        CodeSearchResult serviceResult = result(
                "backend/AuthService.java",
                "method",
                "login",
                0.88,
                "public LoginResponse login() { validatePassword(); issueToken(); }"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1, String.class);
                    if (query.contains("target files methods validation tests")) {
                        return List.of(controller);
                    }
                    if (query.contains("bug cause fix location related callers")) {
                        return List.of(serviceResult);
                    }
                    return List.of();
                });
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The likely patch area is the login controller and auth service [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Fix the login bug and identify impacted tests",
                "auto",
                4
        );

        assertThat(response.evidence()).hasSize(2);
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("Code query planner").contains("intent=PATCH_INTENT").contains("auxiliaryQueries=2"));
        verify(searchService).search(isNull(), eq("Fix the login bug and identify impacted tests"), anyInt(), anyList(), isNull());
        verify(searchService).search(isNull(), argThat(query -> query.contains("target files methods validation tests")), anyInt(), anyList(), isNull());
        verify(searchService).search(isNull(), argThat(query -> query.contains("bug cause fix location related callers")), anyInt(), anyList(), isNull());
    }

    @Test
    void overviewPrioritizesRuntimeImplementationEvidenceOverTestsAndLocalAgentNoise() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        properties.getRag().getPipeline().setCodeContextLimit(2);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult testEvidence = result(
                "backend/src/test/java/com/learnbot/service/CodeRagServiceTest.java",
                "method",
                "diagnosticsReportCodeEvidenceSelectionSummary",
                0.95,
                "test explains indexing rag answer flow"
        );
        CodeSearchResult localAgentEvidence = result(
                "backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java",
                "method",
                "queue",
                0.93,
                "local agent queue handles patch tools"
        );
        CodeSearchResult indexingService = result(
                "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                "method",
                "runIndex",
                0.72,
                "runIndex scans code files, parses chunks, embeds them, and stores code chunks"
        );
        CodeSearchResult ragService = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.70,
                "askPrioritized retrieves code evidence, builds context, and asks the LLM"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(testEvidence, localAgentEvidence, indexingService, ragService));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Indexing is handled by CodeIndexingService and answers are generated by CodeRagService [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "내 서비스의 인덱싱부터 RAG 답변까지 흐름을 설명해줘",
                "overview",
                4
        );

        assertThat(response.evidence())
                .extracting(evidence -> evidence.filePath())
                .containsExactlyInAnyOrder(
                        "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                        "backend/src/main/java/com/learnbot/service/CodeRagService.java"
                );
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("sourceRoles={main=2}").contains("runtimeRoles={service=2}"));
    }

    @Test
    void llmCodeEvidenceAdjudicationCanChooseLowerScoredButBetterEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        properties.getRag().getPipeline().setCodeContextLimit(1);
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationEnabled(true);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult broadService = result(
                "backend/src/main/java/com/learnbot/service/RagService.java",
                "method",
                "ask",
                0.92,
                "document rag answer generation flow"
        );
        CodeSearchResult exactCodeService = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.70,
                "code rag answer generation retrieves code evidence and builds code context"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(broadService, exactCodeService));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(chat("{\"selected\":[{\"index\":2,\"score\":0.96,\"reason\":\"direct code rag flow evidence\"}],\"reason\":\"prefer exact code rag service\"}"));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Code RAG answer generation is handled in CodeRagService [1]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "코드 RAG 답변 생성 흐름은 어디서 처리돼?",
                "overview",
                2
        );

        assertThat(response.evidence())
                .extracting(evidence -> evidence.filePath())
                .containsExactly("backend/src/main/java/com/learnbot/service/CodeRagService.java");
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("llmAdjudicated=1"));
    }

    @Test
    void llmCodeEvidenceAdjudicationFallsBackToDeterministicRankingWhenJudgeFails() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        properties.getRag().getPipeline().setCodeContextLimit(1);
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationEnabled(true);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult top = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.90,
                "code rag answer generation retrieves code evidence and builds code context"
        );
        CodeSearchResult lower = result(
                "backend/src/main/java/com/learnbot/service/RagService.java",
                "method",
                "ask",
                0.70,
                "document rag answer generation flow"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(top, lower));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenThrow(new RuntimeException("judge unavailable"));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Code RAG answer generation is handled in CodeRagService [1]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "코드 RAG 답변 생성 흐름은 어디서 처리돼?",
                "overview",
                2
        );

        assertThat(response.evidence())
                .extracting(evidence -> evidence.filePath())
                .containsExactly("backend/src/main/java/com/learnbot/service/CodeRagService.java");
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("llmAdjudicated=0"));
    }

    @Test
    void followUpRetrievalGroundsInventedServiceFileNamesToRuntimeRagEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult indexing = result(
                "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                "file_section",
                null,
                0.86,
                "CodeIndexingService scans files and creates chunks for indexing"
        );
        CodeSearchResult conversationStore = result(
                "backend/src/main/java/com/learnbot/repository/RagConversationRepository.java",
                "file_section",
                null,
                0.82,
                "RagConversationRepository stores conversation turns and evidence JSON"
        );
        CodeSearchResult finalGate = result(
                "frontend/src/components/code/mutationFinalResponseHandoffGate.js",
                "method",
                "buildMutationFinalResponseHandoffGateView",
                0.80,
                "Frontend gate renders final response handoff status"
        );
        CodeSearchResult runtimeRag = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.20,
                "CodeRagService retrieves code evidence, builds context, calls the LLM model, validates citations, and returns the RAG answer response"
        );

        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"overview\",\"confidence\":0.9,\"queries\":[\"indexing to code rag answer flow\"],\"reason\":\"broad flow question\"}"),
                        chat("{\"enough\":false,\"missingAreas\":[\"retrieval/search pipeline\",\"context construction\",\"answer generation flow\"],\"followUpQueries\":[\"backend/src/main/java/com/learnbot/service/RetrievalService.java: how does retrieval query indexed chunks?\",\"backend/src/main/java/com/learnbot/service/ContextConstructionService.java: how is retrieved context merged?\",\"backend/src/main/java/com/learnbot/service/AnswerGenerationService.java: how does the model generate answers from context?\"],\"queryAreas\":[\"retrieval pipeline\",\"context construction\",\"answer generation with context\"],\"reason\":\"initial evidence lacks runtime RAG answer flow\"}")
                );
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    return List.of(indexing, conversationStore, finalGate);
                });
        when(searchService.runtimeRoleSearch(isNull(), anyString(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(runtimeRag));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The runtime RAG answer flow is handled by CodeRagService after indexing evidence is available [1]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain indexing to RAG response flow",
                "overview",
                3
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::filePath)
                .contains("backend/src/main/java/com/learnbot/service/CodeRagService.java");
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("followUpQueriesUsed=3"));
        verify(searchService, atLeastOnce()).runtimeRoleSearch(
                isNull(),
                argThat(pattern -> pattern.contains("rag") && pattern.contains("context")),
                argThat(pattern -> pattern.contains("answer") || pattern.contains("retriev")),
                anyInt(),
                anyList(),
                isNull()
        );
    }

    @Test
    void ragFlowQuestionsKeepRuntimeRetrievalAndAnswerEvidenceOverSupportEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult indexing = result(
                "backend/src/main/java/app/service/RepositoryIndexingService.java",
                "file_section",
                null,
                0.90,
                "RepositoryIndexingService scans source files, computes content hashes, creates chunks, and tracks indexing progress"
        );
        CodeSearchResult supportGate = result(
                "frontend/src/components/code/finalResponseGate.js",
                "method",
                "finalResponseGateView",
                0.88,
                "Frontend gate renders final response status and disabled text"
        );
        CodeSearchResult turnStore = result(
                "backend/src/main/java/app/repository/ConversationTurnRepository.java",
                "file_section",
                null,
                0.84,
                "ConversationTurnRepository stores conversation turns, citations, evidence, diagnostics, and metadata"
        );
        CodeSearchResult retrieval = result(
                "backend/src/main/java/app/service/RagRetrievalPipeline.java",
                "method",
                "retrieveContext",
                0.35,
                "RagRetrievalPipeline searches indexed chunks, ranks evidence, and builds retrieved context for the query"
        );
        CodeSearchResult answer = result(
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "method",
                "generateAnswer",
                0.34,
                "RagAnswerPipeline builds the prompt from retrieved context, calls the chat model, validates citations, and returns the answer response"
        );

        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"overview\",\"confidence\":0.9,\"queries\":[\"indexing to rag answer flow\"],\"reason\":\"broad flow question\"}"),
                        chat("{\"enough\":false,\"missingAreas\":[\"retrieval/search pipeline\",\"answer generation flow\"],\"followUpQueries\":[\"RAG retrieval pipeline context construction\",\"RAG answer generation model response\"],\"queryAreas\":[\"retrieval pipeline\",\"answer generation\"],\"reason\":\"initial evidence lacks runtime retrieval and answer generation\"}")
                );
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(indexing, supportGate, turnStore));
        when(searchService.runtimeRoleSearch(isNull(), anyString(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(retrieval, answer));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The RAG flow retrieves indexed context and then generates the answer from that context [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain indexing to RAG response flow",
                "overview",
                5
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::filePath)
                .contains(
                        "backend/src/main/java/app/service/RagRetrievalPipeline.java",
                        "backend/src/main/java/app/service/RagAnswerPipeline.java"
                );
        List<String> filePaths = response.evidence().stream()
                .map(CodeEvidence::filePath)
                .toList();
        assertThat(filePaths.indexOf("backend/src/main/java/app/service/RagRetrievalPipeline.java"))
                .isLessThan(filePaths.indexOf("frontend/src/components/code/finalResponseGate.js"));
        assertThat(filePaths.indexOf("backend/src/main/java/app/service/RagAnswerPipeline.java"))
                .isLessThan(filePaths.indexOf("frontend/src/components/code/finalResponseGate.js"));
    }

    private static OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }

    private static OllamaClient.ChatStreamDelta streamDelta(String content, boolean done) {
        return streamDelta(content, done ? "stop" : null, done);
    }

    private static OllamaClient.ChatStreamDelta streamDelta(String content, String doneReason, boolean done) {
        return new OllamaClient.ChatStreamDelta(content, doneReason, done, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
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
