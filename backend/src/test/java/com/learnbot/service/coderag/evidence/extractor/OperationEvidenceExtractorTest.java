package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.CodeEvidenceRetentionPlan;
import com.learnbot.service.coderag.evidence.CodeEvidenceSelectionPolicy;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
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
    void sourceBoundaryBecomesBoundedPreferredIrWithoutBecomingExactProof() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance boundary = new CodeEvidenceOperationProvenance(
                "read_source_boundary", "op-flow-source-boundary-1",
                List.of("claim-flow"), "execution_flow", List.of("index:origin:1-20"),
                "", "src/app/Worker.java", "initialize", UUID.randomUUID().toString(),
                10, 30, null, List.of(), "BOTH", null);
        CodeSearchResult source = result(
                UUID.randomUUID(), "void initialize() {}", boundary, Map.of());

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "How does initialization flow?", EvidenceExtractionStage.POST_OPERATION,
                List.of(source)));
        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(ir);

        assertThat(ir.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(CodeEvidenceSignal.Type.SOURCE_BUNDLE_BOUNDARY);
            assertThat(signal.strength()).isEqualTo(0.9);
        });
        assertThat(ir.constraints()).isEmpty();
        assertThat(plan.lookup(ir.evidenceItems().get(0).evidenceId())).get().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED);
            assertThat(entry.basis()).isEqualTo(CodeEvidenceRetentionPlan.Basis.SOURCE_BUNDLE);
        });
    }

    @Test
    void sourceMemberRemainsExplorationIrWithoutProtectedRetention() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance member = new CodeEvidenceOperationProvenance(
                "read_source_member", "op-flow-source-member-1",
                List.of("claim-flow"), "execution_flow", List.of("index:origin:1-20"),
                "", "src/app/Worker.java", "claimNext", UUID.randomUUID().toString(),
                10, 30, null, List.of(), "BOTH", null);
        CodeSearchResult source = result(
                UUID.randomUUID(), "void claimNext() {}", member, Map.of());

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "How is work claimed?", EvidenceExtractionStage.POST_OPERATION,
                List.of(source)));
        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(ir);

        assertThat(ir.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(CodeEvidenceSignal.Type.SOURCE_BUNDLE_MEMBER);
            assertThat(signal.strength()).isEqualTo(0.6);
        });
        assertThat(ir.constraints()).isEmpty();
        assertThat(plan.lookup(ir.evidenceItems().get(0).evidenceId())).isEmpty();
    }

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
        assertThat(ir.constraints()).isEmpty();

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
        assertThat(ir.constraints()).isEmpty();
    }

    @Test
    void onlyBoundedClaimLinkedSearchHeadsBecomePreferredExplorationEvidence() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        List<CodeSearchResult> searchResults = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(rank -> result(
                        UUID.randomUUID(), "SEARCH_HEAD_" + rank,
                        new CodeEvidenceOperationProvenance(
                                "hybrid_search", "op-search", List.of("claim-1"), "execution_flow",
                                List.of(), "free form query", "", "", "",
                                null, null, null, List.of(), "BOTH", null, rank),
                        Map.of()))
                .toList();

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "How does the flow execute?", EvidenceExtractionStage.POST_OPERATION,
                searchResults));

        assertThat(ir.signals()).hasSize(3).allSatisfy(signal -> {
            assertThat(signal.type()).isEqualTo(CodeEvidenceSignal.Type.CLAIM_LINKED_SEARCH_HEAD);
            assertThat(signal.strength()).isEqualTo(0.8);
            assertThat(signal.reason()).doesNotContain("query", "execution_flow", "claim-1");
        });
        assertThat(ir.constraints()).isEmpty();

        CodeEvidenceRetentionPlan retentionPlan = CodeEvidenceRetentionPlan.from(ir);
        assertThat(searchResults.subList(0, 3)).allSatisfy(result ->
                assertThat(retentionPlan.lookup(CodeEvidenceItem.evidenceId(result))).get()
                        .extracting(CodeEvidenceRetentionPlan.Entry::level)
                        .isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED));
        assertThat(retentionPlan.lookup(CodeEvidenceItem.evidenceId(searchResults.get(3)))).isEmpty();

        CodeSearchResult semantic = result(UUID.randomUUID(), "SEMANTIC", null, Map.of());
        List<CodeSearchResult> ranked = new java.util.ArrayList<>();
        ranked.add(semantic);
        ranked.addAll(searchResults);
        assertThat(CodeEvidenceSelectionPolicy.selectFinalEvidenceWithRetention(
                ranked, List.of(semantic), 4, retentionPlan))
                .containsExactly(semantic, searchResults.get(0), searchResults.get(1), searchResults.get(2));
    }

    @Test
    void uniqueExactSymbolReadProducesBoundedPreferredProofAndSurvivesTightSelection() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance exactRead = new CodeEvidenceOperationProvenance(
                "read_symbol", "op-read-symbol", List.of("claim-1"), "execution_flow",
                List.of("index:origin:1-20"), "",
                "src/app/Worker.java", "Worker.claimNext()", "",
                null, null, null, List.of(), "BOTH", null);
        CodeSearchResult exact = result(
                UUID.randomUUID(), "void claimNext() { execute(); }", exactRead,
                Map.of("symbolEvidenceKind", "DEFINITION", "callableBodyPresent", true));
        CodeSearchResult broad = result(UUID.randomUUID(), "BROAD_CONTAINER", null, Map.of());

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "How is work claimed?", EvidenceExtractionStage.POST_OPERATION, List.of(exact)));

        assertThat(ir.constraints()).singleElement().satisfies(constraint -> {
            assertThat(constraint.type()).isEqualTo(CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED);
            assertThat(constraint.targetId()).isEqualTo(ir.evidenceItems().get(0).evidenceId());
            assertThat(constraint.reason()).doesNotContain("Worker", "claimNext", "execution_flow");
        });
        CodeEvidenceRetentionPlan retentionPlan = CodeEvidenceRetentionPlan.from(ir);
        assertThat(retentionPlan.lookup(ir.evidenceItems().get(0).evidenceId())).get().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED);
            assertThat(entry.basis()).isEqualTo(CodeEvidenceRetentionPlan.Basis.DIRECT_PROOF);
        });
        assertThat(CodeEvidenceSelectionPolicy.selectFinalEvidenceWithRetention(
                List.of(broad, exact), List.of(broad), 1, retentionPlan))
                .containsExactly(exact);
    }

    @Test
    void ambiguousExactSymbolReadKeepsObservationsWithoutCreatingProofConstraints() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance ambiguousRead = new CodeEvidenceOperationProvenance(
                "read_symbol", "op-read-overload", List.of("claim-1"), "execution_flow",
                List.of("index:origin:1-20"), "",
                "src/app/Resolver.java", "Resolver.resolve", "",
                null, null, null, List.of(), "BOTH", null);
        List<CodeSearchResult> overloads = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> result(
                        UUID.randomUUID(), "void resolve(Type" + index + " value) {}", ambiguousRead,
                        Map.of("symbolEvidenceKind", "DEFINITION", "callableBodyPresent", true),
                        "src/app/Resolver.java", "resolve", "resolve"))
                .toList();

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "How is a value resolved?", EvidenceExtractionStage.POST_OPERATION, overloads));

        assertThat(ir.evidenceItems()).hasSize(3);
        assertThat(ir.signals()).hasSize(3)
                .allSatisfy(signal -> assertThat(signal.type())
                        .isEqualTo(CodeEvidenceSignal.Type.DIRECT_OBSERVATION));
        assertThat(ir.constraints()).isEmpty();
    }

    @Test
    void mismatchedOrBodylessSymbolAndNavigationReadsNeverBecomeRequiredProof() {
        OperationEvidenceExtractor extractor = new OperationEvidenceExtractor();
        CodeEvidenceOperationProvenance requestedSymbol = new CodeEvidenceOperationProvenance(
                "read_symbol", "op-read-symbol", List.of("claim-1"), "execution_flow",
                List.of("index:origin:1-20"), "",
                "src/app/Worker.java", "Worker.claimNext", "",
                null, null, null, List.of(), "BOTH", null);
        CodeEvidenceOperationProvenance navigation = new CodeEvidenceOperationProvenance(
                "list_file_symbols", "op-list-symbols", List.of("claim-1"), "execution_flow",
                List.of("index:origin:1-20"), "", "src/app/Worker.java", "", "",
                null, null, null, List.of(), "BOTH", null);
        CodeSearchResult mismatched = result(
                UUID.randomUUID(), "void other() {}", requestedSymbol,
                Map.of("symbolEvidenceKind", "DEFINITION"),
                "src/app/Worker.java", "other", "other");
        CodeSearchResult bodyless = result(
                UUID.randomUUID(), "void claimNext();", requestedSymbol,
                Map.of("symbolEvidenceKind", "DEFINITION", "callableBodyPresent", false));
        CodeSearchResult inventory = result(
                UUID.randomUUID(), "SYMBOL_INVENTORY", navigation, Map.of());

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "How is work claimed?", EvidenceExtractionStage.POST_OPERATION,
                List.of(mismatched, bodyless, inventory)));

        assertThat(ir.signals()).hasSize(3);
        assertThat(ir.constraints()).isEmpty();
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
        assertThat(ir.constraints()).isEmpty();
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

    private CodeSearchResult result(
            UUID chunkId,
            String content,
            CodeEvidenceOperationProvenance provenance,
            Map<String, Object> extraMetadata,
            String path,
            String symbolName,
            String methodName
    ) {
        CodeSearchResult base = result(chunkId, content, provenance, extraMetadata);
        return new CodeSearchResult(
                base.chunkId(), base.repositoryId(), base.fileId(), base.repositoryName(), path,
                base.chunkType(), symbolName, base.className(), methodName, base.namespaceName(),
                base.controlName(), base.eventName(), base.chunkIndex(), base.lineStart(), base.lineEnd(),
                base.content(), base.score(), base.metadata());
    }
}
