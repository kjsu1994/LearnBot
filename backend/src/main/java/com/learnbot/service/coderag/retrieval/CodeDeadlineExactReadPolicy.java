package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;

import java.util.List;

/** Selects a bounded local-source drain when planning consumes the retrieval deadline. */
public final class CodeDeadlineExactReadPolicy {
    static final int MAX_DRAIN_OPERATIONS = 2;

    private CodeDeadlineExactReadPolicy() {
    }

    /**
     * Accepts only plan-validator-approved operations. Search, graph traversal, adjacency,
     * inventory and any unanchored operation are excluded because they can widen retrieval.
     */
    public static List<RagPipelineService.CodeSearchOperation> select(
            List<RagPipelineService.CodeSearchOperation> validatedOperations
    ) {
        if (validatedOperations == null || validatedOperations.isEmpty()) return List.of();
        return validatedOperations.stream()
                .filter(CodeDeadlineExactReadPolicy::isEligible)
                .limit(MAX_DRAIN_OPERATIONS)
                .toList();
    }

    static boolean isEligible(RagPipelineService.CodeSearchOperation operation) {
        if (operation == null || operation.originEvidenceIds().isEmpty()) return false;
        return switch (operation.type()) {
            case "read_chunk" -> !operation.chunkId().isBlank();
            case "read_symbol" -> !operation.symbol().isBlank();
            case "read_file_range" -> !operation.path().isBlank()
                    && operation.lineStart() != null
                    && operation.lineEnd() != null
                    && operation.lineStart() > 0
                    && operation.lineEnd() >= operation.lineStart()
                    && (long) operation.lineEnd() - operation.lineStart() + 1
                    <= CodeEvidenceOperationExecutor.MAX_LINE_SPAN;
            default -> false;
        };
    }
}
