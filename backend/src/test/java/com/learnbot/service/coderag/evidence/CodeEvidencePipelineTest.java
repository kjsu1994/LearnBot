package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.extractor.AssignmentEvidenceExtractor;
import com.learnbot.service.coderag.evidence.extractor.EvidenceExtractorRegistry;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidencePipelineTest {
    @Test
    void accumulationIsIdempotentAcrossRepeatedStages() {
        CodeEvidenceAccumulator accumulator = new CodeEvidenceAccumulator(
                new EvidenceExtractorRegistry(List.of(new AssignmentEvidenceExtractor())));
        CodeEvidenceExtractionContext context = new CodeEvidenceExtractionContext(
                "How does the state change?", EvidenceExtractionStage.PRE_ANSWER,
                List.of(result("state.ready = false;\nstate.ready = true;")));

        CodeEvidenceAccumulator.Accumulation first = accumulator.accumulate(context);
        CodeEvidenceAccumulator.Accumulation second = accumulator.accumulate(first.accumulated(), context);

        assertThat(first.addedEvidenceItems()).isEqualTo(1);
        assertThat(first.addedFacts()).isGreaterThanOrEqualTo(2);
        assertThat(second.addedEvidenceItems()).isZero();
        assertThat(second.addedFacts()).isZero();
        assertThat(second.accumulated().facts()).isEqualTo(first.accumulated().facts());
    }

    @Test
    void adjudicationKeepsNavigationOnlyHandlesOutOfDirectProof() {
        CodeSearchResult source = result("return next();");
        CodeEvidenceItem item = new CodeEvidenceItem(
                CodeEvidenceItem.evidenceId(source), source,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE), CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(item), List.of(),
                List.of(new CodeEvidenceConstraint(
                        CodeEvidenceConstraint.Type.NAVIGATION_ONLY, "navigation:one", "locator only")),
                List.of(), List.of(), List.of());

        CodeEvidenceAdjudicator.Adjudication adjudication = new CodeEvidenceAdjudicator().adjudicate(ir);

        assertThat(adjudication.constraintsSatisfied()).isTrue();
        assertThat(adjudication.directProofEvidenceIds()).containsExactly(item.evidenceId());
        assertThat(adjudication.navigationOnlyHandleIds()).containsExactly("navigation:one");
    }

    private CodeSearchResult result(String content) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/State.java",
                "method", "change", "State", "change", "app", null, null, 1,
                10, 20, content, 0.9,
                Map.of("indexVersion", "v1", "codeIntelligenceAuthority", "SYNTAX"));
    }
}
