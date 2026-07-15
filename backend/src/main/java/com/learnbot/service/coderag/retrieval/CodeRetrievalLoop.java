package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.RagPipelineService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Executes planner-provided operations under deterministic iteration, operation, evidence, and time bounds. */
public final class CodeRetrievalLoop {
    private final LongSupplier nanoTime;

    public CodeRetrievalLoop() {
        this(System::nanoTime);
    }

    public CodeRetrievalLoop(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public CodeRetrievalCoordinator.Outcome run(
            CodeRetrievalCoordinator.Request request,
            CodeRetrievalCoordinator.PlanProvider planner,
            CodeRetrievalCoordinator.OperationRunner runner
    ) {
        return runNormalized(CodeRetrievalCoordinator.normalize(request), planner, runner);
    }

    CodeRetrievalCoordinator.Outcome runNormalized(
            CodeRetrievalCoordinator.Request request,
            CodeRetrievalCoordinator.PlanProvider planner,
            CodeRetrievalCoordinator.OperationRunner runner
    ) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(runner, "runner");
        LinkedHashMap<String, CodeSearchResult> evidence = new LinkedHashMap<>();
        request.initialEvidence().forEach(result -> evidence.put(
                CodeRetrievalCoordinator.evidenceKey(result), result));
        int initialEvidenceCount = evidence.size();
        LinkedHashSet<String> executedKeys = new LinkedHashSet<>();
        TraceCollector trace = new TraceCollector();
        trace.add(0, CodeRetrievalCoordinator.TracePhase.REQUEST, null, "NORMALIZED", 0, 0,
                "maxIterations=" + request.maxIterations()
                        + " maxOperations=" + request.maxOperations()
                        + " maxEvidence=" + request.maxEvidence()
                        + " maxStagnantIterations=" + request.maxStagnantIterations());

        long started = nanoTime.getAsLong();
        long timeoutNanos = request.timeout().toNanos();
        long deadline = started > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : started + timeoutNanos;
        int iterations = 0;
        int operationsExecuted = 0;
        int executionFailures = 0;
        int stagnantIterations = 0;
        String terminationRequest = "NONE";
        String terminalReason = "";
        CodeRetrievalCoordinator.TerminalStatus terminalStatus = null;

        for (int iteration = 1; iteration <= request.maxIterations(); iteration++) {
            if (expired(deadline)) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.DEADLINE_EXCEEDED;
                terminalReason = "retrieval deadline reached before planning";
                break;
            }
            iterations = iteration;
            CodeRetrievalCoordinator.IterationContext context = new CodeRetrievalCoordinator.IterationContext(
                    request.traceId(), request.question(), iteration, List.copyOf(evidence.values()), executedKeys,
                    request.maxOperations() - operationsExecuted,
                    request.maxEvidence() - evidence.size());
            CodeRetrievalCoordinator.IterationPlan plan;
            try {
                plan = planner.plan(context);
                if (plan == null) {
                    plan = new CodeRetrievalCoordinator.IterationPlan(
                            false, "NONE", List.of(), "planner returned null");
                }
            } catch (RuntimeException ex) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.PLANNER_FAILED;
                terminalReason = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                trace.add(iteration, CodeRetrievalCoordinator.TracePhase.PLAN, null, "FAILED", 0, 0,
                        terminalReason);
                break;
            }

            String planStatus = plan.satisfied() ? "SATISFIED"
                    : !"NONE".equals(plan.terminationRequest()) ? "STOP_REQUESTED"
                    : plan.operations().isEmpty() ? "EMPTY" : "READY";
            trace.add(iteration, CodeRetrievalCoordinator.TracePhase.PLAN, null, planStatus, 0, 0,
                    "operations=" + plan.operations().size()
                            + (plan.reason().isBlank() ? "" : " reason=" + plan.reason()));
            if (plan.satisfied()) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.SATISFIED;
                terminalReason = plan.reason();
                break;
            }
            if (!"NONE".equals(plan.terminationRequest())) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.EXPLICIT_STOP;
                terminationRequest = plan.terminationRequest();
                terminalReason = plan.reason();
                break;
            }
            if (plan.operations().isEmpty()) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.NO_OPERATIONS;
                terminalReason = plan.reason().isBlank() ? "planner returned no operations" : plan.reason();
                break;
            }
            if (evidence.size() >= request.maxEvidence()) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.EVIDENCE_LIMIT;
                terminalReason = "evidence limit reached";
                break;
            }
            if (operationsExecuted >= request.maxOperations()) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.OPERATION_LIMIT;
                terminalReason = "operation limit reached";
                break;
            }

            int executedThisIteration = 0;
            int addedThisIteration = 0;
            int remainingOperations = request.maxOperations() - operationsExecuted;
            for (RagPipelineService.CodeSearchOperation requestedOperation
                    : plan.operations().stream().limit(remainingOperations).toList()) {
                if (expired(deadline)) {
                    terminalStatus = CodeRetrievalCoordinator.TerminalStatus.DEADLINE_EXCEEDED;
                    terminalReason = "retrieval deadline reached during operation execution";
                    break;
                }
                RagPipelineService.CodeSearchOperation operation =
                        CodeRetrievalCoordinator.normalizeOperation(requestedOperation);
                String operationKey = CodeRetrievalCoordinator.operationKey(operation);
                if (!executedKeys.add(operationKey)) {
                    trace.add(iteration, CodeRetrievalCoordinator.TracePhase.EXECUTE, operation,
                            "SKIPPED_DUPLICATE", 0, 0, "operation fingerprint was already executed");
                    continue;
                }

                CodeEvidenceOperationExecutor.Execution execution;
                try {
                    execution = runner.execute(operation);
                    if (execution == null) {
                        execution = new CodeEvidenceOperationExecutor.Execution(
                                operation, "FAILED", List.of(), "runner returned null");
                    }
                } catch (RuntimeException ex) {
                    execution = new CodeEvidenceOperationExecutor.Execution(
                            operation, "FAILED", List.of(), ex.getClass().getSimpleName() + ": " + safeMessage(ex));
                }
                operationsExecuted++;
                executedThisIteration++;
                String executionStatus = safe(execution.status()).trim().toUpperCase(Locale.ROOT);
                if ("FAILED".equals(executionStatus) || "INVALID".equals(executionStatus)) {
                    executionFailures++;
                }
                int before = evidence.size();
                int inspected = 0;
                for (CodeSearchResult result : execution.results()) {
                    if (result == null || inspected++ >= request.maxEvidence()) continue;
                    String evidenceKey = CodeRetrievalCoordinator.evidenceKey(result);
                    if (evidenceKey.isBlank()) continue;
                    if (evidence.containsKey(evidenceKey)) {
                        evidence.put(evidenceKey,
                                CodeRetrievalCoordinator.mergeEvidence(evidence.get(evidenceKey), result));
                    } else if (evidence.size() < request.maxEvidence()) {
                        evidence.put(evidenceKey, result);
                    }
                }
                int added = Math.max(0, evidence.size() - before);
                addedThisIteration += added;
                trace.add(iteration, CodeRetrievalCoordinator.TracePhase.EXECUTE, operation,
                        executionStatus.isBlank() ? "FAILED" : executionStatus,
                        execution.results().size(), added, execution.reason());

                if (expired(deadline)) {
                    terminalStatus = CodeRetrievalCoordinator.TerminalStatus.DEADLINE_EXCEEDED;
                    terminalReason = "retrieval deadline reached during operation execution";
                    break;
                }
                if (evidence.size() >= request.maxEvidence()) {
                    terminalStatus = CodeRetrievalCoordinator.TerminalStatus.EVIDENCE_LIMIT;
                    terminalReason = "evidence limit reached";
                    break;
                }
                if (operationsExecuted >= request.maxOperations()) {
                    terminalStatus = CodeRetrievalCoordinator.TerminalStatus.OPERATION_LIMIT;
                    terminalReason = "operation limit reached";
                    break;
                }
            }
            if (terminalStatus != null) break;
            if (executedThisIteration == 0) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.NO_OPERATIONS;
                terminalReason = "all planned operations were duplicates";
                break;
            }
            stagnantIterations = addedThisIteration == 0 ? stagnantIterations + 1 : 0;
            if (stagnantIterations >= request.maxStagnantIterations()) {
                terminalStatus = CodeRetrievalCoordinator.TerminalStatus.NO_PROGRESS;
                terminalReason = "no new evidence was added for " + stagnantIterations + " iteration(s)";
                break;
            }
        }
        if (terminalStatus == null) {
            terminalStatus = CodeRetrievalCoordinator.TerminalStatus.ITERATION_LIMIT;
            terminalReason = "iteration limit reached";
        }
        trace.add(iterations, CodeRetrievalCoordinator.TracePhase.TERMINATE, null,
                terminalStatus.name(), 0, 0, terminalReason);
        return new CodeRetrievalCoordinator.Outcome(
                request, terminalStatus, terminationRequest, terminalReason, List.copyOf(evidence.values()),
                iterations, operationsExecuted, Math.max(0, evidence.size() - initialEvidenceCount),
                executionFailures, executedKeys, trace.entries());
    }

    private boolean expired(long deadline) {
        return nanoTime.getAsLong() >= deadline;
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class TraceCollector {
        private final java.util.ArrayList<CodeRetrievalCoordinator.TraceEntry> entries = new java.util.ArrayList<>();

        void add(
                int iteration,
                CodeRetrievalCoordinator.TracePhase phase,
                RagPipelineService.CodeSearchOperation operation,
                String status,
                int resultCount,
                int addedEvidence,
                String detail
        ) {
            entries.add(new CodeRetrievalCoordinator.TraceEntry(
                    entries.size() + 1,
                    iteration,
                    phase,
                    operation == null ? "" : operation.operationId(),
                    operation == null ? "" : CodeRetrievalCoordinator.operationKey(operation),
                    status,
                    resultCount,
                    addedEvidence,
                    detail));
        }

        List<CodeRetrievalCoordinator.TraceEntry> entries() {
            return List.copyOf(entries);
        }
    }
}
