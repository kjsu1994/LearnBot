package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceItem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Normalizes a retrieval request and delegates bounded execution to {@link CodeRetrievalLoop}.
 * The existing orchestrator can adopt this class as an adapter without changing planner or executor contracts.
 */
public final class CodeRetrievalCoordinator {
    static final int MAX_ITERATIONS = 12;
    static final int MAX_OPERATIONS = 96;
    static final int MAX_EVIDENCE = 512;
    static final int MAX_STAGNANT_ITERATIONS = 3;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

    private final CodeRetrievalLoop loop;
    private final CodeRetrievalPlanValidator planValidator;
    private final CodeEvidenceOperationExecutor operationExecutor;
    private final CodeObservedClaimReadPlanner observedClaimReadPlanner =
            new CodeObservedClaimReadPlanner();

    public CodeRetrievalCoordinator() {
        this(new CodeRetrievalLoop(), new CodeRetrievalPlanValidator(), null);
    }

    public CodeRetrievalCoordinator(CodeRetrievalLoop loop) {
        this(loop, new CodeRetrievalPlanValidator(), null);
    }

    /**
     * Production adapter constructor. The orchestrator keeps its established loop semantics while
     * plan validation and operation execution cross one retrieval-coordination boundary.
     */
    public CodeRetrievalCoordinator(CodeEvidenceOperationExecutor operationExecutor) {
        this(new CodeRetrievalLoop(), new CodeRetrievalPlanValidator(),
                Objects.requireNonNull(operationExecutor, "operationExecutor"));
    }

    CodeRetrievalCoordinator(
            CodeRetrievalLoop loop,
            CodeRetrievalPlanValidator planValidator,
            CodeEvidenceOperationExecutor operationExecutor
    ) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.planValidator = Objects.requireNonNull(planValidator, "planValidator");
        this.operationExecutor = operationExecutor;
    }

    public Outcome coordinate(Request request, PlanProvider planner, OperationRunner runner) {
        return loop.runNormalized(normalize(request), planner, runner);
    }

    public CodeRetrievalPlanValidator.PlanValidationResult validatePlan(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        return planValidator.validate(plan, repositoryMap, executedOperationKeys);
    }

    public CodeRetrievalPlanValidator.PlanValidationResult validateInitialPlan(
            String question,
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        return planValidator.validateInitial(question, plan, repositoryMap, executedOperationKeys);
    }

    public List<RagPipelineService.CodeSearchOperation> selectObservedClaimReads(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        return observedClaimReadPlanner.select(plan, repositoryMap, executedOperationKeys);
    }

    public CodeEvidenceOperationExecutor.Execution executeOperation(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            GraphSearchIntent graphIntent,
            int limit,
            String retrievalIntent
    ) {
        if (operationExecutor == null) {
            throw new IllegalStateException("operation executor is not configured");
        }
        return operationExecutor.execute(
                repositoryId, selectedSpaceId, spaceIds, operation, graphIntent, limit, retrievalIntent);
    }

    static Request normalize(Request request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        String question = safe(request.question()).trim();
        if (question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        String traceId = safe(request.traceId()).trim();
        if (traceId.isBlank()) traceId = "code-retrieval";
        int maxIterations = clamp(request.maxIterations(), 1, MAX_ITERATIONS);
        int maxOperations = clamp(request.maxOperations(), 1, MAX_OPERATIONS);
        int maxEvidence = clamp(request.maxEvidence(), 1, MAX_EVIDENCE);
        int maxStagnantIterations = clamp(
                request.maxStagnantIterations(), 1, MAX_STAGNANT_ITERATIONS);
        Duration timeout = request.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) timeout = DEFAULT_TIMEOUT;
        if (timeout.compareTo(MAX_TIMEOUT) > 0) timeout = MAX_TIMEOUT;
        List<CodeSearchResult> initialEvidence = normalizeEvidence(request.initialEvidence(), maxEvidence);
        return new Request(traceId, question, initialEvidence, maxIterations, maxOperations,
                maxEvidence, maxStagnantIterations, timeout);
    }

    static RagPipelineService.CodeSearchOperation normalizeOperation(
            RagPipelineService.CodeSearchOperation operation
    ) {
        if (operation == null) return null;
        String type = safe(operation.type()).trim().toLowerCase(Locale.ROOT);
        if (type.isBlank()) type = "hybrid_search";
        String path = safe(operation.path()).trim().replace('\\', '/');
        return new RagPipelineService.CodeSearchOperation(
                type,
                safe(operation.query()).trim(),
                safe(operation.area()).trim(),
                safe(operation.evidenceGroup()).trim(),
                path,
                safe(operation.symbol()).trim(),
                safe(operation.chunkId()).trim(),
                operation.lineStart(),
                operation.lineEnd(),
                operation.radius(),
                operation.relations(),
                safe(operation.direction()).trim(),
                operation.maxHops(),
                safe(operation.operationId()).trim(),
                operation.claimIds(),
                operation.originEvidenceIds());
    }

    /** Matches the current orchestrator fingerprint so adapter adoption preserves duplicate behavior. */
    public static String operationKey(RagPipelineService.CodeSearchOperation requested) {
        RagPipelineService.CodeSearchOperation operation = normalizeOperation(requested);
        if (operation == null) return "null";
        String relations = operation.relations().stream()
                .map(value -> safe(value).trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .sorted()
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        String type = operation.type();
        String query = operation.query().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String path = operation.path();
        String symbol = operation.symbol();
        String chunkId = operation.chunkId();
        return switch (type) {
            case "keyword_search", "hybrid_search", "reference_search", "find_endpoint" ->
                    String.join("|", type, query);
            case "list_file_symbols" -> String.join("|", type, path);
            case "read_symbol" -> String.join("|", type, path, symbol);
            case "read_chunk" -> String.join("|", type, chunkId);
            case "read_file_range" -> String.join("|", type, path,
                    String.valueOf(operation.lineStart()), String.valueOf(operation.lineEnd()));
            case "read_adjacent" -> String.join("|", type, chunkId, String.valueOf(operation.radius()));
            case "traverse_graph" -> String.join("|", type, chunkId, relations,
                    operation.direction().toUpperCase(Locale.ROOT), String.valueOf(operation.maxHops()));
            default -> String.join("|", type, query, path, symbol, chunkId);
        };
    }

    static String evidenceKey(CodeSearchResult result) {
        if (result == null) return "";
        if (result.chunkId() != null) return CodeEvidenceItem.evidenceId(result);
        return String.join(":", "unkeyed", safe(result.filePath()),
                String.valueOf(Math.max(0, result.lineStart())),
                String.valueOf(Math.max(0, result.lineEnd())), safe(result.symbolName()));
    }

    static CodeSearchResult mergeEvidence(CodeSearchResult current, CodeSearchResult incoming) {
        if (current == null) return incoming;
        if (incoming == null) return current;
        CodeSearchResult preferred = stronger(incoming, current) ? incoming : current;
        CodeSearchResult secondary = preferred == incoming ? current : incoming;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putMetadata(metadata, secondary.metadata());
        putMetadata(metadata, preferred.metadata());
        return new CodeSearchResult(
                preferred.chunkId(), preferred.repositoryId(), preferred.fileId(), preferred.repositoryName(),
                preferred.filePath(), preferred.chunkType(), preferred.symbolName(), preferred.className(),
                preferred.methodName(), preferred.namespaceName(), preferred.controlName(), preferred.eventName(),
                preferred.chunkIndex(), preferred.lineStart(), preferred.lineEnd(), preferred.content(),
                Math.max(current.score(), incoming.score()), Collections.unmodifiableMap(metadata));
    }

    private static boolean stronger(CodeSearchResult candidate, CodeSearchResult current) {
        CodeIntelligenceAuthority candidateAuthority = CodeEvidenceItem.authority(candidate);
        CodeIntelligenceAuthority currentAuthority = CodeEvidenceItem.authority(current);
        if (candidateAuthority.rank() != currentAuthority.rank()) {
            return candidateAuthority.rank() > currentAuthority.rank();
        }
        int score = Double.compare(candidate.score(), current.score());
        if (score != 0) return score > 0;
        return safe(candidate.content()).length() > safe(current.content()).length();
    }

    private static List<CodeSearchResult> normalizeEvidence(List<CodeSearchResult> values, int limit) {
        LinkedHashMap<String, CodeSearchResult> evidence = new LinkedHashMap<>();
        if (values != null) {
            for (CodeSearchResult result : values) {
                if (result == null) continue;
                String key = evidenceKey(result);
                if (key.isBlank()) continue;
                if (evidence.containsKey(key)) {
                    evidence.put(key, mergeEvidence(evidence.get(key), result));
                } else if (evidence.size() < limit) {
                    evidence.put(key, result);
                }
            }
        }
        return List.copyOf(evidence.values());
    }

    private static void putMetadata(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) return;
        source.forEach((key, value) -> {
            if (key != null && value != null) target.put(key, value);
        });
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Request(
            String traceId,
            String question,
            List<CodeSearchResult> initialEvidence,
            int maxIterations,
            int maxOperations,
            int maxEvidence,
            int maxStagnantIterations,
            Duration timeout
    ) {
        public Request {
            initialEvidence = initialEvidence == null ? List.of()
                    : initialEvidence.stream().filter(Objects::nonNull).toList();
        }

        public static Request defaults(String traceId, String question, List<CodeSearchResult> initialEvidence) {
            return new Request(traceId, question, initialEvidence, 3, 24, 160, 1, DEFAULT_TIMEOUT);
        }
    }

    @FunctionalInterface
    public interface PlanProvider {
        IterationPlan plan(IterationContext context);
    }

    @FunctionalInterface
    public interface OperationRunner {
        CodeEvidenceOperationExecutor.Execution execute(RagPipelineService.CodeSearchOperation operation);
    }

    public record IterationContext(
            String traceId,
            String question,
            int iteration,
            List<CodeSearchResult> evidence,
            Set<String> executedOperationKeys,
            int remainingOperations,
            int remainingEvidenceSlots
    ) {
        public IterationContext {
            traceId = safe(traceId).trim();
            question = safe(question).trim();
            iteration = Math.max(1, iteration);
            evidence = evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull).toList();
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            if (executedOperationKeys != null) {
                executedOperationKeys.stream().filter(Objects::nonNull).map(String::trim)
                        .filter(value -> !value.isBlank()).forEach(keys::add);
            }
            executedOperationKeys = Collections.unmodifiableSet(keys);
            remainingOperations = Math.max(0, remainingOperations);
            remainingEvidenceSlots = Math.max(0, remainingEvidenceSlots);
        }
    }

    public record IterationPlan(
            boolean satisfied,
            String terminationRequest,
            List<RagPipelineService.CodeSearchOperation> operations,
            String reason
    ) {
        public IterationPlan {
            terminationRequest = safe(terminationRequest).trim().toUpperCase(Locale.ROOT);
            if (terminationRequest.isBlank()) terminationRequest = "NONE";
            operations = operations == null ? List.of()
                    : operations.stream().filter(Objects::nonNull).toList();
            reason = traceText(reason);
        }

        public static IterationPlan from(RagPipelineService.CodeEvidenceFollowUpPlan plan) {
            return from(plan, plan == null ? List.of() : plan.operations());
        }

        public static IterationPlan from(
                RagPipelineService.CodeEvidenceFollowUpPlan plan,
                List<RagPipelineService.CodeSearchOperation> executableOperations
        ) {
            if (plan == null) return new IterationPlan(false, "NONE", List.of(), "plan is missing");
            return new IterationPlan(plan.enough(), plan.terminationRequest(), executableOperations, plan.reason());
        }
    }

    public enum TerminalStatus {
        SATISFIED,
        EXPLICIT_STOP,
        DEADLINE_EXCEEDED,
        ITERATION_LIMIT,
        OPERATION_LIMIT,
        EVIDENCE_LIMIT,
        NO_OPERATIONS,
        NO_PROGRESS,
        PLANNER_FAILED
    }

    public enum TracePhase {
        REQUEST,
        PLAN,
        EXECUTE,
        TERMINATE
    }

    public record TraceEntry(
            int sequence,
            int iteration,
            TracePhase phase,
            String operationId,
            String operationKey,
            String status,
            int resultCount,
            int addedEvidence,
            String detail
    ) {
        public TraceEntry {
            sequence = Math.max(1, sequence);
            iteration = Math.max(0, iteration);
            phase = Objects.requireNonNull(phase, "phase");
            operationId = safe(operationId).trim();
            operationKey = safe(operationKey).trim();
            status = safe(status).trim().toUpperCase(Locale.ROOT);
            resultCount = Math.max(0, resultCount);
            addedEvidence = Math.max(0, addedEvidence);
            detail = traceText(detail);
        }
    }

    public record Outcome(
            Request request,
            TerminalStatus terminalStatus,
            String terminationRequest,
            String reason,
            List<CodeSearchResult> evidence,
            int iterations,
            int operationsExecuted,
            int evidenceAdded,
            int executionFailures,
            Set<String> executedOperationKeys,
            List<TraceEntry> trace
    ) {
        public Outcome {
            request = Objects.requireNonNull(request, "request");
            terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
            terminationRequest = safe(terminationRequest).trim().toUpperCase(Locale.ROOT);
            if (terminationRequest.isBlank()) terminationRequest = "NONE";
            reason = traceText(reason);
            evidence = evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull).toList();
            iterations = Math.max(0, iterations);
            operationsExecuted = Math.max(0, operationsExecuted);
            evidenceAdded = Math.max(0, evidenceAdded);
            executionFailures = Math.max(0, executionFailures);
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            if (executedOperationKeys != null) keys.addAll(executedOperationKeys);
            executedOperationKeys = Collections.unmodifiableSet(keys);
            trace = trace == null ? List.of() : trace.stream().filter(Objects::nonNull).toList();
        }

        public boolean satisfied() {
            return terminalStatus == TerminalStatus.SATISFIED;
        }
    }

    static String traceText(String value) {
        String normalized = safe(value).replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }
}
