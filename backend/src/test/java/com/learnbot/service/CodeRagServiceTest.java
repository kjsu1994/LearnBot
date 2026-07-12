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

import java.time.Duration;
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
    void retrievalOperationIdentityIgnoresClaimGroupingButPreservesGraphTraversalSemantics() {
        RagPipelineService.CodeSearchOperation first = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "graph", "claim-a", "", "", "chunk-1",
                null, null, null, List.of("CALLS", "REFERENCES"), "OUT", 2
        );
        RagPipelineService.CodeSearchOperation regrouped = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "graph", "claim-b", "", "", "chunk-1",
                null, null, null, List.of("REFERENCES", "CALLS"), "OUT", 2
        );
        RagPipelineService.CodeSearchOperation differentTraversal = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "graph", "claim-a", "", "", "chunk-1",
                null, null, null, List.of("CALLS"), "IN", 1
        );

        assertThat(CodeRagService.retrievalOperationKey(first))
                .isEqualTo(CodeRagService.retrievalOperationKey(regrouped));
        assertThat(CodeRagService.retrievalOperationKey(first))
                .isNotEqualTo(CodeRagService.retrievalOperationKey(differentTraversal));
    }
    @Test
    void commitQuestionsUseModelRouteInsteadOfServerRegexBypass() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        CommitInsightService commitInsightService = mock(CommitInsightService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
                        chat("{\"enough\":false,\"hypothesis\":\"answer generation needs direct evidence\",\"hypothesisVersion\":1,\"premiseDisposition\":\"UNRESOLVED\",\"terminationRequest\":\"NONE\",\"claimResults\":[{\"claimId\":\"claim-1\",\"status\":\"UNRESOLVED\",\"evidenceIds\":[],\"supportedClaim\":\"\",\"limitations\":[],\"supersededByClaimId\":\"\"}],\"missingAreas\":[\"claim-1\"],\"operations\":[{\"operationId\":\"op-answer\",\"claimIds\":[\"claim-1\"],\"originEvidenceIds\":[],\"type\":\"keyword_search\",\"query\":\"runtime RAG retrieval context construction model answer generation\",\"area\":\"answer generation\",\"evidenceGroup\":\"claim-1\"}],\"followUpQueries\":[],\"queryAreas\":[],\"requiredEvidenceGroups\":[\"claim-1\"],\"checklist\":[{\"claimId\":\"claim-1\",\"evidenceGroup\":\"claim-1\",\"goal\":\"prove answer generation\",\"actor\":\"Code RAG\",\"action\":\"generate\",\"object\":\"answer\",\"expectedOutcome\":\"model answer is returned\",\"scopeHints\":[\"service\"],\"requiredEvidenceKinds\":[\"DIRECT_SOURCE\"],\"queries\":[]}],\"coverageSelections\":[],\"reason\":\"initial evidence lacks answer generation\"}")
                );
        stubRetrievalIterations(
                ollamaClient,
                "{\"enough\":false,\"hypothesis\":\"answer generation needs direct evidence\",\"hypothesisVersion\":1,\"premiseDisposition\":\"UNRESOLVED\",\"terminationRequest\":\"NONE\",\"claimResults\":[{\"claimId\":\"claim-1\",\"status\":\"UNRESOLVED\",\"evidenceIds\":[],\"supportedClaim\":\"\",\"limitations\":[],\"supersededByClaimId\":\"\"}],\"missingAreas\":[\"claim-1\"],\"operations\":[{\"operationId\":\"op-answer\",\"claimIds\":[\"claim-1\"],\"originEvidenceIds\":[],\"type\":\"keyword_search\",\"query\":\"runtime RAG retrieval context construction model answer generation\",\"area\":\"answer generation\",\"evidenceGroup\":\"claim-1\"}],\"followUpQueries\":[],\"queryAreas\":[],\"requiredEvidenceGroups\":[\"claim-1\"],\"checklist\":[{\"claimId\":\"claim-1\",\"evidenceGroup\":\"claim-1\",\"goal\":\"prove answer generation\",\"actor\":\"Code RAG\",\"action\":\"generate\",\"object\":\"answer\",\"expectedOutcome\":\"model answer is returned\",\"scopeHints\":[\"service\"],\"requiredEvidenceKinds\":[\"DIRECT_SOURCE\"],\"queries\":[]}],\"coverageSelections\":[],\"reason\":\"initial evidence lacks answer generation\"}",
                "{\"enough\":false,\"hypothesis\":\"answer generation evidence found\",\"hypothesisVersion\":2,\"premiseDisposition\":\"CONFIRMED\",\"terminationRequest\":\"NO_FURTHER_RETRIEVAL\",\"claimResults\":[{\"claimId\":\"claim-1\",\"status\":\"UNRESOLVED\",\"evidenceIds\":[],\"supportedClaim\":\"\",\"limitations\":[],\"supersededByClaimId\":\"\"}],\"missingAreas\":[\"claim-1\"],\"operations\":[],\"followUpQueries\":[],\"queryAreas\":[],\"requiredEvidenceGroups\":[\"claim-1\"],\"checklist\":[{\"claimId\":\"claim-1\",\"evidenceGroup\":\"claim-1\",\"goal\":\"prove answer generation\",\"actor\":\"Code RAG\",\"action\":\"generate\",\"object\":\"answer\",\"expectedOutcome\":\"model answer is returned\",\"scopeHints\":[\"service\"],\"requiredEvidenceKinds\":[\"DIRECT_SOURCE\"],\"queries\":[]}],\"coverageSelections\":[],\"reason\":\"answer generation evidence is now present\"}"
        );
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    return List.of(indexing);
                });
        when(searchService.cheapSearch(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    if (query.contains("runtime RAG retrieval context construction model answer generation")) {
                        return List.of(answering);
                    }
                    return List.of();
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
                assertThat(note).contains("1 LLM-planned Retrieval Iteration(s)"));
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("followUpQueriesUsed=1").contains("followUpCandidatesAdded=1"));
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("llm retrieval iteration 2"));
        verify(searchService, atLeastOnce()).cheapSearch(isNull(), argThat(query -> query.contains("runtime RAG retrieval context construction model answer generation")), anyInt(), anyList(), isNull());
    }

    @Test
    void overviewKeepsEvidenceWhenChatModelFails() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        assertThat(promptCaptor.getValue().length()).isLessThan(9000);
    }

    @Test
    void marksExcerptCompletenessAndKeepsCoreFlowMethodFullWhenBudgetAllows() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        String expandGraphContent = """
                private List<CodeSearchResult> expandGraph(UUID repositoryId, String query, List<CodeSearchResult> ranked,
                                                           int limit, GraphSearchIntent intent) {
                    Map<UUID, CodeSearchResult> expanded = new LinkedHashMap<>();
                    for (CodeSearchResult result : ranked) {
                        merge(expanded, result);
                    }
                    List<UUID> seeds = ranked.stream().map(CodeSearchResult::chunkId).toList();
                    for (CodeSearchResult related : repository.graphRelatedChunks(repositoryId, seeds, graphEdgeTypes(query, intent), 2, "BOTH", limit)) {
                        merge(expanded, boost(related, graphBoost(query, related)));
                    }
                    return expanded.values().stream().sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed()).toList();
                }
                """;
        CodeSearchResult result = result(
                "backend/src/main/java/com/learnbot/service/CodeSearchService.java",
                "method",
                "expandGraph",
                0.92,
                expandGraphContent
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull())).thenReturn(List.of(result));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("expandGraph expands graph-related chunks [1]."));

        service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain the expandGraph search expansion flow",
                "flow",
                4
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("excerptKind=FULL_CHUNK")
                .contains("contentComplete=true")
                .contains("repository.graphRelatedChunks")
                .contains("return expanded.values()");
    }

    @Test
    void locateRewritesUncitedModelAnswerIntoActionableFallback() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
    void answerContextLabelsGenericEvidenceResponsibilities() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);

        CodeSearchResult retrieval = resultWithParser(
                "backend/src/main/java/com/learnbot/service/SearchPipeline.java",
                "method",
                "expandRelatedEvidence",
                0.86,
                "expandRelatedEvidence retrieves query seeds and expands related chunks for source-code context",
                "javaparser"
        );
        CodeSearchResult traversal = resultWithParser(
                "backend/src/main/java/com/learnbot/repository/GraphTraversalStore.java",
                "method",
                "loadRelatedChunks",
                0.84,
                "loadRelatedChunks traverses graph neighbors by edge types, direction, max hop, and graph path score",
                "javaparser"
        );
        CodeSearchResult ranking = resultWithParser(
                "backend/src/main/java/com/learnbot/service/EvidenceScoringService.java",
                "method",
                "scoreEvidence",
                0.82,
                "scoreEvidence applies evidence score, edge weight, intent evidence score, and ranking reason",
                "javaparser"
        );
        CodeSearchResult answerContext = resultWithParser(
                "backend/src/main/java/com/learnbot/service/AnswerContextBuilder.java",
                "method",
                "buildPromptContext",
                0.80,
                "buildPromptContext formats source code context, citations, excerpts, and answer generation prompt",
                "javaparser"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(retrieval, traversal, ranking, answerContext));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Search expansion, traversal, ranking, and answer context are separate responsibilities [1][2][3][4]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain how graph edges affect retrieval expansion, evidence ranking, and answer context",
                "overview",
                4
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, atLeastOnce()).chatResult(anyString(), promptCaptor.capture(), anyInt());
        String prompt = promptCaptor.getAllValues().get(0);
        assertThat(prompt)
                .doesNotContain("evidenceRole=retrieval/search-expansion")
                .doesNotContain("evidencePhase=SEARCH_EXPANSION")
                .doesNotContain("evidencePhase=RANKING")
                .doesNotContain("evidencePhase=ANSWER_GENERATION")
                .doesNotContain("citationKind=direct_code")
                .doesNotContain("evidenceResponsibility=implementation_flow");
        assertThat(response.evidence())
                .anySatisfy(evidence -> assertThat(evidence.metadata())
                        .containsKey("debugHeuristicEvidencePhase")
                        .containsEntry("debugHeuristicCitationKind", "direct_code")
                        .containsKey("debugHeuristicEvidenceResponsibility")
                        .doesNotContainKeys("evidencePhase", "citationKind", "evidenceResponsibility"));
    }

    @Test
    void answerContextSeparatesGenericFallbackScopes() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);

        CodeSearchResult routingFallback = resultWithParser(
                "backend/src/main/java/app/service/RouteDecisionService.java",
                "method",
                "fallback",
                0.86,
                "fallback returns CODE_SEARCH route when router returned unknown route",
                "javaparser"
        );
        CodeSearchResult graphAnalysisFallback = resultWithParser(
                "backend/src/main/java/app/service/GraphBuildService.java",
                "method",
                "buildWithDiagnostics",
                0.84,
                "buildWithDiagnostics catches RuntimeException and records failed semantic graph analyzer diagnostic",
                "javaparser"
        );
        CodeSearchResult searchExpansionFallback = resultWithParser(
                "backend/src/main/java/app/service/SearchExpansionService.java",
                "method",
                "expandGraph",
                0.82,
                "expandGraph catches RuntimeException from graphRelatedChunks and returns ranked search results",
                "javaparser"
        );
        CodeSearchResult answerFallback = resultWithParser(
                "backend/src/main/java/app/service/AnswerFallbackService.java",
                "method",
                "fallbackAnswer",
                0.80,
                "fallbackAnswer builds evidence-based answer when LLM unavailable, missing citation, or low quality answer occurs",
                "javaparser"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(routingFallback, graphAnalysisFallback, searchExpansionFallback, answerFallback));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Fallback mechanisms are separate [1][2][3][4]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain fallback mechanisms for graph analysis, search expansion, and answer generation",
                "overview",
                4
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, atLeastOnce()).chatResult(anyString(), promptCaptor.capture(), anyInt());
        String prompt = promptCaptor.getAllValues().get(0);
        assertThat(prompt)
                .doesNotContain("Evidence validation:")
                .doesNotContain("fallbackScope=ROUTING")
                .doesNotContain("fallbackScope=GRAPH_ANALYSIS")
                .doesNotContain("fallbackScope=SEARCH_EXPANSION")
                .doesNotContain("fallbackScope=ANSWER_GENERATION")
                .doesNotContain("evidenceResponsibility=route_decision")
                .doesNotContain("evidenceResponsibility=analysis_diagnostic")
                .doesNotContain("evidenceResponsibility=search_fallback")
                .doesNotContain("evidenceResponsibility=answer_fallback");
        assertThat(response.evidence())
                .anySatisfy(evidence -> assertThat(evidence.metadata())
                        .containsEntry("debugFallbackScope", "GRAPH_ANALYSIS")
                        .containsEntry("debugHeuristicEvidenceResponsibility", "analysis_diagnostic")
                        .doesNotContainKeys("fallbackScope", "evidenceResponsibility"));
        assertThat(response.evidence())
                .anySatisfy(evidence -> assertThat(evidence.metadata())
                        .containsEntry("debugFallbackScope", "SEARCH_EXPANSION")
                        .containsEntry("debugHeuristicEvidenceResponsibility", "search_fallback")
                        .doesNotContainKeys("fallbackScope", "evidenceResponsibility"));
        assertThat(response.evidence())
                .anySatisfy(evidence -> assertThat(evidence.metadata())
                        .containsEntry("debugFallbackScope", "ANSWER_GENERATION")
                        .containsEntry("debugHeuristicEvidenceResponsibility", "answer_fallback")
                        .doesNotContainKeys("fallbackScope", "evidenceResponsibility"));
    }


    @Test
    void javaGraphFailureQuestionPrefersMatchingDiagnosticStageOverRoslynDiagnostic() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);

        CodeSearchResult answerFallback = resultWithParserAndMetadata(
                "backend/src/main/java/app/service/AnswerFallbackService.java",
                "method",
                "fallbackAnswer",
                0.98,
                "fallbackAnswer repairs final answer generation when model output is unavailable",
                "parser",
                Map.of("language", "java", "indexVersion", "test-index")
        );
        CodeSearchResult roslynDiagnostic = resultWithParserAndMetadata(
                "backend/src/main/java/app/service/RoslynSemanticGraphAnalyzer.java",
                "method",
                "failed",
                0.94,
                "failed records failed semantic graph analyzer diagnostic for Roslyn analysis",
                "roslyn_semantic_model",
                Map.of(
                        "language", "csharp",
                        "analysisDiagnosticStage", "CSHARP_ROSLYN",
                        "analysisDiagnosticAnalyzer", "Roslyn",
                        "analysisDiagnosticStatus", "FAILED"
                )
        );
        CodeSearchResult javaDiagnostic = resultWithParserAndMetadata(
                "backend/src/main/java/app/service/JavaSemanticGraphAnalyzer.java",
                "method",
                "analyzeWithDiagnostics",
                0.31,
                "analyzeWithDiagnostics records failed semantic graph analyzer diagnostic for JavaParser Symbol Solver",
                "javaparser",
                Map.of(
                        "language", "java",
                        "analysisDiagnosticStage", "JAVA_SEMANTIC",
                        "analysisDiagnosticAnalyzer", "JavaParser Symbol Solver",
                        "analysisDiagnosticStatus", "FAILED"
                )
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(answerFallback, roslynDiagnostic, javaDiagnostic));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Java semantic diagnostics should be the primary graph-analysis evidence [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does Java semantic graph analysis failure fallback work?",
                "overview",
                2
        );

        assertThat(response.evidence())
                .anySatisfy(evidence -> assertThat(evidence.metadata())
                        .containsEntry("debugFallbackScope", "GRAPH_ANALYSIS")
                        .containsEntry("analysisDiagnosticStage", "JAVA_SEMANTIC")
                        .containsEntry("analysisDiagnosticLanguage", "java")
                        .containsEntry("analysisDiagnosticAnalyzer", "JavaParser Symbol Solver"));
    }

    @Test
    void confidenceUsesGraphEvidenceScoreWhenRawSearchScoreIsLow() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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

        assertThat(response.mode()).isEqualTo("overview");
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.evidence().get(0).chunkId()).isEqualTo(pinnedChunkId);
        assertThat(response.evidence().get(0).metadata()).containsEntry("conversationPinned", true);
    }

    @Test
    void conversationalAutoModeDoesNotInheritBroadPreviousModeWhenQuestionHasNoModeKeyword() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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

        assertThat(response.mode()).isEqualTo("overview");
    }

    @Test
    void conversationalAutoModeInheritsNarrowPreviousModeWhenQuestionHasNoModeKeyword() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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

        assertThat(response.mode()).isEqualTo("overview");
    }

    @Test
    void conversationalAutoFallbackForClassAnchorAvoidsLocateWithoutLocationKeyword() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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

        assertThat(response.mode()).isEqualTo("overview");
    }

    @Test
    void implementationReasonQuestionUsesReasoningModeAndPromptGuidance() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
                "reasoning",
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
        OllamaClient ollamaClient = mockOllamaClient();
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
                "reasoning",
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
        OllamaClient ollamaClient = mockOllamaClient();
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
                "locate",
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
        OllamaClient ollamaClient = mockOllamaClient();
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
    void plannerFailureDoesNotInjectServerAuthoredPatchQueries() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
                    if (query.equals("Fix the login bug and identify impacted tests")) {
                        return List.of(controller, serviceResult);
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
        verify(searchService).search(isNull(), eq("Fix the login bug and identify impacted tests"), anyInt(), anyList(), isNull());
        verify(searchService, never()).search(isNull(), argThat(query -> query.contains("target files methods validation tests")), anyInt(), anyList(), isNull());
        verify(searchService, never()).search(isNull(), argThat(query -> query.contains("bug cause fix location related callers")), anyInt(), anyList(), isNull());
    }

    @Test
    void overviewPrioritizesRelevantMainImplementationEvidenceOverTests() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
                0.60,
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
                assertThat(note).contains("sourceRoles={main=2}").doesNotContain("runtimeRoles="));
    }

    @Test
    void llmCodeEvidenceAdjudicationCanChooseLowerScoredButBetterEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
        when(ollamaClient.chatResult(anyString(), anyString(), any(OllamaClient.ChatRole.class), anyInt(), any(Duration.class), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"overview\",\"confidence\":0.9,\"queries\":[],\"commitRef\":\"\",\"targetFile\":\"\",\"targetSymbol\":\"\",\"reason\":\"code rag flow\"}"),
                        chat("{\"enough\":true,\"missingAreas\":[],\"followUpQueries\":[],\"queryAreas\":[],\"requiredEvidenceGroups\":[],\"reason\":\"enough\"}"),
                        chat("{\"selected\":[{\"index\":2,\"score\":0.96,\"reason\":\"direct code rag flow evidence\"}],\"reason\":\"prefer exact code rag service\"}"));
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
        OllamaClient ollamaClient = mockOllamaClient();
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
    void llmCodeEvidenceAdjudicationOrderOwnsFinalEvidenceSlate() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        properties.getRag().getPipeline().setCodeContextLimit(2);
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationEnabled(true);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult coverageHelper = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "ensureLlmPlannedCoverage",
                0.95,
                "ensureLlmPlannedCoverage adjusts selected evidence after retrieval"
        );
        CodeSearchResult graphStorage = result(
                "backend/src/main/java/com/learnbot/repository/CodeRepository.java",
                "method",
                "replaceGraph",
                0.71,
                "INSERT INTO code_graph_nodes ... INSERT INTO code_graph_edges ..."
        );
        CodeSearchResult graphAnalyzer = result(
                "backend/src/main/java/com/learnbot/service/JavaSemanticGraphAnalyzer.java",
                "method",
                "addEndpoint",
                0.70,
                "addEndpoint creates EXPOSES_ENDPOINT graph edges and endpoint metadata"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(coverageHelper, graphStorage, graphAnalyzer));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), any(OllamaClient.ChatRole.class), anyInt(), any(Duration.class), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"overview\",\"confidence\":0.9,\"queries\":[],\"commitRef\":\"\",\"targetFile\":\"\",\"targetSymbol\":\"\",\"reason\":\"graph storage question\"}"),
                        chat("{\"enough\":true,\"missingAreas\":[],\"followUpQueries\":[],\"queryAreas\":[],\"requiredEvidenceGroups\":[],\"reason\":\"enough for adjudication\"}"),
                        chat("""
                        {"selected":[
                          {"index":3,"score":0.98,"evidenceKind":"direct_code","implementationPhase":"GRAPH_STORAGE","responsibility":"graph_persistence","coverageGroup":"graph_persistence","mustUse":true,"supportedClaims":["stores graph nodes and edges"],"notSupportedClaims":["performs coverage planning"],"rankReason":"direct storage SQL","reason":"storage implementation"},
                          {"index":1,"score":0.90,"evidenceKind":"direct_code","implementationPhase":"INDEXING","responsibility":"framework_semantics","coverageGroup":"framework_semantics","mustUse":true,"supportedClaims":["builds endpoint graph edges"],"notSupportedClaims":["persists graph tables"],"rankReason":"direct analyzer implementation","reason":"spring graph analyzer"}
                        ],"reason":"storage and analyzer evidence are stronger than helper coverage code"}
                        """));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("Graph storage is handled by CodeRepository and Spring endpoint graph edges are built by JavaSemanticGraphAnalyzer [1][2]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How are Spring graph edges and metadata stored?",
                "overview",
                3
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::filePath)
                .containsExactly(
                        "backend/src/main/java/com/learnbot/repository/CodeRepository.java",
                        "backend/src/main/java/com/learnbot/service/JavaSemanticGraphAnalyzer.java"
                );
        assertThat(response.evidence().get(0).metadata())
                .containsEntry("llmEvidenceSlateRank", 1)
                .containsEntry("llmEvidenceSlateMustUse", true)
                .containsEntry("llmEvidenceCoverageGroup", "graph_persistence");
    }

    @Test
    void llmChecklistCoverageGroupsArePreservedBeyondContextLimit() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        properties.getRag().getPipeline().setCodeContextLimit(3);
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationEnabled(true);
        RagPipelineService pipelineService = new RagPipelineService(
                ollamaClient, properties, mock(RuntimeTuningService.class));
        CodeRagService service = new CodeRagService(
                searchService, referenceService, null, ollamaClient, properties,
                pipelineService, new CodeEvidenceRanker(properties));
        CodeSearchResult controller = result(
                "backend/src/main/java/com/learnbot/web/CodeController.java",
                "method",
                "ask",
                0.80,
                "POST /api/code/ask receives the request and delegates to CodeRagService"
        );
        CodeSearchResult search = result(
                "backend/src/main/java/com/learnbot/service/CodeSearchService.java",
                "method",
                "expandGraph",
                0.79,
                "expandGraph performs graph expansion from retrieved seed chunks"
        );
        CodeSearchResult ranking = result(
                "backend/src/main/java/com/learnbot/service/CodeEvidenceRanker.java",
                "method",
                "rank",
                0.78,
                "rank scores retrieved code evidence before answer context construction"
        );
        CodeSearchResult generation = result(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "chatWithLimit",
                0.77,
                "chatWithLimit calls the model client to generate the final answer"
        );
        assertThat(List.of(controller, search, ranking, generation))
                .allSatisfy(candidate -> assertThat(candidate.metadata()).containsKey("indexVersion"));
        assertThat(List.of(controller, search, ranking, generation))
                .allSatisfy(candidate -> assertThat(candidate.metadata()).containsKey("indexVersion"));

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(controller, search, ranking, generation));
        when(searchService.cheapSearch(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    if (query.contains("request entrypoint")) {
                        return List.of(controller);
                    }
                    if (query.contains("graph expansion")) {
                        return List.of(search);
                    }
                    if (query.contains("ranking")) {
                        return List.of(ranking);
                    }
                    if (query.contains("answer generation")) {
                        return List.of(generation);
                    }
                    return List.of();
                });
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        String groupedEvidenceDecision = """
                {
                  "usable":true,
                  "confidence":0.9,
                  "queries":[],
                  "enough":true,
                  "missingAreas":[],
                  "followUpQueries":[],
                  "queryAreas":[],
                  "requiredEvidenceGroups":["request_intake","graph_traversal","evidence_ranking","answer_generation"],
                  "coverageSelections":[
                    {"evidenceGroup":"request_intake","evidenceIndexes":[1],"supportedClaims":["request entrypoint"],"pipelineStage":"request_intake"},
                    {"evidenceGroup":"graph_traversal","evidenceIndexes":[2],"supportedClaims":["graph expansion"],"pipelineStage":"search_expansion"},
                    {"evidenceGroup":"evidence_ranking","evidenceIndexes":[3],"supportedClaims":["evidence ranking"],"pipelineStage":"evidence_ranking"},
                    {"evidenceGroup":"answer_generation","evidenceIndexes":[4],"supportedClaims":["answer generation"],"pipelineStage":"answer_generation"}
                  ],
                  "checklist":[
                    {"claimId":"request","evidenceGroup":"request_intake","goal":"find request entrypoint","queries":[]},
                    {"claimId":"graph","evidenceGroup":"graph_traversal","goal":"find graph expansion","queries":[]},
                    {"claimId":"ranking","evidenceGroup":"evidence_ranking","goal":"find ranking","queries":[]},
                    {"claimId":"generation","evidenceGroup":"answer_generation","goal":"find answer generation","queries":[]}
                  ],
                  "selected":[
                    {"index":1,"score":0.95,"evidenceKind":"direct_code","implementationPhase":"SEARCH_EXPANSION","responsibility":"request_intake","coverageGroup":"request_intake","mustUse":true,"supportedClaims":["request entrypoint"],"notSupportedClaims":[],"rankReason":"controller endpoint","reason":"controller endpoint"}
                  ],
                  "reason":"all groups are present even though adjudication selected only the primary entrypoint"
                }
                """;
        when(ollamaClient.chatResult(anyString(), anyString(), any(OllamaClient.ChatRole.class), anyInt(), any(Duration.class), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"flow\",\"confidence\":0.9,\"queries\":[],\"commitRef\":\"\",\"targetFile\":\"\",\"targetSymbol\":\"\",\"reason\":\"api flow\"}"),
                        chat(groupedEvidenceDecision),
                        chat(groupedEvidenceDecision),
                        chat(groupedEvidenceDecision));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The API request flows through request intake, graph expansion, ranking, and answer generation [1][2][3][4]."));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain /api/code/ask request flow through Controller, graph expansion, evidence ranking, and answer generation",
                "flow",
                3
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::methodName)
                .contains("ask", "expandGraph", "rank", "chatWithLimit");
        assertThat(response.evidence())
                .allSatisfy(evidence -> assertThat(evidence.metadata())
                        .containsKey("llmValidatedEvidenceGroup"));
        assertThat(response.evidence()).allSatisfy(evidence ->
                assertThat(evidence.metadata()).containsEntry("llmChecklistGroupRequired", true));
        assertThat(response.answer()).contains("API request flows through");
    }


    @Test
    void ragFlowQuestionsKeepRuntimeRetrievalAndAnswerEvidenceOverSupportEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
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
                        chat("{\"enough\":false,\"missingAreas\":[\"retrieval/search pipeline\",\"answer generation flow\"],\"operations\":[{\"type\":\"hybrid_search\",\"query\":\"RAG retrieval pipeline context construction\",\"area\":\"retrieval pipeline\",\"evidenceGroup\":\"orchestration\"},{\"type\":\"hybrid_search\",\"query\":\"RAG answer generation model response\",\"area\":\"answer generation\",\"evidenceGroup\":\"answer_generation\"}],\"followUpQueries\":[],\"queryAreas\":[],\"reason\":\"initial evidence lacks runtime retrieval and answer generation\"}")
                );
        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(indexing, supportGate, turnStore));
        when(searchService.searchWithoutGraph(isNull(), anyString(), anyInt(), anyList(), isNull(), any()))
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
        assertThat(response.evidence())
                .extracting(CodeEvidence::filePath)
                .doesNotContain("frontend/src/components/code/finalResponseGate.js");
    }

    @Test
    void followUpRetrievalExecutesAllPlannedOperationsWhenEvidenceGroupsAreSatisfiedEarly() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);
        CodeSearchResult seed = result(
                "backend/src/main/java/app/web/WorkerController.java",
                "method",
                "status",
                0.50,
                "WorkerController exposes status lookup for tool executions"
        );
        CodeSearchResult claim = result(
                "backend/src/main/java/app/repository/ToolExecutionRepository.java",
                "method",
                "claimNext",
                0.70,
                "claim next pending queue work item and set status RUNNING with lease"
        );
        CodeSearchResult response = result(
                "backend/src/main/java/app/web/WorkerController.java",
                "method",
                "completeTool",
                0.69,
                "receive response result output completion callback acknowledgement from worker"
        );
        CodeSearchResult persistence = result(
                "backend/src/main/java/app/repository/ToolExecutionRepository.java",
                "method",
                "complete",
                0.68,
                "repository save update status output finished complete persisted result"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    return List.of(seed);
                });
        when(searchService.cheapSearch(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1, String.class);
                    if (query.contains("claim response persistence")) {
                        return List.of(claim, response, persistence);
                    }
                    if (query.contains("unused response query")) {
                        return List.of(response, persistence);
                    }
                    return List.of();
                });
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"flow\",\"confidence\":0.9,\"queries\":[],\"reason\":\"worker flow\"}"),
                        chat(workerFlowPlanJson(false)),
                        chat("{\"selected\":[{\"index\":1,\"score\":0.9,\"evidenceKind\":\"direct_code\",\"implementationPhase\":\"SEARCH_EXPANSION\",\"responsibility\":\"data_structure\",\"coverageGroup\":\"queue_claim\",\"mustUse\":true,\"supportedClaims\":[\"claims work\"],\"notSupportedClaims\":[],\"rankReason\":\"claim evidence\",\"reason\":\"claim evidence\"}],\"reason\":\"ok\"}")
                );
        stubRetrievalIterations(
                ollamaClient,
                workerFlowPlanJson(false),
                workerFlowPlanJson(true)
        );
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The worker claims work and stores the response [1]."));

        CodeAskResponse responseAnswer = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "How does a worker claim a tool request and store the response?",
                "flow",
                4
        );

        assertThat(responseAnswer.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("followUpQueriesUsed=2"));
        verify(searchService, times(2))
                .cheapSearch(isNull(), anyString(), anyInt(), anyList(), isNull());
        assertThat(responseAnswer.evidence())
                .extracting(CodeEvidence::filePath)
                .contains("backend/src/main/java/app/repository/ToolExecutionRepository.java");
    }

    @Test
    void ragFlowSelectionReplacesLineWindowsWithStructuredEvidenceWhenAvailable() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);

        CodeSearchResult indexingWindow = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                "file_section",
                null,
                0.96,
                "CodeIndexingService indexing chunk storage line window",
                "line_window"
        );
        CodeSearchResult conversationWindow = resultWithParser(
                "backend/src/main/java/com/learnbot/repository/RagConversationRepository.java",
                "file_section",
                null,
                0.92,
                "RagConversationRepository conversation storage line window",
                "line_window"
        );
        CodeSearchResult askPrioritized = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                0.42,
                "askPrioritized retrieves code evidence, builds context, calls the model, validates citations, and returns a RAG answer",
                "javaparser"
        );
        CodeSearchResult retrieveEvidence = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "retrieveCodeEvidence",
                0.41,
                "retrieveCodeEvidence searches indexed chunks and merges follow-up evidence for the RAG answer flow",
                "javaparser"
        );
        CodeSearchResult search = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeSearchService.java",
                "method",
                "search",
                0.40,
                "search combines keyword and embedding retrieval for indexed code chunks",
                "javaparser"
        );
        CodeSearchResult pipeline = resultWithParser(
                "backend/src/main/java/com/learnbot/service/RagPipelineService.java",
                "method",
                "generateCodeAnswer",
                0.39,
                "generateCodeAnswer builds the prompt and asks the chat model to answer with citations",
                "javaparser"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(indexingWindow, conversationWindow, askPrioritized, retrieveEvidence, search, pipeline));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain indexing to RAG response flow",
                "overview",
                6
        );

        long lineWindows = response.evidence().stream()
                .filter(evidence -> "line_window".equals(String.valueOf(evidence.metadata().get("parser"))))
                .count();
        long structured = response.evidence().stream()
                .filter(evidence -> List.of("method", "function", "class", "record", "constructor").contains(evidence.chunkType()))
                .count();
        assertThat(structured).isGreaterThanOrEqualTo(4);
        assertThat(lineWindows).isLessThanOrEqualTo(2);
        assertThat(response.evidence())
                .extracting(CodeEvidence::methodName)
                .contains("askPrioritized", "retrieveCodeEvidence");
    }

    @Test
    void llmCoveragePlanCanReplaceMultipleSupportEvidenceWithoutImmutableListFailure() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);

        CodeSearchResult conversationStore = resultWithParser(
                "backend/src/main/java/com/learnbot/repository/RagConversationRepository.java",
                "method",
                "saveTurn",
                0.99,
                "RagConversationRepository stores previous answer and conversation metadata",
                "javaparser"
        );
        CodeSearchResult finalGate = resultWithParser(
                "frontend/src/components/code/finalResponseGate.js",
                "function",
                "finalResponseGateView",
                0.98,
                "final response gate view support component",
                "regex_symbol"
        );
        CodeSearchResult retrieval = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeSearchService.java",
                "method",
                "search",
                0.20,
                "search retrieves indexed code chunks and returns query context evidence with citations",
                "javaparser"
        );
        CodeSearchResult answer = resultWithParser(
                "backend/src/main/java/com/learnbot/service/RagPipelineService.java",
                "method",
                "generateCodeAnswer",
                0.19,
                "generateCodeAnswer builds the prompt and calls the LLM model for answer generation",
                "javaparser"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(conversationStore, finalGate, retrieval, answer));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"overview\",\"confidence\":0.9,\"queries\":[],\"reason\":\"flow question\"}"),
                        chat("{\"enough\":false,\"missingAreas\":[\"search evidence chunks\",\"answer generation model\"],\"followUpQueries\":[\"search evidence chunks\",\"answer generation model\"],\"queryAreas\":[\"search evidence chunks\",\"answer generation model\"],\"reason\":\"runtime areas missing\"}")
                );
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain RAG retrieval context and answer generation flow",
                "overview",
                2
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::methodName)
                .contains("search", "generateCodeAnswer");
    }


    @Test
    void llmCoveragePlanSelectsIndexingPersistenceAndGraphStorageEvidence() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeReferenceService referenceService = mock(CodeReferenceService.class);
        OllamaClient ollamaClient = mockOllamaClient();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        CodeRagService service = new CodeRagService(searchService, referenceService, ollamaClient, properties);

        CodeSearchResult graphSearchSupport = resultWithParser(
                "backend/src/main/java/com/learnbot/repository/DocumentRepository.java",
                "method",
                "graphExpandedChunks",
                0.99,
                "support query for graph expanded chunks",
                "javaparser"
        );
        CodeSearchResult overviewSupport = resultWithParser(
                "backend/src/main/java/com/learnbot/dto/IndexingJobSummary.java",
                "record",
                null,
                0.98,
                "indexing job summary DTO",
                "javaparser"
        );
        CodeSearchResult indexing = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeIndexingService.java",
                "method",
                "runIndexing",
                0.30,
                "runIndexing orchestrates file scan, parser chunk generation, embedding, repository addChunks, and graph build",
                "javaparser"
        );
        CodeSearchResult chunkPersistence = resultWithParser(
                "backend/src/main/java/com/learnbot/repository/CodeRepository.java",
                "method",
                "addChunks",
                0.29,
                "addChunks persists generated code chunks with JDBC batch insert into code_chunks table",
                "javaparser"
        );
        CodeSearchResult graphBuild = resultWithParser(
                "backend/src/main/java/com/learnbot/service/CodeGraphBuilder.java",
                "method",
                "buildWithDiagnostics",
                0.28,
                "buildWithDiagnostics builds graph nodes and edges from class method call reference relationships",
                "javaparser"
        );
        CodeSearchResult graphPersistence = resultWithParser(
                "backend/src/main/java/com/learnbot/repository/CodeRepository.java",
                "method",
                "replaceGraph",
                0.27,
                "replaceGraph stores code_graph_nodes and code_graph_edges rows with insert statements",
                "javaparser"
        );

        when(searchService.search(isNull(), anyString(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(graphSearchSupport, overviewSupport, indexing, chunkPersistence, graphBuild, graphPersistence));
        when(searchService.identifiersFrom(anyString())).thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY), anyInt(), any()))
                .thenReturn(
                        chat("{\"route\":\"CODE_OVERVIEW_FLOW\",\"mode\":\"flow\",\"confidence\":0.9,\"queries\":[],\"reason\":\"indexing graph flow\"}"),
                        chat("{\"enough\":false,\"missingAreas\":[\"indexing chunk generation\",\"graph build diagnostics\",\"code graph storage\"],\"followUpQueries\":[\"indexing chunk generation\",\"graph build diagnostics\",\"code graph storage\"],\"queryAreas\":[\"indexing chunk generation\",\"graph build diagnostics\",\"code graph storage\"],\"reason\":\"need graph build and storage\"}")
                );
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("model unavailable"));

        CodeAskResponse response = service.ask(
                null,
                null,
                List.of(SecurityRepository.DEFAULT_SPACE_ID),
                "Explain code repository indexing flow from file chunk generation to code_graph_nodes and code_graph_edges storage",
                "flow",
                4
        );

        assertThat(response.evidence())
                .extracting(CodeEvidence::methodName)
                .contains("runIndexing", "buildWithDiagnostics", "replaceGraph");
    }


    private static String workerFlowPlanJson(boolean stop) {
        String operations = stop ? "[]" : """
                [{"operationId":"op-claim","claimIds":["claim-1"],"originEvidenceIds":[],"type":"keyword_search","query":"claim response persistence","area":"claim response persistence","evidenceGroup":"claim-1"},{"operationId":"op-response","claimIds":["claim-2"],"originEvidenceIds":[],"type":"keyword_search","query":"unused response query","area":"response","evidenceGroup":"claim-2"}]
                """.trim();
        return """
                {"enough":false,"hypothesis":"worker claim and response flow","hypothesisVersion":1,"premiseDisposition":"UNRESOLVED","terminationRequest":"%s","claimResults":[{"claimId":"claim-1","status":"UNRESOLVED","evidenceIds":[],"supportedClaim":"","limitations":[],"supersededByClaimId":""},{"claimId":"claim-2","status":"UNRESOLVED","evidenceIds":[],"supportedClaim":"","limitations":[],"supersededByClaimId":""}],"missingAreas":["claim-1","claim-2"],"operations":%s,"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["claim-1","claim-2"],"checklist":[{"claimId":"claim-1","evidenceGroup":"claim-1","goal":"prove queued work is claimed","actor":"worker","action":"claim","object":"queued work","expectedOutcome":"work is assigned","scopeHints":["service"],"requiredEvidenceKinds":["DIRECT_SOURCE"],"queries":[]},{"claimId":"claim-2","evidenceGroup":"claim-2","goal":"prove response is stored","actor":"worker","action":"store","object":"response","expectedOutcome":"response is persisted","scopeHints":["repository"],"requiredEvidenceKinds":["DIRECT_SOURCE"],"queries":[]}],"coverageSelections":[],"reason":"need request and response flow"}
                """.formatted(stop ? "NO_FURTHER_RETRIEVAL" : "NONE", operations).trim();
    }

    private static OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }

    private OllamaClient mockOllamaClient() {
        return mock(OllamaClient.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if ("chatResult".equals(invocation.getMethod().getName())
                    && arguments.length == 6
                    && arguments[0] instanceof String systemPrompt
                    && systemPrompt.contains("judge whether current code RAG evidence")) {
                return chat("{\"enough\":true,\"missingAreas\":[],\"operations\":[],\"followUpQueries\":[],\"queryAreas\":[],\"requiredEvidenceGroups\":[],\"reason\":\"test evidence is sufficient\"}");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private void stubRetrievalIterations(OllamaClient ollamaClient, String... responses) {
        OllamaClient.ChatResult[] results = java.util.Arrays.stream(responses)
                .map(CodeRagServiceTest::chat)
                .toArray(OllamaClient.ChatResult[]::new);
        when(ollamaClient.chatResult(
                argThat(prompt -> prompt != null && prompt.contains("judge whether current code RAG evidence")),
                anyString(),
                any(OllamaClient.ChatRole.class),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(results[0], java.util.Arrays.copyOfRange(results, 1, results.length));
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
                Map.of("language", "java", "indexVersion", "test-index")
        );
    }

    private CodeSearchResult resultWithParser(String filePath, String chunkType, String methodName, double score, String content, String parser) {
        return resultWithParserAndMetadata(filePath, chunkType, methodName, score, content, parser, Map.of("language", "java"));
    }

    private CodeSearchResult resultWithParserAndMetadata(
            String filePath,
            String chunkType,
            String methodName,
            double score,
            String content,
            String parser,
            Map<String, Object> extraMetadata
    ) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("language", "java");
        metadata.put("parser", parser);
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        return new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                filePath,
                chunkType,
                methodName == null ? filePath : methodName,
                methodName == null ? null : "RagFlow",
                methodName,
                "com.learnbot.service",
                null,
                null,
                1,
                10,
                24,
                "File: " + filePath + "\n" + content,
                score,
                Map.copyOf(metadata)
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
