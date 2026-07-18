package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.ActiveCodeIndexIdentity;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeNavigationHandle;

import com.learnbot.dto.CodeAnalysisDiagnosticSummary;
import com.learnbot.dto.CodeGraphRelationOutline;
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
                .contains("RepositoryEvidenceMap schemaVersion=4 revision=0")
                .contains("buildWithDiagnostics")
                .contains("seedSymbol=CodeIndexingService.buildCodeGraph")
                .contains("relation=CALLS", "neighborSymbol=CodeGraphBuilder.buildWithDiagnostics")
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
                .contains("seedSymbol=CodeGraphBuilder.buildWithDiagnostics")
                .contains("relation=CALLS", "neighborSymbol=GraphRepository.persistGraph")
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

    @Test
    void projectsTypedCallHandleAsSymbolOnlyObservedOperand() {
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
        String callerPath = "src/Workflow.java";
        CodeSearchResult caller = result(repositoryId, indexVersion, UUID.randomUUID(), callerPath,
                "method", "run", "worker.finish(task);", Map.of());
        String sourceEvidenceId = CodeEvidenceId.from(caller);
        CodeNavigationHandle handle = CodeNavigationHandle.of(
                CodeNavigationHandle.Kind.CALL, callerPath, "worker.finish", caller.chunkId(),
                caller.lineStart(), caller.lineEnd(), sourceEvidenceId);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(), List.of(), List.of(), List.of(), List.of(handle), List.of());

        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "follow the workflow", List.of(caller), ir);
        RagPipelineService.CodeSearchOperation read = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "workflow", "workflow",
                "", "finish", "", null, null, null, List.of(), "BOTH", null,
                "read-finish", List.of("claim-1"), List.of());

        assertThat(map.plannerContext())
                .contains("[CODE_INTELLIGENCE_NAVIGATION_HANDLES] navigationOnly=true")
                .contains("kind=CALL", "observedSymbol=worker.finish", "canonicalSymbol=finish")
                .contains("sourceEvidenceId=" + sourceEvidenceId);
        assertThat(map.observesSymbol("", "finish")).isTrue();
        assertThat(map.observesCallFromPath(callerPath, "worker.finish")).isTrue();
        assertThat(map.originEvidenceIdsFor(read)).containsExactly(sourceEvidenceId);
        assertThat(map.observesSymbol("", "inventedCall")).isFalse();
    }

    @Test
    void projectsActiveGraphNeighborAsNavigationOnlyObservedOperandWithoutLoadingItsBody() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID seedChunkId = UUID.randomUUID();
        UUID neighborChunkId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
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
        when(repository.listActiveGraphRelationOutlinesByChunkIds(
                eq(repositoryId), eq(indexVersion), anyList(), eq(64), any(), any()))
                .thenReturn(List.of(new CodeGraphRelationOutline(
                        edgeId, seedChunkId, "FORWARD", "CALLS",
                        "receiveEvent", "Widget.receiveEvent", "src/Widget.cs",
                        "applyState", "Widget.applyState", "src/Widget.cs",
                        neighborChunkId, 0.94)));
        CodeSearchResult seed = result(repositoryId, indexVersion, seedChunkId,
                "src/Widget.cs", "method", "receiveEvent", "applyState();", Map.of());

        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(spaceId), "trace event state application", List.of(seed));
        String relationEvidenceId = indexVersion + ":graph-relation:" + edgeId;
        RagPipelineService.CodeSearchOperation read = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "state", "event_state",
                "src/Widget.cs", "Widget.applyState", "", null, null, null,
                List.of(), "BOTH", null, "read-state", List.of("claim-1"), List.of());

        assertThat(map.plannerContext())
                .contains("[INDEXED_GRAPH_RELATION_HANDLES] navigationOnly=true")
                .contains("seedSymbol=receiveEvent", "relation=CALLS", "direction=FORWARD")
                .contains("neighborPath=src/Widget.cs", "neighborSymbol=applyState")
                .contains("neighborChunkId=" + neighborChunkId)
                .doesNotContain("SECRET_NEIGHBOR_BODY");
        assertThat(map.observesPath("src/Widget.cs")).isTrue();
        assertThat(map.observesSymbol("src/Widget.cs", "Widget.applyState")).isTrue();
        assertThat(map.observesChunk(neighborChunkId.toString())).isTrue();
        assertThat(map.originEvidenceIdsFor(read)).contains(relationEvidenceId);
        assertThat(map.isDirectProofEvidenceId(relationEvidenceId)).isFalse();
        verify(repository, never()).graphRelatedChunks(
                eq(repositoryId), anyList(), anyList(), anyInt(), anyString(), anyInt());
    }

    @Test
    void operationReadUpgradesTheSameBootstrapChunkAndRecordsMapProgress() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
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
        CodeSearchResult bootstrap = result(repositoryId, indexVersion, chunkId,
                "src/Worker.java", "method", "run", "void run() { finish(); }", Map.of());
        CodeSearchResult directRead = result(repositoryId, indexVersion, chunkId,
                "src/Worker.java", "method", "run", "void run() { finish(); }", Map.of(
                        "llmDirectRead", true,
                        "llmReadFulfilled", true,
                        "llmRetrievalIterationEvidence", true));
        RepositoryQuestionMapBuilder builder = new RepositoryQuestionMapBuilder(repository);
        var initial = builder.build(
                repositoryId, null, List.of(UUID.randomUUID()), "trace run", List.of(bootstrap));

        var update = builder.update(
                initial, null, List.of(UUID.randomUUID()), "trace run", List.of(directRead),
                List.of("phase=INITIAL_PLAN status=COMPLETED"), CodeEvidenceIr.empty());
        String evidenceId = CodeEvidenceId.from(directRead);
        var upgraded = update.map().evidence().get(evidenceId);

        assertThat(update.map().evidenceProgress()).isTrue();
        assertThat(update.map().delta().updatedEvidenceIds()).contains(evidenceId);
        assertThat(upgraded.origin()).isEqualTo("OPERATION");
        assertThat(upgraded.discoveredRevision()).isEqualTo(1);
        assertThat(upgraded.source().metadata())
                .containsEntry("llmDirectRead", true)
                .containsEntry("llmReadFulfilled", true);
        assertThat(update.map().plannerContext())
                .contains("origin=OPERATION", "phase=INITIAL_PLAN status=COMPLETED");
    }

    @Test
    void navigationPromptRetainsHeadMiddleAndTailCallsFromALongCallable() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
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
        CodeSearchResult caller = result(repositoryId, indexVersion, chunkId,
                "src/Workflow.cs", "method", "Run", "void Run() {}", Map.of());
        String sourceEvidenceId = CodeEvidenceId.from(caller);
        List<CodeNavigationHandle> handles = new java.util.ArrayList<>();
        for (int index = 0; index < 30; index++) {
            handles.add(CodeNavigationHandle.of(
                    CodeNavigationHandle.Kind.CALL, caller.filePath(), "step" + index,
                    chunkId, 100 + index, 100 + index, sourceEvidenceId));
        }
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(), List.of(), List.of(), List.of(), handles, List.of());

        String context = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "trace workflow", List.of(caller), ir)
                .plannerContext();

        assertThat(context)
                .contains("observedSymbol=step0", "observedSymbol=step14", "observedSymbol=step29")
                .doesNotContain("observedSymbol=step20\n");
    }

    @Test
    void updatedPlannerMapPrioritizesExactReadRelationsAndStructuralCoverage() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID directChunkId = UUID.randomUUID();
        UUID neighborChunkId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
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
        when(repository.listActiveGraphRelationOutlinesByChunkIds(
                eq(repositoryId), eq(indexVersion), anyList(), eq(64), any(), any()))
                .thenAnswer(invocation -> {
                    List<UUID> seeds = invocation.getArgument(2);
                    if (!seeds.contains(directChunkId)) return List.of();
                    return List.of(new CodeGraphRelationOutline(
                            edgeId, directChunkId, "FORWARD", "CALLS",
                            "coordinate", "Pipeline.coordinate", "src/Pipeline.java",
                            "finalizeStage", "Pipeline.finalizeStage", "src/Pipeline.java",
                            neighborChunkId, 0.98));
                });

        List<CodeSearchResult> bootstrap = new java.util.ArrayList<>();
        List<CodeNavigationHandle> handles = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            CodeSearchResult noise = result(repositoryId, indexVersion, UUID.randomUUID(),
                    "src/Noise" + index + ".java", "method", "noise" + index,
                    "void noise" + index + "() { distract" + index + "(); }", Map.of());
            bootstrap.add(noise);
            handles.add(CodeNavigationHandle.of(
                    CodeNavigationHandle.Kind.CALL, noise.filePath(), "distract" + index,
                    noise.chunkId(), 10, 10, CodeEvidenceId.from(noise)));
        }
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "read_symbol", "read-coordinate", List.of("claim-1"), "pipeline_lifecycle",
                List.of("origin-1"), "", "src/Pipeline.java", "coordinate", "",
                null, null, null, List.of(), "BOTH", null);
        CodeSearchResult direct = result(repositoryId, indexVersion, directChunkId,
                "src/Pipeline.java", "method", "coordinate", "void coordinate() {}", Map.of(
                        "llmDirectRead", true,
                        "llmReadFulfilled", true,
                        CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance)));
        String directEvidenceId = CodeEvidenceId.from(direct);
        for (int index = 0; index < 30; index++) {
            handles.add(CodeNavigationHandle.of(
                    CodeNavigationHandle.Kind.CALL, direct.filePath(), "stage" + index,
                    directChunkId, 100 + index, 100 + index, directEvidenceId));
        }
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(), List.of(), List.of(), List.of(), handles, List.of());

        RepositoryQuestionMapBuilder builder = new RepositoryQuestionMapBuilder(repository);
        var initial = builder.build(
                repositoryId, null, List.of(UUID.randomUUID()), "trace the pipeline", bootstrap);
        String context = builder.update(
                        initial, null, List.of(UUID.randomUUID()), "trace the pipeline lifecycle",
                        List.of(direct), List.of("read completed"), ir)
                .map().plannerContext();

        assertThat(context)
                .hasSizeLessThanOrEqualTo(14_000)
                .contains("symbol=coordinate", "neighborSymbol=finalizeStage")
                .contains("observedSymbol=stage0", "observedSymbol=stage7",
                        "observedSymbol=stage14", "observedSymbol=stage21", "observedSymbol=stage29");
        assertThat(context.indexOf("[INDEXED_GRAPH_RELATION_HANDLES]"))
                .isLessThan(context.indexOf("[CODE_INTELLIGENCE_NAVIGATION_HANDLES]"));
    }

    @Test
    void initialPlannerMapDiversifiesRelationSourcesBeforeBootstrapBodies() {
        CodeRepository repository = mock(CodeRepository.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID dominantChunkId = UUID.randomUUID();
        UUID independentChunkId = UUID.randomUUID();
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
        when(repository.listActiveGraphRelationOutlinesByChunkIds(
                eq(repositoryId), eq(indexVersion), anyList(), eq(64), any(), any()))
                .thenAnswer(invocation -> {
                    List<CodeGraphRelationOutline> outlines = new java.util.ArrayList<>();
                    for (int index = 0; index < 16; index++) {
                        outlines.add(new CodeGraphRelationOutline(
                                UUID.randomUUID(), dominantChunkId, "FORWARD", "RELATION_" + index,
                                "coordinate", "Flow.coordinate", "src/Flow.java",
                                "dominantNeighbor" + index, "Flow.dominantNeighbor" + index,
                                "src/Flow.java", UUID.randomUUID(), 0.99));
                    }
                    outlines.add(new CodeGraphRelationOutline(
                            UUID.randomUUID(), independentChunkId, "FORWARD", "CALLS",
                            "dispatch", "Dispatcher.dispatch", "src/Dispatcher.java",
                            "independentTarget", "Worker.independentTarget", "src/Worker.java",
                            UUID.randomUUID(), 0.20));
                    return outlines;
                });
        CodeSearchResult dominant = result(repositoryId, indexVersion, dominantChunkId,
                "src/Flow.java", "method", "coordinate", "void coordinate() { run(); }", Map.of());
        CodeSearchResult independent = result(repositoryId, indexVersion, independentChunkId,
                "src/Dispatcher.java", "method", "dispatch", "void dispatch() { send(); }", Map.of());

        String context = new RepositoryQuestionMapBuilder(repository).build(
                        repositoryId, null, List.of(UUID.randomUUID()), "trace all retrieved flows",
                        List.of(dominant, independent))
                .plannerContext();

        assertThat(context)
                .contains("symbol=coordinate", "symbol=dispatch", "neighborSymbol=independentTarget");
        assertThat(context.indexOf("[INDEXED_GRAPH_RELATION_HANDLES]"))
                .isLessThan(context.indexOf("[FILE_SYMBOL_INVENTORIES]"));
        assertThat(context.indexOf("[FILE_SYMBOL_INVENTORIES]"))
                .isLessThan(context.indexOf("[DIRECT_BODIES_AND_DEFINITIONS]"));
    }

    @Test
    void initialInventoryBudgetShowsMultipleFilesInsteadOfOneLargeFileOnly() {
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
        List<CodeSymbolOutline> outlines = new java.util.ArrayList<>();
        for (int index = 0; index < 200; index++) {
            outlines.add(new CodeSymbolOutline(
                    "large-" + index, "src/Large.java", "method", "large" + index,
                    "Large.large" + index, index + 1, index + 1, UUID.randomUUID(),
                    "syntax", "SYNTAX", 200));
        }
        outlines.add(new CodeSymbolOutline(
                "small-target", "src/Small.cs", "method", "continueFlow", "Small.continueFlow",
                10, 20, UUID.randomUUID(), "syntax", "SYNTAX", 1));
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(outlines);
        CodeSearchResult large = result(repositoryId, indexVersion, UUID.randomUUID(),
                "src/Large.java", "type", "Large", "class Large {}", Map.of());
        CodeSearchResult small = result(repositoryId, indexVersion, UUID.randomUUID(),
                "src/Small.cs", "type", "Small", "class Small {}", Map.of());

        String context = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "continue the flow", List.of(large, small))
                .plannerContext();

        assertThat(context).contains("path=src/Large.java", "path=src/Small.cs", "Small.continueFlow");
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
