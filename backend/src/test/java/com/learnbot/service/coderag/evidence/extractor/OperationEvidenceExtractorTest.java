package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationEvidenceExtractorTest {

    @Test
    void directOperationProducesOneSyntaxBackedItemAndDirectObservationSignal() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance direct = new CodeEvidenceOperationProvenance(
                "list_file_symbols", "op-direct", List.of("claim-1"), "worker_flow",
                List.of("index:origin:1-20"), "unrelated privileged settings mutation",
                "src/app/Worker.java", "", "", null, null, null,
                List.of(), "BOTH", null);
        CodeSearchResult source = result(
                UUID.randomUUID(), "DIRECT_SOURCE", direct,
                Map.of(
                        "llmDirectRead", true,
                        "llmFollowUpQuery", "unrelated privileged settings mutation",
                        "llmReadArea", "unrelated privileged settings mutation"));

        var postOperation = new CodeEvidenceExtractionContext(
                "How is work claimed?", EvidenceExtractionStage.POST_OPERATION,
                List.of(source, source));
        var ir = extractor.extract(postOperation);

        assertThat(extractor.stages()).containsExactlyInAnyOrder(
                EvidenceExtractionStage.POST_OPERATION, EvidenceExtractionStage.PRE_ANSWER);
        assertThat(extractor.supports(postOperation)).isTrue();
        assertThat(ir.evidenceItems()).singleElement().satisfies(item -> {
            assertThat(item.kinds()).containsExactly(CodeEvidenceItem.Kind.DIRECT_SOURCE);
            assertThat(item.authority()).isEqualTo(CodeIntelligenceAuthority.SYNTAX);
            assertThat(item.source()).isSameAs(source);
        });
        assertThat(ir.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(CodeEvidenceSignal.Type.DIRECT_OBSERVATION);
            assertThat(signal.strength()).isEqualTo(1.0);
            assertThat(signal.sourceEvidenceId()).isEqualTo(ir.evidenceItems().get(0).evidenceId());
            assertThat(signal.reason()).doesNotContain("settings", "mutation", "worker_flow");
        });

        var preAnswer = extractor.extract(new CodeEvidenceExtractionContext(
                "How is work claimed?", EvidenceExtractionStage.PRE_ANSWER, List.of(source)));
        assertThat(preAnswer.signals()).singleElement();
        var postSeed = new CodeEvidenceExtractionContext(
                "How is work claimed?", EvidenceExtractionStage.POST_SEED, List.of(source));
        assertThat(extractor.supports(postSeed)).isFalse();
        assertThat(extractor.extract(postSeed).isEmpty()).isTrue();
    }

    @Test
    void searchQueryAndFreeFormMetadataCannotSpoofADirectObservationSignal() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance search = new CodeEvidenceOperationProvenance(
                "hybrid_search", "op-search", List.of("claim-1"), "privileged_settings",
                List.of(), "privileged settings mutation", "", "", "",
                null, null, null, List.of(), "BOTH", null);
        CodeEvidenceOperationProvenance unanchoredDirect = new CodeEvidenceOperationProvenance(
                "read_symbol", "op-unanchored", List.of("claim-2"), "privileged_settings",
                List.of(), "privileged settings mutation", "", "", "",
                null, null, null, List.of(), "BOTH", null);
        CodeEvidenceOperationProvenance noOriginDirect = new CodeEvidenceOperationProvenance(
                "read_symbol", "op-no-origin", List.of("claim-3"), "privileged_settings",
                List.of(), "privileged settings mutation", "", "Worker.claimNext", "",
                null, null, null, List.of(), "BOTH", null);
        CodeEvidenceOperationProvenance unknownOperation = new CodeEvidenceOperationProvenance(
                "project_specific_read", "op-unknown", List.of("claim-4"), "privileged_settings",
                List.of("origin-evidence"), "privileged settings mutation", "src/app/Worker.java", "", "",
                null, null, null, List.of(), "BOTH", null);
        CodeSearchResult searchSource = result(
                UUID.randomUUID(), "SEARCH_SOURCE", search,
                Map.of("llmDirectRead", true, "llmRequestedSymbol", "privilegedSettingsMutation"));
        CodeSearchResult unanchoredSource = result(
                UUID.randomUUID(), "UNANCHORED_SOURCE", unanchoredDirect,
                Map.of("llmDirectRead", true, "llmReadArea", "privileged settings mutation"));
        CodeSearchResult noOriginSource = result(
                UUID.randomUUID(), "NO_ORIGIN_SOURCE", noOriginDirect,
                Map.of("llmDirectRead", true));
        CodeSearchResult unknownOperationSource = result(
                UUID.randomUUID(), "UNKNOWN_OPERATION_SOURCE", unknownOperation,
                Map.of("llmDirectRead", true));
        CodeSearchResult metadataOnlySpoof = result(
                UUID.randomUUID(), "SPOOF_SOURCE", null,
                Map.of(
                        "llmDirectRead", true,
                        "llmFollowUpQuery", "privileged settings mutation",
                        "llmRequestedSymbol", "privilegedSettingsMutation"));

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "privileged settings mutation", EvidenceExtractionStage.POST_OPERATION,
                List.of(searchSource, unanchoredSource, noOriginSource,
                        unknownOperationSource, metadataOnlySpoof)));

        assertThat(ir.evidenceItems()).extracting(item -> item.source().content())
                .containsExactly("SEARCH_SOURCE", "UNANCHORED_SOURCE",
                        "NO_ORIGIN_SOURCE", "UNKNOWN_OPERATION_SOURCE");
        assertThat(ir.signals()).isEmpty();
    }

    @Test
    void dedupePrefersTheCandidateWithValidatedDirectProvenance() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        UUID chunkId = UUID.randomUUID();
        CodeEvidenceOperationProvenance search = new CodeEvidenceOperationProvenance(
                "keyword_search", "op-search", List.of(), "flow", List.of(),
                "worker flow", "", "", "", null, null, null,
                List.of(), "BOTH", null);
        CodeEvidenceOperationProvenance direct = new CodeEvidenceOperationProvenance(
                "traverse_graph", "op-direct", List.of(), "flow", List.of("origin-evidence"),
                "ignored free-form query", "", "", chunkId.toString(), null, null, null,
                List.of("CALLS"), "FORWARD", 1);
        CodeSearchResult searchCandidate = result(
                chunkId, "SEARCH_CANDIDATE", search,
                Map.of("codeIntelligenceAuthority", "COMPILER_SEMANTIC"));
        CodeSearchResult directCandidate = result(
                chunkId, "DIRECT_CANDIDATE", direct,
                Map.of("codeIntelligenceAuthority", "UNKNOWN", "llmDirectRead", true));

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "follow the worker flow", EvidenceExtractionStage.POST_OPERATION,
                List.of(searchCandidate, directCandidate)));

        assertThat(ir.evidenceItems()).singleElement().satisfies(item -> {
            assertThat(item.source()).isSameAs(directCandidate);
            assertThat(item.source().content()).isEqualTo("DIRECT_CANDIDATE");
            assertThat(item.authority()).isEqualTo(CodeIntelligenceAuthority.SYNTAX);
        });
        assertThat(ir.signals()).singleElement().satisfies(signal ->
                assertThat(signal.type()).isEqualTo(CodeEvidenceSignal.Type.DIRECT_OBSERVATION));
    }

    private CodeSearchResult result(
            UUID chunkId,
            String content,
            CodeEvidenceOperationProvenance provenance,
            Map<String, Object> extraMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("indexVersion", "index-v1");
        if (extraMetadata != null) metadata.putAll(extraMetadata);
        if (provenance != null) {
            metadata.put(CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance));
        }
        return new CodeSearchResult(
                chunkId, UUID.randomUUID(), UUID.randomUUID(), "repo", "src/app/Worker.java",
                "method", "claimNext", "Worker", "claimNext", "app", null, null, 1,
                10, 30, content, 0.8, Map.copyOf(metadata));
    }
}
