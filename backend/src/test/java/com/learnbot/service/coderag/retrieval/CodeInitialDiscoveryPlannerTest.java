package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeInitialDiscoveryPlannerTest {
    private final CodeInitialDiscoveryPlanner planner = new CodeInitialDiscoveryPlanner();

    @Test
    void directReadOnlyPlanGetsQuestionAnchorsAndClaimVocabularyDiscoveryBeforeReads() {
        var first = claim(
                "claim-1", "request_delegation", "controller", "resolve", "symbol references",
                "request is delegated to a service");
        var second = claim(
                "claim-2", "repository_lookup", "service", "find", "definitions and references",
                "reference locations are returned");
        var directRead = new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", "", "request_delegation", "src/Bootstrap.java", "", "chunk-1",
                null, null, 1, List.of(), "BOTH", null,
                "read-bootstrap", List.of("claim-1"), List.of("evidence-1"));

        List<RagPipelineService.CodeSearchOperation> augmented = planner.augmentDirectReadOnlyPlan(
                "심볼 참조 요청의 처리 흐름", List.of(first, second), List.of(directRead), 4);

        assertThat(augmented).hasSize(5);
        assertThat(augmented.subList(0, 4)).allMatch(RagPipelineService.CodeSearchOperation::isSearch);
        assertThat(augmented.get(0).query()).contains("심볼 참조 요청의 처리 흐름", "request_delegation");
        assertThat(augmented.get(1).query()).isEqualTo(
                "resolve symbol references request is delegated to a service controller");
        assertThat(augmented.get(3).query()).isEqualTo(
                "find definitions and references reference locations are returned service");
        assertThat(augmented.get(4)).isEqualTo(directRead);
    }

    @Test
    void anExistingSearchPlanIsNotExpanded() {
        var claim = claim("claim-1", "lookup", "service", "find", "references", "references returned");
        var search = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "find references", "behavior", "lookup",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "search-1", List.of("claim-1"), List.of());

        assertThat(planner.augmentDirectReadOnlyPlan(
                "참조 검색", List.of(claim), List.of(search), 4)).containsExactly(search);
    }

    @Test
    void planReasonAndHypothesisCannotLeakIntoSynthesizedSearchVocabulary() {
        var claim = claim(
                "claim-1", "lookup", "service", "locate", "usages", "locations returned");
        var directRead = new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", "", "lookup", "src/PlausibleButWrong.java", "", "chunk-1",
                null, null, 1, List.of(), "BOTH", null,
                "read-1", List.of("claim-1"), List.of("evidence-1"));

        List<RagPipelineService.CodeSearchOperation> augmented = planner.augmentDirectReadOnlyPlan(
                "사용 위치를 찾아줘", List.of(claim), List.of(directRead), 2);

        assertThat(augmented.get(1).query()).isEqualTo("locate usages locations returned service");
        assertThat(augmented.get(1).query()).doesNotContain("PlausibleButWrong");
    }

    @Test
    void synthesizedAnchorAndCompanionPassTheInitialSemanticContract() {
        var claim = claim(
                "claim-1", "reference_lookup", "service", "find", "symbol references",
                "reference locations are returned");
        var directRead = new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", "", "reference_lookup", "src/Bootstrap.java", "", "chunk-1",
                null, null, 1, List.of(), "BOTH", null,
                "read-1", List.of("claim-1"), List.of("evidence-1"));
        List<RagPipelineService.CodeSearchOperation> searches = planner.augmentDirectReadOnlyPlan(
                        "심볼 참조 요청의 처리 흐름", List.of(claim), List.of(directRead), 2).stream()
                .filter(RagPipelineService.CodeSearchOperation::isSearch)
                .toList();
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(),
                List.of("reference_lookup"), List.of(claim), searches, List.of(),
                "", 1, "UNRESOLVED", List.of(), "NONE");

        var validation = new CodeRetrievalPlanValidator().validateInitial(
                "심볼 참조 요청의 처리 흐름", plan, null, java.util.Set.of());

        assertThat(validation.code()).isEqualTo(CodeRetrievalPlanValidator.PlanValidationCode.VALID);
        assertThat(validation.executableOperations()).containsExactlyElementsOf(searches);
    }

    @Test
    void rejectedMultilingualSearchesCanBeRevalidatedWithBoundedQuestionAnchors() {
        var first = claim(
                "claim-1", "primary_write", "adapter", "apply", "automation value",
                "the primary write path is shown");
        var second = claim(
                "claim-2", "fallback_write", "adapter", "send", "native message",
                "the fallback write path is shown");
        var primary = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "AutomationValueProvider apply value", "behavior", "primary_write",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "search-primary", List.of("claim-1"), List.of());
        var fallback = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "NativeMessageSender send native message", "behavior", "fallback_write",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "search-fallback", List.of("claim-2"), List.of());
        var initial = plan(List.of(first, second), List.of(primary, fallback));
        CodeRetrievalPlanValidator validator = new CodeRetrievalPlanValidator();

        var rejected = validator.validateInitial(
                "입력 값을 넣는 두 경로를 비교해줘", initial, null, java.util.Set.of());
        List<RagPipelineService.CodeSearchOperation> repaired = planner.repairRejectedSearchAnchors(
                "입력 값을 넣는 두 경로를 비교해줘", initial.checklist(), initial.operations(),
                rejected.errors().stream().map(CodeRetrievalPlanValidator.PlanValidationError::operationId)
                        .collect(java.util.stream.Collectors.toSet()),
                4);
        var validated = validator.validateInitial(
                "입력 값을 넣는 두 경로를 비교해줘", plan(List.of(first, second), repaired),
                null, java.util.Set.of());

        assertThat(rejected.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(repaired.subList(0, 2)).allMatch(operation ->
                operation.operationId().startsWith("initial-repair-anchor-"));
        assertThat(validated.code()).isEqualTo(CodeRetrievalPlanValidator.PlanValidationCode.VALID);
        assertThat(validated.executableOperations()).contains(primary, fallback);
    }

    @Test
    void aQuestionAnchorDoesNotAuthorizeADriftedSourceQuery() {
        var claim = claim(
                "claim-1", "state_write", "store", "persist", "session state",
                "the state is durable");
        var drifted = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "delete billing records", "behavior", "state_write",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "search-drifted", List.of("claim-1"), List.of());
        List<RagPipelineService.CodeSearchOperation> repaired = planner.repairRejectedSearchAnchors(
                "세션 상태 저장 흐름을 설명해줘", List.of(claim), List.of(drifted),
                java.util.Set.of("search-drifted"), 2);

        var validation = new CodeRetrievalPlanValidator().validateInitial(
                "세션 상태 저장 흐름을 설명해줘", plan(List.of(claim), repaired),
                null, java.util.Set.of());

        assertThat(validation.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(validation.executableOperations()).noneMatch(operation ->
                operation.operationId().equals("search-drifted"));
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan plan(
            List<RagPipelineService.CodeEvidenceChecklistItem> checklist,
            List<RagPipelineService.CodeSearchOperation> operations
    ) {
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", checklist.stream().map(
                        RagPipelineService.CodeEvidenceChecklistItem::claimId).toList(),
                List.of(), List.of(), checklist.stream().map(
                        RagPipelineService.CodeEvidenceChecklistItem::evidenceGroup).toList(),
                checklist, operations, List.of(), "", 1, "UNRESOLVED", List.of(), "NONE");
    }

    private RagPipelineService.CodeEvidenceChecklistItem claim(
            String id,
            String group,
            String actor,
            String action,
            String object,
            String outcome
    ) {
        return new RagPipelineService.CodeEvidenceChecklistItem(
                id, group, action + " " + object, List.of(), actor, action, object, outcome,
                List.of(), List.of("DIRECT_SOURCE"));
    }
}
