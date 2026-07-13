package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodeEndpointQueryVariants {
    private static final Pattern ROUTE = Pattern.compile("(?:/[A-Za-z0-9._{}:-]+){2,}");
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "api", "endpoint", "route", "controller", "handler", "method", "request", "service",
            "call", "calls", "handle", "handles", "which", "what", "where", "does", "the", "and", "for"
    );

    private CodeEndpointQueryVariants() {
    }

    static List<String> expand(String query) {
        String safe = query == null ? "" : query.trim();
        if (safe.isBlank()) return List.of();
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(safe);
        Matcher matcher = ROUTE.matcher(safe);
        while (matcher.find() && variants.size() < 5) {
            String route = matcher.group();
            List<String> segments = java.util.Arrays.stream(route.split("/"))
                    .filter(value -> !value.isBlank()).toList();
            if (segments.size() < 2) continue;
            variants.add(String.join(" ", segments));
            variants.add("/" + segments.get(segments.size() - 1));
            if (segments.size() > 2) {
                variants.add("/" + String.join("/", segments.subList(0, segments.size() - 1)));
            }
        }
        return new ArrayList<>(variants).stream().limit(5).toList();
    }

    static List<String> routes(String query) {
        String safe = query == null ? "" : query;
        LinkedHashSet<String> routes = new LinkedHashSet<>();
        Matcher matcher = ROUTE.matcher(safe);
        while (matcher.find() && routes.size() < 4) routes.add(matcher.group());
        return List.copyOf(routes);
    }

    static List<CodeSearchResult> rankCandidates(String query, List<CodeSearchResult> candidates, int limit) {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || candidates == null || candidates.isEmpty()) return List.of();
        List<String> distinctive = queryTerms.stream().filter(term -> !GENERIC_QUERY_TERMS.contains(term)).toList();
        if (distinctive.isEmpty()) return List.of();

        return candidates.stream()
                .filter(java.util.Objects::nonNull)
                .map(candidate -> scoredCandidate(candidate, queryTerms, distinctive))
                .filter(scored -> scored.distinctiveMatches() > 0)
                .sorted(Comparator.comparingDouble(ScoredEndpoint::score).reversed()
                        .thenComparing(scored -> scored.result().filePath())
                        .thenComparingInt(scored -> scored.result().lineStart()))
                .limit(Math.max(1, limit))
                .map(CodeEndpointQueryVariants::markCandidate)
                .toList();
    }

    private static ScoredEndpoint scoredCandidate(
            CodeSearchResult candidate,
            List<String> queryTerms,
            List<String> distinctiveTerms
    ) {
        String route = metadataText(candidate, "endpointRoute");
        String symbols = String.join(" ", safe(candidate.className()), safe(candidate.methodName()), safe(candidate.symbolName()));
        String path = safe(candidate.filePath());
        String content = safe(candidate.content());
        Set<String> routeTerms = Set.copyOf(terms(route));
        Set<String> symbolTerms = Set.copyOf(terms(symbols));
        Set<String> pathTerms = Set.copyOf(terms(path));
        Set<String> contentTerms = Set.copyOf(terms(content));
        double score = 0;
        int distinctiveMatches = 0;
        for (String term : queryTerms) {
            boolean matched = false;
            if (routeTerms.contains(term)) { score += 4.0; matched = true; }
            if (symbolTerms.contains(term)) { score += 3.0; matched = true; }
            if (pathTerms.contains(term)) { score += 2.0; matched = true; }
            if (contentTerms.contains(term)) { score += 1.0; matched = true; }
            if (matched && distinctiveTerms.contains(term)) distinctiveMatches++;
        }
        String compactCandidate = compact(route + " " + symbols + " " + path + " " + content);
        for (int index = 0; index + 1 < queryTerms.size(); index++) {
            String first = queryTerms.get(index);
            String second = queryTerms.get(index + 1);
            if (!GENERIC_QUERY_TERMS.contains(first) || !GENERIC_QUERY_TERMS.contains(second)) {
                if (compactCandidate.contains(first + second)) score += 2.0;
            }
        }
        return new ScoredEndpoint(candidate, score, distinctiveMatches);
    }

    private static CodeSearchResult markCandidate(ScoredEndpoint scored) {
        CodeSearchResult result = scored.result();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("deterministicEndpointCandidate", true);
        metadata.put("endpointCandidateScore", scored.score());
        metadata.put("evidenceRankReason", "Endpoint graph candidate ranked by route, symbol, path, and source lexical coverage");
        double boost = Math.min(0.45, scored.score() / 40.0);
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score() + boost, Map.copyOf(metadata));
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

    private static String metadataText(CodeSearchResult result, String key) {
        Object value = result.metadata() == null ? null : result.metadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredEndpoint(CodeSearchResult result, double score, int distinctiveMatches) {
    }
}
