package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.ActiveCodeIndexIdentity;
import com.learnbot.service.RagPipelineService;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.CodeSymbolOutline;
import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeRetrievalPlanValidatorTest {
    private final CodeRetrievalPlanValidator validator = new CodeRetrievalPlanValidator();

    @Test
    void unresolvedClaimsWithoutOperationsAreInvalidBeforeTheLoop() {
        var plan = plan(List.of(), "NONE");

        var result = validator.validate(plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_NO_EXECUTABLE_OPERATION);
    }

    @Test
    void explicitNoFurtherRetrievalAllowsAnEmptyPlan() {
        var plan = plan(List.of(), "NO_FURTHER_RETRIEVAL");

        var result = validator.validate(plan, null, Set.of());

        assertThat(result.valid()).isTrue();
    }

    @Test
    void initialPlanFiltersArchitectureDriftButKeepsClaimAlignedSourceQuery() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "reference_flow", "resolve symbol references", List.of(),
                "request handler", "resolve", "symbol references", "definitions and usages are returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var drifted = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "AdminController service method calls", "controller layer", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "drifted", List.of("claim-1"), List.of());
        var aligned = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "symbol reference definitions usages", "reference behavior", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "aligned", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("reference_flow"),
                List.of(claim), List.of(drifted, aligned), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how a symbol-reference request crosses the application layers.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).containsExactly(aligned);
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.operationId()).isEqualTo("drifted");
            assertThat(error.detail()).contains("question-behavior anchor");
        });
    }

    @Test
    void initialPlanAcceptsOriginalQuestionVocabularyAlongsideTranslatedClaims() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "state_change", "locate state transition", List.of(),
                "worker", "persist", "state transition", "state is stored",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "상태 전환 저장 흐름", "state behavior", "state_change",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "original-vocabulary", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("state_change"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "상태 전환 저장 흐름을 추적해줘", plan, null, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(operation);
    }

    @Test
    void dominantOriginalQueryAllowsATranslatedClaimSharingOneCodeIdentifier() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "state_save", "persist state after save", List.of(),
                "worker", "call save", "state", "state is persisted",
                List.of(), List.of("DIRECT_SOURCE"));
        String original = "\uc800\uc7a5 save \ud638\ucd9c \ud6c4 \uc0c1\ud0dc \uc800\uc7a5 \ud750\ub984";
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", original, "state flow", "state_save",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "mixed-language", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("state_save"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(original, plan, null, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(operation);
    }

    @Test
    void initialPlanRejectsAClaimAndQueryThatDriftTogetherFromTheQuestion() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "settings_update", "update administrator settings", List.of(),
                "administrator", "update", "settings", "settings are persisted",
                List.of("controller", "service"), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "AdminController update settings", "controller", "settings_update",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "drifted", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("settings_update"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how symbol references are resolved and returned.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void initialPlanRejectsDriftThatBorrowsOnlyOneQuestionToken() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "preferences_update", "update administrator preferences", List.of(),
                "administrator", "update", "preferences", "preferences are persisted",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "resolved administrator update preferences", "administration", "preferences_update",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "single-token-graft", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("preferences_update"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how symbol references are resolved and returned.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void initialPlanRejectsASingleGenericQuestionToken() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "preferences_update", "update administrator preferences", List.of(),
                "administrator", "update", "preferences", "preferences are persisted",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "trace", "administration", "preferences_update",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "generic-graft", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("preferences_update"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how symbol references are resolved and returned.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void initialPlanRejectsAMultiClaimQueryThatAnchorsOnlyOneClaim() {
        var validation = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-validate", "validation", "validate payload", List.of(),
                "processor", "validate", "payload", "payload is accepted",
                List.of(), List.of("DIRECT_SOURCE"));
        var persistence = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-persist", "audit", "persist audit record", List.of(),
                "processor", "persist", "audit record", "audit record is stored",
                List.of(), List.of("DIRECT_SOURCE"));
        var partial = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "validate payload rules", "processing", "validation",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "partial-multi-claim", List.of("claim-validate", "claim-persist"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-validate", "claim-persist"), List.of(), List.of(),
                List.of("validation", "audit"), List.of(validation, persistence), List.of(partial), List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how a payload is validated and an audit record is persisted.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void initialPlanAcceptsAMultiClaimQueryThatBridgesEveryClaim() {
        var validation = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-validate", "validation", "validate payload", List.of(),
                "processor", "validate", "payload", "payload is accepted",
                List.of(), List.of("DIRECT_SOURCE"));
        var persistence = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-persist", "audit", "persist audit record", List.of(),
                "processor", "persist", "audit record", "audit record is stored",
                List.of(), List.of("DIRECT_SOURCE"));
        var complete = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "validate payload persist audit record", "processing", "validation audit",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "complete-multi-claim", List.of("claim-validate", "claim-persist"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-validate", "claim-persist"), List.of(), List.of(),
                List.of("validation", "audit"), List.of(validation, persistence), List.of(complete), List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how a payload is validated and an audit record is persisted.", plan, null, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(complete);
    }

    @Test
    void initialPlanRejectsLongMultiClaimDriftWithOnlyTwoBorrowedTokens() {
        var first = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "first", "resolve symbol references", List.of(),
                "gateway", "resolve", "symbol references", "references are returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var second = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-2", "second", "inspect symbol references", List.of(),
                "gateway", "inspect", "symbol references", "references are inspected",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search",
                "symbol references administrator settings permissions transaction repository migration",
                "unrelated", "first second", "", "", "", null, null, null,
                List.of(), "BOTH", null, "long-drift", List.of("claim-1", "claim-2"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1", "claim-2"), List.of(), List.of(),
                List.of("first", "second"), List.of(first, second), List.of(operation), List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how symbol references are resolved and returned.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void initialPlanRejectsMultiClaimQueriesWithOnlyHalfQuestionVocabulary() {
        var first = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "first", "resolve symbol references", List.of(),
                "gateway", "resolve", "symbol references", "references are returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var second = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-2", "second", "inspect symbol references", List.of(),
                "gateway", "inspect", "symbol references", "references are inspected",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "symbol references administrator settings", "mixed", "first second",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "half-question", List.of("claim-1", "claim-2"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1", "claim-2"), List.of(), List.of(),
                List.of("first", "second"), List.of(first, second), List.of(operation), List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace how symbol references are resolved and returned.", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void oneShortPrefixCannotInflateMultiClaimQuestionCoverage() {
        var first = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "first", "resolve references", List.of(),
                "gateway", "resolve", "references", "references returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var second = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-2", "second", "return resolved references", List.of(),
                "gateway", "return", "resolved references", "references returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "re admin settings permissions", "mixed", "first second",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "prefix-inflation", List.of("claim-1", "claim-2"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1", "claim-2"), List.of(), List.of(),
                List.of("first", "second"), List.of(first, second), List.of(operation), List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "references resolved returned", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void oneShortPrefixCannotInflateAClaimCompanion() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "reference_flow", "resolve references", List.of(),
                "gateway", "resolve", "references", "references returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var anchor = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "references resolved returned", "flow", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "anchor", List.of("claim-1"), List.of());
        var companion = new RagPipelineService.CodeSearchOperation(
                "keyword_search", "re admin settings permissions", "mixed", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "prefix-companion", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(),
                List.of("reference_flow"), List.of(claim), List.of(anchor, companion), List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "references resolved returned", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).containsExactly(anchor);
    }

    @Test
    void initialPlanAcceptsRoleScopeAndBehaviorWhenTheyBridgeTheClaim() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "persistence", "persist user settings", List.of(),
                "user", "persist", "settings", "settings persistence completes",
                List.of("settings"), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "user settings persist", "persistence", "persistence",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "role-scope-behavior", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("persistence"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "Trace user settings persistence", plan, null, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(operation);
    }

    @Test
    void initialPlanAllowsOneClaimAlignedSourceVocabularyCompanionAfterAQuestionAnchor() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "state_change", "locate state transition", List.of(),
                "worker", "persist", "state transition", "state is stored",
                List.of(), List.of("DIRECT_SOURCE"));
        var anchor = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "상태 전환 저장 흐름", "state behavior", "state_change",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "question-anchor", List.of("claim-1"), List.of());
        var companion = new RagPipelineService.CodeSearchOperation(
                "keyword_search", "persist state transition storage", "state behavior", "state_change",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "source-companion", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("state_change"),
                List.of(claim), List.of(anchor, companion), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial("상태 전환 저장 흐름을 추적해줘", plan, null, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(anchor, companion);
    }

    @Test
    void initialPlanAcceptsAnObservedCompositeCallableAsAMultilingualSourceBridge() {
        var map = observedMap("src/Highlighter.cs", "UpdateHighlight");
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "focus_update", "포커스 변경 뒤 강조 표시를 갱신한다", List.of(),
                "클라이언트", "갱신한다", "강조 표시", "새 포커스가 표시된다",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "UpdateHighlight method implementation", "focus behavior", "focus_update",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "observed-callable", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("focus_update"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "키보드 포커스가 바뀌면 강조 표시가 어떻게 갱신돼?", plan, map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(operation);
    }

    @Test
    void observedContainersAndSinglePartCallablesDoNotBypassQuestionAnchoring() {
        var containerMap = observedMapWithOutlineKind(
                "src/AdminController.java", "AdminController", "class");
        var simpleCallableMap = observedMap("src/Settings.java", "update");
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "reference_flow", "resolve symbol references", List.of(),
                "resolver", "resolve", "references", "references are returned",
                List.of(), List.of("DIRECT_SOURCE"));
        var containerQuery = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "AdminController service calls", "administration", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "container", List.of("claim-1"), List.of());
        var genericVerbQuery = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "update settings", "administration", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "generic", List.of("claim-1"), List.of());

        var containerResult = validator.validateInitial(
                "Trace how symbol references are resolved and returned.",
                planWithClaim(claim, List.of(containerQuery)), containerMap, Set.of());
        var genericResult = validator.validateInitial(
                "Trace how symbol references are resolved and returned.",
                planWithClaim(claim, List.of(genericVerbQuery)), simpleCallableMap, Set.of());

        assertThat(containerResult.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(containerResult.executableOperations()).isEmpty();
        assertThat(genericResult.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(genericResult.executableOperations()).isEmpty();
    }

    @Test
    void observedCallableRequiresAnExactIdentifierBoundary() {
        var map = observedMap("src/Highlighter.cs", "UpdateHighlight");
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "focus_update", "update focus marker", List.of(),
                "client", "update", "focus marker", "focus marker changes",
                List.of(), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "UpdateHighlighting implementation", "focus", "focus_update",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "substring", List.of("claim-1"), List.of());

        var result = validator.validateInitial(
                "포커스 표시를 바꾸는 흐름", planWithClaim(claim, List.of(operation)), map, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void initialPlanDoesNotTreatNonEnglishActorAndScopeWordsAsBehaviorAnchors() {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "reference_flow", "심볼 참조를 찾는다", List.of(),
                "컨트롤러", "찾는다", "심볼 참조", "사용 위치를 반환한다",
                List.of("서비스", "저장소"), List.of("DIRECT_SOURCE"));
        var operation = new RagPipelineService.CodeSearchOperation(
                "hybrid_search", "컨트롤러 서비스 저장소 구조", "아키텍처", "reference_flow",
                "", "", "", null, null, null, List.of(), "BOTH", null,
                "role-only", List.of("claim-1"), List.of());
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("reference_flow"),
                List.of(claim), List.of(operation), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");

        var result = validator.validateInitial(
                "컨트롤러 서비스 저장소에서 심볼 참조를 찾아 사용 위치를 반환하는 흐름", plan, null, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH);
        assertThat(result.executableOperations()).isEmpty();
    }

    @Test
    void explicitStopIsRejectedWhileAClaimLinkedObservedSymbolHasNotBeenRead() {
        String path = "src/Worker.java";
        var map = observedMap(path, "startWork");
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "start_work", "find work startup", List.of("start work implementation"),
                "worker", "start", "work", "work begins", List.of(), List.of("DIRECT_SOURCE"));
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("start_work"),
                List.of(claim), List.of(), List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NO_FURTHER_RETRIEVAL");

        var result = validator.validate(plan, map, Set.of());

        assertThat(result.code()).isEqualTo(
                CodeRetrievalPlanValidator.PlanValidationCode.INVALID_UNTRIED_NAVIGATION);
    }

    @Test
    void broadContainerEvidenceIsNavigationRatherThanConcreteBehaviorProof() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        String broadEvidenceId = map.evidence().values().stream()
                .filter(entry -> entry.source().methodName() == null || entry.source().methodName().isBlank())
                .map(RepositoryQuestionMapBuilder.EvidenceEntry::evidenceId)
                .findFirst()
                .orElseThrow();

        assertThat(map.isDirectProofEvidenceId(broadEvidenceId)).isFalse();
    }

    @Test
    void overlappingRangeMetadataDoesNotTurnABroadContainerIntoConcreteProof() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete", Map.of(
                "llmDirectRead", true,
                "llmReadOperation", "read_file_range",
                "llmRequestedLineStart", 10,
                "llmRequestedLineEnd", 30
        ));
        String broadEvidenceId = map.evidence().values().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.source().metadata().get("llmDirectRead")))
                .map(RepositoryQuestionMapBuilder.EvidenceEntry::evidenceId)
                .findFirst()
                .orElseThrow();

        assertThat(map.isDirectProofEvidenceId(broadEvidenceId)).isFalse();
    }

    @Test
    void nestedTypeAndFileContainersRemainNavigationEvidence() {
        Map<String, Object> directRead = Map.of("llmDirectRead", true);
        var nestedType = observedMap(
                "src/Outer.java", "Inner", directRead, "type", "Inner", "Outer", "");
        var fileContainer = observedMap(
                "src/Gateway.java", "Gateway", directRead, "file", "Gateway", "", "");

        assertThat(nestedType.evidence().values().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.source().metadata().get("llmDirectRead")))
                .allMatch(entry -> !nestedType.isDirectProofEvidenceId(entry.evidenceId()))).isTrue();
        assertThat(fileContainer.evidence().values().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.source().metadata().get("llmDirectRead")))
                .allMatch(entry -> !fileContainer.isDirectProofEvidenceId(entry.evidenceId()))).isTrue();
    }

    @Test
    void summaryAndUnknownContainersFailClosedWithoutAChunkTypeDenylist() {
        for (String chunkType : List.of(
                "project_structure", "repository_summary", "directory_summary", "file_summary",
                "future_container_v2")) {
            var map = observedMap(
                    "__learnbot__/context.md", "overview", Map.of("llmDirectRead", true),
                    chunkType, "overview", "", "");

            assertThat(map.evidence().values().stream()
                    .filter(entry -> Boolean.TRUE.equals(entry.source().metadata().get("llmDirectRead")))
                    .allMatch(entry -> !map.isDirectProofEvidenceId(entry.evidenceId())))
                    .as("chunk type %s", chunkType)
                    .isTrue();
        }
    }

    @Test
    void constructorMethodIdentityIsConcreteCallableProof() {
        var map = observedMap(
                "src/Widget.java", "Widget", Map.of(
                        "llmDirectRead", true, "callableBodyPresent", true),
                "constructor", "Widget", "Widget", "Widget");

        assertThat(map.evidence().values().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.source().metadata().get("llmDirectRead")))
                .anyMatch(entry -> map.isDirectProofEvidenceId(entry.evidenceId()))).isTrue();
    }

    @Test
    void declarationOnlyCallableIsNotConcreteImplementationProof() {
        var map = observedMap(
                "src/Port.java", "save", Map.of(
                        "llmDirectRead", true, "callableBodyPresent", false),
                "method", "save", "Port", "save");

        assertThat(map.evidence().values().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.source().metadata().get("llmDirectRead")))
                .allMatch(entry -> !map.isDirectProofEvidenceId(entry.evidenceId()))).isTrue();
    }

    @Test
    void directReadRequiresAnObservedPathSymbolAndBoundOrigin() {
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String path = "src/Gateway.java";
        CodeRepository repository = mock(CodeRepository.class);
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(List.of(new CodeSymbolOutline(
                        "symbol-complete", path, "method", "complete", "Gateway.complete",
                        10, 30, chunkId, "java", "COMPILER_SEMANTIC", 1)));
        CodeSearchResult candidate = new CodeSearchResult(
                chunkId, repositoryId, UUID.randomUUID(), "repo", path, "type", "Gateway", "Gateway", "",
                "app", null, null, 0, 1, 40, "class Gateway {}", 0.9,
                Map.of("indexVersion", indexVersion.toString()));
        var map = new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), "complete response", List.of(candidate));
        String origin = map.symbolInventories().get(path).evidenceId();
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "implementation", "claim-1", path, "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of(origin));

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).containsExactly(operation);
    }

    @Test
    void inventedDirectReadPathIsRejected() {
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "implementation", "claim-1", "invented/Service.java", "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of("missing"));

        var result = validator.validate(plan(List.of(operation), "NONE"), null, Set.of());

        assertThat(result.code()).isEqualTo(CodeRetrievalPlanValidator.PlanValidationCode.INVALID_UNKNOWN_ORIGIN);
    }

    @Test
    void bindsAPathOnlyDirectReadToObservedFileEvidence() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        var operation = new RagPipelineService.CodeSearchOperation(
                "list_file_symbols", "", "implementation", "claim-1", path, "", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of());

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).singleElement().satisfies(executable ->
                assertThat(executable.originEvidenceIds()).hasSize(1));
    }

    @Test
    void replacesAStaleModelOriginWithCurrentObservedProvenanceForTheSameOperand() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_symbol", "", "implementation", "claim-1", path, "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of("stale:id"));

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).singleElement().satisfies(executable -> {
            assertThat(executable.originEvidenceIds()).hasSize(1);
            assertThat(executable.originEvidenceIds()).doesNotContain("stale:id");
            assertThat(map.containsEvidenceId(executable.originEvidenceIds().get(0))).isTrue();
        });
    }

    @Test
    void changesARangeReadWithoutObservedLinesToAnObservedSymbolRead() {
        String path = "src/Gateway.java";
        var map = observedMap(path, "complete");
        var operation = new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "implementation", "claim-1", path, "complete", "",
                null, null, null, List.of(), "BOTH", null, "op-1", List.of("claim-1"), List.of());

        var result = validator.validate(plan(List.of(operation), "NONE"), map, Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.executableOperations()).singleElement().satisfies(executable -> {
            assertThat(executable.type()).isEqualTo("read_symbol");
            assertThat(executable.symbol()).isEqualTo("complete");
            assertThat(executable.originEvidenceIds()).hasSize(1);
        });
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap observedMap(String path, String symbol) {
        return observedMap(path, symbol, Map.of());
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap observedMapWithOutlineKind(
            String path,
            String symbol,
            String outlineKind
    ) {
        return observedMap(path, symbol, Map.of(), "class", "Gateway", "Gateway", "", outlineKind);
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap observedMap(
            String path,
            String symbol,
            Map<String, Object> extraMetadata
    ) {
        return observedMap(path, symbol, extraMetadata, "class", "Gateway", "Gateway", "");
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap observedMap(
            String path,
            String symbol,
            Map<String, Object> extraMetadata,
            String chunkType,
            String candidateSymbol,
            String candidateClass,
            String candidateMethod
    ) {
        return observedMap(path, symbol, extraMetadata, chunkType, candidateSymbol,
                candidateClass, candidateMethod, "method");
    }

    private RepositoryQuestionMapBuilder.RepositoryQuestionMap observedMap(
            String path,
            String symbol,
            Map<String, Object> extraMetadata,
            String chunkType,
            String candidateSymbol,
            String candidateClass,
            String candidateMethod,
            String outlineKind
    ) {
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        CodeRepository repository = mock(CodeRepository.class);
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        when(repository.findActiveIndexIdentity(eq(repositoryId), any(), any()))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.findActiveChunksByPath(eq(repositoryId), eq("__learnbot__/project-context.md"), eq(8), any(), any()))
                .thenReturn(List.of());
        when(repository.listAnalysisDiagnostics(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listJobFailures(repositoryId, indexVersion)).thenReturn(List.of());
        when(repository.listActiveSymbolOutlinesByPaths(eq(repositoryId), any(), anyInt(), any(), any()))
                .thenReturn(List.of(new CodeSymbolOutline(
                        "symbol-complete", path, outlineKind, symbol, "Gateway." + symbol,
                        10, 30, chunkId, "java", "COMPILER_SEMANTIC", 1)));
        Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata);
        metadata.put("indexVersion", indexVersion.toString());
        CodeSearchResult candidate = new CodeSearchResult(
                chunkId, repositoryId, UUID.randomUUID(), "repo", path, chunkType,
                candidateSymbol, candidateClass, candidateMethod,
                "app", null, null, 0, 10, 30, "void " + symbol + "() {}", 0.9,
                Map.copyOf(metadata));
        return new RepositoryQuestionMapBuilder(repository).build(
                repositoryId, null, List.of(UUID.randomUUID()), symbol, List.of(candidate));
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan planWithClaim(
            RagPipelineService.CodeEvidenceChecklistItem claim,
            List<RagPipelineService.CodeSearchOperation> operations
    ) {
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of(claim.claimId()), List.of(), List.of(),
                List.of(claim.evidenceGroup()), List.of(claim), operations, List.of(),
                "hypothesis", 1, "UNRESOLVED", List.of(), "NONE");
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan plan(
            List<RagPipelineService.CodeSearchOperation> operations,
            String terminationRequest
    ) {
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "claim-1", "complete the response", List.of(),
                "gateway", "complete", "response", "response is persisted", List.of(), List.of("DIRECT_SOURCE"));
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "test", List.of("claim-1"), List.of(), List.of(), List.of("claim-1"),
                List.of(claim), operations, List.of(), "hypothesis", 1, "UNRESOLVED", List.of(), terminationRequest);
    }
}
