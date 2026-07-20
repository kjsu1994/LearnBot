package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeInitialEvidenceSelectorTest {
    private final CodeInitialEvidenceSelector selector = new CodeInitialEvidenceSelector();

    @Test
    void searchCutReservesBoundedMembershipForAppendedSourceStructure() {
        var search = operation("hybrid_search", "search-flow");
        CodeSearchResult semanticFirst = result("src/Overview.code", "overview", Map.of());
        CodeSearchResult semanticSecond = result("src/Flow.code", "flow", Map.of());
        CodeSearchResult semanticThird = result("src/Details.code", "details", Map.of());
        CodeSearchResult semanticFourth = result("src/Notes.code", "notes", Map.of());
        CodeSearchResult member = result("src/FlowWorker.code", "StartPhase", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY,
                List.of(provenance("read_source_member", "search-flow"))));
        CodeSearchResult boundary = result("src/FlowWorker.code", "FinishPhase", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY,
                List.of(provenance("read_source_boundary", "search-flow"))));

        List<CodeSearchResult> selected = selector.select(search, List.of(
                semanticFirst, semanticSecond, semanticThird, semanticFourth, member, boundary), 4);

        assertThat(selected).containsExactly(member, boundary, semanticFirst, semanticSecond)
                .doesNotContain(semanticThird, semanticFourth);
    }

    @Test
    void searchCutDoesNotLetRankedMembersStarveAnAppendedCallableBoundary() {
        var search = operation("hybrid_search", "search-flow");
        CodeSearchResult semanticFirst = result("src/Overview.code", "overview", Map.of());
        CodeSearchResult semanticSecond = result("src/Flow.code", "flow", Map.of());
        CodeSearchResult semanticThird = result("src/Details.code", "details", Map.of());
        CodeSearchResult firstMember = result("src/FlowWorker.code", "RunPhase", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY,
                List.of(provenance("read_source_member", "search-flow"))));
        CodeSearchResult secondMember = result("src/FlowWorker.code", "SavePhase", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY,
                List.of(provenance("read_source_member", "search-flow"))));
        CodeSearchResult boundary = result("src/FlowWorker.code", "StartPhase", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY,
                List.of(provenance("read_source_boundary", "search-flow"))));

        List<CodeSearchResult> selected = selector.select(search, List.of(
                semanticFirst, semanticSecond, semanticThird,
                firstMember, secondMember, boundary), 4);

        assertThat(selected)
                .containsExactly(firstMember, boundary, semanticFirst, semanticSecond)
                .doesNotContain(secondMember, semanticThird);
    }

    @Test
    void directReadKeepsTheExecutorOrderAndHardLimit() {
        var read = operation("read_symbol", "read-flow");
        CodeSearchResult first = result("src/FlowWorker.code", "RunPhase", Map.of());
        CodeSearchResult second = result("src/FlowWorker.code", "RunPhaseAsync", Map.of());
        CodeSearchResult third = result("src/FlowWorker.code", "RunPhaseCore", Map.of());

        assertThat(selector.select(read, List.of(first, second, third), 2))
                .containsExactly(first, second);
    }

    private RagPipelineService.CodeSearchOperation operation(String type, String operationId) {
        return new RagPipelineService.CodeSearchOperation(
                type, "flow phase", "implementation", "flow", "", "", "",
                null, null, null, List.of(), "BOTH", null,
                operationId, List.of("claim-flow"), List.of());
    }

    private CodeEvidenceOperationProvenance provenance(String type, String operationId) {
        return new CodeEvidenceOperationProvenance(
                type, operationId, List.of("claim-flow"), "flow", List.of(),
                "flow phase", "src/FlowWorker.code", "StartPhase", "",
                null, null, null, List.of(), "BOTH", null);
    }

    private CodeSearchResult result(String path, String method, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "FlowWorker", method, "module", null, null, 1,
                1, 20, "void " + method + "() { execute(); }", 0.8, metadata);
    }
}
