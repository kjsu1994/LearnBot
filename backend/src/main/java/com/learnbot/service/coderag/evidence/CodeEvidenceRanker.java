package com.learnbot.service.coderag.evidence;

import com.learnbot.service.CodeSourceClassifier;
import com.learnbot.service.CodeIntelligenceAuthority;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Component
public class CodeEvidenceRanker {
    private static final int NEUTRAL_FLOW_RANK = Integer.MAX_VALUE;
    private final LearnBotProperties properties;

    public CodeEvidenceRanker(LearnBotProperties properties) {
        this.properties = properties;
    }

    public List<CodeSearchResult> rank(String question, CodeQuestionMode mode, List<CodeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (!enabled()) {
            return results.stream()
                    .sorted(Comparator.comparingDouble((CodeSearchResult result) -> legacyRelevance(question, mode, result)).reversed()
                            .thenComparing(CodeSearchResult::filePath)
                            .thenComparingInt(CodeSearchResult::lineStart))
                    .toList();
        }
        List<CodeSearchResult> ranked = results.stream()
                .map(result -> rankOne(question, mode, result))
                .sorted(Comparator.comparingDouble(this::score).reversed()
                        .thenComparing(CodeSearchResult::filePath)
                        .thenComparingInt(CodeSearchResult::lineStart))
                .toList();
        return applyDiversityPenalty(ranked).stream()
                .sorted(Comparator.comparingDouble((CodeSearchResult result) -> score(result)).reversed()
                        .thenComparingInt(this::flowRank)
                        .thenComparing(CodeSearchResult::filePath)
                        .thenComparingInt(CodeSearchResult::lineStart))
                .toList();
    }

    public double score(CodeSearchResult result) {
        if (result == null) {
            return 0;
        }
        Object value = result.metadata() == null ? null : result.metadata().get("evidenceScore");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? result.score() : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return result.score();
        }
    }

    public String reliability(CodeSearchResult result) {
        if (!hasGraphEvidence(result)) {
            return "none";
        }
        if (Boolean.TRUE.equals(result.metadata().get("graphTraversalTruncated"))) {
            return "partial";
        }
        return numberMetadata(result, "graphDepth", 1) <= 1 ? "strong" : "medium";
    }

    public GraphReliabilitySummary summarizeGraph(List<CodeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return GraphReliabilitySummary.empty();
        }
        int expanded = 0;
        int strong = 0;
        int medium = 0;
        int partial = 0;
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();
        for (CodeSearchResult result : results) {
            if (!hasGraphEvidence(result)) {
                continue;
            }
            expanded++;
            String reliability = reliability(result);
            if ("strong".equals(reliability)) {
                strong++;
            } else if ("medium".equals(reliability)) {
                medium++;
            } else if ("partial".equals(reliability)) {
                partial++;
            }
            String edgeType = String.valueOf(result.metadata().getOrDefault("graphEdgeType", "RELATED"));
            edgeCounts.merge(edgeType, 1, Integer::sum);
        }
        return new GraphReliabilitySummary(expanded, strong, medium, partial, edgeCounts);
    }

    public Map<String, Object> responseMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || debug()) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> compact = new LinkedHashMap<>(metadata);
        compact.remove("evidenceScoreParts");
        return Map.copyOf(compact);
    }

    public boolean debug() {
        return properties.getCode().getGraph().isEvidenceRankingDebug();
    }

    private List<CodeSearchResult> applyDiversityPenalty(List<CodeSearchResult> ranked) {
        Map<String, Integer> fileCounts = new LinkedHashMap<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        List<CodeSearchResult> adjusted = new ArrayList<>();
        for (CodeSearchResult result : ranked) {
            double penalty = Math.max(0, fileCounts.getOrDefault(result.filePath(), 0) * 0.08)
                    + Math.max(0, categoryCounts.getOrDefault(category(result), 0) * 0.05);
            adjusted.add(adjust(result, -penalty, penalty == 0 ? null : "diversity penalty"));
            fileCounts.merge(result.filePath(), 1, Integer::sum);
            categoryCounts.merge(category(result), 1, Integer::sum);
        }
        return adjusted;
    }

    private CodeSearchResult rankOne(String question, CodeQuestionMode mode, CodeSearchResult result) {
        double base = clamp(result.score(), 0, 1);
        double text = textMatchScore(question, result);
        double graph = graphEvidenceScore(mode, result);
        double literal = literalEvidenceScore(question, result);
        double intent = intentEvidenceScore(mode, result);
        double structure = structureEvidenceScore(mode, result);
        double legacy = Math.max(0, legacyRelevance(question, mode, result) - result.score()) * 0.20;
        double flow = mode == CodeQuestionMode.CALL_FLOW ? flowOrderScore(result) : 0;
        double conversation = isConversationPinned(result) ? 0.18 : 0;
        double sourcePolicy = sourcePolicyScore(mode, result);
        double granularity = implementationGranularityScore(result);
        double total = base + text + graph + literal + intent + structure + legacy + flow
                + conversation + sourcePolicy + granularity;
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("baseSearch", round(base));
        parts.put("textMatch", round(text));
        parts.put("graph", round(graph));
        if (literal > 0) {
            parts.put("literal", round(literal));
        }
        parts.put("intent", round(intent));
        parts.put("structure", round(structure));
        parts.put("legacyRerank", round(legacy));
        if (flow > 0) {
            parts.put("flowOrder", round(flow));
        }
        if (conversation > 0) {
            parts.put("conversationPinned", round(conversation));
        }
        if (sourcePolicy != 0) {
            parts.put("sourcePolicy", round(sourcePolicy));
        }
        if (granularity != 0) {
            parts.put("granularity", round(granularity));
        }
        return withMetadata(result, total, parts,
                reason(question, mode, result, graph, intent, structure, flow, sourcePolicy));
    }

    private CodeSearchResult adjust(CodeSearchResult result, double adjustment, String reason) {
        if (adjustment == 0 && (reason == null || reason.isBlank())) {
            return result;
        }
        Map<String, Object> sourceMetadata = result.metadata() == null ? Map.of() : result.metadata();
        Map<String, Object> parts = new LinkedHashMap<>(metadataMap(sourceMetadata.get("evidenceScoreParts")));
        if (adjustment != 0) {
            parts.put("diversity", round(adjustment));
        }
        String currentReason = String.valueOf(sourceMetadata.getOrDefault("evidenceRankReason", ""));
        String nextReason = reason == null || reason.isBlank()
                ? currentReason
                : currentReason.isBlank() ? reason : currentReason + "; " + reason;
        return withMetadata(result, score(result) + adjustment, parts, nextReason);
    }

    private CodeSearchResult withMetadata(CodeSearchResult result, double score, Map<String, Object> parts, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("evidenceScore", round(score));
        if (debug()) {
            metadata.put("evidenceScoreParts", Map.copyOf(parts));
        }
        metadata.put("evidenceRankReason", reason);
        metadata.put("graphReliability", reliability(result));
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score(), Map.copyOf(metadata)
        );
    }

    private double textMatchScore(String question, CodeSearchResult result) {
        List<String> terms = primaryQuestionTerms(question);
        if (terms.isEmpty()) {
            return 0;
        }
        String path = normalize(result.filePath());
        String symbol = normalize(String.join(" ",
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.controlName(), ""),
                safe(result.eventName(), "")
        ));
        String content = normalize(result.content());
        double score = 0;
        for (String term : terms) {
            if (path.contains(term)) score += 0.10;
            if (symbol.contains(term)) score += 0.12;
            if (content.contains(term)) score += 0.03;
        }
        return Math.min(0.55, score);
    }

    private double literalEvidenceScore(String question, CodeSearchResult result) {
        List<String> literals = literalTerms(question);
        if (literals.isEmpty()) {
            return 0;
        }
        String path = normalize(result.filePath());
        String symbol = normalize(String.join(" ",
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.controlName(), ""),
                safe(result.eventName(), "")
        ));
        String content = normalize(result.content());
        double score = 0;
        for (String literal : literals) {
            String term = normalize(literal);
            if (term.isBlank()) {
                continue;
            }
            if (path.contains(term)) score += 0.16;
            if (symbol.contains(term)) score += 0.18;
            if (content.contains(term)) score += literal.length() >= 8 ? 0.16 : 0.08;
        }
        return Math.min(0.45, score);
    }

    private List<String> literalTerms(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher quoted = Pattern.compile("[\"'`](.{3,120}?)[\"'`]").matcher(question);
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        Matcher indexed = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\[[0-9]+]\\b").matcher(question);
        while (indexed.find()) {
            values.add(indexed.group());
        }
        Matcher codeToken = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b").matcher(question);
        while (codeToken.find()) {
            values.add(codeToken.group());
        }
        return values.stream().distinct().limit(8).toList();
    }

    private double graphEvidenceScore(CodeQuestionMode mode, CodeSearchResult result) {
        if (!hasGraphEvidence(result)) {
            return 0;
        }
        double confidence = graphConfidenceScore(result);
        int depth = Math.max(1, (int) numberMetadata(result, "graphDepth", 1));
        double depthFactor = switch (depth) {
            case 1 -> 1.0;
            case 2 -> 0.72;
            case 3 -> 0.52;
            default -> 0.36;
        };
        double edgePresenceFactor = hasGraphEdgeEvidence(result) ? 1.0 : 0.82;
        double identityFactor = hasObservedIdentity(result) ? 1.0 : 0.86;
        double evidenceKindFactor = graphEvidenceKindFactor(result);
        double authorityFactor = graphAuthorityFactor(result);
        double modeFactor = graphModeFactor(mode);
        double truncationFactor = Boolean.TRUE.equals(result.metadata().get("graphTraversalTruncated")) ? 0.82 : 1.0;
        return 0.45
                * (0.55 + (0.45 * confidence))
                * depthFactor
                * edgePresenceFactor
                * identityFactor
                * evidenceKindFactor
                * authorityFactor
                * modeFactor
                * truncationFactor;
    }

    private double graphConfidenceScore(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return 0.5;
        }
        for (String key : List.of("graphPathScore", "graphScore", "graphConfidence")) {
            if (!result.metadata().containsKey(key)) {
                continue;
            }
            double value = numberMetadata(result, key, Double.NaN);
            if (Double.isFinite(value)) {
                return clamp(value, 0, 1);
            }
        }
        return 0.5;
    }

    private double graphAuthorityFactor(CodeSearchResult result) {
        return 0.88 + Math.min(0.12, authorityScore(result) * 1.5);
    }

    private double graphModeFactor(CodeQuestionMode mode) {
        if (mode == null) {
            return 0.90;
        }
        return switch (mode) {
            case CALL_FLOW, REASONING, IMPACT, UI_EVENT -> 1.0;
            case OVERVIEW, EXPLAIN_METHOD -> 0.92;
            case LOCATE -> 0.86;
        };
    }

    private double implementationGranularityScore(CodeSearchResult result) {
        int span = Math.max(1, result.lineEnd() - result.lineStart() + 1);
        boolean concreteSymbol = !safe(result.methodName(), "").isBlank()
                || !safe(result.symbolName(), "").isBlank();
        if (concreteSymbol && span <= 3) return 0.04;
        if (concreteSymbol && span <= 8) return 0.07;
        if (concreteSymbol && span <= 20) return 0.10;
        if (concreteSymbol && span <= 400) return 0.12;
        if (!concreteSymbol && span > 1500) return -0.35;
        if (!concreteSymbol && span > 500) return -0.20;
        return 0;
    }

    private double graphEvidenceKindFactor(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return 1.0;
        }
        String kind = String.valueOf(result.metadata().getOrDefault("graphEvidenceKind", "direct"))
                .toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "candidate" -> 0.62;
            case "inferred" -> 0.82;
            default -> 1.0;
        };
    }

    private double intentEvidenceScore(CodeQuestionMode mode, CodeSearchResult result) {
        String type = result.chunkType() == null ? "" : result.chunkType();
        boolean graphEvidence = hasGraphEvidence(result);
        boolean graphEdge = hasGraphEdgeEvidence(result);
        boolean observedIdentity = hasObservedIdentity(result);
        boolean structured = isStructured(result);
        boolean authoritative = authorityScore(result) > 0;
        boolean methodIdentity = notBlank(result.methodName()) || "method".equals(type);
        return switch (mode) {
            case CALL_FLOW -> graphEvidence ? (graphEdge ? 0.18 : 0.14) : observedIdentity ? 0.06 : 0.02;
            case IMPACT -> graphEvidence ? (authoritative ? 0.22 : 0.18)
                    : authoritative && structured ? 0.12 : structured ? 0.06 : 0.03;
            case REASONING -> graphEvidence ? (authoritative ? 0.21 : 0.18)
                    : authoritative && structured ? 0.16 : structured ? 0.10 : 0.04;
            case UI_EVENT -> graphEvidence && observedIdentity ? 0.24
                    : observedIdentity ? 0.20 : graphEvidence ? 0.12 : 0.02;
            case OVERVIEW -> isProjectContext(type) ? 0.18 : graphEvidence ? 0.12 : structured ? 0.08 : 0.04;
            case EXPLAIN_METHOD -> methodIdentity ? 0.22 : observedIdentity ? 0.12 : graphEvidence ? 0.08 : 0.02;
            case LOCATE -> observedIdentity ? 0.18 : graphEvidence ? 0.10 : structured ? 0.06 : 0.03;
        };
    }

    private double sourcePolicyScore(CodeQuestionMode mode, CodeSearchResult result) {
        String sourceRole = CodeSourceClassifier.sourceRole(result);
        double score = 0;

        if (CodeSourceClassifier.SOURCE_MAIN.equals(sourceRole)) {
            score += mode == CodeQuestionMode.OVERVIEW
                    || mode == CodeQuestionMode.CALL_FLOW
                    || mode == CodeQuestionMode.REASONING
                    || mode == CodeQuestionMode.IMPACT ? 0.18 : 0.08;
        } else if (CodeSourceClassifier.SOURCE_TEST.equals(sourceRole)) {
            score -= 0.38;
        } else if (CodeSourceClassifier.SOURCE_DOCS.equals(sourceRole)) {
            score += mode == CodeQuestionMode.OVERVIEW ? -0.04 : -0.16;
        } else if (CodeSourceClassifier.SOURCE_GENERATED.equals(sourceRole) || CodeSourceClassifier.SOURCE_VENDOR.equals(sourceRole)) {
            score -= 0.45;
        }

        return score;
    }

    private double structureEvidenceScore(CodeQuestionMode mode, CodeSearchResult result) {
        double score = isStructured(result) ? 0.10 : 0;
        if (notBlank(result.methodName())) score += 0.05;
        if (notBlank(result.className())) score += 0.04;
        if (notBlank(result.symbolName())) score += 0.03;
        score += authorityScore(result);
        if ((mode == CodeQuestionMode.OVERVIEW || mode == CodeQuestionMode.IMPACT) && isProjectContext(result.chunkType())) {
            score += 0.10;
        }
        return Math.max(-0.16, Math.min(0.34, score));
    }

    private String reason(String question, CodeQuestionMode mode, CodeSearchResult result, double graph, double intent, double structure, double flow, double sourcePolicy) {
        List<String> reasons = new ArrayList<>();
        if (graph > 0) reasons.add("graph evidence");
        if (literalEvidenceScore(question, result) > 0) reasons.add("literal code/log term match");
        if (intent >= 0.18) reasons.add(mode.value() + " intent match");
        if (structure >= 0.10) reasons.add("structured code evidence");
        if (flow > 0) reasons.add("flow order hint");
        if (sourcePolicy > 0) reasons.add("implementation source policy");
        if (sourcePolicy < 0 && CodeSourceClassifier.SOURCE_TEST.equals(CodeSourceClassifier.sourceRole(result))) {
            reasons.add("test evidence deprioritized");
        }
        if (reasons.isEmpty()) reasons.add("hybrid search relevance");
        return String.join(", ", reasons);
    }

    private double legacyRelevance(String question, CodeQuestionMode mode, CodeSearchResult result) {
        double score = result.score();
        List<String> terms = primaryQuestionTerms(question);
        String path = normalize(result.filePath());
        String symbolText = normalize(String.join(" ",
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.controlName(), ""),
                safe(result.eventName(), "")
        ));
        String content = normalize(result.content());
        for (String term : terms) {
            if (path.contains(term)) score += 0.55;
            if (symbolText.contains(term)) score += 0.45;
            if (content.contains(term)) score += 0.12;
        }
        if (isStructured(result)) score += 0.08;
        if ((mode == CodeQuestionMode.OVERVIEW || mode == CodeQuestionMode.IMPACT) && isProjectContext(result.chunkType())) {
            score += "project_structure".equals(result.chunkType()) || "repository_summary".equals(result.chunkType()) ? 0.65 : 0.30;
        }
        if (mode != CodeQuestionMode.OVERVIEW && mode != CodeQuestionMode.IMPACT && isProjectContext(result.chunkType())) {
            score -= "file_summary".equals(result.chunkType()) ? 0.04 : 0.16;
        }
        int flowRank = flowRank(result);
        if (mode == CodeQuestionMode.CALL_FLOW && flowRank != NEUTRAL_FLOW_RANK) {
            score += 0.08 / (1.0 + Math.min(flowRank, 1000));
        }
        if (hasGraphEvidence(result)) {
            score += switch (mode) {
                case CALL_FLOW, IMPACT -> 0.18;
                case OVERVIEW -> 0.10;
                default -> 0.05;
            };
        }
        return score;
    }

    public int flowRank(CodeSearchResult result) {
        Integer executionOrder = typedNonNegativeIntegerMetadata(result, "executionOrder");
        if (executionOrder != null) {
            return executionOrder;
        }
        Integer graphDepth = typedNonNegativeIntegerMetadata(result, "graphDepth");
        return graphDepth == null ? NEUTRAL_FLOW_RANK : graphDepth;
    }

    private double flowOrderScore(CodeSearchResult result) {
        int rank = flowRank(result);
        return rank == NEUTRAL_FLOW_RANK ? 0 : 0.125 / (1.0 + Math.min(rank, 1000));
    }

    private boolean isGraphExpanded(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("graphExpanded"));
    }

    private boolean hasGraphEvidence(CodeSearchResult result) {
        return isGraphExpanded(result)
                || hasGraphEdgeEvidence(result)
                || hasMetadataValue(result, "graphPath")
                || hasMetadataValue(result, "graphPathNodes")
                || hasMetadataValue(result, "graphPathScore")
                || hasMetadataValue(result, "graphEvidenceKind");
    }

    private boolean hasGraphEdgeEvidence(CodeSearchResult result) {
        return hasMetadataValue(result, "graphEdgeType") || hasMetadataValue(result, "graphEdgeTypes");
    }

    private boolean hasMetadataValue(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || !result.metadata().containsKey(key)) {
            return false;
        }
        Object value = result.metadata().get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private boolean isConversationPinned(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("conversationPinned"));
    }

    private List<String> primaryQuestionTerms(String question) {
        List<String> terms = new ArrayList<>();
        addTerms(terms, question);
        return terms.stream()
                .map(this::normalize)
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
    }

    private void addTerms(List<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : normalize(value).split("\\s+")) {
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHangul}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String category(CodeSearchResult result) {
        if (result == null) {
            return "unknown|unknown|unknown";
        }
        String sourceRole = normalizeCategoryComponent(CodeSourceClassifier.sourceRole(result));
        String chunkType = normalizeCategoryComponent(result.chunkType());
        String graphCategory = "non_graph";
        if (hasGraphEvidence(result)) {
            Object kind = result.metadata().getOrDefault("graphEvidenceKind", "unknown");
            Object authority = result.metadata().getOrDefault("codeIntelligenceAuthority", "unknown");
            graphCategory = "graph_" + normalizeCategoryComponent(String.valueOf(kind))
                    + "_" + normalizeCategoryComponent(String.valueOf(authority));
        }
        return String.join("|", sourceRole, chunkType, graphCategory);
    }

    private String normalizeCategoryComponent(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private boolean isStructured(CodeSearchResult result) {
        if (result == null) {
            return false;
        }
        String chunkType = safe(result.chunkType(), "");
        return "class".equals(chunkType)
                || "method".equals(chunkType)
                || "function".equals(chunkType)
                || "constructor".equals(chunkType)
                || "record".equals(chunkType)
                || "enum".equals(chunkType)
                || "component".equals(chunkType)
                || hasObservedIdentity(result)
                || isProjectContext(chunkType);
    }

    private boolean hasObservedIdentity(CodeSearchResult result) {
        return result != null && (notBlank(result.symbolName())
                || notBlank(result.className())
                || notBlank(result.methodName())
                || notBlank(result.controlName())
                || notBlank(result.eventName()));
    }

    private double authorityScore(CodeSearchResult result) {
        if (result == null || result.metadata() == null || !result.metadata().containsKey("codeIntelligenceAuthority")) {
            return 0;
        }
        CodeIntelligenceAuthority authority = CodeIntelligenceAuthority.from(
                String.valueOf(result.metadata().get("codeIntelligenceAuthority")));
        return switch (authority) {
            case COMPILER_SEMANTIC -> 0.08;
            case SCIP_SEMANTIC, LSP_SEMANTIC -> 0.07;
            case SYNTAX -> 0.05;
            case LEXICAL -> 0.02;
            case LLM_INFERRED, UNKNOWN -> 0;
        };
    }

    private boolean isProjectContext(String chunkType) {
        return "project_structure".equals(chunkType)
                || "repository_summary".equals(chunkType)
                || "directory_summary".equals(chunkType)
                || "file_summary".equals(chunkType);
    }

    private double numberMetadata(CodeSearchResult result, String key, double fallback) {
        if (result == null || result.metadata() == null) {
            return fallback;
        }
        Object value = result.metadata().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer typedNonNegativeIntegerMetadata(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null) {
            return null;
        }
        Object value = result.metadata().get(key);
        if (!(value instanceof Number number)) {
            return null;
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric)
                || numeric < 0
                || numeric > Integer.MAX_VALUE
                || numeric != Math.rint(numeric)) {
            return null;
        }
        return (int) numeric;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return value instanceof Map<?, ?> map
                ? map.entrySet().stream()
                .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new))
                : Map.of();
    }

    private boolean enabled() {
        return properties.getCode().getGraph().isEvidenceRankingEnabled();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final List<String> STOP_WORDS = List.of(
            "the", "and", "for", "with", "this", "that", "from", "into", "onto", "about",
            "what", "where", "when", "how", "why", "which", "code", "file", "method", "class"
    );

    public record GraphReliabilitySummary(
            int expanded,
            int strong,
            int medium,
            int partial,
            Map<String, Integer> edgeCounts
    ) {
        static GraphReliabilitySummary empty() {
            return new GraphReliabilitySummary(0, 0, 0, 0, Map.of());
        }

        public boolean hasGraphEvidence() {
            return expanded > 0;
        }

        public String edgeSummary() {
            if (edgeCounts == null || edgeCounts.isEmpty()) {
                return "";
            }
            return edgeCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                    .limit(5)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
        }
    }
}

