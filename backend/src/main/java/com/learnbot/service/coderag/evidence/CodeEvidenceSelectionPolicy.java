package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps provenance-backed evidence from being discarded by a purely score-based top-N cut. */
public final class CodeEvidenceSelectionPolicy {
    private static final List<String> REQUIRED_FLAGS = List.of(
            "deterministicEndpointEvidence",
            "llmValidatedEvidence",
            "llmEvidenceSlateMustUse",
            "llmChecklistGroupRequired"
    );

    private CodeEvidenceSelectionPolicy() {
    }

    public static List<CodeSearchResult> select(List<CodeSearchResult> ranked, int requestedLimit) {
        List<CodeSearchResult> safeRanked = ranked == null ? List.of() : ranked;
        if (safeRanked.isEmpty()) return List.of();

        int limit = Math.max(1, requestedLimit);
        List<CodeSearchResult> selected = new ArrayList<>(safeRanked.stream().limit(limit).toList());
        for (CodeSearchResult required : safeRanked) {
            if (!isRequired(required) || containsChunk(selected, required)) continue;

            int replacement = weakestNonRequiredIndex(selected);
            if (selected.size() < limit) {
                selected.add(required);
            } else if (replacement >= 0) {
                selected.set(replacement, required);
            } else {
                // All retained candidates carry stronger provenance than a score-only size cap.
                selected.add(required);
            }
        }
        return List.copyOf(selected);
    }

    private static int weakestNonRequiredIndex(List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (!isRequired(selected.get(index))) return index;
        }
        return -1;
    }

    private static boolean containsChunk(List<CodeSearchResult> selected, CodeSearchResult candidate) {
        if (candidate == null || candidate.chunkId() == null) return false;
        return selected.stream().anyMatch(result -> result != null && candidate.chunkId().equals(result.chunkId()));
    }

    private static boolean isRequired(CodeSearchResult result) {
        Map<String, Object> metadata = result == null || result.metadata() == null ? Map.of() : result.metadata();
        return REQUIRED_FLAGS.stream().anyMatch(flag -> Boolean.TRUE.equals(metadata.get(flag)));
    }
}
