package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRagAnswerGateTest {
    @Test
    void blocksGenerationWhenEvidenceIntegrityGateDeniesTheContext() {
        var denied = new CodeEvidenceCoverageGate.Outcome(
                CodeEvidenceCoverageGate.Decision.DENY,
                List.of("evidence comes from multiple index identities"), List.of(), List.of());

        assertThat(CodeRagService.shouldBlockAnswerGeneration(denied, "SATISFIED", null)).isTrue();
    }

    @Test
    void permitsBoundedGenerationFromValidEvidenceAfterAnInvalidPlannerOperation() {
        var partial = new CodeEvidenceCoverageGate.Outcome(
                CodeEvidenceCoverageGate.Decision.PARTIAL,
                List.of("one claim remains unresolved"), List.of("flow"), List.of("claim-1"));

        assertThat(CodeRagService.shouldBlockAnswerGeneration(partial, "INVALID_MISSING_ORIGIN", null)).isFalse();
        assertThat(CodeRagService.shouldBlockAnswerGeneration(partial, "NO_EVIDENCE_PROGRESS", null)).isFalse();
    }

    @Test
    void permitsCompleteValidatedCoverageEvenIfAnUnusedOperationWasInvalid() {
        var full = new CodeEvidenceCoverageGate.Outcome(
                CodeEvidenceCoverageGate.Decision.FULL, List.of(), List.of("flow"), List.of("claim-1"));

        assertThat(CodeRagService.shouldBlockAnswerGeneration(full, "INVALID_OPERAND", null)).isFalse();
    }

    @Test
    void permitsGroundedGenerationWhenPlannerClaimsRemainUnresolved() {
        var discovery = new CodeEvidenceCoverageGate.Outcome(
                CodeEvidenceCoverageGate.Decision.DISCOVERY,
                List.of("controller entry remains unresolved"), List.of("entry"), List.of());
        var claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "entry", "find the entry", List.of(),
                "handler", "receive", "request", "entry is verified", List.of(), List.of("DIRECT_SOURCE"));
        var unresolved = new RagPipelineService.CodeClaimResult(
                "claim-1", "UNRESOLVED", List.of(), "", List.of(), "");
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "missing", List.of("claim-1"), List.of(), List.of(), List.of("entry"),
                List.of(claim), List.of(), List.of(), "hypothesis", 1, "UNRESOLVED",
                List.of(unresolved), "NO_FURTHER_RETRIEVAL");

        assertThat(CodeRagService.shouldBlockAnswerGeneration(
                discovery, "NO_EVIDENCE_PROGRESS", plan)).isFalse();
    }
}
