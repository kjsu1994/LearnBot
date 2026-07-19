package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSourceBundleExpanderTest {
    private static final UUID REPOSITORY_ID = UUID.randomUUID();
    private static final UUID SPACE_ID = UUID.randomUUID();
    private static final String PATH = "src/Runtime/SessionCoordinator.cs";

    @Test
    void expandsACohesiveSearchHeadToBoundedCallableSourceBoundaries() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);
        CodeSearchResult open = result("openChannel", 30);
        CodeSearchResult close = result("closeChannel", 70);
        CodeSearchResult other = result("unrelatedLookup", 15, "OtherType");
        CodeSearchResult constructor = result("SessionCoordinator", 1);
        CodeSearchResult initialize = result("initializeRuntime", 10);
        CodeSearchResult middle = result("refreshRuntime", 50);
        CodeSearchResult finish = result("finishRuntime", 90);
        when(repository.listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID)))
                .thenReturn(List.of(constructor, initialize, open, middle, close, finish));

        List<CodeSearchResult> expanded = expander.expand(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), searchOperation(),
                GraphSearchIntent.FLOW, List.of(open, close, other), "open close channel");

        assertThat(expanded).extracting(CodeSearchResult::methodName)
                .containsExactly("openChannel", "closeChannel", "initializeRuntime", "finishRuntime");
        assertThat(expanded.subList(0, 2)).allSatisfy(result ->
                assertThat(CodeEvidenceOperationProvenance.from(result)).singleElement().satisfies(provenance ->
                        assertThat(provenance.operationType()).isEqualTo("read_source_member")));
        assertThat(expanded.subList(2, 4)).allSatisfy(result -> {
            assertThat(result.filePath()).isEqualTo(PATH);
            assertThat(CodeEvidenceOperationProvenance.from(result)).singleElement().satisfies(provenance -> {
                assertThat(provenance.operationType()).isEqualTo("read_source_boundary");
                assertThat(provenance.operationId()).isEqualTo("op-lifecycle");
                assertThat(provenance.claimIds()).containsExactly("claim-lifecycle");
                assertThat(provenance.evidenceGroup()).isEqualTo("lifecycle");
                assertThat(provenance.originEvidenceIds()).hasSize(2);
                assertThat(provenance.path()).isEqualTo(PATH);
                assertThat(provenance.symbol()).isEqualTo(result.methodName());
            });
        });
        verify(repository).listActiveSymbolsByPath(
                REPOSITORY_ID, PATH, 80, List.of(SPACE_ID), SPACE_ID);
    }

    @Test
    void doesNotExpandForAnIntentWithoutWorkflowStructure() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);

        List<CodeSearchResult> expanded = expander.expand(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), searchOperation(),
                GraphSearchIntent.LOCATE, List.of(result("first", 10), result("second", 20)), "");

        assertThat(expanded).isEmpty();
        verify(repository, never()).listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID));
    }

    @Test
    void doesNotExpandAnUntypedSearchWithoutAClaimBinding() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);
        RagPipelineService.CodeSearchOperation untyped = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "runtime lifecycle", "runtime lifecycle", "lifecycle");

        List<CodeSearchResult> expanded = expander.expand(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), untyped,
                GraphSearchIntent.FLOW, List.of(result("first", 10), result("second", 20)),
                "runtime lifecycle");

        assertThat(expanded).isEmpty();
        verify(repository, never()).listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID));
    }

    @Test
    void classContainerAnchorExposesIntentRankedCallableMembersWithoutANameRule() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);
        CodeSearchResult container = new CodeSearchResult(
                UUID.randomUUID(), REPOSITORY_ID, UUID.randomUUID(), "repo", PATH,
                "class", "SessionCoordinator", "SessionCoordinator", "", "runtime",
                null, null, 1, 1, 120, "class SessionCoordinator { }", 0.9, Map.of());
        CodeSearchResult cleanup = result("cleanupCache", 10);
        CodeSearchResult process = result("processQueuedRequest", 40);
        CodeSearchResult report = result("reportMetrics", 80);
        when(repository.listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID)))
                .thenReturn(List.of(cleanup, process, report));

        List<CodeSearchResult> expanded = expander.expand(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), searchOperation(),
                GraphSearchIntent.LOCATE, List.of(container), "process queued request");

        assertThat(expanded).isNotEmpty();
        assertThat(expanded.get(0).methodName()).isEqualTo("processQueuedRequest");
        assertThat(CodeEvidenceOperationProvenance.from(expanded.get(0)))
                .singleElement().satisfies(provenance -> {
                    assertThat(provenance.operationType()).isEqualTo("read_source_member");
                    assertThat(provenance.originEvidenceIds())
                            .containsExactly(CodeEvidenceItem.evidenceId(container));
                });
    }

    @Test
    void classContainerDoesNotExposeArbitraryMembersWithoutLexicalSupport() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);
        CodeSearchResult container = new CodeSearchResult(
                UUID.randomUUID(), REPOSITORY_ID, UUID.randomUUID(), "repo", PATH,
                "class", "SessionCoordinator", "SessionCoordinator", "", "runtime",
                null, null, 1, 1, 120, "class SessionCoordinator { }", 0.9, Map.of());
        when(repository.listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID)))
                .thenReturn(List.of(result("cleanupCache", 10), result("reportMetrics", 80)));

        List<CodeSearchResult> expanded = expander.expand(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), searchOperation(),
                GraphSearchIntent.LOCATE, List.of(container), "persist transaction state");

        assertThat(expanded).isEmpty();
    }

    @Test
    void directConstructorContainerExposesOnlyQuestionSupportedMembers() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);
        CodeSearchResult constructor = result("SessionCoordinator", 1);
        CodeSearchResult dispatch = result("dispatchQueuedTask", 30);
        CodeSearchResult metrics = result("publishMetrics", 60);
        when(repository.listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID)))
                .thenReturn(List.of(constructor, dispatch, metrics));
        RagPipelineService.CodeSearchOperation direct = new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", "", "request_flow", PATH, "", constructor.chunkId().toString(),
                null, null, null, List.of(), "BOTH", null,
                "read-container", List.of("claim-request"), List.of("origin-container"));

        List<CodeSearchResult> expanded = expander.expandDirectContainer(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), direct,
                GraphSearchIntent.FLOW, List.of(constructor),
                "Which entry dispatches the queued task?");

        assertThat(expanded).extracting(CodeSearchResult::methodName)
                .containsExactly("dispatchQueuedTask");
        assertThat(CodeEvidenceOperationProvenance.from(expanded.get(0)))
                .singleElement().satisfies(provenance ->
                        assertThat(provenance.operationType()).isEqualTo("read_source_member"));
    }

    @Test
    void alreadyObservedCallableCanStillCarryTheSourceBoundarySignal() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeSourceBundleExpander expander = new CodeSourceBundleExpander(repository);
        CodeSearchResult start = result("startLifecycle", 10);
        CodeSearchResult middle = result("refreshLifecycle", 40);
        CodeSearchResult finish = result("finishLifecycle", 80);
        when(repository.listActiveSymbolsByPath(
                eq(REPOSITORY_ID), eq(PATH), eq(80), eq(List.of(SPACE_ID)), eq(SPACE_ID)))
                .thenReturn(List.of(start, middle, finish));

        List<CodeSearchResult> expanded = expander.expand(
                REPOSITORY_ID, SPACE_ID, List.of(SPACE_ID), searchOperation(),
                GraphSearchIntent.FLOW, List.of(start, finish), "refresh lifecycle");

        assertThat(expanded.stream()
                .filter(result -> CodeEvidenceOperationProvenance.from(result).stream()
                        .anyMatch(value -> "read_source_boundary".equals(value.operationType())))
                .map(CodeSearchResult::methodName))
                .containsExactly("startLifecycle", "finishLifecycle");
    }

    private RagPipelineService.CodeSearchOperation searchOperation() {
        return new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "runtime lifecycle", "runtime lifecycle", "lifecycle",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "op-lifecycle", List.of("claim-lifecycle"), List.of());
    }

    private CodeSearchResult result(String method, int lineStart) {
        return result(method, lineStart, "SessionCoordinator");
    }

    private CodeSearchResult result(String method, int lineStart, String className) {
        return new CodeSearchResult(
                UUID.randomUUID(), REPOSITORY_ID, UUID.randomUUID(), "repo", PATH,
                "method", method, className, method, "runtime", null, null, lineStart,
                lineStart, lineStart + 8, "void " + method + "() { }", 0.8, Map.of());
    }
}
