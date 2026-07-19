package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeDeadlineExactReadPolicyTest {
    @Test
    void drainsOnlyAnchoredExactLocalReadsAndKeepsTheBound() {
        RagPipelineService.CodeSearchOperation search = operation(
                "hybrid_search", "find flow", "", "", "", null, null, List.of());
        RagPipelineService.CodeSearchOperation inventory = operation(
                "list_file_symbols", "", "src/Worker.java", "", "", null, null, List.of("origin"));
        RagPipelineService.CodeSearchOperation symbol = operation(
                "read_symbol", "", "src/Worker.java", "execute", "", null, null, List.of("origin"));
        RagPipelineService.CodeSearchOperation chunk = operation(
                "read_chunk", "", "", "", "chunk-1", null, null, List.of("origin"));
        RagPipelineService.CodeSearchOperation range = operation(
                "read_file_range", "", "src/Worker.java", "", "", 10, 20, List.of("origin"));

        assertThat(CodeDeadlineExactReadPolicy.select(
                List.of(search, inventory, symbol, chunk, range)))
                .containsExactly(symbol, chunk);
    }

    @Test
    void rejectsUnanchoredAndOversizedReads() {
        RagPipelineService.CodeSearchOperation unanchored = operation(
                "read_symbol", "", "src/Worker.java", "execute", "", null, null, List.of());
        RagPipelineService.CodeSearchOperation oversized = operation(
                "read_file_range", "", "src/Worker.java", "", "", 1,
                CodeEvidenceOperationExecutor.MAX_LINE_SPAN + 1, List.of("origin"));

        assertThat(CodeDeadlineExactReadPolicy.select(List.of(unanchored, oversized))).isEmpty();
    }

    private RagPipelineService.CodeSearchOperation operation(
            String type,
            String query,
            String path,
            String symbol,
            String chunkId,
            Integer lineStart,
            Integer lineEnd,
            List<String> origins
    ) {
        return new RagPipelineService.CodeSearchOperation(
                type, query, "behavior", "evidence", path, symbol, chunkId,
                lineStart, lineEnd, null, List.of(), "", null,
                "op-" + type + "-" + symbol + chunkId, List.of("claim-1"), origins);
    }
}
