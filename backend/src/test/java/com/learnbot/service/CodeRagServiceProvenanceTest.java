package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRagServiceProvenanceTest {

    @Test
    void mergeKeepsValidatedClaimProvenanceAsCollections() {
        CodeSearchResult validated = result(0.7, Map.of(
                "llmValidatedEvidence", true,
                "llmSupportedClaims", List.of("Run invokes the worker"),
                "llmNotSupportedClaims", List.of("Run persists the result")));
        CodeSearchResult preferred = result(0.9, Map.of("llmDirectRead", true));

        Map<String, Object> merged = CodeRagService.mergeEvidenceMetadata(preferred, preferred, validated);

        assertThat(merged).containsEntry("llmValidatedEvidence", true);
        assertThat(merged.get("llmSupportedClaims")).isEqualTo(List.of("Run invokes the worker"));
        assertThat(merged.get("llmNotSupportedClaims")).isEqualTo(List.of("Run persists the result"));
    }

    private CodeSearchResult result(double score, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.cs",
                "method", "Run", "Worker", "Run", "Sample", null, null, 1,
                1, 20, "void Run() {}", score, metadata);
    }
}
