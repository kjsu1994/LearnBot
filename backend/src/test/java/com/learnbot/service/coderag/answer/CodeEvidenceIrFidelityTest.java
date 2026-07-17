package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.learnbot.service.coderag.answer.CodeAnswerVerification.Disposition;
import static com.learnbot.service.coderag.answer.CodeAnswerVerification.FailureKind;
import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceIrFidelityTest {

    @Test
    void requiredFuturePredicateRequiresSubjectIdentityAndExactValue() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier(
                CodeAnswerVerifier.AnswerQualityPolicy.accepting());
        CodeSearchResult source = result("src/Engine.java", "engine.phase = stable-value;", Map.of());
        CodeEvidenceItem item = item(source, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact fact = fact(
                item, "engine.phase", "FUTURE_OBSERVATION", "stable-value",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = ir(List.of(item), List.of(fact), List.of(required(fact)));

        CodeAnswerVerification missingSubject = verifier.verify(
                "What phase was observed?", "The value is stable-value [1].",
                List.of(source), "stop", true, ir);
        CodeAnswerVerification missingValue = verifier.verify(
                "What phase was observed?", "The engine.phase value is available [1].",
                List.of(source), "stop", true, ir);
        CodeAnswerVerification complete = verifier.verify(
                "What phase was observed?", "engine.phase is stable-value [1].",
                List.of(source), "stop", true, ir);

        assertThat(missingSubject.failureKind()).isEqualTo(FailureKind.EXACT_FACT);
        assertThat(missingSubject.reason()).contains("engine.phase");
        assertThat(missingValue.failureKind()).isEqualTo(FailureKind.EXACT_FACT);
        assertThat(missingValue.reason()).contains("stable-value");
        assertThat(complete.disposition()).isEqualTo(Disposition.ACCEPT);
        assertThat(CodeEvidenceIrFidelity.promptFacts("engine phase", ir, List.of(source)))
                .contains("engine.phase: FUTURE_OBSERVATION=stable-value", "[1]");
    }

    @Test
    void knownPredicateNamesReceiveTheSameGenericTreatment() {
        CodeSearchResult source = result("src/Observed.java", "observed facts", Map.of());
        CodeEvidenceItem item = item(source, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact route = fact(
                item, "OrderApi.submit", "EXPOSES_ENDPOINT", "/api/orders/{id}",
                CodeEvidenceFact.Exactness.NORMALIZED, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact value = fact(
                item, "session.ready", "ASSIGNS_LITERAL", "true",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = ir(
                List.of(item), List.of(route, value), List.of(required(route), required(value)));

        String prompt = CodeEvidenceIrFidelity.promptFacts("observed facts", ir, List.of(source));

        assertThat(prompt)
                .contains(
                        "OrderApi.submit: EXPOSES_ENDPOINT=/api/orders/{id}",
                        "session.ready: ASSIGNS_LITERAL=true")
                .doesNotContain("endpoint route", "assignments relevant");
        assertThat(CodeEvidenceIrFidelity.missingReason(
                "OrderApi.submit uses /api/orders/{id}; session.ready is true.", ir)).isNull();
    }

    @Test
    void inferredLowAuthorityAndOrphanFactsCannotSpoofTrustedFidelity() {
        CodeSearchResult trustedSource = result("src/Trusted.java", "trusted", Map.of());
        CodeSearchResult weakSource = result("src/Weak.java", "weak", Map.of());
        CodeEvidenceItem trustedItem = item(trustedSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem weakItem = item(weakSource, CodeIntelligenceAuthority.LLM_INFERRED);
        CodeEvidenceFact trusted = fact(
                trustedItem, "future.safe", "FUTURE_TRUTH", "allowed",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact inferred = fact(
                trustedItem, "future.inferred", "FUTURE_GUESS", "inferredSpoof",
                CodeEvidenceFact.Exactness.INFERRED, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact weakFact = fact(
                trustedItem, "future.weak", "FUTURE_WEAK", "factSpoof",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.LLM_INFERRED);
        CodeEvidenceFact weakItemFact = fact(
                weakItem, "future.item", "FUTURE_ITEM", "itemSpoof",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact orphan = CodeEvidenceFact.of(
                "missing-evidence", "future.orphan", "FUTURE_ORPHAN", "orphanSpoof",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        List<CodeEvidenceFact> facts = List.of(trusted, inferred, weakFact, weakItemFact, orphan);
        CodeEvidenceIr ir = ir(
                List.of(trustedItem, weakItem),
                facts,
                facts.stream().map(this::required).toList());

        String prompt = CodeEvidenceIrFidelity.promptFacts(
                "future facts", ir, List.of(trustedSource, weakSource));

        assertThat(prompt)
                .contains("future.safe: FUTURE_TRUTH=allowed", "[1]")
                .doesNotContain("inferredSpoof", "factSpoof", "itemSpoof", "orphanSpoof");
        assertThat(CodeEvidenceIrFidelity.relevantEvidenceIds("future facts", ir))
                .containsExactly(trustedItem.evidenceId());
        assertThat(CodeEvidenceIrFidelity.missingReason("future.safe is allowed.", ir)).isNull();
    }

    @Test
    void requiredFactsComeBeforeQuestionRelevantFactsWithFinalCitations() {
        CodeSearchResult incidentalSource = result("src/Internal.java", "internal fact", Map.of());
        CodeSearchResult requiredSource = result("src/Required.java", "required fact", Map.of());
        CodeSearchResult relevantSource = result("src/Selected.java", "selected fact", Map.of());
        CodeEvidenceItem incidentalItem = item(incidentalSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem requiredItem = item(requiredSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem relevantItem = item(relevantSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact incidental = fact(
                incidentalItem, "internal.secret", "FUTURE_DETAIL", "hidden",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact required = fact(
                requiredItem, "omega.required", "FUTURE_REQUIRED", "fixed",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact relevant = fact(
                relevantItem, "selected.currentValue", "FUTURE_COMPUTATION", "resolved",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = ir(
                List.of(incidentalItem, requiredItem, relevantItem),
                List.of(incidental, relevant, required),
                List.of(required(required)));

        String prompt = CodeEvidenceIrFidelity.promptFacts(
                "How is selected current value computed?",
                ir,
                List.of(incidentalSource, requiredSource, relevantSource));

        assertThat(prompt.indexOf("omega.required: FUTURE_REQUIRED=fixed"))
                .isLessThan(prompt.indexOf("selected.currentValue: FUTURE_COMPUTATION=resolved"));
        assertThat(prompt)
                .contains("FUTURE_REQUIRED=fixed` [2]", "FUTURE_COMPUTATION=resolved` [3]")
                .doesNotContain("internal.secret");
        assertThat(CodeEvidenceIrFidelity.relevantEvidenceIds(
                "How is selected current value computed?", ir))
                .containsExactlyInAnyOrder(requiredItem.evidenceId(), relevantItem.evidenceId());
    }

    @Test
    void directReadIgnoresFreeFormClaimMetadataButCanUseRequestedStructure() {
        Map<String, Object> spoofMetadata = Map.of(
                "llmDirectRead", true,
                "llmFollowUpQuery", "privileged settings mutation",
                "llmChecklistGoal", "privileged settings mutation",
                "llmSearchPlanQuery", "privileged settings mutation",
                "llmReadArea", "privileged settings mutation",
                "llmRequestedPath", "src/InternalCache.java");
        CodeSearchResult spoofSource = result("src/InternalCache.java", "internal cache", spoofMetadata);
        CodeEvidenceItem spoofItem = item(spoofSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact spoofFact = fact(
                spoofItem, "internal.cache", "FUTURE_CACHE", "refresh",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr spoofIr = ir(List.of(spoofItem), List.of(spoofFact), List.of());

        assertThat(CodeEvidenceIrFidelity.promptFacts(
                "privileged settings mutation", spoofIr, List.of(spoofSource))).isEmpty();
        assertThat(CodeEvidenceIrFidelity.relevantEvidenceIds(
                "privileged settings mutation", spoofIr)).isEmpty();

        CodeSearchResult structuralSource = result(
                "src/Coordinator.java", "observed",
                Map.of("llmDirectRead", true, "llmRequestedSymbol", "privilegedSettingsMutation"));
        CodeEvidenceItem structuralItem = item(structuralSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact structuralFact = fact(
                structuralItem, "coordinator.result", "FUTURE_RESULT", "observed",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr structuralIr = ir(
                List.of(structuralItem), List.of(structuralFact), List.of());

        assertThat(CodeEvidenceIrFidelity.promptFacts(
                "privileged settings mutation", structuralIr, List.of(structuralSource)))
                .contains("coordinator.result: FUTURE_RESULT=observed", "[1]");
    }

    @Test
    void nonDirectTrustedClaimMetadataCanSelectAnArbitraryPredicate() {
        CodeSearchResult source = result(
                "src/StateCoordinator.java", "runtime phase",
                Map.of("llmChecklistGoal", "상태 변경 근거 확인"));
        CodeEvidenceItem item = item(source, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact fact = fact(
                item, "runtime.phase", "FUTURE_PHASE", "next",
                CodeEvidenceFact.Exactness.NORMALIZED, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = ir(List.of(item), List.of(fact), List.of());

        assertThat(CodeEvidenceIrFidelity.promptFacts("상태 변경", ir, List.of(source)))
                .contains("runtime.phase: FUTURE_PHASE=next", "[1]");
        assertThat(CodeEvidenceIrFidelity.relevantEvidenceIds("상태 변경", ir))
                .containsExactly(item.evidenceId());
    }

    @Test
    void multipleRequiredFactsForOneSubjectRequireEveryExactAtom() {
        CodeSearchResult source = result("src/Phase.java", "phase status", Map.of());
        CodeEvidenceItem item = item(source, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact initial = fact(
                item, "phase.status", "INITIAL_VALUE", "\"active\"",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact terminal = fact(
                item, "phase.status", "FINAL_VALUE", "\"inactive\"",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = ir(
                List.of(item), List.of(initial, terminal), List.of(required(initial), required(terminal)));

        assertThat(CodeEvidenceIrFidelity.missingReason(
                "phase.status becomes inactive.", ir)).contains("active");
        assertThat(CodeEvidenceIrFidelity.missingReason(
                "phase.status changes from active to inactive.", ir)).isNull();
    }

    @Test
    void factsWithoutFinalEvidenceAreProtectedButNotRendered() {
        CodeSearchResult source = result("src/Retained.java", "retained", Map.of());
        CodeEvidenceItem item = item(source, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact fact = fact(
                item, "retained.value", "FUTURE_VALUE", "exact",
                CodeEvidenceFact.Exactness.EXACT, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = ir(List.of(item), List.of(fact), List.of(required(fact)));

        assertThat(CodeEvidenceIrFidelity.promptFacts("retained value", ir, List.of())).isEmpty();
        assertThat(CodeEvidenceIrFidelity.relevantEvidenceIds("retained value", ir))
                .containsExactly(item.evidenceId());
    }

    private CodeEvidenceItem item(CodeSearchResult source, CodeIntelligenceAuthority authority) {
        return new CodeEvidenceItem(
                CodeEvidenceItem.evidenceId(source),
                source,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE),
                authority);
    }

    private CodeEvidenceFact fact(
            CodeEvidenceItem item,
            String subject,
            String predicate,
            String value,
            CodeEvidenceFact.Exactness exactness,
            CodeIntelligenceAuthority authority
    ) {
        return CodeEvidenceFact.of(
                item.evidenceId(), subject, predicate, value, exactness, 1.0, authority);
    }

    private CodeEvidenceConstraint required(CodeEvidenceFact fact) {
        return new CodeEvidenceConstraint(
                CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                fact.factId(),
                "preserve trusted fact");
    }

    private CodeEvidenceIr ir(
            List<CodeEvidenceItem> items,
            List<CodeEvidenceFact> facts,
            List<CodeEvidenceConstraint> constraints
    ) {
        return new CodeEvidenceIr(items, facts, constraints, List.of(), List.of(), List.of());
    }

    private CodeSearchResult result(String path, String content, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", "observe", "Feature", "observe", "app", null, null, 1,
                10, 30, content, 0.9, metadata);
    }
}
