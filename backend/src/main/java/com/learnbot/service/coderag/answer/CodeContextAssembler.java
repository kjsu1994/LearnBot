package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.EvidenceExcerptSelector;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;
import com.learnbot.service.coderag.model.CodeAnalysisDiagnosticMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builds source-code evidence context supplied to answer generation.
 *
 * <p>This component owns evidence ordering, excerpt rendering, streaming compaction, and
 * prompt-budget trimming. Retrieval and evidence adjudication must finish before calling it.</p>
 */
public final class CodeContextAssembler {
    private static final int OVERVIEW_CONTEXT_CHARS = 620;
    private static final int DEFAULT_CONTEXT_CHARS = 1200;
    private static final int REASONING_CONTEXT_CHARS = 1000;
    private static final Set<String> COVERAGE_STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "that", "this", "into", "onto",
            "where", "what", "when", "which", "who", "why", "how", "does", "are", "was", "were"
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
        RenderedContext rendered = safeRequest.compactForStreaming()
                ? renderStreamingContext(safeRequest.question(), mode, selected, allowFullCoreEvidence)
                : renderContext(safeRequest.question(), mode, selected, allowFullCoreEvidence);
        String context = rendered.context();
        int budget = promptTokenBudget(
                safeRequest.contextWindow(), safeRequest.configuredPromptTokenBudget());
        Set<String> requiredEvidenceIds = safeRequest.requiredEvidenceIds();
        int requiredCount = (int) selected.stream()
                .filter(result -> isRequiredContextEvidence(result, requiredEvidenceIds))
                .count();
        int minResults = Math.min(selected.size(), Math.max(
                requiredCount,
                isConversationPinned(selected) ? 1 : Math.min(2, selected.size())));

        if (allowFullCoreEvidence
                && estimatedPromptTokens(safeRequest.systemPrompt(), safeRequest.promptPrefix(), context) > budget) {
            allowFullCoreEvidence = false;
            rendered = safeRequest.compactForStreaming()
                    ? renderStreamingContext(safeRequest.question(), mode, selected, false)
                    : renderContext(safeRequest.question(), mode, selected, false);
            context = rendered.context();
        }
        while (selected.size() > minResults
                && estimatedPromptTokens(safeRequest.systemPrompt(), safeRequest.promptPrefix(), context) > budget) {
            if (!removeBudgetCandidate(selected, requiredEvidenceIds)) {
                break;
            }
            rendered = safeRequest.compactForStreaming()
                    ? renderStreamingContext(safeRequest.question(), mode, selected, false)
                    : renderContext(safeRequest.question(), mode, selected, false);
            context = rendered.context();
        }
        return new ContextBundle(
                rendered.results(),
                context,
                Math.max(0, original.size() - selected.size()));
    }

    public String buildContext(String question, String mode, List<CodeSearchResult> results) {
        return renderContext(safe(question), Mode.from(mode), safeResults(results), true).context();
    }

    public String buildStreamingContext(String question, String mode, List<CodeSearchResult> results) {
        return renderStreamingContext(safe(question), Mode.from(mode), safeResults(results), true).context();
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

    private RenderedContext renderContext(
            String question,
            Mode mode,
            List<CodeSearchResult> results,
            boolean allowFullCoreEvidence
    ) {
        if (results.isEmpty()) {
            return RenderedContext.empty();
        }
        int maxChars = mode == Mode.OVERVIEW
                ? OVERVIEW_CONTEXT_CHARS
                : mode == Mode.REASONING ? REASONING_CONTEXT_CHARS : DEFAULT_CONTEXT_CHARS;
        String validation = evidenceValidationContext(results);
        List<RenderedEvidence> rendered = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    CodeExcerpt excerpt = codeExcerptInfo(
                            question,
                            result,
                            contextCharsFor(question, mode, result, index, maxChars, allowFullCoreEvidence));
                    return new RenderedEvidence(result, excerpt, index + 1);
                })
                .toList();
        String context = rendered.stream()
                .map(item -> {
                    CodeSearchResult result = item.source();
                    CodeExcerpt excerpt = item.excerpt();
                    return "[" + item.citationNumber() + "] "
                            + result.filePath() + ":" + excerpt.excerptLineStart() + "-" + excerpt.excerptLineEnd()
                            + " type=" + result.chunkType()
                            + nullable(" class=", result.className())
                            + nullable(" method=", result.methodName())
                            + nullable(" control=", result.controlName())
                            + nullable(" event=", result.eventName())
                            + citationKindContext(result)
                            + analysisDiagnosticContext(result)
                            + graphContext(result)
                            + evidenceRankingContext(result)
                            + adjudicationClaimContext(result, excerpt)
                            + excerptContext(result, excerpt)
                            + "\n" + excerpt.text();
                })
                .collect(Collectors.joining("\n\n"));
        return new RenderedContext(
                rendered.stream().map(this::promptResult).toList(),
                validation.isBlank() ? context : validation + "\n\n" + context);
    }

    private RenderedContext renderStreamingContext(
            String question,
            Mode mode,
            List<CodeSearchResult> results,
            boolean allowFullCoreEvidence
    ) {
        if (results.isEmpty()) {
            return RenderedContext.empty();
        }
        int detailedLimit = Math.min(results.size(), detailedStreamingContextLimit(mode, results));
        int detailedChars = streamingDetailedContextChars(mode);
        int compactChars = streamingCompactContextChars(mode);
        String validation = evidenceValidationContext(results);
        List<RenderedEvidence> rendered = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    boolean detailed = index < detailedLimit || isRequiredConversationPinned(result);
                    int maxChars = detailed
                            ? contextCharsFor(question, mode, result, index, detailedChars, allowFullCoreEvidence)
                            : compactChars;
                    return new RenderedEvidence(
                            result, codeExcerptInfo(question, result, maxChars), index + 1, detailed);
                })
                .toList();
        String context = rendered.stream()
                .map(item -> item.detailed()
                        ? streamingDetailedContextLine(item)
                        : streamingCompactContextLine(item))
                .collect(Collectors.joining("\n\n"));
        return new RenderedContext(
                rendered.stream().map(this::promptResult).toList(),
                validation.isBlank() ? context : validation + "\n\n" + context);
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

    private String streamingDetailedContextLine(RenderedEvidence item) {
        CodeSearchResult result = item.source();
        CodeExcerpt excerpt = item.excerpt();
        return "[" + item.citationNumber() + "] " + compactCodeHeader(result, excerpt)
                + citationKindContext(result)
                + analysisDiagnosticContext(result)
                + graphContext(result)
                + evidenceRankingContext(result)
                + adjudicationClaimContext(result, excerpt)
                + excerptContext(result, excerpt)
                + "\n" + excerpt.text();
    }

    private String streamingCompactContextLine(RenderedEvidence item) {
        CodeSearchResult result = item.source();
        CodeExcerpt excerpt = item.excerpt();
        return "[" + item.citationNumber() + "] " + compactCodeHeader(result, excerpt)
                + excerptContext(result, excerpt)
                + "\nKey excerpt: " + excerpt.text();
    }

    private CodeSearchResult promptResult(RenderedEvidence item) {
        CodeSearchResult source = item.source();
        CodeExcerpt excerpt = item.excerpt();
        Map<String, Object> metadata = promptMetadata(source, excerpt);
        return new CodeSearchResult(
                source.chunkId(), source.repositoryId(), source.fileId(), source.repositoryName(),
                source.filePath(), source.chunkType(), source.symbolName(), source.className(),
                source.methodName(), source.namespaceName(), source.controlName(), source.eventName(),
                source.chunkIndex(), excerpt.excerptLineStart(), excerpt.excerptLineEnd(), excerpt.text(),
                source.score(), metadata);
    }

    private Map<String, Object> promptMetadata(CodeSearchResult source, CodeExcerpt excerpt) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                source == null || source.metadata() == null ? Map.of() : source.metadata());
        int sourceLineStart = resultLineStart(source);
        int sourceLineEnd = resultLineEnd(source);
        boolean omittedByBudget = excerpt.omittedByBudget()
                || metadataBoolean(source, "omittedByBudget");
        boolean contentComplete = excerpt.contentComplete()
                && !omittedByBudget
                && !metadataExplicitlyFalse(source, "contentComplete");

        preserveSourceMetadata(metadata, "actualLineStart", "sourceActualLineStart");
        preserveSourceMetadata(metadata, "actualLineEnd", "sourceActualLineEnd");
        metadata.put("sourceLineStart", sourceLineStart);
        metadata.put("sourceLineEnd", sourceLineEnd);
        metadata.put("actualLineStart", excerpt.excerptLineStart());
        metadata.put("actualLineEnd", excerpt.excerptLineEnd());
        metadata.put("excerptLineStart", excerpt.excerptLineStart());
        metadata.put("excerptLineEnd", excerpt.excerptLineEnd());
        metadata.put("excerptKind", excerpt.kind());
        metadata.put("contentComplete", contentComplete);
        metadata.put("omittedByBudget", omittedByBudget);

        if (!contentComplete) {
            metadata.put("llmValidatedEvidence", false);
            metadata.remove("llmValidatedEvidenceGroup");
            metadata.remove("llmSupportedClaims");
            metadata.remove("llmNotSupportedClaims");
        }
        return Collections.unmodifiableMap(metadata);
    }

    private void preserveSourceMetadata(Map<String, Object> metadata, String key, String sourceKey) {
        if (metadata.containsKey(key)) {
            metadata.putIfAbsent(sourceKey, metadata.get(key));
        }
    }

    private boolean metadataExplicitlyFalse(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || !result.metadata().containsKey(key)) {
            return false;
        }
        Object value = result.metadata().get(key);
        return value instanceof Boolean bool ? !bool : !Boolean.parseBoolean(String.valueOf(value));
    }

    private String compactCodeHeader(CodeSearchResult result, CodeExcerpt excerpt) {
        return result.filePath() + ":" + excerpt.excerptLineStart() + "-" + excerpt.excerptLineEnd()
                + " type=" + result.chunkType()
                + nullable(" class=", result.className())
                + nullable(" method=", result.methodName())
                + nullable(" control=", result.controlName())
                + nullable(" event=", result.eventName());
    }

    private boolean isRequiredContextEvidence(CodeSearchResult result, Set<String> requiredEvidenceIds) {
        return isTypedRequiredEvidence(result, requiredEvidenceIds)
                || isConversationPinned(result)
                || isRequiredConversationPinned(result)
                || metadataBoolean(result, "llmValidatedEvidence")
                || metadataBoolean(result, "llmEvidenceSlateMustUse")
                || metadataBoolean(result, "llmChecklistGroupRequired");
    }

    private boolean isTypedRequiredEvidence(CodeSearchResult result, Set<String> requiredEvidenceIds) {
        return result != null
                && requiredEvidenceIds != null
                && !requiredEvidenceIds.isEmpty()
                && requiredEvidenceIds.contains(CodeEvidenceId.from(result));
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

    private boolean removeBudgetCandidate(
            List<CodeSearchResult> selected,
            Set<String> requiredEvidenceIds
    ) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult candidate = selected.get(index);
            if (!isRequiredContextEvidence(candidate, requiredEvidenceIds)) {
                selected.remove(index);
                return true;
            }
        }
        return false;
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
        if (result == null || !isDirectCodeEvidence(result) || !isImplementationFlowQuestion(mode)) {
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
        return identityTerms.stream().anyMatch(queryTerms::contains);
    }

    private boolean isImplementationFlowQuestion(Mode mode) {
        return mode == Mode.EXPLAIN_METHOD
                || mode == Mode.CALL_FLOW
                || mode == Mode.REASONING
                || mode == Mode.IMPACT
                || mode == Mode.UI_EVENT;
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

    private String analysisDiagnosticContext(CodeSearchResult result) {
        CodeAnalysisDiagnosticMetadata diagnostic = CodeAnalysisDiagnosticMetadata.from(result);
        if (!diagnostic.present()) {
            return "";
        }
        return " analysisDiagnosticStatus=" + diagnostic.status()
                + " analysisDiagnosticScope=" + diagnostic.scope()
                + nullable(" analysisDiagnosticStage=", diagnostic.stage())
                + nullable(" analysisDiagnosticLanguage=", diagnostic.language())
                + nullable(" analysisDiagnosticAnalyzer=", diagnostic.analyzer())
                + (diagnostic.authority() == CodeIntelligenceAuthority.UNKNOWN
                        ? ""
                        : " analysisDiagnosticAuthority=" + diagnostic.authority().name());
    }

    private String evidenceValidationContext(List<CodeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        boolean hasClassification = results.stream().anyMatch(this::hasLlmEvidenceClassification);
        boolean hasDiagnostics = results.stream()
                .map(CodeAnalysisDiagnosticMetadata::from)
                .anyMatch(CodeAnalysisDiagnosticMetadata::present);
        if (!hasClassification && !hasDiagnostics) {
            return "";
        }
        List<String> checks = new ArrayList<>();
        checks.add("Evidence validation: classification metadata describes what each excerpt can directly support; ground factual claims in cited content and do not infer unobserved behavior.");
        checks.add("rank/evidenceScore are retrieval relevance signals and do not establish runtime or causal order.");
        checks.add("Failure-handling claims require cited content or diagnostics that directly report the relevant status, branch, or exception.");
        checks.add("Retrieval provenance describes how evidence was found; it does not by itself prove behavior or state changes.");
        checks.add("Behavioral, ordering, and state-transition claims require cited content that directly shows the asserted relationship.");
        if (hasDiagnostics) {
            checks.add("Diagnostic metadata reports analysis scope, stage, language, status, and authority; it does not by itself prove application runtime behavior.");
            checks.add("Use diagnostics as primary support only when their scope, stage, and language match the claim; otherwise treat them as supporting context.");
        }
        return String.join(" ", checks);
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

    private String adjudicationClaimContext(CodeSearchResult result, CodeExcerpt excerpt) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        if (!excerpt.contentComplete()
                || excerpt.omittedByBudget()
                || metadataExplicitlyFalse(result, "contentComplete")
                || metadataBoolean(result, "omittedByBudget")) {
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

    private static Set<String> safeEvidenceIds(Set<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) return Set.of();
        return evidenceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
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
            int configuredPromptTokenBudget,
            Set<String> requiredEvidenceIds
    ) {
        public AssemblyRequest {
            question = safe(question);
            mode = safe(mode);
            systemPrompt = safe(systemPrompt);
            promptPrefix = safe(promptPrefix);
            results = safeResults(results);
            requiredEvidenceIds = safeEvidenceIds(requiredEvidenceIds);
        }

        public AssemblyRequest(
                String question,
                String mode,
                String systemPrompt,
                String promptPrefix,
                List<CodeSearchResult> results,
                boolean compactForStreaming,
                int contextWindow,
                int configuredPromptTokenBudget
        ) {
            this(question, mode, systemPrompt, promptPrefix, results, compactForStreaming,
                    contextWindow, configuredPromptTokenBudget, Set.of());
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

    private record RenderedContext(List<CodeSearchResult> results, String context) {
        private RenderedContext {
            results = safeResults(results);
            context = safe(context);
        }

        private static RenderedContext empty() {
            return new RenderedContext(List.of(), "No source-code context retrieved.");
        }
    }

    private record RenderedEvidence(
            CodeSearchResult source,
            CodeExcerpt excerpt,
            int citationNumber,
            boolean detailed
    ) {
        private RenderedEvidence(CodeSearchResult source, CodeExcerpt excerpt, int citationNumber) {
            this(source, excerpt, citationNumber, true);
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
