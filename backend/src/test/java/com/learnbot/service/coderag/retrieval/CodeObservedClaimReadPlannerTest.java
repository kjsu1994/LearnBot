package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeObservedClaimReadPlannerTest {
    private final CodeObservedClaimReadPlanner planner = new CodeObservedClaimReadPlanner();

    @Test
    void selectsOneObservedExactChunkPerUnresolvedClaimAndKeepsTheGlobalBound() {
        RagPipelineService.CodeEvidenceChecklistItem managed = claim(
                "claim-managed", "managed_path", "apply managed API value");
        RagPipelineService.CodeEvidenceChecklistItem nativeApi = claim(
                "claim-native", "native_path", "apply native API value");
        RagPipelineService.CodeEvidenceChecklistItem persistence = claim(
                "claim-store", "store_path", "persist workflow value");
        Fixture managedCandidate = candidate(
                "src/InputAdapter.code", "ApplyWithManagedApi", managed.claimId(), 1, true);
        Fixture nativeCandidate = candidate(
                "src/InputAdapter.code", "ApplyWithNativeApi", nativeApi.claimId(), 2, true);
        Fixture storeCandidate = candidate(
                "src/FlowStore.code", "PersistWorkflowValue", persistence.claimId(), 1, true);
        Fixture unrelated = candidate(
                "src/Clock.code", "RenderClock", nativeApi.claimId(), 1, true);
        var plan = plan(List.of(managed, nativeApi, persistence), List.of());
        var map = map(managedCandidate, nativeCandidate, storeCandidate, unrelated);

        List<RagPipelineService.CodeSearchOperation> selected = planner.select(plan, map, Set.of());

        assertThat(selected).hasSize(CodeObservedClaimReadPlanner.MAX_READS);
        assertThat(selected).extracting(RagPipelineService.CodeSearchOperation::type)
                .containsOnly("read_chunk");
        assertThat(selected).extracting(RagPipelineService.CodeSearchOperation::chunkId)
                .containsExactly(
                        managedCandidate.result().chunkId().toString(),
                        nativeCandidate.result().chunkId().toString())
                .doesNotContain(storeCandidate.result().chunkId().toString());
        assertThat(selected.get(0).claimIds()).containsExactly(managed.claimId());
        assertThat(selected.get(1).claimIds()).containsExactly(nativeApi.claimId());
        assertThat(selected.get(0).originEvidenceIds())
                .containsExactly(managedCandidate.evidenceId());

        var recoveryPlan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                plan.attempted(), plan.enough(), plan.reason(), plan.missingAreas(),
                plan.followUpQueries(), plan.queryAreas(), plan.requiredEvidenceGroups(),
                plan.checklist(), selected, plan.coverageSelections(), plan.hypothesis(),
                plan.hypothesisVersion(), plan.premiseDisposition(), plan.claimResults(),
                plan.terminationRequest());
        var validation = new CodeRetrievalPlanValidator().validate(
                recoveryPlan, map, Set.of());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.executableOperations()).containsExactlyElementsOf(selected);
        assertThat(CodeDeadlineExactReadPolicy.select(validation.executableOperations()))
                .containsExactlyElementsOf(selected);
    }

    @Test
    void rejectsResolvedBodylessUnlinkedAndAlreadyReadCandidates() {
        RagPipelineService.CodeEvidenceChecklistItem resolved = claim(
                "claim-resolved", "resolved_path", "resolve workflow value");
        RagPipelineService.CodeEvidenceChecklistItem pending = claim(
                "claim-pending", "pending_path", "process pending value");
        Fixture resolvedCandidate = candidate(
                "src/Resolver.code", "ResolveWorkflowValue", resolved.claimId(), 1, true);
        Fixture bodyless = candidate(
                "src/Pending.code", "ProcessPendingValue", pending.claimId(), 1, false);
        Fixture unlinked = candidate(
                "src/Pending.code", "ProcessPendingValue", "another-claim", 1, true);
        Fixture alreadyRead = candidate(
                "src/Pending.code", "ProcessPendingValue", pending.claimId(), 2, true);
        RagPipelineService.CodeClaimResult supported = new RagPipelineService.CodeClaimResult(
                resolved.claimId(), "SUPPORTED", List.of(resolvedCandidate.evidenceId()),
                "workflow value is resolved", List.of(), "");
        var plan = plan(List.of(resolved, pending), List.of(supported));
        var map = map(resolvedCandidate, bodyless, unlinked, alreadyRead);
        Set<String> executed = Set.of(
                "read_chunk|" + alreadyRead.result().chunkId());

        assertThat(planner.select(plan, map, executed)).isEmpty();
    }

    @Test
    void scopeHintAloneCannotTurnAnUnrelatedCallableIntoAnExactRead() {
        RagPipelineService.CodeEvidenceChecklistItem claim = claim(
                "claim-action", "action_path", "apply managed value");
        Fixture unrelated = candidate(
                "src/Clock.code", "RenderClock", claim.claimId(), 1, true);

        assertThat(planner.select(
                plan(List.of(claim), List.of()), map(unrelated), Set.of()))
                .isEmpty();
    }

    @Test
    void directoryTokenInAClaimCannotStandInForCallableIdentity() {
        RagPipelineService.CodeEvidenceChecklistItem claim = claim(
                "claim-location", "location_path", "inspect src behavior");
        Fixture unrelated = candidate(
                "src/Clock.code", "RenderClock", claim.claimId(), 1, true);

        assertThat(planner.select(
                plan(List.of(claim), List.of()), map(unrelated), Set.of()))
                .isEmpty();
    }

    private RagPipelineService.CodeEvidenceChecklistItem claim(
            String claimId,
            String group,
            String intent
    ) {
        return new RagPipelineService.CodeEvidenceChecklistItem(
                claimId, group, intent, List.of(intent),
                "worker", "apply", intent, intent,
                List.of("src"), List.of("DIRECT_SOURCE"));
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan plan(
            List<RagPipelineService.CodeEvidenceChecklistItem> checklist,
            List<RagPipelineService.CodeClaimResult> claimResults
    ) {
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "exact implementation is still required",
                checklist.stream().map(RagPipelineService.CodeEvidenceChecklistItem::claimId).toList(),
                List.of(), List.of(),
                checklist.stream().map(RagPipelineService.CodeEvidenceChecklistItem::evidenceGroup).toList(),
                checklist, List.of(), List.of(), "workflow hypothesis", 1,
                "DISTRIBUTED", claimResults, "NONE");
    }

    private Fixture candidate(
            String path,
            String method,
            String claimId,
            int rank,
            boolean callableBody
    ) {
        UUID chunkId = UUID.randomUUID();
        String evidenceId = "index-v1:" + chunkId + ":10-30";
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "keyword_search", "search-" + claimId, List.of(claimId), "flow",
                List.of(), method, "", "", "", null, null, null,
                List.of(), "BOTH", null, rank);
        Map<String, Object> metadata = Map.of(
                "indexVersion", "index-v1",
                "callableBodyPresent", callableBody,
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance));
        String content = callableBody
                ? "void " + method + "() { execute(); }"
                : "void " + method + "();";
        CodeSearchResult result = new CodeSearchResult(
                chunkId, UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "InputAdapter", method, "app",
                null, null, 1, 10, 30, content, 1.0 - (rank * 0.01), metadata);
        RepositoryQuestionMapBuilder.EvidenceEntry entry =
                new RepositoryQuestionMapBuilder.EvidenceEntry(
                        evidenceId, "IMPLEMENTATION_BODY", "DIRECT_SOURCE",
                        path, method, 10, 30, chunkId, result.score(), 1,
                        "OPERATION", content, result);
        return new Fixture(evidenceId, result, entry);
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap map(Fixture... fixtures) {
        Map<String, RepositoryQuestionMapBuilder.EvidenceEntry> evidence = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) evidence.put(fixture.evidenceId(), fixture.entry());
        return new RepositoryQuestionMapBuilder.RepositoryQuestionMap(
                4, 1, "question", null,
                RepositoryQuestionMapBuilder.RepositoryManifest.empty(), Map.of(), evidence,
                CodeEvidenceIr.empty(), List.of(), List.of(), List.of(), List.of(),
                new RepositoryQuestionMapBuilder.MapDelta(
                        0, 1, List.copyOf(evidence.keySet()), List.of(), false, true));
    }

    private record Fixture(
            String evidenceId,
            CodeSearchResult result,
            RepositoryQuestionMapBuilder.EvidenceEntry entry
    ) {
    }
}
