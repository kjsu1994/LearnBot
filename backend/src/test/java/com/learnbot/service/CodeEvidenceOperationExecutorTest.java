package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
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
        });
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
                eq(null), eq("src/app/Worker.cs"), eq(24), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(first, second));

        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "", "worker behavior", "worker_flow",
                "src\\app\\Worker.cs", "", "", null, null, null);
        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .containsExactly("Start", "Complete");
        assertThat(execution.results()).allSatisfy(result ->
                assertThat(result.metadata()).containsEntry("llmReadOperation", "list_file_symbols"));
        assertThat(execution.observation()).contains("target={path=src/app/Worker.cs}");
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
    void readFileRangeExpandsToTheObservedEnclosingStructureWithinTheBound() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeEvidenceOperationExecutor executor = new CodeEvidenceOperationExecutor(
                mock(CodeSearchService.class), repository, mock(CodeReferenceService.class));
        CodeSearchResult enclosing = result("src/app/Controller.java", "Controller", 41, 231);
        CodeSearchResult completion = result("src/app/Controller.java", "complete", 204, 219);
        when(repository.findActiveChunksByPathAndLineRange(
                eq(null), eq("src/app/Controller.java"), eq(1), eq(100), anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(enclosing));
        when(repository.findActiveChunksByPathAndLineRange(
                eq(null), eq("src/app/Controller.java"), eq(1), eq(231), anyInt(), anyList(), eq(SPACE_ID)))
                .thenReturn(List.of(enclosing, completion));
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "response", "response_persistence",
                "src/app/Controller.java", "", "", 1, 100, null);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.results()).extracting(CodeSearchResult::methodName)
                .contains("Controller", "complete");
        verify(repository).findActiveChunksByPathAndLineRange(
                eq(null), eq("src/app/Controller.java"), eq(1), eq(231), anyInt(), anyList(), eq(SPACE_ID));
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
                List.of("CALLS"), "FORWARD", 2);

        var execution = executor.execute(null, SPACE_ID, List.of(SPACE_ID), operation, GraphSearchIntent.FLOW, 8);

        assertThat(execution.status()).isEqualTo("COMPLETED");
        assertThat(execution.results()).extracting(CodeSearchResult::chunkId)
                .containsExactly(allowed.chunkId());
        assertThat(execution.results()).allSatisfy(result ->
                assertThat(result.metadata()).containsEntry("llmReadOperation", "traverse_graph"));
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
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Worker", method, "app", null, null, 1,
                start, end, "void " + method + "() {}", 0.8, Map.of("language", "java"));
    }
}
