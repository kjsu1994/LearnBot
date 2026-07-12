package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRagAnswerGateTest {
    @Test
    void blocksNormalGenerationWhenTheRetrievalContractIsInvalidAndCoverageIsIncomplete() {
        var partial = new CodeEvidenceCoverageGate.Outcome(
                CodeEvidenceCoverageGate.Decision.PARTIAL,
                List.of("one claim remains unresolved"), List.of("flow"), List.of("claim-1"));

        assertThat(CodeRagService.shouldBlockAnswerGeneration(partial, "INVALID_MISSING_ORIGIN")).isTrue();
        assertThat(CodeRagService.shouldBlockAnswerGeneration(partial, "NO_EVIDENCE_PROGRESS")).isFalse();
    }

    @Test
    void permitsCompleteValidatedCoverageEvenIfAnUnusedOperationWasInvalid() {
        var full = new CodeEvidenceCoverageGate.Outcome(
                CodeEvidenceCoverageGate.Decision.FULL, List.of(), List.of("flow"), List.of("claim-1"));

        assertThat(CodeRagService.shouldBlockAnswerGeneration(full, "INVALID_OPERAND")).isFalse();
    }
}
