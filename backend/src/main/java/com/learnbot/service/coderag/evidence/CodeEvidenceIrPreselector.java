package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded membership for evidence extraction before semantic answer selection. */
public final class CodeEvidenceIrPreselector {
    private static final int EXACT_READ_RESERVE_DIVISOR = 4;

    private CodeEvidenceIrPreselector() {
    }

    public static List<CodeSearchResult> select(List<CodeSearchResult> ranked, int requestedLimit) {
        if (ranked == null || ranked.isEmpty() || requestedLimit <= 0) return List.of();

        Map<String, CodeSearchResult> unique = new LinkedHashMap<>();
        ranked.stream().filter(Objects::nonNull).forEach(result -> {
            String evidenceId = CodeEvidenceId.from(result);
            if (!evidenceId.isBlank()) unique.putIfAbsent(evidenceId, result);
        });
        List<CodeSearchResult> candidates = List.copyOf(unique.values());
        int limit = Math.min(requestedLimit, candidates.size());
        if (candidates.size() <= limit) return candidates;

        int exactReadReserve = Math.max(1, limit / EXACT_READ_RESERVE_DIVISOR);
        Set<String> selectedIds = new LinkedHashSet<>();
        candidates.stream()
                .filter(CodeEvidenceIrPreselector::hasAnchoredExactRead)
                .limit(exactReadReserve)
                .map(CodeEvidenceId::from)
                .forEach(selectedIds::add);
        candidates.stream()
                .map(CodeEvidenceId::from)
                .filter(id -> selectedIds.size() < limit)
                .forEach(selectedIds::add);

        return candidates.stream()
                .filter(result -> selectedIds.contains(CodeEvidenceId.from(result)))
                .limit(limit)
                .toList();
    }

    private static boolean hasAnchoredExactRead(CodeSearchResult result) {
        return CodeEvidenceOperationProvenance.from(result).stream()
                .anyMatch(CodeEvidenceOperationProvenance::isAnchoredExactReadCandidate);
    }
}
