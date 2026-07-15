package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.learnbot.service.coderag.answer.CodeAnswerVerification.Disposition;
import static com.learnbot.service.coderag.answer.CodeAnswerVerification.FailureKind;
import static org.assertj.core.api.Assertions.assertThat;

class CodeAnswerVerifierTest {
    @Test
    void acceptsAGroundedAnswerAndReportsCitationCoverage() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier(
                CodeAnswerVerifier.AnswerQualityPolicy.accepting(),
                CodeAnswerVerifier.ExactFactVerifier.accepting());
        CodeSearchResult evidence = result(
                "src/web/CodeController.java",
                "return codeService.askConversational(request);",
                Map.of());

        CodeAnswerVerification verification = verifier.verify(
                "How is the request handled?",
                "The controller delegates the request to askConversational [1].",
                List.of(evidence),
                "stop",
                true);

        assertThat(verification.accepted()).isTrue();
        assertThat(verification.disposition()).isEqualTo(Disposition.ACCEPT);
        assertThat(verification.failureKind()).isEqualTo(FailureKind.NONE);
        assertThat(verification.citationQuality().referencedCount()).isEqualTo(1);
        assertThat(verification.citationQuality().invalidCount()).isZero();
        assertThat(verification.citationQuality().coveragePercent()).isEqualTo(100);
    }

    @Test
    void sendsAnInvalidCitationToRetryAndRetainsCitationDiagnostics() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier();

        CodeAnswerVerification verification = verifier.verify(
                "How is the request handled?",
                "The controller delegates the incoming request to the application service [2].",
                List.of(result("src/web/CodeController.java", "return service.ask();", Map.of())),
                "stop",
                true);

        assertThat(verification.disposition()).isEqualTo(Disposition.RETRY);
        assertThat(verification.failureKind()).isEqualTo(FailureKind.INVALID_CITATION);
        assertThat(verification.reason()).isEqualTo("citation out of range");
        assertThat(verification.citationQuality().invalidCount()).isEqualTo(1);
    }

    @Test
    void usesFallbackWhenARepairableFailureHasNoRetryAvailable() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier();

        CodeAnswerVerification verification = verifier.verify(
                "How is the request handled?",
                "The controller delegates the incoming request to the application service.",
                List.of(result("src/web/CodeController.java", "return service.ask();", Map.of())),
                "stop",
                false);

        assertThat(verification.disposition()).isEqualTo(Disposition.FALLBACK);
        assertThat(verification.failureKind()).isEqualTo(FailureKind.MISSING_CITATION);
    }

    @Test
    void delegatesExactFactValidationAfterGenericChecksPass() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier(
                CodeAnswerVerifier.AnswerQualityPolicy.accepting(),
                (question, answer, evidence) -> "missing exact endpoint route visible in evidence: /api/code/ask");

        CodeAnswerVerification verification = verifier.verify(
                "Which endpoint handles Code RAG?",
                "CodeController handles the Code RAG request through its ask method [1].",
                List.of(result("src/web/CodeController.java", "return service.ask();", Map.of())),
                "stop",
                true);

        assertThat(verification.disposition()).isEqualTo(Disposition.RETRY);
        assertThat(verification.failureKind()).isEqualTo(FailureKind.EXACT_FACT);
        assertThat(verification.reason()).contains("/api/code/ask");
    }

    @Test
    void defaultVerifierPreservesTheLegacyExactEndpointRuleBehindTheBridge() {
        CodeSearchResult endpoint = result(
                "src/web/CodeController.java",
                "return codeService.askConversational(request);",
                Map.of(
                        "endpointRoute", "/api/code/ask",
                        "httpMethod", "POST",
                        "deterministicEndpointBestMatch", true));

        CodeAnswerVerification verification = new CodeAnswerVerifier().verify(
                "Which endpoint handles the Code RAG ask API?",
                "CodeController handles the Code RAG request through its ask method [1].",
                List.of(endpoint),
                "stop",
                true);

        assertThat(verification.failureKind()).isEqualTo(FailureKind.EXACT_FACT);
        assertThat(verification.reason()).contains("/api/code/ask");
    }

    @Test
    void weakLexicalSupportRemainsDiagnosticInsteadOfChangingAcceptance() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier(
                CodeAnswerVerifier.AnswerQualityPolicy.accepting(),
                CodeAnswerVerifier.ExactFactVerifier.accepting());

        CodeAnswerVerification verification = verifier.verify(
                "Explain the behavior",
                "The scheduler publishes a completion event after processing finishes [1].",
                List.of(result("src/store/Repository.java", "database.save(entity);", Map.of())),
                "stop",
                true);

        assertThat(verification.disposition()).isEqualTo(Disposition.ACCEPT);
        assertThat(verification.citationQuality().weakSupportCount()).isEqualTo(1);
        assertThat(verification.citationQuality().summary()).contains("weak lexical support");
    }

    @Test
    void blocksBeforeAnswerValidationWhenTheEvidenceIntegrityGateDeniedGeneration() {
        CodeAnswerVerification verification = new CodeAnswerVerifier().verify(
                "Explain the behavior", "", List.of(), null, true, false);

        assertThat(verification.disposition()).isEqualTo(Disposition.BLOCK);
        assertThat(verification.failureKind()).isEqualTo(FailureKind.GENERATION_BLOCKED);
    }

    @Test
    void convertsVerifierExceptionsIntoABoundedRepairDecision() {
        CodeAnswerVerifier verifier = new CodeAnswerVerifier(
                (answer, evidenceCount, doneReason) -> {
                    throw new IllegalStateException("sensitive details");
                },
                CodeAnswerVerifier.ExactFactVerifier.accepting());

        CodeAnswerVerification verification = verifier.verify(
                "Explain the behavior",
                "The controller delegates the incoming request to the application service [1].",
                List.of(result("src/web/CodeController.java", "return service.ask();", Map.of())),
                "stop",
                false);

        assertThat(verification.disposition()).isEqualTo(Disposition.FALLBACK);
        assertThat(verification.failureKind()).isEqualTo(FailureKind.VERIFIER_ERROR);
        assertThat(verification.reason())
                .contains("IllegalStateException")
                .doesNotContain("sensitive details");
    }

    private CodeSearchResult result(String path, String content, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", "ask", "CodeController", "ask", "app", null, null, 1,
                10, 30, content, 0.9, metadata);
    }
}
