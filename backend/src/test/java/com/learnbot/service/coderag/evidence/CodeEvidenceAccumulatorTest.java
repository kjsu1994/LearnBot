package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceAccumulatorTest {
    @Test
    void metadataMergePreservesTypedProvenanceFromEveryOperation() {
        CodeEvidenceOperationProvenance search = new CodeEvidenceOperationProvenance(
                "find_endpoint", "find-entry", List.of("claim-entry"), "request_entry");
        CodeEvidenceOperationProvenance read = new CodeEvidenceOperationProvenance(
                "read_symbol", "read-handler", List.of("claim-handler"), "handler_body");
        CodeSearchResult current = result(Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(search)));
        CodeSearchResult incoming = result(Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(read)));

        Map<String, Object> merged = CodeEvidenceAccumulator.mergeMetadata(current, current, incoming);

        assertThat(CodeEvidenceOperationProvenance.fromMetadata(
                merged.get(CodeEvidenceOperationProvenance.METADATA_KEY)))
                .containsExactly(search, read);
    }

    @Test
    void typedProvenanceReadsLegacyMetadataWithoutInventingOperands() {
        Map<String, Object> legacy = Map.of(
                "operationType", "read_symbol",
                "operationId", "read-handler",
                "claimIds", List.of("claim-handler"),
                "evidenceGroup", "handler_body",
                "query", "privileged settings mutation"
        );

        assertThat(CodeEvidenceOperationProvenance.fromMetadata(legacy))
                .containsExactly(new CodeEvidenceOperationProvenance(
                        "read_symbol", "read-handler", List.of("claim-handler"), "handler_body"));
    }

    @Test
    void metadataMergeDoesNotPromoteLegacyDeterministicMarkers() {
        CodeSearchResult current = result(Map.of());
        CodeSearchResult incoming = result(Map.ofEntries(
                Map.entry("deterministicEndpointEvidence", true),
                Map.entry("deterministicEndpointCandidate", true),
                Map.entry("deterministicEndpointBestMatch", true),
                Map.entry("deterministicLexicalCandidate", true),
                Map.entry("deterministicNavigationCandidate", true),
                Map.entry("deterministicNavigationBestMatch", true),
                Map.entry("observedNavigationIdentifier", "spoofedIdentifier"),
                Map.entry("llmDirectRead", true),
                Map.entry("graphRelation", "CALLS")
        ));

        Map<String, Object> merged = CodeEvidenceAccumulator.mergeMetadata(current, current, incoming);

        assertThat(merged)
                .containsEntry("llmDirectRead", true)
                .containsEntry("graphRelation", "CALLS")
                .doesNotContainKeys(
                        "deterministicEndpointEvidence",
                        "deterministicEndpointCandidate",
                        "deterministicEndpointBestMatch",
                        "deterministicLexicalCandidate",
                        "deterministicNavigationCandidate",
                        "deterministicNavigationBestMatch",
                        "observedNavigationIdentifier");
    }

    private CodeSearchResult result(Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Api.java",
                "method", "submit", "Api", "submit", "app", null, null, 1,
                10, 30, "void submit() {}", 0.9, metadata);
    }
}
