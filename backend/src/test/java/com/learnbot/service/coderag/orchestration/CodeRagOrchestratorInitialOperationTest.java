package com.learnbot.service.coderag.orchestration;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import com.learnbot.service.CodeReferenceService;
import com.learnbot.service.CodeSearchService;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.OllamaClient;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeEvidenceRanker;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeRagOrchestratorInitialOperationTest {
    @Test
    void combinedPlannerModeGovernsAllPostPlanRetrievalPolicies() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        String question = "Trace the execution across components";
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        RagPipelineService pipelineService = mock(RagPipelineService.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                searchService,
                codeRepository,
                mock(CodeReferenceService.class),
                null,
                mock(OllamaClient.class),
                properties,
                pipelineService,
                new CodeEvidenceRanker(properties),
                null
        );
        CodeSearchResult seed = genericStructuralResult(
                repositoryId, 10, 40, Map.of("callableBodyPresent", true));
        when(searchService.searchWithoutGraph(
                eq(repositoryId), eq(question), anyInt(), eq(List.of(spaceId)), eq(spaceId),
                eq(GraphSearchIntent.OVERVIEW)))
                .thenReturn(List.of(seed));
        when(pipelineService.codeSearchLimit(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pipelineService.codeRetrievalDeadlineSeconds()).thenReturn(5);
        when(pipelineService.codeRetrievalMaxIterations()).thenReturn(1);
        when(pipelineService.planCodeEvidenceSearch(eq(question), eq("overview"), anyString(), anyInt()))
                .thenReturn(new RagPipelineService.CodeEvidenceSearchPlan(
                        true, true, 0.9, List.of(), List.of(), "flow selected from repository plan",
                        "", 1, List.of(), RagPipelineService.CodeRagRoute.CODE_SEARCH,
                        "flow", "", "", ""));
        when(pipelineService.assessCode(eq(question), anyList(), anyInt(), anyInt()))
                .thenReturn(new RagPipelineService.EvidenceAssessment(
                        true, 1, 0.9, 1, 1.0, List.of("sufficient")));
        when(pipelineService.planCodeEvidenceIteration(
                eq(question), eq("flow"), anyList(), anyInt(), anyList(), anyList(), anyInt(), anyString()))
                .thenReturn(new RagPipelineService.CodeEvidenceFollowUpPlan(
                        true, true, "complete", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of()));

        var retrieve = CodeRagOrchestrator.class.getDeclaredMethod(
                "retrieveCodeEvidence", UUID.class, UUID.class, List.class, String.class,
                CodeQuestionMode.class, int.class, RagConversationContext.class);
        retrieve.setAccessible(true);
        retrieve.invoke(
                orchestrator, repositoryId, spaceId, List.of(spaceId), question,
                CodeQuestionMode.OVERVIEW, 8, null);

        verify(pipelineService).planCodeEvidenceIteration(
                eq(question), eq("flow"), anyList(), anyInt(), anyList(), anyList(), eq(1), anyString());
    }

    @Test
    void approvedInitialGraphReadRetainsEveryExecutorBoundedResult() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                searchService,
                codeRepository,
                mock(CodeReferenceService.class),
                null,
                mock(OllamaClient.class),
                properties,
                mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties),
                null
        );
        CodeSearchResult seed = genericStructuralResult(
                repositoryId, 10, 40, Map.of("callableBodyPresent", true));
        List<CodeSearchResult> graphResults = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> genericStructuralResult(
                        repositoryId, 50 + index * 10, 55 + index * 10,
                        Map.of("callableBodyPresent", true)))
                .toList();
        when(codeRepository.findActiveChunksByIds(
                eq(repositoryId), anyList(), eq(List.of(spaceId)), eq(spaceId)))
                .thenAnswer(invocation -> {
                    List<UUID> ids = invocation.getArgument(1);
                    return ids.equals(List.of(seed.chunkId())) ? List.of(seed) : graphResults;
                });
        when(codeRepository.graphRelatedChunks(
                eq(repositoryId), eq(List.of(seed.chunkId())), eq(List.of("CALLS")), eq(2),
                eq("BOTH"), anyInt()))
                .thenReturn(graphResults);
        RagPipelineService.CodeEvidenceChecklistItem claim =
                new RagPipelineService.CodeEvidenceChecklistItem(
                        "claim-1", "flow", "trace execution", List.of("trace execution"));
        RagPipelineService.CodeSearchOperation traversal =
                new RagPipelineService.CodeSearchOperation(
                        "traverse_graph", "", "execution", "flow", "", "",
                        seed.chunkId().toString(), null, null, null, List.of("CALLS"), "BOTH", 2,
                        "initial-graph", List.of("claim-1"), List.of("observed-origin"));
        RagPipelineService.CodeEvidenceSearchPlan plan =
                new RagPipelineService.CodeEvidenceSearchPlan(
                        true, true, 0.9, List.of(), List.of(claim), "bounded graph read",
                        "", 1, List.of(traversal));
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();

        CodeRagOrchestrator.InitialPlanExecution execution = orchestrator.executeInitialPlanEvidence(
                repositoryId, spaceId, List.of(spaceId), "Trace execution",
                CodeQuestionMode.CALL_FLOW, 12, plan, List.of(traversal), merged);

        assertThat(execution.candidatesAdded()).isEqualTo(6);
        assertThat(merged.keySet()).containsExactlyInAnyOrderElementsOf(
                graphResults.stream().map(CodeSearchResult::chunkId).toList());
    }

    @Test
    void seedQueryExecutesOnceWithoutEndpointSpecificExpansion() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        String query = "How does /resources/{id} behave?";
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                searchService,
                codeRepository,
                mock(CodeReferenceService.class),
                null,
                mock(OllamaClient.class),
                properties,
                mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties),
                null
        );
        CodeSearchResult result = genericStructuralResult(
                repositoryId, 4, 20, Map.of("callableBodyPresent", true));
        when(searchService.searchWithoutGraph(
                eq(repositoryId), eq(query), anyInt(), eq(List.of(spaceId)), eq(spaceId),
                eq(GraphSearchIntent.OVERVIEW)))
                .thenReturn(List.of(result));
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();

        orchestrator.collectEvidenceForQuery(
                repositoryId, spaceId, List.of(spaceId), query, CodeQuestionMode.OVERVIEW, 8, merged);

        assertThat(merged.values())
                .singleElement()
                .satisfies(ranked -> {
                    assertThat(ranked.chunkId()).isEqualTo(result.chunkId());
                    assertThat(ranked.filePath()).isEqualTo(result.filePath());
                });
        verify(searchService).searchWithoutGraph(
                eq(repositoryId), eq(query), anyInt(), eq(List.of(spaceId)), eq(spaceId),
                eq(GraphSearchIntent.OVERVIEW));
        verify(searchService, never()).search(
                eq(repositoryId), anyString(), anyInt(), anyList(), eq(spaceId));
        verify(codeRepository, never()).findEndpointChunks(
                eq(repositoryId), anyString(), anyInt(), anyList(), eq(spaceId));
    }

    @Test
    void sourceVocabularyBridgeUsesBoundedActiveGraphExpansion() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        String query = "symbol reference definitions and usages";
        CodeSearchService searchService = mock(CodeSearchService.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                searchService, mock(CodeRepository.class), mock(CodeReferenceService.class), null,
                mock(OllamaClient.class), properties, mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties), null);
        CodeSearchResult graphTarget = genericStructuralResult(
                repositoryId, 40, 70, Map.of("graphExpanded", true, "graphEdgeType", "CALLS"));
        when(searchService.search(
                eq(repositoryId), eq(query), anyInt(), eq(List.of(spaceId)), eq(spaceId),
                eq(GraphSearchIntent.FLOW))).thenReturn(List.of(graphTarget));
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();

        orchestrator.collectGraphExpandedEvidenceForQuery(
                repositoryId, spaceId, List.of(spaceId), query, CodeQuestionMode.CALL_FLOW, 8, merged);

        assertThat(merged.values()).containsExactly(graphTarget);
        verify(searchService).search(
                eq(repositoryId), eq(query), anyInt(), eq(List.of(spaceId)), eq(spaceId),
                eq(GraphSearchIntent.FLOW));
        verify(searchService, never()).searchWithoutGraph(
                eq(repositoryId), eq(query), anyInt(), anyList(), eq(spaceId), eq(GraphSearchIntent.FLOW));
    }

    @Test
    void meaningfulEvidenceUsesStructuralMetadataIdentityAndSpanWithoutSyntaxMarkers() {
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                mock(CodeSearchService.class),
                mock(CodeRepository.class),
                mock(CodeReferenceService.class),
                null,
                mock(OllamaClient.class),
                properties,
                mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties),
                null
        );
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult bounded = genericStructuralResult(
                repositoryId, 4, 20, Map.of("callableBodyPresent", true));
        CodeSearchResult explicitlyBodyless = genericStructuralResult(
                repositoryId, 24, 40, Map.of("callableBodyPresent", false));
        CodeSearchResult oversized = genericStructuralResult(
                repositoryId, 44, 500, Map.of("callableBodyPresent", true));

        assertThat(bounded.content()).doesNotContain("{", "=>");
        assertThat(orchestrator.meaningfulEvidenceCount(List.of(bounded))).isEqualTo(1);
        assertThat(orchestrator.meaningfulEvidenceCount(List.of(explicitlyBodyless, oversized))).isZero();
    }

    @Test
    void approvedInitialSearchKeepsExecutorBoundaryAndDoesNotPerformImplicitReads() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                searchService,
                codeRepository,
                mock(CodeReferenceService.class),
                null,
                mock(OllamaClient.class),
                properties,
                mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties),
                null
        );
        CodeSearchResult structuralResult = structuralResult(repositoryId);
        when(searchService.cheapSearch(
                eq(repositoryId), anyString(), anyInt(), anyList(), eq(spaceId)))
                .thenReturn(List.of(structuralResult));
        when(codeRepository.listActiveSymbolsByPath(
                eq(repositoryId), eq(structuralResult.filePath()), anyInt(), anyList(), eq(spaceId)))
                .thenReturn(List.of());
        when(searchService.identifiersFrom("queued work claim behavior"))
                .thenReturn(List.of("CandidateSymbol"));

        RagPipelineService.CodeEvidenceChecklistItem claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "queue_claim", "prove queued work claim", List.of(),
                "worker", "claim", "queued work", "work becomes assigned", List.of(), List.of("DIRECT_SOURCE"));
        RagPipelineService.CodeSearchOperation operation = new RagPipelineService.CodeSearchOperation(
                "keyword_search", "queued work claim behavior", "queue implementation", "queue_claim",
                "", "", "", null, null, null, List.of(), "", null,
                "initial-search", List.of("claim-1"), List.of());
        RagPipelineService.CodeEvidenceChecklistItem rejectedClaim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-2", "unrelated_admin", "change administrator settings", List.of(),
                "administrator", "change", "settings", "settings change", List.of(), List.of("DIRECT_SOURCE"));
        RagPipelineService.CodeSearchOperation rejectedOperation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "AdminController settings update", "admin layer", "unrelated_admin",
                "", "", "", null, null, null, List.of(), "", null,
                "rejected-search", List.of("claim-2"), List.of());
        RagPipelineService.CodeEvidenceChecklistItem duplicateClaimDrift = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "unrelated_admin", "change administrator settings", List.of("AdminController update"),
                "administrator", "change", "settings", "settings change", List.of(), List.of("DIRECT_SOURCE"));
        RagPipelineService.CodeEvidenceSearchPlan plan = new RagPipelineService.CodeEvidenceSearchPlan(
                true, true, 0.9, List.of(operation.query(), rejectedOperation.query()),
                List.of(claim, duplicateClaimDrift, rejectedClaim), "reason containing AdminController settings update",
                "worker may claim queued work", 1, List.of(operation, rejectedOperation));
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();

        CodeRagOrchestrator.InitialPlanExecution execution = orchestrator.executeInitialPlanEvidence(
                repositoryId, spaceId, List.of(spaceId), "How is queued work claimed?",
                CodeQuestionMode.OVERVIEW, 8, plan, List.of(operation), merged);

        assertThat(execution.candidatesAdded()).isEqualTo(1);
        assertThat(execution.executedOperationKeys())
                .containsExactly("keyword_search|queued work claim behavior");
        assertThat(execution.observations()).singleElement()
                .asString()
                .contains("phase=INITIAL_PLAN", "operationId=initial-search", "status=COMPLETED");
        assertThat(merged.values()).singleElement().satisfies(result ->
                assertThat(CodeEvidenceOperationProvenance.from(result))
                        .containsExactly(new CodeEvidenceOperationProvenance(
                                "keyword_search", "initial-search", List.of("claim-1"), "queue_claim",
                                List.of(), "queued work claim behavior", "", "", "",
                                null, null, null, List.of(), "BOTH", null, 1)));
        verify(searchService, never()).identifiersFrom("queued work claim behavior");
        verify(codeRepository, never()).findSymbolDefinitions(
                eq(repositoryId), anyString(), anyString(), anyInt(), anyList(), eq(spaceId));
        verify(codeRepository, never()).findActiveChunksByPathAndLineRange(
                eq(repositoryId), anyString(), anyInt(), anyInt(), anyInt(), anyList(), eq(spaceId));

        assertThat(orchestrator.searchPlanIntent(
                "How is queued work claimed?", plan, List.of(operation)))
                .contains("How is queued work claimed?", "queued work claim behavior")
                .doesNotContain("prove queued work claim", "AdminController", "administrator settings", "unrelated_admin");

        assertThat(orchestrator.approvedInitialChecklist(
                "How is queued work claimed?", plan, List.of(operation)))
                .satisfiesExactly(
                        approved -> {
                            assertThat(approved.claimId()).isEqualTo("claim-1");
                            assertThat(approved.queries()).containsExactly("queued work claim behavior");
                            assertThat(approved.goal()).isEqualTo("queued work claim behavior");
                            assertThat(approved.actor()).isBlank();
                            assertThat(approved.action()).isBlank();
                            assertThat(approved.object()).isBlank();
                        },
                        fallback -> {
                            assertThat(fallback.claimId()).isEqualTo("claim-2");
                            assertThat(fallback.evidenceGroup()).isEqualTo("claim-2");
                            assertThat(fallback.goal()).isEqualTo("How is queued work claimed?");
                            assertThat(fallback.queries()).containsExactly("How is queued work claimed?");
                            assertThat(fallback.goal()).doesNotContain(
                                    "AdminController", "administrator settings", "unrelated_admin");
                            assertThat(fallback.actor()).isBlank();
                            assertThat(fallback.action()).isBlank();
                            assertThat(fallback.object()).isBlank();
                        });

        assertThat(orchestrator.approvedInitialHypothesis(
                "How is queued work claimed?", plan, List.of(operation)))
                .contains("How is queued work claimed?", "queued work claim behavior")
                .doesNotContain(
                        plan.hypothesis(), plan.reason(), "AdminController", "administrator settings");
    }

    @Test
    void approvedInitialDirectReadCannotInjectItsUnvalidatedQuery() {
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                mock(CodeSearchService.class), mock(CodeRepository.class), mock(CodeReferenceService.class),
                null, mock(OllamaClient.class), properties, mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties), null);
        String question = "How is queued work claimed?";
        RagPipelineService.CodeEvidenceChecklistItem claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "queue_claim", "prove queued work claim", List.of(),
                "worker", "claim", "queued work", "work becomes assigned", List.of(), List.of("DIRECT_SOURCE"));
        RagPipelineService.CodeSearchOperation directRead = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "AdminController settings update", "implementation", "queue_claim",
                "src/Worker.java", "claim", "", null, null, null, List.of(), "BOTH", null,
                "direct-read", List.of("claim-1"), List.of("observed-origin"));
        RagPipelineService.CodeEvidenceSearchPlan plan = new RagPipelineService.CodeEvidenceSearchPlan(
                true, true, 0.9, List.of(), List.of(claim), "unsafe reason",
                "unsafe hypothesis", 1, List.of(directRead));

        assertThat(orchestrator.searchPlanIntent(question, plan, List.of(directRead)))
                .isEqualTo(question)
                .doesNotContain("AdminController");
        assertThat(orchestrator.initialSearchOperationIntent(question, directRead))
                .isEqualTo(question)
                .doesNotContain("AdminController");
        assertThat(orchestrator.approvedInitialChecklist(question, plan, List.of(directRead)))
                .singleElement()
                .satisfies(approved -> {
                    assertThat(approved.goal()).isEqualTo(question);
                    assertThat(approved.queries()).containsExactly(question);
                });
        assertThat(orchestrator.approvedInitialHypothesis(question, plan, List.of(directRead)))
                .isEqualTo(question)
                .doesNotContain("AdminController", "unsafe hypothesis");
        assertThat(orchestrator.retrievalOperationIntent(question, directRead, List.of(claim)))
                .isEqualTo(question)
                .doesNotContain("AdminController", "prove queued work claim", "implementation");

        CodeSearchResult markedDirect = orchestrator.markLlmIterationEvidence(
                structuralResult(UUID.randomUUID()), directRead);
        assertThat(String.valueOf(markedDirect.metadata().get("llmFollowUpQuery")))
                .contains("read_symbol", "src/Worker.java", "claim")
                .doesNotContain("AdminController", "settings update", "implementation");

        RagPipelineService.CodeSearchOperation search = new RagPipelineService.CodeSearchOperation(
                "keyword_search", "queued work claim behavior", "implementation", "queue_claim",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "follow-up-search", List.of("claim-1"), List.of());
        assertThat(orchestrator.retrievalOperationIntent(question, search, List.of(claim)))
                .isEqualTo(question)
                .doesNotContain("queued work claim behavior", "prove queued work claim");
        assertThat(orchestrator.retrievalOperationEvidenceIntent(search))
                .contains("keyword_search", "queued work claim behavior");
    }

    @Test
    void accumulatedIrRetentionSurvivesTheNextRankedCandidateSelection() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationMaxCandidates(2);
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                mock(CodeSearchService.class), mock(CodeRepository.class), mock(CodeReferenceService.class),
                null, mock(OllamaClient.class), properties, mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties), null);
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult first = rankedResult(repositoryId, "first", 0.95, 10);
        CodeSearchResult second = rankedResult(repositoryId, "second", 0.90, 30);
        CodeSearchResult exactRead = rankedResult(repositoryId, "retained", 0.05, 50);
        String evidenceId = CodeEvidenceId.from(exactRead);
        CodeEvidenceIr accumulated = new CodeEvidenceIr(
                List.of(new CodeEvidenceItem(
                        evidenceId, exactRead, Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE),
                        CodeIntelligenceAuthority.SYNTAX)),
                List.of(),
                List.of(new CodeEvidenceConstraint(
                        CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED,
                        evidenceId, "typed exact read resolved its structural operand")),
                List.of(), List.of(), List.of());
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        merged.put(first.chunkId(), first);
        merged.put(second.chunkId(), second);
        merged.put(exactRead.chunkId(), exactRead);

        List<CodeSearchResult> selected = orchestrator.rankedCodeEvidence(
                "Explain the workflow", CodeQuestionMode.LOCATE, merged, 2, accumulated);

        assertThat(selected).hasSize(2);
        assertThat(selected).extracting(CodeSearchResult::chunkId)
                .contains(exactRead.chunkId());
    }

    @Test
    void apiPreviewPreservesTheExactCanonicalEvidenceText() {
        LearnBotProperties properties = new LearnBotProperties();
        CodeRagOrchestrator orchestrator = new CodeRagOrchestrator(
                mock(CodeSearchService.class),
                mock(CodeRepository.class),
                mock(CodeReferenceService.class),
                null,
                mock(OllamaClient.class),
                properties,
                mock(RagPipelineService.class),
                new CodeEvidenceRanker(properties),
                null
        );
        String canonical = "\n    indented declaration\n        indented proof\n";

        assertThat(orchestrator.preview(canonical)).isEqualTo(canonical);
    }

    @Test
    void graphTraversalObservationPublishesReturnedSymbolsForTheNextPlan() {
        UUID repositoryId = UUID.randomUUID();
        var graph = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "implementation", "flow", "", "", "",
                null, null, null, List.of("CALLS"), "FORWARD", 2,
                "graph-flow", List.of("claim-flow"), List.of());
        CodeSearchResult first = rankedResult(repositoryId, "dispatchFrame", 0.8, 20);
        CodeSearchResult second = rankedResult(repositoryId, "persistFrame", 0.7, 40);

        assertThat(CodeRagOrchestrator.operationResultHandles(graph, List.of(first, second)))
                .contains("observedSymbols=[dispatchFrame, persistFrame]");
    }

    private CodeSearchResult structuralResult(UUID repositoryId) {
        return new CodeSearchResult(
                UUID.randomUUID(), repositoryId, UUID.randomUUID(), "repo", "src/app/Worker.java",
                "file_section", "Worker", "Worker", null, "app", null, null, 1,
                1, 160, "class Worker { void claim() {} }", 0.8, Map.of("language", "java"));
    }

    private CodeSearchResult rankedResult(
            UUID repositoryId,
            String method,
            double score,
            int lineStart
    ) {
        return new CodeSearchResult(
                UUID.randomUUID(), repositoryId, UUID.randomUUID(), "repo", "src/app/Worker.java",
                "method", method, "Worker", method, "app", null, null, 1,
                lineStart, lineStart + 8, "void " + method + "() { perform(); }", score,
                Map.of("indexVersion", "index-v1"));
    }

    private CodeSearchResult genericStructuralResult(
            UUID repositoryId,
            int lineStart,
            int lineEnd,
            Map<String, Object> metadata
    ) {
        return new CodeSearchResult(
                UUID.randomUUID(), repositoryId, UUID.randomUUID(), "repo", "src/module/Worker.code",
                "callable", "perform", null, "perform", "module", null, null, 1,
                lineStart, lineEnd, "step one\nstep two", 0.8, metadata);
    }
}
