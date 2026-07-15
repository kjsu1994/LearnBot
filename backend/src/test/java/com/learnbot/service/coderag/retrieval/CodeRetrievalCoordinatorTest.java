package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.RagPipelineService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRetrievalCoordinatorTest {
    @Test
    void productionAdapterOwnsLegacyPlanValidationAndOperationExecution() {
        CodeRetrievalCoordinator coordinator = new CodeRetrievalCoordinator(
                new CodeEvidenceOperationExecutor(null, null, null));

        CodeRetrievalPlanValidator.PlanValidationResult validation = coordinator.validatePlan(
                null, null, Set.of());
        CodeEvidenceOperationExecutor.Execution execution = coordinator.executeOperation(
                null, null, List.of(), null, GraphSearchIntent.LOCATE, 4, "question intent");

        assertThat(validation.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_NO_EXECUTABLE_OPERATION);
        assertThat(execution.status()).isEqualTo("INVALID");
        assertThat(execution.reason()).isEqualTo("operation is required");
    }

    @Test
    void normalizesRequestAndMergesInitialEvidenceByAuthorityWithoutLosingScore() {
        UUID chunkId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        CodeSearchResult lexical = result(chunkId, "src/Route.java", 0.91, "lexical", Map.of(
                "indexVersion", "index-1",
                "codeIntelligenceAuthority", "LEXICAL",
                "lexicalOnly", true));
        CodeSearchResult compiler = result(chunkId, "src/Route.java", 0.31, "compiler", Map.of(
                "indexVersion", "index-1",
                "codeIntelligenceAuthority", "COMPILER_SEMANTIC",
                "compilerOnly", true));
        CodeRetrievalCoordinator.Request raw = new CodeRetrievalCoordinator.Request(
                " ", "  Which route?  ", List.of(lexical, compiler),
                99, 0, 2, 99, Duration.ofSeconds(-1));

        CodeRetrievalCoordinator.Outcome outcome = new CodeRetrievalCoordinator().coordinate(
                raw,
                context -> new CodeRetrievalCoordinator.IterationPlan(true, "NONE", List.of(), "covered"),
                operation -> new CodeEvidenceOperationExecutor.Execution(operation, "COMPLETED", List.of(), ""));

        assertThat(outcome.request().traceId()).isEqualTo("code-retrieval");
        assertThat(outcome.request().question()).isEqualTo("Which route?");
        assertThat(outcome.request().maxIterations()).isEqualTo(12);
        assertThat(outcome.request().maxOperations()).isEqualTo(1);
        assertThat(outcome.request().maxStagnantIterations()).isEqualTo(3);
        assertThat(outcome.request().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(outcome.terminalStatus()).isEqualTo(CodeRetrievalCoordinator.TerminalStatus.SATISFIED);
        assertThat(outcome.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.content()).isEqualTo("compiler");
            assertThat(evidence.score()).isEqualTo(0.91);
            assertThat(evidence.metadata()).containsKeys("lexicalOnly", "compilerOnly")
                    .containsEntry("codeIntelligenceAuthority", "COMPILER_SEMANTIC");
        });
        assertThat(outcome.trace()).extracting(CodeRetrievalCoordinator.TraceEntry::sequence)
                .containsExactly(1, 2, 3);
    }

    @Test
    void accumulatesOperationEvidenceAndSkipsEquivalentOperationDeterministically() {
        UUID chunkId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        AtomicInteger executions = new AtomicInteger();
        CodeRetrievalCoordinator.Request request = new CodeRetrievalCoordinator.Request(
                "trace-duplicate", "Find handler", List.of(), 3, 5, 10, 2, Duration.ofSeconds(5));

        CodeRetrievalCoordinator.Outcome outcome = new CodeRetrievalCoordinator().coordinate(
                request,
                context -> context.evidence().isEmpty()
                        ? new CodeRetrievalCoordinator.IterationPlan(false, "NONE", List.of(
                        operation("op-1", "  Find   Handler "),
                        operation("op-2", "find handler")), "search")
                        : new CodeRetrievalCoordinator.IterationPlan(true, "NONE", List.of(), "covered"),
                operation -> {
                    executions.incrementAndGet();
                    return new CodeEvidenceOperationExecutor.Execution(operation, "COMPLETED", List.of(
                            result(chunkId, "src/Handler.java", 0.8, "handler", Map.of("indexVersion", "index-1"))), "");
                });

        assertThat(outcome.terminalStatus()).isEqualTo(CodeRetrievalCoordinator.TerminalStatus.SATISFIED);
        assertThat(outcome.iterations()).isEqualTo(2);
        assertThat(outcome.operationsExecuted()).isEqualTo(1);
        assertThat(outcome.evidenceAdded()).isEqualTo(1);
        assertThat(executions).hasValue(1);
        assertThat(outcome.executedOperationKeys()).containsExactly("hybrid_search|find handler");
        assertThat(outcome.trace()).anySatisfy(entry -> {
            assertThat(entry.phase()).isEqualTo(CodeRetrievalCoordinator.TracePhase.EXECUTE);
            assertThat(entry.status()).isEqualTo("SKIPPED_DUPLICATE");
            assertThat(entry.operationId()).isEqualTo("op-2");
        });
    }

    @Test
    void stopsAfterBoundedStagnationAndProducesSameTraceForSameInputs() {
        CodeRetrievalCoordinator.Outcome first = stagnantOutcome();
        CodeRetrievalCoordinator.Outcome second = stagnantOutcome();

        assertThat(first.terminalStatus()).isEqualTo(CodeRetrievalCoordinator.TerminalStatus.NO_PROGRESS);
        assertThat(first.iterations()).isEqualTo(2);
        assertThat(first.operationsExecuted()).isEqualTo(2);
        assertThat(first.reason()).contains("2 iteration(s)");
        assertThat(first.trace()).isEqualTo(second.trace());
        assertThat(first.trace()).extracting(CodeRetrievalCoordinator.TraceEntry::sequence)
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void deadlineStopsLoopEvenWhenRunnerReturnsNormally() {
        AtomicLong clock = new AtomicLong();
        CodeRetrievalCoordinator coordinator = new CodeRetrievalCoordinator(new CodeRetrievalLoop(clock::get));
        CodeRetrievalCoordinator.Request request = new CodeRetrievalCoordinator.Request(
                "trace-deadline", "Find deadline", List.of(), 4, 4, 10, 3, Duration.ofNanos(10));

        CodeRetrievalCoordinator.Outcome outcome = coordinator.coordinate(
                request,
                context -> new CodeRetrievalCoordinator.IterationPlan(
                        false, "NONE", List.of(operation("deadline-op", "deadline")), "search"),
                operation -> {
                    clock.addAndGet(11);
                    return new CodeEvidenceOperationExecutor.Execution(operation, "COMPLETED", List.of(), "");
                });

        assertThat(outcome.terminalStatus())
                .isEqualTo(CodeRetrievalCoordinator.TerminalStatus.DEADLINE_EXCEEDED);
        assertThat(outcome.operationsExecuted()).isEqualTo(1);
        assertThat(outcome.trace().get(outcome.trace().size() - 1).status())
                .isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    void globalOperationLimitCapsPlannerBatch() {
        AtomicInteger executions = new AtomicInteger();
        CodeRetrievalCoordinator.Request request = new CodeRetrievalCoordinator.Request(
                "trace-limit", "Bound operations", List.of(), 5, 2, 10, 3, Duration.ofSeconds(5));

        CodeRetrievalCoordinator.Outcome outcome = new CodeRetrievalCoordinator().coordinate(
                request,
                context -> new CodeRetrievalCoordinator.IterationPlan(false, "NONE", List.of(
                        operation("op-1", "one"), operation("op-2", "two"), operation("op-3", "three")), "batch"),
                operation -> {
                    int number = executions.incrementAndGet();
                    UUID id = new UUID(0, 100 + number);
                    return new CodeEvidenceOperationExecutor.Execution(operation, "COMPLETED", List.of(
                            result(id, "src/" + number + ".java", 0.5, "result", Map.of("indexVersion", "index-1"))), "");
                });

        assertThat(outcome.terminalStatus()).isEqualTo(CodeRetrievalCoordinator.TerminalStatus.OPERATION_LIMIT);
        assertThat(outcome.operationsExecuted()).isEqualTo(2);
        assertThat(outcome.evidenceAdded()).isEqualTo(2);
        assertThat(executions).hasValue(2);
    }

    private CodeRetrievalCoordinator.Outcome stagnantOutcome() {
        CodeRetrievalCoordinator.Request request = new CodeRetrievalCoordinator.Request(
                "trace-stagnant", "Find missing code", List.of(), 5, 10, 10, 2, Duration.ofSeconds(5));
        return new CodeRetrievalCoordinator().coordinate(
                request,
                context -> new CodeRetrievalCoordinator.IterationPlan(false, "NONE",
                        List.of(operation("op-" + context.iteration(), "query-" + context.iteration())), "continue"),
                operation -> new CodeEvidenceOperationExecutor.Execution(
                        operation, "NOT_FOUND", List.of(), "no active evidence"));
    }

    private RagPipelineService.CodeSearchOperation operation(String operationId, String query) {
        return new RagPipelineService.CodeSearchOperation(
                "HYBRID_SEARCH", query, "behavior", "claim-1",
                "", "", "", null, null, null, List.of(), "", null,
                operationId, List.of("claim-1"), List.of());
    }

    private CodeSearchResult result(
            UUID chunkId,
            String path,
            double score,
            String content,
            Map<String, Object> metadata
    ) {
        return new CodeSearchResult(
                chunkId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "repository", path, "method", "run", "Sample", "run", "sample",
                null, null, 0, 10, 20, content, score, metadata);
    }
}
