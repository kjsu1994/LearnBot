package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagPipelineServiceTest {
    @Test
    void queryRewriteFallsBackToDeterministicQueriesWhenModelReturnsInvalidJson() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());

        when(ollamaClient.chat(anyString(), anyString())).thenReturn("not json");

        RagPipelineService.QueryPlan plan = service.buildQueryPlan(
                "What changed recently?",
                RagPipelineService.Domain.CODE,
                List.of("latest commit changes")
        );

        assertThat(plan.rewriteUsed()).isFalse();
        assertThat(plan.rewriteFailed()).isTrue();
        assertThat(plan.queries()).contains("What changed recently?", "latest commit changes");
    }

    @Test
    void codeRouteUsesStructuredFormatAndRetriesTruncatedJson() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(
                new OllamaClient.ChatResult("{\"route\":\"CODE_SEARCH\"", "length", true, 20, 12, "http://ollama", "test", "auxiliary", false),
                new OllamaClient.ChatResult("""
                        {"route":"CODE_SEARCH","mode":"flow","confidence":0.8,"queries":["call flow"],"commitRef":"","targetFile":"","targetSymbol":"","reason":"ok"}
                        """, "stop", true, 20, 40, "http://ollama", "test", "auxiliary", false)
        );

        RagPipelineService.CodeRagRouteDecision decision = service.routeCodeRagIntent("call flow", "auto", null, false);

        assertThat(decision.route()).isEqualTo(RagPipelineService.CodeRagRoute.CODE_SEARCH);
        assertThat(decision.mode()).isEqualTo("flow");
        assertThat(decision.queries()).containsExactly("call flow");

        ArgumentCaptor<Object> formatCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ollamaClient, times(2)).chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                formatCaptor.capture()
        );
        assertThat(formatCaptor.getAllValues()).allSatisfy(format -> {
            assertThat(format).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) format).get("type")).isEqualTo("object");
        });
    }

    @Test
    void codeEvidenceAdjudicationAddsLlmEvidenceClassificationMetadata() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationEnabled(true);
        RagPipelineService service = new RagPipelineService(ollamaClient, properties);
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/repository/CodeRepository.java",
                "method",
                "replaceGraph",
                "CodeRepository",
                "replaceGraph",
                "com.learnbot.repository",
                null,
                null,
                1,
                998,
                1065,
                "INSERT INTO code_graph_nodes ... INSERT INTO code_graph_edges ...",
                0.7,
                Map.of()
        );

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"selected":[{"index":1,"score":0.96,"evidenceKind":"direct_code","implementationPhase":"GRAPH_STORAGE","responsibility":"graph_persistence","coverageGroup":"graph_persistence","mustUse":true,"supportedClaims":["persists graph nodes and edges"],"notSupportedClaims":["performs graph traversal"],"rankReason":"direct storage SQL","reason":"storage SQL"}],"reason":"ok"}
                """, "stop", true, 100, 80, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceAdjudication adjudication = service.adjudicateCodeEvidence(
                "How are graph nodes and edges stored?",
                "overview",
                List.of(candidate),
                4
        );

        assertThat(adjudication.used()).isTrue();
        assertThat(adjudication.results().get(0).metadata())
                .containsEntry("llmEvidenceKind", "direct_code")
                .containsEntry("llmImplementationPhase", "GRAPH_STORAGE")
                .containsEntry("llmEvidenceResponsibility", "graph_persistence")
                .containsEntry("llmEvidenceCoverageGroup", "graph_persistence")
                .containsEntry("llmEvidenceSlateRank", 1)
                .containsEntry("llmEvidenceSlateMustUse", true)
                .containsEntry("llmEvidenceClassificationSource", "llm_adjudication");
    }

    @Test
    void codeEvidenceAdjudicationCanSelectBeyondLegacyTopTenCandidates() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        List<CodeSearchResult> candidates = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            candidates.add(new CodeSearchResult(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "LearnBot",
                    "backend/src/main/java/com/learnbot/service/Candidate" + index + ".java",
                    "method",
                    "candidate" + index,
                    "Candidate" + index,
                    "candidate" + index,
                    "com.learnbot.service",
                    null,
                    null,
                    index,
                    10,
                    24,
                    "candidate " + index + " content",
                    1.0 - (index * 0.01),
                    Map.of()
            ));
        }

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"selected":[{"index":12,"score":0.97,"evidenceKind":"direct_code","implementationPhase":"ANSWER_GENERATION","responsibility":"answer_context","coverageGroup":"response_intake","mustUse":true,"supportedClaims":["handles completion response"],"notSupportedClaims":["claims queued work"],"rankReason":"direct completion handler","reason":"best completion evidence"}],"reason":"selected completion evidence"}
                """, "stop", true, 400, 120, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceAdjudication adjudication = service.adjudicateCodeEvidence(
                "How does a worker response get completed and stored?",
                "flow",
                candidates,
                4
        );

        assertThat(adjudication.used()).isTrue();
        assertThat(adjudication.results().get(0).filePath())
                .isEqualTo("backend/src/main/java/com/learnbot/service/Candidate12.java");
        assertThat(adjudication.results().get(0).metadata())
                .containsEntry("llmEvidenceCoverageGroup", "response_intake")
                .containsEntry("llmEvidenceSlateRank", 1);
    }

    @Test
    void codeEvidenceFollowUpParsesRequiredEvidenceGroups() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/CodeSearchService.java",
                "method",
                "expandGraph",
                "CodeSearchService",
                "expandGraph",
                "com.learnbot.service",
                null,
                null,
                1,
                184,
                216,
                "private List<CodeSearchResult> expandGraph(...) { ... }",
                0.72,
                Map.of()
        );

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["graph schema","graph persistence"],"operations":[{"type":"keyword_search","query":"graph storage nodes edges","area":"persistence","evidenceGroup":"graph_persistence"}],"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["graph_schema","graph_persistence","queue_claim","response_intake","persistence_update","async_transport","unknown","graph_schema"],"reason":"need storage proof"}
                """, "stop", true, 120, 90, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "How are graph nodes and edges stored?",
                "overview",
                List.of(candidate),
                4
        );

        assertThat(plan.enough()).isFalse();
        assertThat(plan.requiredEvidenceGroups()).containsExactly(
                "graph_schema",
                "graph_persistence",
                "queue_claim",
                "response_intake",
                "persistence_update",
                "async_transport"
        );
        assertThat(plan.followUpQueries()).containsExactly("graph storage nodes edges");
        assertThat(plan.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo("keyword_search");
            assertThat(operation.query()).isEqualTo("graph storage nodes edges");
            assertThat(operation.evidenceGroup()).isEqualTo("graph_persistence");
        });
    }

    @Test
    void codeEvidenceFollowUpParsesAllSearchOperationTypes() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["implementation"],"operations":[{"type":"keyword_search","query":"LocalAgentController nextTool","area":"controller","evidenceGroup":"request_intake"},{"type":"hybrid_search","query":"claim queued tool execution","area":"service","evidenceGroup":"queue_claim"},{"type":"reference_search","query":"completeTool","area":"call sites","evidenceGroup":"response_intake"}],"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["request_intake","queue_claim","response_intake"],"reason":"need concrete flow"}
                """, "stop", true, 120, 90, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain the tool request and response flow",
                "flow",
                List.of(followUpCandidate(UUID.randomUUID())),
                4
        );

        assertThat(plan.operations()).extracting(RagPipelineService.CodeSearchOperation::type)
                .containsExactly("keyword_search", "hybrid_search", "reference_search");
        assertThat(plan.followUpQueries()).containsExactly(
                "LocalAgentController nextTool",
                "claim queued tool execution",
                "completeTool"
        );
        assertThat(plan.operations()).allSatisfy(operation -> {
            assertThat(operation.isSearch()).isTrue();
            assertThat(operation.validationError()).isBlank();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void codeEvidenceFollowUpParsesDirectReadOperationsAndStructuredSchema() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        UUID chunkId = UUID.fromString("0bda573c-f187-43af-93ee-8377ea026472");
        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["concrete implementation"],"operations":[{"type":"read_chunk","chunkId":"0bda573c-f187-43af-93ee-8377ea026472","area":"method body","evidenceGroup":"queue_claim"},{"type":"read_symbol","path":"backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java","symbol":"claimNext","area":"service method","evidenceGroup":"queue_claim"},{"type":"list_file_symbols","path":"backend/src/main/java/com/learnbot/service/CodeRagService.java","area":"file navigation","evidenceGroup":"orchestration"},{"type":"read_file_range","path":"backend/src/main/java/com/learnbot/repository/LocalAgentToolExecutionRepository.java","lineStart":64,"lineEnd":132,"area":"repository update","evidenceGroup":"persistence_update"}],"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["queue_claim","persistence_update"],"reason":"read exact evidence"}
                """, "stop", true, 180, 140, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain how the next tool request is claimed and persisted",
                "flow",
                List.of(followUpCandidate(chunkId)),
                4
        );

        assertThat(plan.followUpQueries()).isEmpty();
        assertThat(plan.operations()).hasSize(4).allSatisfy(operation -> {
            assertThat(operation.isDirectRead()).isTrue();
            assertThat(operation.validationError()).isBlank();
        });
        assertThat(plan.operations().get(0).chunkId()).isEqualTo(chunkId.toString());
        assertThat(plan.operations().get(1).path()).isEqualTo("backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java");
        assertThat(plan.operations().get(1).symbol()).isEqualTo("claimNext");
        assertThat(plan.operations().get(2).type()).isEqualTo("list_file_symbols");
        assertThat(plan.operations().get(2).path()).isEqualTo("backend/src/main/java/com/learnbot/service/CodeRagService.java");
        assertThat(plan.operations().get(3).lineStart()).isEqualTo(64);
        assertThat(plan.operations().get(3).lineEnd()).isEqualTo(132);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> formatCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ollamaClient).chatResult(
                systemPromptCaptor.capture(),
                promptCaptor.capture(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                formatCaptor.capture()
        );
        assertThat(promptCaptor.getValue()).contains("chunkId=" + chunkId);
        assertThat(systemPromptCaptor.getValue())
                .contains("list_file_symbols requires path")
                .contains("read_file_range requires path, lineStart, and lineEnd")
                .contains("traverse_graph requires an observed chunkId");

        Map<String, Object> schema = (Map<String, Object>) formatCaptor.getValue();
        Map<String, Object> rootProperties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> operationsSchema = (Map<String, Object>) rootProperties.get("operations");
        Map<String, Object> operationSchema = (Map<String, Object>) operationsSchema.get("items");
        Map<String, Object> operationProperties = (Map<String, Object>) operationSchema.get("properties");
        Map<String, Object> typeSchema = (Map<String, Object>) operationProperties.get("type");
        assertThat((List<String>) typeSchema.get("enum")).contains(
                "keyword_search", "hybrid_search", "reference_search",
                "read_chunk", "read_symbol", "list_file_symbols", "read_file_range", "read_adjacent", "traverse_graph"
        );
        assertThat(operationProperties).containsKeys(
                "path", "symbol", "chunkId", "lineStart", "lineEnd", "radius",
                "relations", "direction", "maxHops"
        );
        assertThat((List<String>) operationSchema.get("required"))
                .containsExactlyInAnyOrder("type", "area", "evidenceGroup");
    }

    @Test
    void codeEvidenceFollowUpPreservesInvalidDirectReadsWithoutInventingRequiredFields() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["implementation"],"operations":[{"type":"unknown_read","path":"invented.java","area":"unknown","evidenceGroup":"unknown"},{"type":"keyword_search","query":"","area":"search","evidenceGroup":"unknown"},{"type":"read_symbol","path":"Service.java","area":"method","evidenceGroup":"orchestration"},{"type":"read_file_range","path":"Repository.java","lineStart":12,"lineEnd":"not-a-number","area":"storage","evidenceGroup":"persistence_update"}],"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["orchestration","persistence_update"],"reason":"model omitted required fields"}
                """, "stop", true, 120, 90, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain the implementation",
                "method",
                List.of(followUpCandidate(UUID.randomUUID())),
                4
        );

        assertThat(plan.operations()).hasSize(2);
        assertThat(plan.operations().get(0).type()).isEqualTo("read_symbol");
        assertThat(plan.operations().get(0).symbol()).isBlank();
        assertThat(plan.operations().get(0).validationError()).isEqualTo("symbol is required");
        assertThat(plan.operations().get(1).type()).isEqualTo("read_file_range");
        assertThat(plan.operations().get(1).lineStart()).isEqualTo(12);
        assertThat(plan.operations().get(1).lineEnd()).isNull();
        assertThat(plan.operations().get(1).validationError()).isEqualTo("lineEnd is required");
    }

    @Test
    void legacyFollowUpQueriesAndFourArgumentOperationRemainCompatible() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["service"],"followUpQueries":["claimNext service implementation"],"queryAreas":["service"],"requiredEvidenceGroups":["orchestration"],"reason":"legacy response"}
                """, "stop", true, 80, 60, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain claimNext",
                "method",
                List.of(followUpCandidate(UUID.randomUUID())),
                2
        );
        RagPipelineService.CodeSearchOperation legacy = new RagPipelineService.CodeSearchOperation(
                "keyword_search", "claimNext", "service", "orchestration"
        );

        assertThat(plan.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo("hybrid_search");
            assertThat(operation.query()).isEqualTo("claimNext service implementation");
            assertThat(operation.path()).isBlank();
            assertThat(operation.lineStart()).isNull();
        });
        assertThat(legacy.query()).isEqualTo("claimNext");
        assertThat(legacy.path()).isBlank();
        assertThat(legacy.radius()).isNull();
        assertThat(legacy.validationError()).isBlank();
    }

    @Test
    void sufficientFollowUpStillPreservesRequiredEvidenceContract() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        List<RagPipelineService.CodeEvidenceChecklistItem> checklist = List.of(
                new RagPipelineService.CodeEvidenceChecklistItem(
                        "queue-claim", "queue_claim", "find the concrete claim", List.of("claimNext")
                )
        );
        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":true,"missingAreas":[],"operations":[{"type":"read_chunk","chunkId":"0bda573c-f187-43af-93ee-8377ea026472","area":"method","evidenceGroup":"queue_claim"}],"followUpQueries":["unused"],"queryAreas":["unused"],"requiredEvidenceGroups":["queue_claim","persistence_update"],"reason":"evidence is sufficient"}
                """, "stop", true, 80, 60, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain claim and persistence",
                "flow",
                List.of(followUpCandidate(UUID.randomUUID())),
                2,
                checklist
        );

        assertThat(plan.enough()).isTrue();
        assertThat(plan.operations()).isEmpty();
        assertThat(plan.followUpQueries()).isEmpty();
        assertThat(plan.requiredEvidenceGroups()).containsExactly("queue_claim", "persistence_update");
        assertThat(plan.checklist()).containsExactlyElementsOf(checklist);
    }

    @Test
    void codeEvidenceIterationPromptCarriesBoundedOperationObservations() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["persistence"],"operations":[{"type":"read_symbol","path":"Repository.java","symbol":"complete","area":"storage","evidenceGroup":"persistence_update"}],"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["persistence_update"],"reason":"need persistence evidence"}
                """, "stop", true, 80, 60, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceIteration(
                "Explain completion persistence",
                "flow",
                List.of(followUpCandidate(UUID.randomUUID())),
                2,
                List.of(),
                List.of(
                        "type=read_chunk status=NOT_FOUND reason=chunk missing",
                        "type=hybrid_search status=SUCCESS candidates=4"
                ),
                2
        );

        assertThat(plan.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo("read_symbol");
            assertThat(operation.symbol()).isEqualTo("complete");
        });
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(
                anyString(),
                promptCaptor.capture(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        );
        assertThat(promptCaptor.getValue())
                .contains("Retrieval iteration: 2")
                .contains("type=read_chunk status=NOT_FOUND reason=chunk missing")
                .contains("type=hybrid_search status=SUCCESS candidates=4")
                .contains("avoid repeating failed or duplicate operations");
    }

    @Test
    void codeEvidenceSearchPlanParsesChecklistItems() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RuntimeTuningService runtimeTuningService = mock(RuntimeTuningService.class);
        when(runtimeTuningService.codeEvidenceDecisionModel()).thenReturn(1);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties(), runtimeTuningService);

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.PRIMARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"usable":true,"confidence":0.86,"queries":["/api/code/ask CodeController ask CodeRagService"],"checklist":[{"claimId":"request-entrypoint","evidenceGroup":"request_intake","goal":"find endpoint handling /api/code/ask","queries":["CodeController ask /api/code/ask"]},{"claimId":"graph-expansion","evidenceGroup":"graph_traversal","goal":"find graph expansion implementation","queries":["CodeSearchService expandGraph graphRelatedChunks"]}],"reason":"phase-specific plan"}
                """, "stop", true, 200, 160, "http://ollama", "test", "primary", false));

        RagPipelineService.CodeEvidenceSearchPlan plan = service.planCodeEvidenceSearch(
                "Explain /api/code/ask from controller to graph expansion and answer generation",
                "flow",
                "__learnbot__/project-context.md",
                4
        );

        assertThat(plan.usable()).isTrue();
        assertThat(plan.checklist()).hasSize(2);
        assertThat(plan.checklist().get(0).claimId()).isEqualTo("request-entrypoint");
        assertThat(plan.checklist().get(1).evidenceGroup()).isEqualTo("graph_traversal");
        assertThat(plan.checklist().get(1).queries()).containsExactly("CodeSearchService expandGraph graphRelatedChunks");
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, times(2)).chatResult(
                systemPrompt.capture(),
                anyString(),
                eq(OllamaClient.ChatRole.PRIMARY),
                anyInt(),
                any(Duration.class),
                any()
        );
        assertThat(systemPrompt.getAllValues().get(0))
                .contains("one checklist item per action")
                .contains("Do not make one checklist item per layer")
                .contains("class declaration, constructor, dependency field")
                .contains("Preserve the actor, object, action, direction, state transition, and side effect")
                .contains("Do not invent likely class or method names")
                .doesNotContain("producer enqueue", "approval changes");
        assertThat(systemPrompt.getAllValues().get(1))
                .contains("independent audit")
                .contains("actor, object, action, direction")
                .doesNotContain("producer enqueue", "approval changes");
    }

    @Test
    void codeEvidenceFollowUpPromptCarriesChecklistForward() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                "CodeRagService",
                "askPrioritized",
                "com.learnbot.service",
                null,
                null,
                1,
                170,
                220,
                "private CodeAskResponse askPrioritized(...) { ... }",
                0.72,
                Map.of()
        );
        List<RagPipelineService.CodeEvidenceChecklistItem> checklist = List.of(
                new RagPipelineService.CodeEvidenceChecklistItem(
                        "graph-expansion",
                        "graph_traversal",
                        "find concrete graph expansion implementation",
                        List.of("CodeSearchService expandGraph graphRelatedChunks")
                )
        );

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["graph expansion","answer generation"],"operations":[{"type":"hybrid_search","query":"CodeSearchService expandGraph","area":"graph expansion","evidenceGroup":"graph_traversal"},{"type":"hybrid_search","query":"answer generation model client call","area":"answer generation","evidenceGroup":"answer_generation"}],"followUpQueries":["CodeSearchService expandGraph","answer generation model client call"],"queryAreas":["graph expansion","answer generation"],"requiredEvidenceGroups":["graph_traversal"],"checklist":[{"claimId":"graph-expansion","evidenceGroup":"graph_traversal","goal":"find concrete graph expansion implementation","queries":["CodeSearchService expandGraph graphRelatedChunks"]},{"claimId":"answer-generation","evidenceGroup":"answer_generation","goal":"find the requested answer generation behavior","queries":["answer generation model client call"]}],"coverageSelections":[],"reason":"need concrete traversal evidence"}
                """, "stop", true, 120, 90, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain /api/code/ask graph expansion",
                "flow",
                List.of(candidate),
                2,
                checklist
        );

        assertThat(plan.checklist()).extracting(RagPipelineService.CodeEvidenceChecklistItem::evidenceGroup)
                .containsExactly("graph_traversal", "answer_generation");
        assertThat(plan.requiredEvidenceGroups()).contains("graph_traversal", "answer_generation");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(
                anyString(),
                promptCaptor.capture(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        );
        assertThat(promptCaptor.getValue())
                .contains("Required evidence checklist")
                .contains("graph-expansion")
                .contains("find concrete graph expansion implementation");
    }

    @Test
    void codeEvidenceCanBeSufficientWhenStructuredEvidenceIsStrongEvenIfTermsDiffer() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());
        CodeSearchResult result = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/LoginService.java",
                "method",
                "login",
                "LoginService",
                "login",
                "com.learnbot.service",
                null,
                null,
                1,
                10,
                32,
                "public LoginResponse login(...) { authenticate(); issueToken(); }",
                0.82,
                Map.of("language", "java")
        );

        RagPipelineService.EvidenceAssessment assessment = service.assessCode("sign-in flow", List.of(result), 2, 1);

        assertThat(assessment.sufficient()).isTrue();
    }

    @Test
    void answerSelfCheckRejectsCitationOutsideEvidenceRange() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());

        RagPipelineService.AnswerAssessment assessment = service.assessAnswer("Answer based on evidence [2].", 1, true);

        assertThat(assessment.acceptable()).isFalse();
        assertThat(assessment.reason()).isEqualTo("citation out of range");
    }

    @Test
    void answerSelfCheckRejectsLengthStoppedGeneration() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());

        RagPipelineService.AnswerAssessment assessment = service.assessAnswer(
                "근거에 따르면 관리자 권한 관리가 추가되었습니다 [1].",
                1,
                true,
                "length"
        );

        assertThat(assessment.acceptable()).isFalse();
        assertThat(assessment.reason()).isEqualTo("model stopped before finishing");
    }

    @Test
    void answerSelfCheckRejectsIncompleteFinalSentence() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());

        RagPipelineService.AnswerAssessment assessment = service.assessAnswer(
                "근거에 따르면 설정 클래스에 Pipeline이라는 정",
                1,
                false
        );

        assertThat(assessment.acceptable()).isFalse();
        assertThat(assessment.reason()).isEqualTo("answer appears incomplete");
    }

    @Test
    void codeEvidenceSearchPlanReviewsArchitectureOnlyChecklistIntoBehaviorClaims() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RuntimeTuningService runtimeTuningService = mock(RuntimeTuningService.class);
        when(runtimeTuningService.codeEvidenceDecisionModel()).thenReturn(1);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties(), runtimeTuningService);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY),
                anyInt(), any(Duration.class), any())).thenReturn(
                new OllamaClient.ChatResult("""
                        {"usable":true,"confidence":0.9,"queries":["worker controller service repository"],"checklist":[{"claimId":"controller","evidenceGroup":"controller_layer","goal":"controller","queries":["controller"]},{"claimId":"repository","evidenceGroup":"repository_layer","goal":"repository","queries":["repository"]}],"reason":"layers"}
                        """, "stop", true, 100, 80, "http://ollama", "test", "primary", false),
                new OllamaClient.ChatResult("""
                        {"usable":true,"confidence":0.95,"queries":["claim queued work","persist completed response"],"checklist":[{"claimId":"claim","evidenceGroup":"worker_flow","goal":"prove queued work is claimed","queries":["claim queued work"]},{"claimId":"complete","evidenceGroup":"worker_flow","goal":"prove completed response is persisted","queries":["persist completed response"]}],"reason":"behavioral rewrite"}
                        """, "stop", true, 100, 80, "http://ollama", "test", "primary", false));

        var plan = service.planCodeEvidenceSearch("How does a worker claim work and persist its response?", "flow", "", 4);

        assertThat(plan.checklist()).extracting(RagPipelineService.CodeEvidenceChecklistItem::evidenceGroup)
                .containsExactly("worker_flow", "complete");
        verify(ollamaClient, times(2)).chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY),
                anyInt(), any(Duration.class), any());
    }

    private CodeSearchResult followUpCandidate(UUID chunkId) {
        return new CodeSearchResult(
                chunkId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java",
                "method",
                "claimNext",
                "LocalAgentToolGatewayService",
                "claimNext",
                "com.learnbot.service",
                null,
                null,
                1,
                1046,
                1054,
                "public Optional<LocalAgentQueuedToolRequest> claimNext(...) { ... }",
                0.82,
                Map.of("language", "java")
        );
    }
}
