package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRagEvidenceMetadataMergeTest {

    @Test
    void preservesPreferredSourceMetadataAndUnionsCoverageProvenance() {
        UUID chunkId = UUID.randomUUID();
        CodeSearchResult current = result(chunkId, 1.2, Map.of(
                "retrievalSource", "keyword",
                "sourceMarker", "preferred-content-metadata",
                "llmEvidenceCoverageGroup", "queue_claim",
                "llmFollowUpQuery", "claimNext",
                "llmFollowUpEvidence", true
        ));
        CodeSearchResult incoming = result(chunkId, 0.8, Map.of(
                "retrievalSource", "semantic",
                "sourceMarker", "non-preferred-content-metadata",
                "llmEvidenceCoverageGroup", "response_intake",
                "llmFollowUpQuery", "completeTool",
                "llmDirectRead", true
        ));

        Map<String, Object> merged = CodeRagService.mergeEvidenceMetadata(current, current, incoming);

        assertThat(merged)
                .containsEntry("retrievalSource", "keyword")
                .containsEntry("sourceMarker", "preferred-content-metadata")
                .containsEntry("llmFollowUpEvidence", true)
                .containsEntry("llmDirectRead", true);
        assertThat(((List<?>) merged.get("llmEvidenceCoverageGroup")).stream().map(String::valueOf).toList())
                .containsExactly("queue_claim", "response_intake");
        assertThat(((List<?>) merged.get("llmFollowUpQuery")).stream().map(String::valueOf).toList())
                .containsExactly("claimNext", "completeTool");
    }

    private CodeSearchResult result(UUID chunkId, double score, Map<String, Object> metadata) {
        return new CodeSearchResult(
                chunkId, UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "work", "Worker", "work", "app", null, null, 1,
                1, 20, "void work() {}", score, metadata);
    }
}
