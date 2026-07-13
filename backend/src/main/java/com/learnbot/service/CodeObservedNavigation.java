package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts bounded navigation operands that are explicitly visible in retrieved code. */
final class CodeObservedNavigation {
    private static final Pattern QUALIFIED_CALL = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern TYPE_REFERENCE = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]{2,})(?=\\s*\\.|(?:\\s*<[^;\\r\\n]{0,80}>)?\\s+[a-z_][A-Za-z0-9_]*\\b)");
    private static final Set<String> COMMON = Set.of(
            "string", "object", "system", "list", "map", "set", "array", "optional", "stream",
            "collectors", "integer", "long", "boolean", "double", "runtimeexception", "exception");

    private CodeObservedNavigation() {
    }

    static List<String> identifiers(String intent, List<CodeSearchResult> evidence, int limit) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        Map<String, Observed> observed = new LinkedHashMap<>();
        int order = 0;
        for (CodeSearchResult result : evidence) {
            String content = result == null || result.content() == null ? "" : result.content();
            Matcher calls = QUALIFIED_CALL.matcher(content);
            while (calls.find()) {
                order = observe(observed, calls.group(2), order, true);
            }
            Matcher types = TYPE_REFERENCE.matcher(content);
            while (types.find()) order = observe(observed, types.group(1), order, false);
        }
        Set<String> intentTerms = new LinkedHashSet<>(terms(intent));
        return observed.values().stream()
                .sorted(Comparator.comparingDouble((Observed value) -> score(value, intentTerms)).reversed()
                        .thenComparingInt(Observed::order))
                .limit(Math.max(1, limit))
                .map(Observed::identifier)
                .toList();
    }

    private static int observe(Map<String, Observed> observed, String identifier, int order, boolean call) {
        String safe = identifier == null ? "" : identifier.trim();
        if (safe.length() < 3 || COMMON.contains(safe.toLowerCase(Locale.ROOT))) return order;
        String key = safe.toLowerCase(Locale.ROOT);
        Observed current = observed.get(key);
        if (current == null) observed.put(key, new Observed(safe, order++, call));
        else if (call && !current.call()) observed.put(key, new Observed(current.identifier(), current.order(), true));
        return order;
    }

    private static double score(Observed value, Set<String> intentTerms) {
        double score = value.call() ? 2.0 : 2.5;
        for (String term : terms(value.identifier())) if (intentTerms.contains(term)) score += 5.0;
        return score;
    }

    private static List<String> terms(String value) {
        String separated = value == null ? "" : value.replaceAll("([\\p{Ll}\\p{Nd}])([\\p{Lu}])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        LinkedHashSet<String> output = new LinkedHashSet<>();
        for (String term : separated.split("[^\\p{L}\\p{N}]+")) if (term.length() >= 2) output.add(term);
        return new ArrayList<>(output);
    }

    private record Observed(String identifier, int order, boolean call) {
    }
}
