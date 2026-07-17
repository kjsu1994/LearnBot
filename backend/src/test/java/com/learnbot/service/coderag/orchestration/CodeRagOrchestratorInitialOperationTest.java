package com.learnbot.service.coderag.orchestration;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import com.learnbot.service.CodeReferenceService;
import com.learnbot.service.CodeSearchService;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.OllamaClient;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeEvidenceRanker;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        int added = orchestrator.collectSearchPlanEvidence(
                repositoryId, spaceId, List.of(spaceId), "How is queued work claimed?",
                CodeQuestionMode.OVERVIEW, 8, plan, List.of(operation), merged);

        assertThat(added).isEqualTo(1);
        assertThat(merged.values()).singleElement().satisfies(result ->
                assertThat(CodeEvidenceOperationProvenance.from(result))
                        .containsExactly(new CodeEvidenceOperationProvenance(
                                "keyword_search", "initial-search", List.of("claim-1"), "queue_claim",
                                List.of(), "queued work claim behavior", "", "", "",
                                null, null, null, List.of(), "BOTH", null)));
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
                .singleElement()
                .satisfies(approved -> {
                    assertThat(approved.claimId()).isEqualTo("claim-1");
                    assertThat(approved.queries()).containsExactly("queued work claim behavior");
                    assertThat(approved.goal()).isEqualTo("queued work claim behavior");
                    assertThat(approved.actor()).isBlank();
                    assertThat(approved.action()).isBlank();
                    assertThat(approved.object()).isBlank();
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
                .contains(question, "queued work claim behavior", "prove queued work claim");
        assertThat(orchestrator.retrievalOperationEvidenceIntent(search))
                .contains("keyword_search", "queued work claim behavior");
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

    private CodeSearchResult structuralResult(UUID repositoryId) {
        return new CodeSearchResult(
                UUID.randomUUID(), repositoryId, UUID.randomUUID(), "repo", "src/app/Worker.java",
                "file_section", "Worker", "Worker", null, "app", null, null, 1,
                1, 160, "class Worker { void claim() {} }", 0.8, Map.of("language", "java"));
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
