package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Preserves a bounded amount of typed structural expansion when an initial search result is cut.
 * Search rank still fills most slots; source-bundle members cannot disappear solely because the
 * executor appends them after the semantic search window.
 */
public final class CodeInitialEvidenceSelector {
    private static final int MAX_STRUCTURAL_RESERVATION = 2;

    public List<CodeSearchResult> select(
            RagPipelineService.CodeSearchOperation operation,
            List<CodeSearchResult> results,
            int limit
    ) {
        if (results == null || results.isEmpty() || limit <= 0) return List.of();
        int safeLimit = Math.min(limit, results.size());
        if (operation == null || !operation.isSearch()) {
            return results.stream().limit(safeLimit).toList();
        }

        int reservation = Math.min(MAX_STRUCTURAL_RESERVATION, Math.max(1, safeLimit / 2));
        LinkedHashMap<UUID, CodeSearchResult> selected = new LinkedHashMap<>();
        results.stream()
                .filter(result -> isSourceBundleEvidence(operation, result))
                .limit(reservation)
                .forEach(result -> selected.putIfAbsent(result.chunkId(), result));
        for (CodeSearchResult result : results) {
            if (selected.size() >= safeLimit) break;
            if (result != null && result.chunkId() != null) selected.putIfAbsent(result.chunkId(), result);
        }
        return List.copyOf(selected.values());
    }

    private boolean isSourceBundleEvidence(
            RagPipelineService.CodeSearchOperation operation,
            CodeSearchResult result
    ) {
        if (result == null || result.chunkId() == null) return false;
        return CodeEvidenceOperationProvenance.from(result).stream()
                .filter(provenance -> Objects.equals(operation.operationId(), provenance.operationId()))
                .map(CodeEvidenceOperationProvenance::operationType)
                .anyMatch(type -> "read_source_member".equals(type)
                        || "read_source_boundary".equals(type));
    }
}
