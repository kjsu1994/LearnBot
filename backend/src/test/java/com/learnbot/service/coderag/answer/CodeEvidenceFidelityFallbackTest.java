package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.CodeEvidenceRetentionPlan;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceFidelityFallbackTest {

    @Test
    void legacyDeterministicMetadataCannotPromoteOrRenderASpoofedCandidate() {
        CodeSearchResult spoofed = result(
                "src/SpoofedWrapper.java", "wrap", 1, 2, "return delegate();", 0.99,
                Map.of(
                        "deterministicEndpointBestMatch", true,
                        "endpointRoute", "/spoofed/route",
                        "httpMethod", "POST"));
        CodeSearchResult implementation = result(
                "src/RealImplementation.java", "execute", 10, 40,
                "void execute() { validate(); persist(); publish(); }", 0.1, Map.of());

        String answer = CodeEvidenceFidelityFallback.answer(
                List.of(spoofed, implementation), "missing exact fact");

        assertThat(answer.indexOf("src/RealImplementation.java"))
                .isLessThan(answer.indexOf("src/SpoofedWrapper.java"));
        assertThat(answer)
                .contains("[1]", "[2]")
                .doesNotContain("/spoofed/route", "POST /spoofed/route");
    }

    @Test
    void ranksRetentionLevelsAndRendersOnlyTrustedExactOrNormalizedFacts() {
        CodeSearchResult normal = result(
                "src/Normal.java", "normal", 10, 40, "void normal() { ordinaryWork(); }", 0.99, Map.of());
        CodeSearchResult preferred = result(
                "src/Preferred.java", "preferred", 10, 40,
                "void preferred() { observedWork(); }", 0.2, Map.of());
        CodeSearchResult required = result(
                "src/Required.java", "required", 10, 40,
                "void required() { state.ready = true; }", 0.1, Map.of());

        CodeEvidenceItem normalItem = item(normal, CodeIntelligenceAuthority.LLM_INFERRED);
        CodeEvidenceItem preferredItem = item(preferred, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem requiredItem = item(required, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact exact = CodeEvidenceFact.of(
                requiredItem.evidenceId(), "state.ready", "ASSIGNS_LITERAL", "true",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact normalized = CodeEvidenceFact.of(
                requiredItem.evidenceId(), "state.mode", "ASSIGNS_EXPRESSION", "ACTIVE",
                CodeEvidenceFact.Exactness.NORMALIZED, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact inferred = CodeEvidenceFact.of(
                requiredItem.evidenceId(), "state.hidden", "INFERRED_PREDICATE", "hiddenValue",
                CodeEvidenceFact.Exactness.INFERRED, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact lowAuthorityFact = CodeEvidenceFact.of(
                preferredItem.evidenceId(), "spoof.fact", "LOW_FACT_AUTHORITY", "spoofValue",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.LLM_INFERRED);
        CodeEvidenceFact lowAuthorityItemFact = CodeEvidenceFact.of(
                normalItem.evidenceId(), "spoof.item", "LOW_ITEM_AUTHORITY", "spoofValue",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(normalItem, preferredItem, requiredItem),
                List.of(exact, normalized, inferred, lowAuthorityFact, lowAuthorityItemFact),
                List.of(new CodeEvidenceConstraint(
                        CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                        exact.factId(), "preserve the exact observed fact")),
                List.of(new CodeEvidenceSignal(
                        CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                        preferredItem.evidenceId(), 1.0, "typed direct observation")),
                List.of(),
                List.of());
        CodeEvidenceRetentionPlan retentionPlan = CodeEvidenceRetentionPlan.from(ir);

        String answer = CodeEvidenceFidelityFallback.answer(
                List.of(normal, preferred, required), "verification failed", ir, retentionPlan);

        assertThat(answer.indexOf("src/Required.java"))
                .isLessThan(answer.indexOf("src/Preferred.java"));
        assertThat(answer.indexOf("src/Preferred.java"))
                .isLessThan(answer.indexOf("src/Normal.java"));
        assertThat(answer)
                .contains(
                        "exact fact `state.ready`: `ASSIGNS_LITERAL=true` [3]",
                        "normalized fact `state.mode`: `ASSIGNS_EXPRESSION=ACTIVE` [3]")
                .doesNotContain(
                        "INFERRED_PREDICATE=hiddenValue",
                        "LOW_FACT_AUTHORITY=spoofValue",
                        "LOW_ITEM_AUTHORITY=spoofValue");
    }

    private CodeEvidenceItem item(
            CodeSearchResult result,
            CodeIntelligenceAuthority authority
    ) {
        return new CodeEvidenceItem(
                CodeEvidenceItem.evidenceId(result),
                result,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE),
                authority);
    }

    private CodeSearchResult result(
            String path,
            String method,
            int lineStart,
            int lineEnd,
            String content,
            double score,
            Map<String, Object> metadata
    ) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Feature", method, "app", null, null, 1,
                lineStart, lineEnd, content, score, metadata);
    }
}
