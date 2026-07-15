package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds a last-resort answer from literal selected evidence instead of a repository overview. */
public final class CodeEvidenceFidelityFallback {
    private static final int MAX_ITEMS = 10;
    private static final int MAX_EXCERPT_CHARS = 560;

    private CodeEvidenceFidelityFallback() {
    }

    public static String answer(List<CodeSearchResult> evidence, String failureReason) {
        if (evidence == null || evidence.isEmpty()) {
            return "The generated answer did not pass evidence-fidelity checks, and no code evidence was available.";
        }
        List<IndexedEvidence> ranked = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) ranked.add(new IndexedEvidence(index, evidence.get(index)));
        ranked.sort(Comparator
                .comparingInt((IndexedEvidence item) -> requiredRank(item.result()))
                .thenComparingInt(item -> implementationRank(item.result()))
                .thenComparing((IndexedEvidence item) -> -item.result().score())
                .thenComparingInt(IndexedEvidence::index));

        StringBuilder answer = new StringBuilder();
        answer.append("The generated answer did not pass evidence-fidelity checks");
        if (failureReason != null && !failureReason.isBlank()) answer.append(" (").append(failureReason).append(")");
        answer.append(". The directly observed code facts are:\n\n");
        for (IndexedEvidence item : ranked.stream().limit(MAX_ITEMS).toList()) {
            CodeSearchResult result = item.result();
            answer.append("- `").append(safe(result.filePath())).append("`")
                    .append(" lines ").append(result.lineStart()).append("-").append(result.lineEnd());
            String symbol = firstNonBlank(result.methodName(), result.symbolName(), result.className());
            if (!symbol.isBlank()) answer.append(" symbol `").append(symbol).append("`");
            String route = metadata(result, "endpointRoute");
            String method = metadata(result, "httpMethod");
            if (!route.isBlank()) answer.append(" endpoint `").append(method.isBlank() ? "" : method + " ").append(route).append("`");
            String excerpt = excerpt(result.content());
            if (!excerpt.isBlank()) answer.append(": `").append(excerpt).append("`");
            answer.append(" [").append(item.index() + 1).append("]\n");
        }
        return answer.toString().trim();
    }

    private static int requiredRank(CodeSearchResult result) {
        return flag(result, "llmChecklistGroupRequired")
                || flag(result, "llmValidatedEvidence")
                || flag(result, "llmDirectRead")
                || flag(result, "deterministicEndpointBestMatch") ? 0 : 1;
    }

    private static int implementationRank(CodeSearchResult result) {
        String symbol = firstNonBlank(result == null ? null : result.methodName(), result == null ? null : result.symbolName());
        int span = result == null ? 0 : Math.max(0, result.lineEnd() - result.lineStart() + 1);
        return !symbol.isBlank() && span >= 3 ? 0 : 1;
    }

    private static String excerpt(String content) {
        String compact = safe(content).replaceAll("(?m)^\\s*\\d+:\\s*", "")
                .replace('`', '\'')
                .replaceAll("\\s+", " ")
                .trim();
        if (compact.length() <= MAX_EXCERPT_CHARS) return compact;
        return compact.substring(0, MAX_EXCERPT_CHARS - 3).trim() + "...";
    }

    private static boolean flag(CodeSearchResult result, String key) {
        String value = metadata(result, key);
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static String metadata(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null) return "";
        Object value = result.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record IndexedEvidence(int index, CodeSearchResult result) {
    }
}
