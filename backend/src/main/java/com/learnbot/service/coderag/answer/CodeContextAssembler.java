package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.EvidenceExcerptSelector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builds the source-code context supplied to Code RAG answer generation.
 *
 * <p>This component owns evidence ordering, excerpt rendering, streaming compaction, and
 * prompt-budget trimming. Retrieval and evidence adjudication must finish before calling it.</p>
 */
public final class CodeContextAssembler {
    private static final int OVERVIEW_CONTEXT_CHARS = 620;
    private static final int DEFAULT_CONTEXT_CHARS = 1200;
    private static final int REASONING_CONTEXT_CHARS = 1000;
    private static final Set<String> COVERAGE_STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "that", "this", "into", "onto", "where", "what", "when", "how",
            "does", "code", "source", "file", "files", "class", "method", "implementation", "implements", "logic",
            "flow", "pipeline", "service", "services", "request", "response", "result", "results", "objects",
            "locate", "find", "based", "current", "related", "using", "used", "user",
            "backend", "frontend", "main", "java", "learnbot", "src"
    );

    private final boolean evidenceRankingDebug;

    public CodeContextAssembler() {
        this(false);
    }

    public CodeContextAssembler(boolean evidenceRankingDebug) {
        this.evidenceRankingDebug = evidenceRankingDebug;
    }

    public ContextBundle assemble(AssemblyRequest request) {
        AssemblyRequest safeRequest = request == null ? AssemblyRequest.empty() : request;
        Mode mode = Mode.from(safeRequest.mode());
        List<CodeSearchResult> original = safeRequest.results();
        List<CodeSearchResult> selected = new ArrayList<>(original);
        boolean allowFullCoreEvidence = true;
        String context = safeRequest.compactForStreaming()
                ? buildStreamingContext(safeRequest.question(), mode, selected, allowFullCoreEvidence)
                : buildContext(safeRequest.question(), mode, selected, allowFullCoreEvidence);
        int budget = promptTokenBudget(
                safeRequest.contextWindow(), safeRequest.configuredPromptTokenBudget());
        int requiredCount = (int) selected.stream().filter(this::isRequiredContextEvidence).count();
        int minResults = Math.min(selected.size(), Math.max(
                requiredCount,
                isConversationPinned(selected) ? 1 : Math.min(2, selected.size())));

        if (allowFullCoreEvidence
                && estimatedPromptTokens(safeRequest.systemPrompt(), safeRequest.promptPrefix(), context) > budget) {
            allowFullCoreEvidence = false;
            context = safeRequest.compactForStreaming()
                    ? buildStreamingContext(safeRequest.question(), mode, selected, false)
                    : buildContext(safeRequest.question(), mode, selected, false);
        }
        while (selected.size() > minResults
                && estimatedPromptTokens(safeRequest.systemPrompt(), safeRequest.promptPrefix(), context) > budget) {
            removeBudgetCandidate(selected);
            context = safeRequest.compactForStreaming()
                    ? buildStreamingContext(safeRequest.question(), mode, selected, false)
                    : buildContext(safeRequest.question(), mode, selected, false);
        }
        return new ContextBundle(
                selected,
                context,
                Math.max(0, original.size() - selected.size()));
    }

    public String buildContext(String question, String mode, List<CodeSearchResult> results) {
        return buildContext(safe(question), Mode.from(mode), safeResults(results), true);
    }

    public String buildStreamingContext(String question, String mode, List<CodeSearchResult> results) {
        return buildStreamingContext(safe(question), Mode.from(mode), safeResults(results), true);
    }

    static int promptTokenBudget(int contextWindow, int configuredPromptTokenBudget) {
        int safeContextWindow = Math.max(2048, contextWindow);
        int configured = Math.max(512, configuredPromptTokenBudget);
        return Math.min(configured, Math.max(1800, safeContextWindow - 700));
    }

    static int estimateTokens(String value) {
        String compact = safe(value).trim();
        if (compact.isEmpty()) {
            return 0;
        }
        return Math.max(1, (compact.length() + 2) / 3);
    }

    private int estimatedPromptTokens(String systemPrompt, String promptPrefix, String context) {
        return estimateTokens(systemPrompt) + estimateTokens(promptPrefix) + estimateTokens(context);
    }

    private String buildContext(
            String question,
            Mode mode,
            List<CodeSearchResult> results,
            boolean allowFullCoreEvidence
    ) {
        if (results.isEmpty()) {
            return "No source-code context retrieved.";
        }
        int maxChars = mode == Mode.OVERVIEW
                ? OVERVIEW_CONTEXT_CHARS
                : mode == Mode.REASONING ? REASONING_CONTEXT_CHARS : DEFAULT_CONTEXT_CHARS;
        String validation = evidenceValidationContext(results);
        String context = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    CodeExcerpt excerpt = codeExcerptInfo(
                            question,
                            result,
                            contextCharsFor(question, mode, result, index, maxChars, allowFullCoreEvidence));
                    return "[" + (index + 1) + "] "
                            + result.filePath() + ":" + result.lineStart() + "-" + result.lineEnd()
                            + " type=" + result.chunkType()
                            + nullable(" class=", result.className())
                            + nullable(" method=", result.methodName())
                            + nullable(" control=", result.controlName())
                            + nullable(" event=", result.eventName())
                            + endpointContext(result)
                            + citationKindContext(result)
                            + executionOrderContext(result)
                            + analysisDiagnosticContext(result)
                            + graphContext(result)
                            + evidenceRankingContext(result)
                            + adjudicationClaimContext(result)
                            + excerptContext(result, excerpt)
                            + "\n" + excerpt.text();
                })
                .collect(Collectors.joining("\n\n"));
        return validation.isBlank() ? context : validation + "\n\n" + context;
    }

    private String buildStreamingContext(
            String question,
            Mode mode,
            List<CodeSearchResult> results,
            boolean allowFullCoreEvidence
    ) {
        if (results.isEmpty()) {
            return "No source-code context retrieved.";
        }
        int detailedLimit = Math.min(results.size(), detailedStreamingContextLimit(mode, results));
        int detailedChars = streamingDetailedContextChars(mode);
        int compactChars = streamingCompactContextChars(mode);
        String validation = evidenceValidationContext(results);
        String context = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    boolean detailed = index < detailedLimit || isRequiredConversationPinned(result);
                    return detailed
                            ? streamingDetailedContextLine(
                                    question, mode, result, index, detailedChars, allowFullCoreEvidence)
                            : streamingCompactContextLine(question, result, index + 1, compactChars);
                })
                .collect(Collectors.joining("\n\n"));
        return validation.isBlank() ? context : validation + "\n\n" + context;
    }

    private int detailedStreamingContextLimit(Mode mode, List<CodeSearchResult> results) {
        int requiredCount = (int) results.stream().filter(this::isRequiredConversationPinned).count();
        int base = switch (mode) {
            case LOCATE -> 3;
            case OVERVIEW, REASONING, CALL_FLOW -> 5;
            case EXPLAIN_METHOD, UI_EVENT, IMPACT -> 4;
        };
        return Math.max(base, requiredCount);
    }

    private int streamingDetailedContextChars(Mode mode) {
        return switch (mode) {
            case LOCATE -> 520;
            case OVERVIEW -> 620;
            case REASONING, CALL_FLOW -> 900;
            case EXPLAIN_METHOD, UI_EVENT, IMPACT -> 820;
        };
    }

    private int streamingCompactContextChars(Mode mode) {
        return switch (mode) {
            case LOCATE -> 180;
            case OVERVIEW -> 220;
            case REASONING, CALL_FLOW -> 320;
            case EXPLAIN_METHOD, UI_EVENT, IMPACT -> 280;
        };
    }

    private String streamingDetailedContextLine(
            String question,
            Mode mode,
            CodeSearchResult result,
            int index,
            int maxChars,
            boolean allowFullCoreEvidence
    ) {
        CodeExcerpt excerpt = codeExcerptInfo(
                question,
                result,
                contextCharsFor(question, mode, result, index, maxChars, allowFullCoreEvidence));
        return "[" + (index + 1) + "] " + compactCodeHeader(result)
                + citationKindContext(result)
                + executionOrderContext(result)
                + analysisDiagnosticContext(result)
                + graphContext(result)
                + evidenceRankingContext(result)
                + adjudicationClaimContext(result)
                + excerptContext(result, excerpt)
                + "\n" + excerpt.text();
    }

    private String streamingCompactContextLine(
            String question,
            CodeSearchResult result,
            int citationNumber,
            int maxChars
    ) {
        CodeExcerpt excerpt = codeExcerptInfo(question, result, maxChars);
        return "[" + citationNumber + "] " + compactCodeHeader(result)
                + excerptContext(result, excerpt)
                + "\nKey excerpt: " + excerpt.text();
    }

    private String compactCodeHeader(CodeSearchResult result) {
        return result.filePath() + ":" + result.lineStart() + "-" + result.lineEnd()
                + " type=" + result.chunkType()
                + nullable(" class=", result.className())
                + nullable(" method=", result.methodName())
                + nullable(" control=", result.controlName())
                + nullable(" event=", result.eventName())
                + endpointContext(result);
    }

    private boolean isRequiredContextEvidence(CodeSearchResult result) {
        return isRequiredConversationPinned(result)
                || metadataBoolean(result, "deterministicEndpointEvidence")
                || metadataBoolean(result, "deterministicEndpointBestMatch")
                || metadataBoolean(result, "llmValidatedEvidence")
                || metadataBoolean(result, "llmEvidenceSlateMustUse")
                || metadataBoolean(result, "llmChecklistGroupRequired");
    }

    private boolean isConversationPinned(List<CodeSearchResult> results) {
        return results != null && results.stream().anyMatch(this::isConversationPinned);
    }

    private boolean isConversationPinned(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.TRUE.equals(result.metadata().get("conversationPinned"));
    }

    private boolean isRequiredConversationPinned(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.TRUE.equals(result.metadata().get("conversationRequired"));
    }

    private void removeBudgetCandidate(List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult candidate = selected.get(index);
            if (!isConversationPinned(candidate)
                    && !isRequiredConversationPinned(candidate)
                    && !metadataBoolean(candidate, "llmEvidenceSlateMustUse")
                    && !metadataBoolean(candidate, "llmChecklistGroupRequired")) {
                selected.remove(index);
                return;
            }
        }
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult candidate = selected.get(index);
            if (!isRequiredConversationPinned(candidate)
                    && !metadataBoolean(candidate, "llmEvidenceSlateMustUse")
                    && !metadataBoolean(candidate, "llmChecklistGroupRequired")) {
                selected.remove(index);
                return;
            }
        }
        selected.remove(selected.size() - 1);
    }

    private int contextCharsFor(
            String question,
            Mode mode,
            CodeSearchResult result,
            int index,
            int defaultMaxChars,
            boolean allowFullCoreEvidence
    ) {
        if (!allowFullCoreEvidence
                || index > 0
                || !isCoreFullContextCandidate(question, mode, result)) {
            return defaultMaxChars;
        }
        String content = safe(result == null ? "" : result.content());
        if (content.isBlank()) {
            return defaultMaxChars;
        }
        return Math.max(defaultMaxChars, content.length());
    }

    private boolean isCoreFullContextCandidate(String question, Mode mode, CodeSearchResult result) {
        if (result == null || !isDirectCodeEvidence(result) || !isImplementationFlowQuestion(question, mode)) {
            return false;
        }
        String symbol = firstNonBlank(
                result.methodName(), result.symbolName(), result.className(), result.controlName(), result.eventName());
        if (symbol.isBlank()) {
            return false;
        }
        String identity = normalizeCodeText(splitIdentifierTerms(String.join(
                " ", symbol, safe(result.filePath()))));
        String query = normalizeCodeText(splitIdentifierTerms(question));
        Set<String> identityTerms = coverageTerms(identity);
        Set<String> queryTerms = coverageTerms(query);
        return identityTerms.stream().anyMatch(query::contains)
                || queryTerms.stream().anyMatch(identity::contains);
    }

    private boolean isImplementationFlowQuestion(String question, Mode mode) {
        if (mode == Mode.CALL_FLOW || mode == Mode.REASONING || mode == Mode.IMPACT || mode == Mode.UI_EVENT) {
            return true;
        }
        String normalized = normalizeCodeText(question);
        return containsRoleTerms(
                normalized,
                "flow", "pipeline", "process", "through", "calls", "call", "ranking", "expansion",
                "generation", "handler", "binding", "transaction", "fallback", "complete", "entire");
    }

    private boolean isDirectCodeEvidence(CodeSearchResult result) {
        if (!hasLlmEvidenceClassification(result)) {
            return result != null;
        }
        return "direct_code".equals(llmEvidenceKind(result));
    }

    private Set<String> coverageTerms(String text) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(text));
        if (normalized.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 3)
                .filter(term -> !COVERAGE_STOP_WORDS.contains(term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String endpointContext(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        String route = String.valueOf(result.metadata().getOrDefault("endpointRoute", "")).trim();
        String method = String.valueOf(result.metadata().getOrDefault("httpMethod", "")).trim();
        return nullable(" endpointRoute=", route.isBlank() ? null : route)
                + nullable(" httpMethod=", method.isBlank() ? null : method);
    }

    private String graphContext(CodeSearchResult result) {
        if (!isGraphExpanded(result)) {
            return "";
        }
        Object edgeType = result.metadata().get("graphEdgeType");
        Object graphPath = result.metadata().get("graphPath");
        Object edgeTypes = result.metadata().get("graphEdgeTypes");
        Object depth = result.metadata().get("graphDepth");
        Object evidenceKind = result.metadata().get("graphEvidenceKind");
        Object graphConfidence = result.metadata().get("graphConfidence");
        Object confidenceReason = result.metadata().get("confidenceReason");
        Object sourceDetail = result.metadata().get("sourceDetail");
        String kind = safeValue(evidenceKind, "direct");
        return " graphEvidence=" + kind
                + " graphEdge=" + safeValue(edgeType, "RELATED")
                + nullable(" edges=", stringValue(edgeTypes))
                + nullable(" depth=", stringValue(depth))
                + nullable(" confidence=", stringValue(graphConfidence))
                + nullable(" confidenceReason=", stringValue(confidenceReason))
                + nullable(" sourceDetail=", sourceDetail == null ? null : truncate(String.valueOf(sourceDetail), 120))
                + nullable(" path=", stringValue(graphPath));
    }

    private String citationKindContext(CodeSearchResult result) {
        return hasLlmEvidenceClassification(result) ? " citationKind=" + citationKind(result) : "";
    }

    private String citationKind(CodeSearchResult result) {
        if (result == null) {
            return "unknown";
        }
        String kind = llmEvidenceKind(result);
        if (!kind.isBlank()) {
            return switch (kind) {
                case "graph_relationship" -> "graph_relationship";
                case "supporting_context" -> "supporting_context";
                default -> "direct_code";
            };
        }
        return "unknown";
    }

    private String executionOrderContext(CodeSearchResult result) {
        List<String> phases = evidencePhases(result);
        return phases.isEmpty() ? "" : " executionOrder=" + executionOrder(phases);
    }

    private List<String> evidencePhases(CodeSearchResult result) {
        if (result == null) {
            return List.of();
        }
        String phase = llmImplementationPhase(result);
        return !phase.isBlank() && !"UNKNOWN".equals(phase) ? List.of(phase) : List.of();
    }

    private String executionOrder(List<String> phases) {
        if (phases == null || phases.isEmpty()) {
            return "unknown";
        }
        return phases.stream()
                .map(phase -> {
                    int order = phaseOrder(phase);
                    String previous = phaseByOrder(order - 1);
                    String next = phaseByOrder(order + 1);
                    return phase
                            + (previous.isBlank() ? "" : ".happensAfter=" + previous)
                            + (next.isBlank() ? "" : ".happensBefore=" + next);
                })
                .collect(Collectors.joining("|"));
    }

    private int phaseOrder(String phase) {
        return switch (safe(phase)) {
            case "INDEXING" -> 0;
            case "GRAPH_STORAGE" -> 1;
            case "SEARCH_EXPANSION" -> 2;
            case "RANKING" -> 3;
            case "ANSWER_GENERATION" -> 4;
            default -> 99;
        };
    }

    private String phaseByOrder(int order) {
        return switch (order) {
            case 0 -> "INDEXING";
            case 1 -> "GRAPH_STORAGE";
            case 2 -> "SEARCH_EXPANSION";
            case 3 -> "RANKING";
            case 4 -> "ANSWER_GENERATION";
            default -> "";
        };
    }

    private String analysisDiagnosticContext(CodeSearchResult result) {
        String status = directAnalysisDiagnosticStatus(result);
        if (status.isBlank()) {
            return "";
        }
        return " analysisDiagnosticStatus=" + status
                + " analysisDiagnosticScope=" + directAnalysisDiagnosticScope(result)
                + nullable(" analysisDiagnosticStage=", directAnalysisDiagnosticStage(result))
                + nullable(" analysisDiagnosticLanguage=", directAnalysisDiagnosticLanguage(result))
                + nullable(" analysisDiagnosticAnalyzer=", directAnalysisDiagnosticAnalyzer(result));
    }

    private String evidenceValidationContext(List<CodeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        boolean hasClassification = results.stream().anyMatch(this::hasLlmEvidenceClassification);
        boolean hasDiagnostics = results.stream()
                .anyMatch(result -> !directAnalysisDiagnosticStatus(result).isBlank());
        if (!hasClassification && !hasDiagnostics) {
            return "";
        }
        List<String> checks = new ArrayList<>();
        checks.add("Evidence validation: LLM evidence classification describes what each chunk can prove; do not use unclassified chunks as proof for phase-specific claims.");
        checks.add("rank/evidenceScore are relevance signals, not execution order.");
        checks.add("Guard clauses and early returns are failure handling only when cited code or diagnostic metadata reports failed, partial, skipped, unavailable, or exception.");
        checks.add("retrievalSource=graph_expansion means the chunk was found through graph traversal; it is not graph persistence evidence.");
        checks.add("GRAPH_STORAGE evidence requires code/schema that creates, inserts, updates, deletes, replaces, or activates graph nodes/edges.");
        if (hasDiagnostics) {
            checks.add("analysisDiagnosticStatus is diagnostic metadata for graph/index analysis evidence, not proof of runtime answer-generation fallback.");
            checks.add("If analysisDiagnosticLanguage or analysisDiagnosticStage does not match a language/framework named in the question, treat it as cross-language supporting evidence rather than the primary answer basis.");
        }
        return String.join(" ", checks);
    }

    private String directAnalysisDiagnosticStatus(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        for (String key : List.of("analysisDiagnosticStatus", "diagnosticStatus", "analysisStatus")) {
            String normalized = normalizeDiagnosticStatus(stringValue(result.metadata().get(key)));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String directAnalysisDiagnosticScope(CodeSearchResult result) {
        String scope = metadataString(result, "analysisDiagnosticScope", "diagnosticScope");
        return scope.isBlank() && !directAnalysisDiagnosticStatus(result).isBlank()
                ? "GRAPH_ANALYSIS"
                : scope;
    }

    private String directAnalysisDiagnosticStage(CodeSearchResult result) {
        String stage = metadataString(result, "analysisDiagnosticStage", "diagnosticStage", "stage");
        return stage.isBlank() ? "" : stage.toUpperCase(Locale.ROOT);
    }

    private String directAnalysisDiagnosticLanguage(CodeSearchResult result) {
        return normalizeDiagnosticLanguage(metadataString(
                result, "analysisDiagnosticLanguage", "diagnosticLanguage", "language"));
    }

    private String directAnalysisDiagnosticAnalyzer(CodeSearchResult result) {
        return metadataString(result, "analysisDiagnosticAnalyzer", "diagnosticAnalyzer", "analyzer");
    }

    private String normalizeDiagnosticStatus(String value) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(value));
        if (normalized.isBlank()) {
            return "";
        }
        if (containsRoleTerms(normalized, "failed", "failure", "error", "exception")) {
            return "FAILED";
        }
        if (containsRoleTerms(normalized, "partial", "partially", "incomplete")) {
            return "PARTIAL";
        }
        if (containsRoleTerms(normalized, "skipped", "skip", "unavailable", "disabled")) {
            return "SKIPPED";
        }
        if (containsRoleTerms(normalized, "success", "succeeded", "complete", "completed")) {
            return "SUCCESS";
        }
        return "";
    }

    private String normalizeDiagnosticLanguage(String value) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(value));
        if (normalized.isBlank()) {
            return "";
        }
        if (containsRoleTerms(normalized, "java")
                && !containsRoleTerms(normalized, "javascript", "java script")) {
            return "java";
        }
        if (containsRoleTerms(normalized, "csharp", "c sharp", "c#", "dotnet", "roslyn")) {
            return "csharp";
        }
        return "";
    }

    private String evidenceRankingContext(CodeSearchResult result) {
        if (result == null
                || result.metadata() == null
                || !result.metadata().containsKey("evidenceScore")) {
            return "";
        }
        Object score = result.metadata().get("evidenceScore");
        Object reason = result.metadata().get("evidenceRankReason");
        return nullable(" rank=", stringValue(score))
                + (evidenceRankingDebug && isGraphExpanded(result)
                        ? nullable(" reason=", stringValue(reason))
                        : "");
    }

    private String adjudicationClaimContext(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        return nullable(" llmSupportedClaims=", stringValue(result.metadata().get("llmSupportedClaims")))
                + nullable(" llmNotSupportedClaims=", stringValue(result.metadata().get("llmNotSupportedClaims")));
    }

    private String excerptContext(CodeSearchResult result, CodeExcerpt excerpt) {
        if (excerpt == null) {
            return "";
        }
        return " excerptKind=" + excerpt.kind()
                + " contentComplete=" + excerpt.contentComplete()
                + " omittedByBudget=" + excerpt.omittedByBudget()
                + " sourceLines=" + resultLineStart(result) + "-" + resultLineEnd(result)
                + " excerptLines=" + excerpt.excerptLineStart() + "-" + excerpt.excerptLineEnd();
    }

    private CodeExcerpt codeExcerptInfo(String question, CodeSearchResult result, int maxChars) {
        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(question, result, maxChars);
        return new CodeExcerpt(
                excerpt.text(),
                excerpt.kind(),
                excerpt.contentComplete(),
                excerpt.omittedByBudget(),
                excerpt.lineStart(),
                excerpt.lineEnd());
    }

    private int resultLineStart(CodeSearchResult result) {
        return result == null ? 0 : Math.max(0, result.lineStart());
    }

    private int resultLineEnd(CodeSearchResult result) {
        return result == null ? 0 : Math.max(resultLineStart(result), result.lineEnd());
    }

    private boolean hasLlmEvidenceClassification(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && "llm_adjudication".equals(String.valueOf(
                        result.metadata().get("llmEvidenceClassificationSource")));
    }

    private String llmEvidenceKind(CodeSearchResult result) {
        if (!hasLlmEvidenceClassification(result)) {
            return "";
        }
        String kind = metadataString(result, "llmEvidenceKind");
        return switch (kind) {
            case "graph_relationship", "supporting_context", "direct_code" -> kind;
            default -> "";
        };
    }

    private String llmImplementationPhase(CodeSearchResult result) {
        if (!hasLlmEvidenceClassification(result)) {
            return "";
        }
        String phase = metadataString(result, "llmImplementationPhase");
        return switch (phase) {
            case "INDEXING", "GRAPH_STORAGE", "SEARCH_EXPANSION", "RANKING", "ANSWER_GENERATION", "UNKNOWN" -> phase;
            default -> "";
        };
    }

    private boolean isGraphExpanded(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.TRUE.equals(result.metadata().get("graphExpanded"));
    }

    private boolean metadataBoolean(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || key == null) {
            return false;
        }
        Object value = result.metadata().get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String metadataString(CodeSearchResult result, String... keys) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        for (String key : keys) {
            Object value = result.metadata().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private boolean containsRoleTerms(String value, String... terms) {
        String safeValue = safe(value);
        for (String term : terms) {
            if (safeValue.contains(normalizeQuestionText(splitIdentifierTerms(term)))) {
                return true;
            }
        }
        return false;
    }

    private String splitIdentifierTerms(String value) {
        return safe(value)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('/', ' ')
                .replace('.', ' ');
    }

    private String normalizeQuestionText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHangul}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeCodeText(String value) {
        return value == null ? "" : value
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values == null ? new String[0] : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private static List<CodeSearchResult> safeResults(List<CodeSearchResult> results) {
        return results == null ? List.of() : List.copyOf(results);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record AssemblyRequest(
            String question,
            String mode,
            String systemPrompt,
            String promptPrefix,
            List<CodeSearchResult> results,
            boolean compactForStreaming,
            int contextWindow,
            int configuredPromptTokenBudget
    ) {
        public AssemblyRequest {
            question = safe(question);
            mode = safe(mode);
            systemPrompt = safe(systemPrompt);
            promptPrefix = safe(promptPrefix);
            results = safeResults(results);
        }

        static AssemblyRequest empty() {
            return new AssemblyRequest("", "locate", "", "", List.of(), false, 2048, 512);
        }
    }

    public record ContextBundle(List<CodeSearchResult> results, String context, int droppedCount) {
        public ContextBundle {
            results = safeResults(results);
            context = safe(context);
            droppedCount = Math.max(0, droppedCount);
        }
    }

    private record CodeExcerpt(
            String text,
            String kind,
            boolean contentComplete,
            boolean omittedByBudget,
            int excerptLineStart,
            int excerptLineEnd
    ) {
    }

    private enum Mode {
        OVERVIEW,
        REASONING,
        LOCATE,
        EXPLAIN_METHOD,
        CALL_FLOW,
        UI_EVENT,
        IMPACT;

        static Mode from(String value) {
            if (value == null || value.isBlank()) {
                return LOCATE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "overview" -> OVERVIEW;
                case "reasoning" -> REASONING;
                case "method" -> EXPLAIN_METHOD;
                case "flow" -> CALL_FLOW;
                case "ui_event" -> UI_EVENT;
                case "impact" -> IMPACT;
                default -> LOCATE;
            };
        }
    }
}
