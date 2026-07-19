package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceIrPreselectorTest {

    @Test
    void anchoredExactReadsSurviveBeyondTheSemanticHeadWithoutPromotingSearchResults() {
        List<CodeSearchResult> semantic = java.util.stream.IntStream.range(0, 70)
                .mapToObj(index -> result("SEMANTIC_" + index, null))
                .toList();
        List<CodeSearchResult> exactReads = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> result("EXACT_" + index,
                        new CodeEvidenceOperationProvenance(
                                "read_symbol", "read-" + index, List.of("claim-1"), "execution_flow",
                                List.of("index:origin:1-20"), "", "src/Worker.java", "step" + index,
                                "", null, null, null, List.of(), "BOTH", null)))
                .toList();
        CodeSearchResult searchOnly = result("SEARCH_ONLY",
                new CodeEvidenceOperationProvenance(
                        "hybrid_search", "search", List.of("claim-1"), "execution_flow",
                        List.of(), "worker flow", "", "", "", null, null, null,
                        List.of(), "BOTH", null, 1));
        List<CodeSearchResult> ranked = new ArrayList<>(semantic);
        ranked.add(searchOnly);
        ranked.addAll(exactReads);

        List<CodeSearchResult> selected = CodeEvidenceIrPreselector.select(ranked, 64);

        assertThat(selected).hasSize(64).containsAll(exactReads).doesNotContain(searchOnly);
        assertThat(selected.subList(selected.size() - exactReads.size(), selected.size()))
                .containsExactlyElementsOf(exactReads);
    }

    @Test
    void exactReadReservationRemainsBoundedToOneQuarterOfTheIrSlate() {
        List<CodeSearchResult> exactReads = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> result("EXACT_" + index,
                        new CodeEvidenceOperationProvenance(
                                "read_chunk", "read-" + index, List.of("claim-1"), "execution_flow",
                                List.of("origin"), "", "", "", UUID.randomUUID().toString(),
                                null, null, null, List.of(), "BOTH", null)))
                .toList();
        List<CodeSearchResult> ranked = new ArrayList<>();
        ranked.addAll(java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> result("SEMANTIC_" + index, null)).toList());
        ranked.addAll(exactReads);

        List<CodeSearchResult> selected = CodeEvidenceIrPreselector.select(ranked, 8);

        assertThat(selected).hasSize(8)
                .contains(exactReads.get(0), exactReads.get(1))
                .doesNotContain(exactReads.get(2), exactReads.get(3), exactReads.get(4), exactReads.get(5));
    }

    @Test
    void sourceBundleCandidatesDoNotDisplaceTheSemanticHeadBeforeIrExtraction() {
        List<CodeSearchResult> semantic = java.util.stream.IntStream.range(0, 70)
                .mapToObj(index -> result("SEMANTIC_" + index, null))
                .toList();
        List<CodeSearchResult> firstOperation = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> sourceCandidate("FIRST_" + index, "source-op-1", index))
                .toList();
        List<CodeSearchResult> secondOperation = java.util.stream.IntStream.range(0, 2)
                .mapToObj(index -> sourceCandidate("SECOND_" + index, "source-op-2", index))
                .toList();
        List<CodeSearchResult> ranked = new ArrayList<>(semantic);
        ranked.addAll(firstOperation);
        ranked.addAll(secondOperation);

        List<CodeSearchResult> selected = CodeEvidenceIrPreselector.select(ranked, 64);

        assertThat(selected).hasSize(64)
                .containsExactlyElementsOf(semantic.subList(0, 64))
                .doesNotContainAnyElementsOf(firstOperation)
                .doesNotContainAnyElementsOf(secondOperation);
    }

    private CodeSearchResult sourceCandidate(String content, String operationId, int rank) {
        return result(content, new CodeEvidenceOperationProvenance(
                rank % 2 == 0 ? "read_source_member" : "read_source_boundary",
                operationId, List.of("claim-flow"), "execution_flow",
                List.of("index:origin:1-20"), "", "src/Worker.java", "step" + rank,
                UUID.randomUUID().toString(), 10, 30, null, List.of(), "BOTH", null,
                rank + 1));
    }

    private CodeSearchResult result(
            String content,
            CodeEvidenceOperationProvenance provenance
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("indexVersion", "index-v1");
        if (provenance != null) {
            metadata.put(CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance));
        }
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "step", "Worker", "step", "app", null, null, 1,
                10, 30, content, 0.5, Map.copyOf(metadata));
    }
}
