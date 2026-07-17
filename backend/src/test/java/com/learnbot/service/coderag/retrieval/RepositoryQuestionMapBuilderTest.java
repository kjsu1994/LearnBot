package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.ActiveCodeIndexIdentity;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;

import com.learnbot.dto.CodeAnalysisDiagnosticSummary;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.CodeSymbolOutline;
import com.learnbot.dto.IndexingJobFailureSummary;
import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryQuestionMapBuilderTest {
    @Test
    void buildsVersionedMapFromAnchorsRelationsDiagnosticsAndFailures() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID seedId = UUID.randomUUID();
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        CodeSearchResult projectContext = result(
                repositoryId, indexVersion, UUID.randomUUID(),
                "__learnbot__/project-context.md", "repository_summary", null,
                "Repository summary with backend modules", Map.of());
        when(repository.findActiveChunksByPath(
                eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of(projectContext));
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of(
                new CodeAnalysisDiagnosticSummary(
                        UUID.randomUUID(), repositoryId, indexVersion, "JAVA_SEMANTIC",
                        "Java analyzer", "PARTIAL", "SOURCE", 10, 9, 1, 30, 2,
                        50, 60, 120, "one source file failed", Map.of(), OffsetDateTime.now())
        ));
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of(
                new IndexingJobFailureSummary(
                        UUID.randomUUID(), indexVersion, repositoryId, null,
                        "CODE_GRAPH", "non-fatal graph failure", OffsetDateTime.now())
        ));
        CodeSearchResult anchor = result(
                repositoryId, indexVersion, seedId,
                "backend/CodeGraphBuilder.java", "method", "buildWithDiagnostics",
                "try { merge(nodes, edges, result.graph()); } catch (RuntimeException ex) { diagnostic(ex); }",
                Map.of(
                        "retrievalSource", "graph_expansion",
                        "graphPathNodes", List.of("CodeIndexingService.buildCodeGraph", "CodeGraphBuilder.buildWithDiagnostics"),
                        "graphEdgeTypes", List.of("CALLS"),
                        "graphEvidenceKind", "resolved",
                        "graphConfidence", 0.95,
                        "indexVersion", indexVersion.toString()
                ));

        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), List.of(anchor));

        assertThat(map.indexVersion()).isEqualTo(indexVersion.toString());
        assertThat(map.plannerContext())
                .contains("RepositoryEvidenceMap schemaVersion=3 revision=0")
                .contains("buildWithDiagnostics")
                .contains("from=CodeIndexingService.buildCodeGraph type=CALLS to=CodeGraphBuilder.buildWithDiagnostics")
                .contains("stage=JAVA_SEMANTIC analyzer=Java analyzer status=PARTIAL")
                .contains("stage=CODE_GRAPH", "non-fatal graph failure");
    }

    @Test
    void updatesMapOnlyFromExplicitOperationResultsWithoutImplicitGraphWidening() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(
                eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());

        CodeSearchResult bootstrap = result(
                repositoryId, indexVersion, UUID.randomUUID(), "backend/RouteService.java", "method", "route",
                "return fallback();", Map.of());
        CodeSearchResult discovered = result(
                repositoryId, indexVersion, UUID.randomUUID(), "backend/CodeGraphBuilder.java", "method",
                "buildWithDiagnostics", "CodeGraph base = buildBase(); merge(base, semantic);", Map.of("llmDirectRead", true));
        CodeSearchResult neighbor = result(
                repositoryId, indexVersion, UUID.randomUUID(), "backend/GraphRepository.java", "method",
                "persistGraph", "void persistGraph() {}", Map.of(
                        "retrievalSource", "graph_expansion",
                        "graphPathNodes", List.of("CodeGraphBuilder.buildWithDiagnostics", "GraphRepository.persistGraph"),
                        "graphEdgeTypes", List.of("CALLS"),
                        "graphEvidenceKind", "resolved"));
        CodeSearchResult unrequestedNeighbor = result(
                repositoryId, indexVersion, UUID.randomUUID(), "backend/HiddenGraphRepository.java", "method",
                "hiddenPersist", "void hiddenPersist() {}", Map.of("retrievalSource", "graph_expansion"));
        when(repository.graphRelatedChunks(
                eq(repositoryId), anyList(), anyList(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of(unrequestedNeighbor));
        RepositoryQuestionMapBuilder builder = new RepositoryQuestionMapBuilder(repository);
        var initial = builder.build(repositoryId, null, List.of(UUID.randomUUID()), "graph failure", List.of(bootstrap));

        var update = builder.update(initial, null, List.of(UUID.randomUUID()),
                List.of(discovered, neighbor), List.of("operationId=op-2 status=COMPLETED"));

        assertThat(update.identityChanged()).isFalse();
        assertThat(update.map().revision()).isEqualTo(1);
        assertThat(update.map().evidenceProgress()).isTrue();
        assertThat(update.map().delta().addedEvidenceIds())
                .contains(CodeEvidenceId.from(discovered), CodeEvidenceId.from(neighbor));
        assertThat(update.map().observesChunk(neighbor.chunkId().toString())).isTrue();
        assertThat(update.map().observesChunk(unrequestedNeighbor.chunkId().toString())).isFalse();
        assertThat(update.map().plannerContext())
                .contains("revision=1", "[MAP_DELTA] from=0 to=1", "buildWithDiagnostics", "persistGraph")
                .contains("from=CodeGraphBuilder.buildWithDiagnostics type=CALLS to=GraphRepository.persistGraph")
                .contains("operationId=op-2 status=COMPLETED")
                .doesNotContain("HiddenGraphRepository", "hiddenPersist");
        verify(repository, never()).graphRelatedChunks(
                eq(repositoryId), anyList(), anyList(), anyInt(), anyString(), anyInt());
    }

    @Test
    void promptSelectionKeepsFileDiversityWhenOneLargeFileDominatesCandidates() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(
                eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        java.util.ArrayList<CodeSearchResult> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            candidates.add(result(repositoryId, indexVersion, UUID.randomUUID(),
                    "backend/a/LargeService.java", "method", "method" + index,
                    "void method" + index + "() {}", Map.of()));
        }
        candidates.add(result(repositoryId, indexVersion, UUID.randomUUID(),
                "backend/z/ExecutionRepository.java", "method", "persistResponse",
                "void persistResponse() {}", Map.of()));

        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "response persistence", candidates);

        assertThat(map.plannerContext()).contains("backend/z/ExecutionRepository.java", "persistResponse");
    }

    @Test
    void discoveredLargeFileAutomaticallyIncludesSemanticSymbolInventory() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        String path = "backend/LargeGatewayService.java";
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        java.util.ArrayList<CodeSymbolOutline> symbols = new java.util.ArrayList<>();
        for (int index = 1; index <= 200; index++) {
            String name = index == 200 ? "completeToolResponse" : "method" + index;
            symbols.add(new CodeSymbolOutline(
                    "symbol-" + index, path, "method", name, "LargeGatewayService." + name,
                    index * 10, index * 10 + 5, UUID.randomUUID(), "java", "COMPILER_SEMANTIC", 200));
        }
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(symbols);
        CodeSearchResult candidate = result(repositoryId, indexVersion, UUID.randomUUID(), path,
                "type", "LargeGatewayService", "class LargeGatewayService {}", Map.of());

        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "how is the response completed", List.of(candidate));

        assertThat(map.plannerContext())
                .hasSizeLessThanOrEqualTo(28_000)
                .contains("[FILE_SYMBOL_INVENTORIES]", "shown=200 total=200 complete=true")
                .contains("authorities=[COMPILER_SEMANTIC] analyzers=[java]")
                .contains("completeToolResponse");
        assertThat(map.containsEvidenceId("symbol-200")).isTrue();
    }

    @Test
    void operationRevisionPrioritizesBoundedHeadMiddleTailImplementationEvidence() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(
                eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(List.of());
        CodeSearchResult bootstrap = result(
                repositoryId, indexVersion, UUID.randomUUID(), "src/Seed.java", "method", "seed",
                "void seed() {}", Map.of());
        String longBody = "HEAD_MARKER();\n" + "a".repeat(1_500)
                + "\nMIDDLE_MARKER();\n" + "b".repeat(1_500) + "\nTAIL_MARKER();";
        CodeSearchResult directRead = result(
                repositoryId, indexVersion, UUID.randomUUID(), "src/Worker.java", "method", "executeWork",
                longBody, Map.of("llmDirectRead", true));
        RepositoryQuestionMapBuilder builder = new RepositoryQuestionMapBuilder(repository);
        var initial = builder.build(
                repositoryId, null, List.of(UUID.randomUUID()), "작업 실행 흐름", List.of(bootstrap));

        var updated = builder.update(
                initial, null, List.of(UUID.randomUUID()), List.of(directRead), List.of("read completed")).map();
        String context = updated.plannerContext();

        assertThat(updated.revision()).isEqualTo(1);
        assertThat(context)
                .hasSizeLessThanOrEqualTo(14_000)
                .contains("HEAD_MARKER", "MIDDLE_MARKER", "TAIL_MARKER")
                .contains("... [middle excerpt] ...", "... [tail excerpt] ...");
        assertThat(context.indexOf("[DIRECT_BODIES_AND_DEFINITIONS]"))
                .isLessThan(context.indexOf("[FILE_SYMBOL_INVENTORIES]"));
        assertThat(initial.plannerContext().indexOf("[FILE_SYMBOL_INVENTORIES]"))
                .isLessThan(initial.plannerContext().indexOf("[DIRECT_BODIES_AND_DEFINITIONS]"));
    }

    private CodeSearchResult result(
            UUID repositoryId,
            UUID indexVersion,
            UUID chunkId,
            String path,
            String chunkType,
            String symbol,
            String content,
            Map<String, Object> extraMetadata
    ) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(extraMetadata);
        metadata.put("indexVersion", indexVersion.toString());
        return new CodeSearchResult(
                chunkId, repositoryId, UUID.randomUUID(), "repo", path, chunkType,
                symbol, "CodeGraphBuilder", symbol, "com.example", null, null,
                0, 1, 80, content, 0.9, Map.copyOf(metadata));
    }
}
