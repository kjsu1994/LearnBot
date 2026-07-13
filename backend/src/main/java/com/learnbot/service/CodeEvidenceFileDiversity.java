package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Keeps a bounded answer slate from collapsing onto many chunks from one file.
 * The second pass fills unused capacity, so focused single-file questions do not
 * lose relevant evidence merely because no meaningful cross-file evidence exists.
 */
final class CodeEvidenceFileDiversity {
    private CodeEvidenceFileDiversity() {
    }

    static List<CodeSearchResult> select(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit,
            Predicate<CodeSearchResult> required
    ) {
        int safeLimit = Math.max(1, limit);
        List<CodeSearchResult> pool = uniquePool(selected, ranked);
        if (pool.isEmpty()) return List.of();

        List<CodeSearchResult> output = new ArrayList<>();
        Map<String, Integer> perFile = new LinkedHashMap<>();
        Predicate<CodeSearchResult> safeRequired = required == null ? ignored -> false : required;

        for (CodeSearchResult result : pool) {
            if (safeRequired.test(result)) add(output, perFile, result, safeLimit);
        }

        int dominantFileLimit = Math.max(3, (int) Math.ceil(safeLimit * 2.0 / 3.0));
        for (CodeSearchResult result : pool) {
            if (output.size() >= safeLimit) break;
            String file = fileKey(result);
            if (perFile.getOrDefault(file, 0) >= dominantFileLimit) continue;
            add(output, perFile, result, safeLimit);
        }

        for (CodeSearchResult result : pool) {
            if (output.size() >= safeLimit) break;
            add(output, perFile, result, safeLimit);
        }
        return List.copyOf(output);
    }

    private static List<CodeSearchResult> uniquePool(List<CodeSearchResult> selected, List<CodeSearchResult> ranked) {
        Map<UUID, CodeSearchResult> unique = new LinkedHashMap<>();
        for (CodeSearchResult result : selected == null ? List.<CodeSearchResult>of() : selected) {
            if (result != null) unique.putIfAbsent(result.chunkId(), result);
        }
        for (CodeSearchResult result : ranked == null ? List.<CodeSearchResult>of() : ranked) {
            if (result != null) unique.putIfAbsent(result.chunkId(), result);
        }
        return List.copyOf(unique.values());
    }

    private static void add(
            List<CodeSearchResult> output,
            Map<String, Integer> perFile,
            CodeSearchResult result,
            int limit
    ) {
        if (output.size() >= limit || output.stream().anyMatch(current -> current.chunkId().equals(result.chunkId()))) return;
        output.add(result);
        String file = fileKey(result);
        perFile.put(file, perFile.getOrDefault(file, 0) + 1);
    }

    private static String fileKey(CodeSearchResult result) {
        String path = result == null || result.filePath() == null ? "" : result.filePath().trim().toLowerCase(Locale.ROOT);
        return path.isBlank() ? "chunk:" + (result == null ? "unknown" : result.chunkId()) : path;
    }
}
