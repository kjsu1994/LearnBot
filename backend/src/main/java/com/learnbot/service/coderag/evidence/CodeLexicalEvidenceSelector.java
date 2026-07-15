package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CodeLexicalEvidenceSelector {
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "that", "this", "into", "which", "what", "where",
            "code", "file", "class", "method", "implementation", "service", "request", "response", "result"
    );

    private CodeLexicalEvidenceSelector() {
    }

    public static List<CodeSearchResult> rank(String intent, List<CodeSearchResult> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<String> intentTerms = terms(intent).stream().filter(term -> !STOP_WORDS.contains(term)).toList();
        if (intentTerms.isEmpty()) return candidates.stream().limit(Math.max(1, limit)).toList();
        String compactIntent = compact(intent);
        return candidates.stream()
                .filter(java.util.Objects::nonNull)
                .map(result -> new Scored(result, score(result, intentTerms, compactIntent)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(scored -> safe(scored.result().filePath()))
                        .thenComparingInt(scored -> scored.result().lineStart()))
                .limit(Math.max(1, limit))
                .map(CodeLexicalEvidenceSelector::mark)
                .toList();
    }

    private static double score(CodeSearchResult result, List<String> intentTerms, String compactIntent) {
        String symbol = String.join(" ", safe(result.methodName()), safe(result.symbolName()), safe(result.className()));
        String path = safe(result.filePath());
        String content = safe(result.content());
        Set<String> symbolTerms = new LinkedHashSet<>(terms(symbol));
        Set<String> pathTerms = new LinkedHashSet<>(terms(path));
        Set<String> contentTerms = new LinkedHashSet<>(terms(content));
        double score = result.score();
        String compactSymbol = compact(String.join(" ", safe(result.methodName()), safe(result.symbolName())));
        if (compactSymbol.length() >= 4 && compactIntent.contains(compactSymbol)) score += 8.0;
        for (String term : intentTerms) {
            if (symbolTerms.contains(term)) score += 4.0;
            if (pathTerms.contains(term)) score += 2.0;
            if (contentTerms.contains(term)) score += 0.45;
        }
        int span = Math.max(1, result.lineEnd() - result.lineStart() + 1);
        if ((!safe(result.methodName()).isBlank() || !safe(result.symbolName()).isBlank()) && span > 3) {
            score += Math.min(0.75, span / 80.0);
        }
        return score;
    }

    private static CodeSearchResult mark(Scored scored) {
        CodeSearchResult result = scored.result();
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("deterministicLexicalCandidate", true);
        metadata.put("retrievalIntentScore", scored.score());
        metadata.put("evidenceRankReason", String.valueOf(metadata.getOrDefault("evidenceRankReason", ""))
                + (metadata.containsKey("evidenceRankReason") ? "; " : "")
                + "Bounded symbol candidate ranked against the user question and planner claim");
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score() + Math.min(0.35, scored.score() / 60.0), Map.copyOf(metadata));
    }

    private static List<String> terms(String value) {
        String separated = safe(value).replaceAll("([\\p{Ll}\\p{Nd}])([\\p{Lu}])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        LinkedHashSet<String> output = new LinkedHashSet<>();
        for (String term : separated.split("[^\\p{L}\\p{N}]+")) {
            if (term.length() >= 2) output.add(term);
        }
        return List.copyOf(output);
    }

    private static String compact(String value) {
        return safe(value).replaceAll("([\\p{Ll}\\p{Nd}])([\\p{Lu}])", "$1 $2")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Scored(CodeSearchResult result, double score) {
    }
}
