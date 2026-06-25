package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodeEvidenceRanker {
    private final LearnBotProperties properties;

    public CodeEvidenceRanker(LearnBotProperties properties) {
        this.properties = properties;
    }

    public List<CodeSearchResult> rank(String question, CodeRagService.CodeQuestionMode mode, List<CodeSearchResult> results) {
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
                .sorted(Comparator.comparingDouble(this::score).reversed())
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
        if (!isGraphExpanded(result)) {
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
            if (!isGraphExpanded(result)) {
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

    private CodeSearchResult rankOne(String question, CodeRagService.CodeQuestionMode mode, CodeSearchResult result) {
        double base = clamp(result.score(), 0, 1);
        double text = textMatchScore(question, result);
        double graph = graphEvidenceScore(mode, result);
        double intent = intentEvidenceScore(mode, result);
        double structure = structureEvidenceScore(mode, result);
        double legacy = Math.max(0, legacyRelevance(question, mode, result) - result.score()) * 0.20;
        double flow = mode == CodeRagService.CodeQuestionMode.CALL_FLOW ? Math.max(0, 0.025 * (5 - flowRank(result))) : 0;
        double conversation = isConversationPinned(result) ? 0.18 : 0;
        double total = base + text + graph + intent + structure + legacy + flow + conversation;
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("baseSearch", round(base));
        parts.put("textMatch", round(text));
        parts.put("graph", round(graph));
        parts.put("intent", round(intent));
        parts.put("structure", round(structure));
        parts.put("legacyRerank", round(legacy));
        if (flow > 0) {
            parts.put("flowOrder", round(flow));
        }
        if (conversation > 0) {
            parts.put("conversationPinned", round(conversation));
        }
        return withMetadata(result, total, parts, reason(mode, result, graph, intent, structure, flow));
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

    private double graphEvidenceScore(CodeRagService.CodeQuestionMode mode, CodeSearchResult result) {
        if (!isGraphExpanded(result)) {
            return 0;
        }
        double pathScore = clamp(numberMetadata(result, "graphPathScore", 0), 0, 1);
        int depth = Math.max(1, (int) numberMetadata(result, "graphDepth", 1));
        double depthFactor = switch (depth) {
            case 1 -> 1.0;
            case 2 -> 0.72;
            case 3 -> 0.52;
            default -> 0.36;
        };
        double edgeFactor = edgeWeight(String.valueOf(result.metadata().getOrDefault("graphEdgeType", "")), mode);
        double sourceFactor = "llm_fallback".equals(String.valueOf(result.metadata().get("source"))) ? 0.70 : 1.0;
        double truncationFactor = Boolean.TRUE.equals(result.metadata().get("graphTraversalTruncated")) ? 0.82 : 1.0;
        return 0.45 * pathScore * depthFactor * edgeFactor * sourceFactor * truncationFactor;
    }

    private double edgeWeight(String edgeType, CodeRagService.CodeQuestionMode mode) {
        if (edgeType == null || edgeType.isBlank()) {
            return 0.65;
        }
        if ("REFERENCES".equals(edgeType)) return 0.45;
        if ("CALLS".equals(edgeType) || "HANDLES_EVENT".equals(edgeType) || "EXPOSES_ENDPOINT".equals(edgeType)) {
            return mode == CodeRagService.CodeQuestionMode.CALL_FLOW || mode == CodeRagService.CodeQuestionMode.UI_EVENT ? 1.15 : 1.0;
        }
        if ("IMPLEMENTS".equals(edgeType) || "OVERRIDES".equals(edgeType) || "EXTENDS".equals(edgeType)) {
            return mode == CodeRagService.CodeQuestionMode.IMPACT || mode == CodeRagService.CodeQuestionMode.OVERVIEW ? 1.05 : 0.85;
        }
        if ("READS_FIELD".equals(edgeType) || "WRITES_FIELD".equals(edgeType) || "USES_ENTITY".equals(edgeType)) {
            return mode == CodeRagService.CodeQuestionMode.IMPACT ? 1.10 : 0.82;
        }
        if ("CONTAINS".equals(edgeType) || "DEFINES".equals(edgeType)) {
            return mode == CodeRagService.CodeQuestionMode.OVERVIEW || mode == CodeRagService.CodeQuestionMode.LOCATE ? 0.95 : 0.70;
        }
        return 0.80;
    }

    private double intentEvidenceScore(CodeRagService.CodeQuestionMode mode, CodeSearchResult result) {
        String type = result.chunkType() == null ? "" : result.chunkType();
        String path = result.filePath() == null ? "" : result.filePath().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case CALL_FLOW -> (isGraphEdge(result, "CALLS", "EXPOSES_ENDPOINT", "HANDLES_EVENT") ? 0.18 : 0);
            case IMPACT -> isGraphEdge(result, "CALLS", "IMPLEMENTS", "OVERRIDES", "READS_FIELD", "WRITES_FIELD", "USES_ENTITY") ? 0.22 : 0.04;
            case REASONING -> isGraphEdge(result, "CALLS", "IMPLEMENTS", "OVERRIDES", "READS_FIELD", "WRITES_FIELD", "USES_ENTITY", "CONTAINS", "DEFINES") || isStructured(type) ? 0.20 : 0.04;
            case UI_EVENT -> ("event_handler".equals(type) || "xaml_event".equals(type) || "xaml_view".equals(type)
                    || isGraphEdge(result, "HANDLES_EVENT", "BINDS_TO")) ? 0.25 : 0.02;
            case OVERVIEW -> isProjectContext(type) || path.contains("/config/") || path.contains("/web/") || path.contains("/service/") ? 0.18 : 0.04;
            case EXPLAIN_METHOD -> "method".equals(type) || notBlank(result.methodName()) ? 0.22 : 0.02;
            case LOCATE -> notBlank(result.methodName()) || notBlank(result.className()) || notBlank(result.symbolName()) ? 0.18 : 0.03;
        };
    }

    private double structureEvidenceScore(CodeRagService.CodeQuestionMode mode, CodeSearchResult result) {
        double score = isStructured(result.chunkType()) ? 0.10 : 0;
        if (notBlank(result.methodName())) score += 0.05;
        if (notBlank(result.className())) score += 0.04;
        if ((mode == CodeRagService.CodeQuestionMode.OVERVIEW || mode == CodeRagService.CodeQuestionMode.IMPACT) && isProjectContext(result.chunkType())) {
            score += 0.10;
        }
        return Math.min(0.24, score);
    }

    private String reason(CodeRagService.CodeQuestionMode mode, CodeSearchResult result, double graph, double intent, double structure, double flow) {
        List<String> reasons = new ArrayList<>();
        if (graph > 0) reasons.add("graph " + String.valueOf(result.metadata().getOrDefault("graphEdgeType", "RELATED")));
        if (intent >= 0.18) reasons.add(mode.value() + " intent match");
        if (structure >= 0.10) reasons.add("structured code evidence");
        if (flow > 0) reasons.add("flow order hint");
        if (reasons.isEmpty()) reasons.add("hybrid search relevance");
        return String.join(", ", reasons);
    }

    private double legacyRelevance(String question, CodeRagService.CodeQuestionMode mode, CodeSearchResult result) {
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
        if (isStructured(result.chunkType())) score += 0.08;
        if ((mode == CodeRagService.CodeQuestionMode.OVERVIEW || mode == CodeRagService.CodeQuestionMode.IMPACT) && isProjectContext(result.chunkType())) {
            score += "project_structure".equals(result.chunkType()) || "repository_summary".equals(result.chunkType()) ? 0.65 : 0.30;
        }
        if (mode != CodeRagService.CodeQuestionMode.OVERVIEW && mode != CodeRagService.CodeQuestionMode.IMPACT && isProjectContext(result.chunkType())) {
            score -= "file_summary".equals(result.chunkType()) ? 0.04 : 0.16;
        }
        if (mode == CodeRagService.CodeQuestionMode.CALL_FLOW) {
            score += Math.max(0, 0.08 * (5 - flowRank(result)));
        }
        if (isGraphExpanded(result)) {
            score += switch (mode) {
                case CALL_FLOW, IMPACT -> 0.18;
                case OVERVIEW -> 0.10;
                default -> 0.05;
            };
        }
        if (isLoginQuestion(question) && path.contains("git") && !path.contains("auth") && !path.contains("login")) {
            score -= 0.6;
        }
        return score;
    }

    public int flowRank(CodeSearchResult result) {
        String path = result.filePath() == null ? "" : result.filePath().toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith("frontend/") || path.contains("/view/") || path.contains("/pages/")) return 0;
        if (path.contains("controller") || path.contains("/web/")) return 1;
        if (path.contains("/service/")) return 2;
        if (path.contains("/repository/")) return 3;
        if (path.contains("/config/") || path.contains("/security/")) return 4;
        return 5;
    }

    private boolean isGraphEdge(CodeSearchResult result, String... types) {
        if (!isGraphExpanded(result)) {
            return false;
        }
        String edgeType = String.valueOf(result.metadata().get("graphEdgeType"));
        return List.of(types).contains(edgeType);
    }

    private boolean isGraphExpanded(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("graphExpanded"));
    }

    private boolean isConversationPinned(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("conversationPinned"));
    }

    private List<String> primaryQuestionTerms(String question) {
        List<String> terms = new ArrayList<>();
        addTerms(terms, question);
        String normalized = normalize(question);
        if (isLoginQuestion(question)) {
            terms.addAll(List.of("login", "signin", "auth", "authentication"));
        }
        if (normalized.contains("index")) {
            terms.addAll(List.of("index", "indexing", "repository", "chunk", "embedding"));
        }
        if (normalized.contains("error")) {
            terms.addAll(List.of("error", "exception", "failed", "failure"));
        }
        if (normalized.contains("admin")) {
            terms.addAll(List.of("admin", "role", "authority"));
        }
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

    private boolean isLoginQuestion(String question) {
        String normalized = normalize(question);
        return normalized.contains("login")
                || normalized.contains("signin")
                || normalized.contains("auth");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHangul}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String category(CodeSearchResult result) {
        String path = result.filePath() == null ? "" : result.filePath().toLowerCase(java.util.Locale.ROOT);
        if (path.contains("/web/") || path.contains("controller")) return "controller";
        if (path.contains("/service/")) return "service";
        if (path.contains("/repository/")) return "repository";
        if (path.contains("/security/") || path.contains("/config/")) return "security";
        if (path.contains("/dto/")) return "dto";
        if (path.startsWith("frontend/")) return "frontend";
        return "other";
    }

    private boolean isStructured(String chunkType) {
        return "class".equals(chunkType)
                || "method".equals(chunkType)
                || "event_handler".equals(chunkType)
                || "xaml_event".equals(chunkType)
                || "xaml_view".equals(chunkType)
                || isProjectContext(chunkType);
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
