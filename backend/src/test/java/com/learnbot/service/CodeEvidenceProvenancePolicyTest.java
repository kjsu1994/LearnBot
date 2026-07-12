package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceProvenancePolicyTest {
    @Test
    void distinguishesNavigationCandidatesFromFocusedProof() {
        var exactMethod = result("execute", "execute");
        var classMember = result("helper", "helper");
        assertThat(CodeRagService.operationProducesFocusedEvidence(operation("keyword_search"), exactMethod)).isFalse();
        assertThat(CodeRagService.operationProducesFocusedEvidence(operation("list_file_symbols"), exactMethod)).isFalse();
        assertThat(CodeRagService.operationProducesFocusedEvidence(operation("read_symbol"), exactMethod)).isTrue();
        assertThat(CodeRagService.operationProducesFocusedEvidence(operation("read_symbol"), classMember)).isFalse();
        assertThat(CodeRagService.operationProducesFocusedEvidence(operation("traverse_graph"), exactMethod)).isTrue();
        assertThat(CodeRagService.operationProducesFocusedEvidence(operation("find_endpoint"), exactMethod)).isTrue();
    }

    private RagPipelineService.CodeSearchOperation operation(String type) {
        return new RagPipelineService.CodeSearchOperation(
                type, "query", "area", "behavior", "src/Service.java", "execute", "chunk",
                1, 20, 1, List.of("CALLS"), "FORWARD", 1, "op", List.of("claim-1"), List.of());
    }

    private com.learnbot.dto.CodeSearchResult result(String symbol, String method) {
        return new com.learnbot.dto.CodeSearchResult(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "repo",
                "src/Service.java", "method", symbol, "Service", method, "app", null, null,
                1, 1, 20, "void " + method + "() {}", 0.8, java.util.Map.of());
    }
}
