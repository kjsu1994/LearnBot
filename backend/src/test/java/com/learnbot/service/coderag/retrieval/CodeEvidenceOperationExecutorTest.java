package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.CodeReferenceService;
import com.learnbot.service.CodeSearchService;
import com.learnbot.service.EvidenceExcerptSelector;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.CodeEndpointOutline;
import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeEvidenceOperationExecutorTest {
    private static final UUID SPACE_ID = UUID.randomUUID();

    @Test
    void readFileRangeNormalizesPathAndAddsDirectReadProvenance() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult result = result("src/main/java/app/Worker.java", "claimNext", 20, 60);
        when(repository.findActiveChunksByPathAndLineRange(
                eq(null), eq("src/main/java/app/Worker.java"), eq(20), eq(60), anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(result));

        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "queue implementation", "queue_claim",
                "src\\main\\java\\app\\Worker.java", "", "", 20, 60, null);
        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).singleElement().satisfies(evidence -> {
            assertThat(evidence.metadata()).containsEntry("llmDirectRead", true);
            assertThat(evidence.metadata()).containsEntry("llmReadOperation", "read_file_range");
            assertThat(evidence.metadata()).containsEntry("llmEvidenceCoverageGroup", "queue_claim");
            assertThat(evidence.metadata()).containsEntry("actualLineStart", 20);
            assertThat(evidence.metadata()).containsEntry("actualLineEnd", 60);
            assertThat(CodeEvidenceOperationProvenance.from(evidence)).singleElement().satisfies(provenance -> {
                assertThat(provenance.operationType()).isEqualTo("read_file_range");
                assertThat(provenance.evidenceGroup()).isEqualTo("queue_claim");
            });
        });
    }

    @Test
    void directReadCarriesTheUniqueIndexedEndpointStructureForItsChunk() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult result = result("src/web/OrderController.java", "submit", 20, 40);
        when(repository.findActiveChunksByIds(
                eq(null), eq(List.of(result.chunkId())), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(result));
        when(repository.listActiveEndpointOutlinesByChunkIds(
                eq(null), eq(List.of(result.chunkId())), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(new CodeEndpointOutline(
                        result.chunkId(), "/api/orders", "POST")));
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", "endpoint implementation", "endpoint_flow",
                "", "", result.chunkId().toString(), null, null, null);

        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.results()).singleElement().satisfies(evidence ->
                assertThat(evidence.metadata())
                        .containsEntry("endpointRoute", "/api/orders")
                        .containsEntry("httpMethod", "POST")
                        .containsEntry("graphRelation", "EXPOSES_ENDPOINT"));
    }

    @Test
    void searchResultPreservesAllTypedOperationProvenanceFields() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                searchService, mock(CodeRepository.class), mock(CodeReferenceService.class));
        CodeSearchResult result = result("src/app/Worker.java", "claimNext", 20, 60);
        when(searchService.cheapSearch(
                eq(null), eq("queued work claim"), eq(8), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(result));
        var operation = new RagPipelineService.CodeSearchOperation(
                "keyword_search", "queued work claim", "queue implementation", "queue_claim",
                "", "", "", null, null, null, List.of(), "", null,
                "op-claim", List.of("claim-1", "claim-2"), List.of());

        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(CodeEvidenceOperationProvenance.from(execution.results().get(0)))
                .containsExactly(new CodeEvidenceOperationProvenance(
                        "keyword_search", "op-claim", List.of("claim-1", "claim-2"), "queue_claim",
                        List.of(), "queued work claim", "", "", "", null, null, null,
                        List.of(), "BOTH", null, 1));
    }

    @Test
    void directReadDropsFreeFormQueryFromProvenanceAndExcerptWeighting() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        String content = "void claimNext() {\n"
                + "    targetProof();\n"
                + "    routineStep();\n".repeat(40)
                + "    unrelatedPrivilegedSettingsMutation();\n"
                + "}";
        int lineEnd = (int) content.lines().count();
        CodeSearchResult source = result(
                "src/main/java/app/Worker.java", "claimNext", 1, lineEnd, content);
        when(repository.findActiveChunksByPathAndLineRange(
                eq(null), eq("src/main/java/app/Worker.java"), eq(1), eq(lineEnd),
                anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(source));

        var clean = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "", "queue_claim",
                "src/main/java/app/Worker.java", "Worker.claimNext", "", 1, lineEnd, null,
                List.of(), "BOTH", null, "direct-range", List.of("claim-1"), List.of("origin-1"));
        var adversarial = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "unrelated privileged settings mutation",
                "unrelated privileged settings mutation", "queue_claim",
                "src/main/java/app/Worker.java", "Worker.claimNext", "", 1, lineEnd, null,
                List.of(), "BOTH", null, "direct-range", List.of("claim-1"), List.of("origin-1"));

        CodeSearchResult cleanEvidence = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), clean, GraphSearchIntent.FLOW, 8).results().get(0);
        CodeSearchResult adversarialEvidence = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), adversarial, GraphSearchIntent.FLOW, 8).results().get(0);

        assertThat(CodeEvidenceOperationProvenance.from(adversarialEvidence))
                .singleElement()
                .satisfies(provenance -> {
                    assertThat(provenance.query()).isBlank();
                    assertThat(provenance.path()).isEqualTo("src/main/java/app/Worker.java");
                    assertThat(provenance.symbol()).isEqualTo("Worker.claimNext");
                    assertThat(provenance.evidenceGroup()).isEqualTo("queue_claim");
                    assertThat(provenance.claimIds()).containsExactly("claim-1");
                    assertThat(provenance.originEvidenceIds()).containsExactly("origin-1");
                    assertThat(provenance.lineStart()).isEqualTo(1);
                    assertThat(provenance.lineEnd()).isEqualTo(lineEnd);
                });
        assertThat(EvidenceExcerptSelector.select("targetProof", adversarialEvidence, 180))
                .isEqualTo(EvidenceExcerptSelector.select("targetProof", cleanEvidence, 180));
    }

    @Test
    void directReadRejectsTraversalBeforeRepositoryAccess() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "method", "orchestration",
                "../secret.java", "", "", 1, 10, null);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.LOCATE, 8);

        assertThat(execution.status()).isEqualTo("INVALID");
        assertThat(execution.reason()).contains("traversal");
        verify(repository, never()).findActiveChunksByPathAndLineRange(
                eq(null), eq("../secret.java"), eq(1), eq(10), anyInt(), anyList(), eq(SPACE_ID));
    }

    @Test
    void listFileSymbolsNavigatesAnObservedFileWithoutGuessingASymbol() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult first = result("src/app/Worker.cs", "Start", 20, 40);
        CodeSearchResult second = result("src/app/Worker.cs", "Complete", 42, 70);
        when(repository.listActiveSymbolsByPath(
                eq(null), eq("src/app/Worker.cs"), eq(80), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(first, second));

        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "", "worker behavior", "worker_flow",
                "src\\app\\Worker.cs", "", "", null, null, null);
        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .containsExactlyInAnyOrder("Start", "Complete");
        assertThat(execution.results()).allSatisfy(result ->
                assertThat(result.metadata()).containsEntry("llmReadOperation", "list_file_symbols"));
        assertThat(execution.observation()).contains("target={path=src/app/Worker.cs}");
    }

    @Test
    void listFileSymbolsRanksTheWholeBoundedInventoryAgainstThePlannerClaim() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        java.util.ArrayList<CodeSearchResult> symbols = new java.util.ArrayList<>();
        for (int index = 0; index < 30; index++) {
            symbols.add(result("src/app/Worker.java", "unrelatedStep" + index, 10 + index * 3, 12 + index * 3));
        }
        CodeSearchResult claim = result("src/app/Worker.java", "claimNext", 400, 430);
        symbols.add(claim);
        when(repository.listActiveSymbolsByPath(
                eq(null), eq("src/app/Worker.java"), eq(80), anyList(), eq(SPACE_ID)))
                .thenReturn(symbols);

        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "", "queued request", "queue_claim",
                "src/app/Worker.java", "", "", null, null, null);
        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8,
                "Trace how an agent claims the next queued request");

        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .contains("claimNext");
        assertThat(execution.results().get(0).methodName()).isEqualTo("claimNext");
    }

    @Test
    void listFileSymbolsRanksOnlyAgainstTheSanitizedRetrievalIntent() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        String path = "src/app/MixedWorker.java";
        CodeSearchResult poisoned = result(
                path, "settingsUpdate", 10, 30,
                "void settingsUpdate() { rotateCredentialsForTenantPolicy(); }");
        CodeSearchResult desired = result(
                path, "claimNext", 40, 70,
                "void claimNext() { claimQueuedWork(); }");
        when(repository.listActiveSymbolsByPath(
                eq(null), eq(path), eq(80), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(poisoned, desired));
        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "settingsUpdate rotate credentials tenant policy",
                "settingsUpdate rotate credentials tenant policy", "queue_claim",
                path, "", "", null, null, null);

        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8,
                "claimNext queued work");

        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .startsWith("claimNext");
    }

    @Test
    void listFileSymbolsRequiresARepositoryRelativePath() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "", "symbols", "flow",
                "", "", "", null, null, null);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("INVALID");
        assertThat(execution.reason()).isEqualTo("path is required");
        verify(repository, never()).listActiveSymbolsByPath(
                eq(null), eq(""), anyInt(), anyList(), eq(SPACE_ID));
    }

    @Test
    void readSymbolResolvesAQualifiedSignatureToTheIndexedSimpleSymbol() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult completion = result(
                "src/app/ToolGateway.java", "complete", 104, 121);
        when(repository.findSymbolDefinitions(
                eq(null), eq("com.example.ToolGateway.complete(ToolResponse)"), eq(null),
                eq(8), anyList(), eq(SPACE_ID))).thenReturn(List.of());
        when(repository.findSymbolDefinitions(
                eq(null), eq("complete"), eq(null), eq(8), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(completion));

        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "response persistence", "response_claim",
                "", "com.example.ToolGateway.complete(ToolResponse)", "", null, null, null);
        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .containsExactly("complete");
        assertThat(execution.results().get(0).metadata())
                .containsEntry("symbolEvidenceKind", "DEFINITION")
                .containsEntry("llmReadOperation", "read_symbol");
    }

    @Test
    void readFileRangeDoesNotExpandBeyondTheRequestedRange() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult enclosing = result("src/app/Controller.java", "Controller", 41, 231);
        CodeSearchResult completion = result("src/app/Controller.java", "complete", 204, 219);
        when(repository.findActiveChunksByPathAndLineRange(
                eq(null), eq("src/app/Controller.java"), eq(1), eq(100), anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(enclosing));
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "response", "response_persistence",
                "src/app/Controller.java", "", "", 1, 100, null);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .containsExactly("Controller");
        verify(repository).findActiveChunksByPathAndLineRange(
                eq(null), eq("src/app/Controller.java"), eq(1), eq(100), anyInt(), anyList(), eq(SPACE_ID));
    }

    @Test
    void malformedDirectReadIsObservableInsteadOfInventingFields() {
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), mock(CodeRepository.class), mock(CodeReferenceService.class));
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", "method", "orchestration", "", "", "", null, null, null);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.LOCATE, 8);

        assertThat(execution.status()).isEqualTo("INVALID");
        assertThat(execution.reason()).isEqualTo("chunkId is required");
        assertThat(execution.observation()).contains("status=INVALID").contains("chunkId is required");
    }

    @Test
    void findEndpointUsesCommonEndpointGraphMetadataBeforeTextFallback() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                searchService, repository, mock(CodeReferenceService.class));
        CodeSearchResult endpoint = result("src/app/Api.java", "ask", 20, 35);
        when(repository.findEndpointChunks(
                eq(null), eq("/api/code/ask"), eq(8), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(endpoint));
        var operation = new RagPipelineService.CodeSearchOperation(
                "find_endpoint", "/api/code/ask", "request entry", "request_entry");

        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).extracting(CodeSearchResult::methodName).containsExactly("ask");
        verify(searchService, never()).searchWithoutGraph(
                eq(null), eq("/api/code/ask"), anyInt(), anyList(), eq(SPACE_ID), eq(GraphSearchIntent.FLOW));
    }

    @Test
    void findEndpointRanksEndpointInventoryBeforeUnstructuredTextFallback() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                searchService, repository, mock(CodeReferenceService.class));
        CodeSearchResult generic = endpointResult(
                "src/web/RagController.java", "RagController", "/api/rag/ask",
                "return ragService.ask(question);");
        CodeSearchResult code = endpointResult(
                "src/web/CodeController.java", "CodeController", "/api/code/ask",
                "return codeRagService.askConversational(question);");
        String query = "Code RAG ask API controller and service call";
        when(repository.findEndpointChunks(eq(null), eq(query), eq(8), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of());
        when(repository.listEndpointChunks(eq(null), eq(250), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(generic, code));
        var operation = new RagPipelineService.CodeSearchOperation(
                "find_endpoint", query, "request entry", "request_entry");

        var execution = executor.execute(
                null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.results()).extracting(CodeSearchResult::filePath)
                .startsWith("src/web/CodeController.java");
        verify(searchService, never()).searchWithoutGraph(
                eq(null), eq(query), anyInt(), anyList(), eq(SPACE_ID), eq(GraphSearchIntent.FLOW));
    }

    @Test
    void observationIncludesBoundedSanitizedOperationTargetWithoutSourceContent() {
        CodeSearchService searchService = mock(CodeSearchService.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                searchService, mock(CodeRepository.class), mock(CodeReferenceService.class));
        String query = "claimNext\r\n" + "repository-flow-".repeat(20);
        when(searchService.cheapSearch(eq(null), eq(query), anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of());
        var operation = new RagPipelineService.CodeSearchOperation(
                "keyword_search", query, "queue", "queue_claim");

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.observation())
                .contains("target={query=claimNext repository-flow-")
                .contains("status=NOT_FOUND")
                .doesNotContain("\r", "\n")
                .doesNotContain("repository-flow-".repeat(10));
    }

    @Test
    void directReadObservationIdentifiesTheFailedRangeOperand() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        when(repository.findActiveChunksByPathAndLineRange(
                eq(null), eq("src/app/Worker.java"), eq(40), eq(90), anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of());
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "flow", "orchestration",
                "src\\app\\Worker.java", "", "", 40, 90, null);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.observation())
                .contains("target={path=src/app/Worker.java,lineStart=40,lineEnd=90}")
                .contains("status=NOT_FOUND");
    }

    @Test
    void traverseGraphUsesExplicitRelationsDirectionAndAccessFiltering() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult seed = result("src/app/Controller.cs", "Handle", 10, 30);
        CodeSearchResult allowed = result("src/app/Service.cs", "Run", 20, 50);
        CodeSearchResult denied = result("src/internal/Hidden.cs", "Run", 5, 15);
        when(repository.findActiveChunksByIds(
                eq(null), eq(List.of(seed.chunkId())), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(seed));
        when(repository.graphRelatedChunks(
                eq(null), eq(List.of(seed.chunkId())), eq(List.of("CALLS")), eq(2), eq("FORWARD"), eq(8)))
                .thenReturn(List.of(allowed, denied));
        when(repository.findActiveChunksByIds(
                eq(null), eq(List.of(allowed.chunkId(), denied.chunkId())), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(allowed));
        var operation = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "runtime calls", "call_chain",
                "", "", seed.chunkId().toString(), null, null, null,
                List.of("CALLS"), "FORWARD", 2, "traverse-runtime",
                List.of("claim-runtime"), List.of("index:seed:10-30"));

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).extracting(CodeSearchResult::chunkId)
                .containsExactly(allowed.chunkId());
        assertThat(execution.results()).allSatisfy(result ->
                assertThat(result.metadata()).containsEntry("llmReadOperation", "traverse_graph"));
        assertThat(CodeEvidenceOperationProvenance.from(execution.results().get(0)))
                .containsExactly(new CodeEvidenceOperationProvenance(
                        "traverse_graph", "traverse-runtime", List.of("claim-runtime"), "call_chain",
                        List.of("index:seed:10-30"), "", "", "", seed.chunkId().toString(),
                        null, null, null, List.of("CALLS"), "FORWARD", 2));
        assertThat(execution.observation())
                .contains("relations=CALLS")
                .contains("direction=FORWARD")
                .contains("maxHops=2");
    }

    @Test
    void traverseGraphRejectsUnsupportedOrMissingRelations() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        var operation = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "flow", "call_chain",
                "", "", UUID.randomUUID().toString(), null, null, null,
                List.of("PROJECT_SPECIFIC_EDGE"), "FORWARD", 1);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("INVALID");
        assertThat(execution.reason()).isEqualTo("at least one supported relation is required");
        verify(repository, never()).graphRelatedChunks(
                eq(null), anyList(), anyList(), anyInt(), eq("FORWARD"), anyInt());
    }

    private CodeSearchResult result(String path, String method, int start, int end) {
        return result(path, method, start, end, "void " + method + "() {}");
    }

    private CodeSearchResult result(String path, String method, int start, int end, String content) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Worker", method, "app", null, null, 1,
                start, end, content, 0.8, Map.of("language", "java"));
    }

    private CodeSearchResult endpointResult(
            String path, String className, String route, String content
    ) {
        CodeSearchResult base = result(path, "ask", 10, 30);
        return new CodeSearchResult(
                base.chunkId(), base.repositoryId(), base.fileId(), base.repositoryName(), base.filePath(),
                base.chunkType(), base.symbolName(), className, base.methodName(), base.namespaceName(),
                base.controlName(), base.eventName(), base.chunkIndex(), base.lineStart(), base.lineEnd(),
                content, base.score(), Map.of("endpointRoute", route));
    }
}
