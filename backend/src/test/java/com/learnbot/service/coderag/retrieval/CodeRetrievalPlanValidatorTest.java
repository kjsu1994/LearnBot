package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.ActiveCodeIndexIdentity;
import com.learnbot.service.RagPipelineService;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.CodeSymbolOutline;
import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeRetrievalPlanValidatorTest {
    private final CodeRetrievalPlanValidator validator = new CodeRetrievalPlanValidator();

    @Test
    void unresolvedClaimsWithoutOperationsAreInvalidBeforeTheLoop() {
        var plan = plan(List.of(), "NONE");

        var result = validator.validate(plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_NO_EXECUTABLE_OPERATION);
    }

    @Test
    void explicitNoFurtherRetrievalAllowsAnEmptyPlan() {
        var plan = plan(List.of(), "NO_FURTHER_RETRIEVAL");

        var result = validator.validate(plan, null, Set.of());

        assertThat(result.valid()).isTrue();
    }

    @Test
    void directReadRequiresAnObservedPathSymbolAndBoundOrigin() {
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String path = "src/Gateway.java";
        CodeRepository repository = mock(CodeRepository.class);
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(List.of(new CodeSymbolOutline(
                        "symbol-complete", path, "method", "complete", "Gateway.complete",
                        10, 30, chunkId, "java", "COMPILER_SEMANTIC", 1)));
        CodeSearchResult candidate = new CodeSearchResult(
                chunkId, repositoryId, UUID.randomUUID(), "repo", path, "type", "Gateway", "Gateway", "",
                "app", null, null, 0, 1, 40, "class Gateway {}", 0.9,
                Map.of("indexVersion", indexVersion.toString()));
        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "complete response", List.of(candidate));
        String origin = map.symbolInventories().get(path).evidenceId();
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "implementation", "claim-1", path, "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of(origin));

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(operation);
    }

    @Test
    void inventedDirectReadPathIsRejected() {
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "implementation", "claim-1", "invented/Service.java", "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of("missing"));

        var result = validator.validate(plan(List.of(operation), "NONE"), null, Set.of());

        assertThat(result.code()).isEqualTo(CodeRetrievalPlanValidator.PlanValidationCode.INVALID_UNKNOWN_ORIGIN);
    }

    @Test
    void bindsAPathOnlyDirectReadToObservedFileEvidence() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "", "implementation", "claim-1", path, "", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of());

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).singleElement().satisfies(executable ->
                assertThat(executable.originEvidenceIds()).hasSize(1));
    }

    @Test
    void replacesAStaleModelOriginWithCurrentObservedProvenanceForTheSameOperand() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "implementation", "claim-1", path, "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of("stale:id"));

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).singleElement().satisfies(executable -> {
            assertThat(executable.originEvidenceIds()).hasSize(1);
            assertThat(executable.originEvidenceIds()).doesNotContain("stale:id");
            assertThat(map.containsEvidenceId(executable.originEvidenceIds().get(0))).isTrue();
        });
    }

    @Test
    void changesARangeReadWithoutObservedLinesToAnObservedSymbolRead() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "implementation", "claim-1", path, "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of());

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).singleElement().satisfies(executable -> {
            assertThat(executable.type()).isEqualTo("read_symbol");
            assertThat(executable.symbol()).isEqualTo("complete");
            assertThat(executable.originEvidenceIds()).hasSize(1);
        });
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap observedMap(String path, String symbol) {
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        CodeRepository repository = mock(CodeRepository.class);
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(List.of(new CodeSymbolOutline(
                        "symbol-complete", path, "method", symbol, "Gateway." + symbol,
                        10, 30, chunkId, "java", "COMPILER_SEMANTIC", 1)));
        CodeSearchResult candidate = new CodeSearchResult(
                chunkId, repositoryId, UUID.randomUUID(), "repo", path, "method", "Gateway", symbol, symbol,
                "app", null, null, 0, 10, 30, "void " + symbol + "() {}", 0.9,
                Map.of("indexVersion", indexVersion.toString()));
        return new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), symbol, List.of(candidate));
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan plan(
            List<RagPipelineService.CodeSearchOperation> operations,
            String terminationRequest
    ) {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "claim-1", "complete the response", List.of(),
                "gateway", "complete", "response", "response is persisted", List.of(), List.of("DIRECT_SOURCE"));
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("claim-1"),
                List.of(claim), operations, List.of(), "hypothesis", 1, "UNRESOLVED", List.of(), terminationRequest);
    }
}
