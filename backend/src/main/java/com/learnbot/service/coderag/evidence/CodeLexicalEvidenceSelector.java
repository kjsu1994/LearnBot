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
        return rank(intent, "", candidates, limit);
    }

    public static List<CodeSearchResult> rank(
            String trustedIntent,
            String auxiliaryIntent,
            List<CodeSearchResult> candidates,
            int limit
    ) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<String> trustedTerms = meaningfulTerms(trustedIntent);
        List<String> auxiliaryTerms = meaningfulTerms(auxiliaryIntent);
        if (trustedTerms.isEmpty() && auxiliaryTerms.isEmpty()) {
            return candidates.stream().limit(Math.max(1, limit)).toList();
        }
        String compactTrustedIntent = compact(trustedIntent);
        return candidates.stream()
                .filter(java.util.Objects::nonNull)
                .map(result -> new Scored(result, score(
                        result, trustedTerms, auxiliaryTerms, compactTrustedIntent)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(scored -> safe(scored.result().filePath()))
                        .thenComparingInt(scored -> scored.result().lineStart()))
                .limit(Math.max(1, limit))
                .map(CodeLexicalEvidenceSelector::mark)
                .toList();
    }

    /**
     * Returns whether either intent names behavior in a callable itself. File and containing-type
     * matches deliberately do not qualify because they would select every sibling in that type.
     */
    public static boolean hasCallableLexicalMatch(
            String trustedIntent,
            String auxiliaryIntent,
            CodeSearchResult result
    ) {
        if (result == null) return false;
        List<String> trustedTerms = meaningfulTerms(trustedIntent);
        List<String> auxiliaryTerms = meaningfulTerms(auxiliaryIntent);
        String symbol = String.join(" ", safe(result.methodName()), safe(result.symbolName()));
        Set<String> symbolTerms = new LinkedHashSet<>(terms(symbol));
        Set<String> contentTerms = new LinkedHashSet<>(terms(result.content()));
        String compactSymbol = compact(String.join(" ", safe(result.methodName()), safe(result.symbolName())));
        boolean exactTrustedSymbol = compactSymbol.length() >= 4
                && compact(trustedIntent).contains(compactSymbol);
        return exactTrustedSymbol
                || addTermScores(trustedTerms, symbolTerms, Set.of(), contentTerms,
                1.0, 0.0, 1.0) > 0
                || addTermScores(auxiliaryTerms, symbolTerms, Set.of(), contentTerms,
                1.0, 0.0, 1.0) > 0;
    }

    private static double score(
            CodeSearchResult result,
            List<String> trustedTerms,
            List<String> auxiliaryTerms,
            String compactTrustedIntent
    ) {
        String symbol = String.join(" ", safe(result.methodName()), safe(result.symbolName()), safe(result.className()));
        String path = safe(result.filePath());
        String content = safe(result.content());
        Set<String> symbolTerms = new LinkedHashSet<>(terms(symbol));
        Set<String> pathTerms = new LinkedHashSet<>(terms(path));
        Set<String> contentTerms = new LinkedHashSet<>(terms(content));
        double score = result.score();
        String compactSymbol = compact(String.join(" ", safe(result.methodName()), safe(result.symbolName())));
        if (compactSymbol.length() >= 4 && compactTrustedIntent.contains(compactSymbol)) score += 8.0;
        double trustedScore = addTermScores(
                trustedTerms, symbolTerms, pathTerms, contentTerms, 4.0, 2.0, 0.45);
        double auxiliaryScore = addTermScores(
                auxiliaryTerms, symbolTerms, pathTerms, contentTerms, 1.5, 0.75, 0.15);
        boolean lexicalMatch = trustedScore > 0 || auxiliaryScore > 0;
        score += trustedScore + auxiliaryScore;
        int span = Math.max(1, result.lineEnd() - result.lineStart() + 1);
        if (lexicalMatch
                && (!safe(result.methodName()).isBlank() || !safe(result.symbolName()).isBlank())
                && span > 3) {
            score += Math.min(0.75, span / 80.0);
        }
        return score;
    }

    private static double addTermScores(
            List<String> intentTerms,
            Set<String> symbolTerms,
            Set<String> pathTerms,
            Set<String> contentTerms,
            double symbolWeight,
            double pathWeight,
            double contentWeight
    ) {
        double score = 0.0;
        for (String term : intentTerms) {
            if (symbolTerms.contains(term)) score += symbolWeight;
            if (pathTerms.contains(term)) score += pathWeight;
            if (contentTerms.contains(term)) score += contentWeight;
        }
        return score;
    }

    private static List<String> meaningfulTerms(String value) {
        return terms(value).stream().filter(term -> !STOP_WORDS.contains(term)).toList();
    }

    private static CodeSearchResult mark(Scored scored) {
        CodeSearchResult result = scored.result();
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
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
