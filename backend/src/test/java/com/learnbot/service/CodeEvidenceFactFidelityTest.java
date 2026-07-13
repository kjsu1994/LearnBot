package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceFactFidelityTest {
    @Test
    void selectsTheQuestionRelevantEndpointAndDetectsAnOmittedRoute() {
        CodeSearchResult generic = result("src/web/GeneralController.java", "ask", 10, 20,
                "return generalService.ask();", Map.of("endpointRoute", "/api/general/ask", "httpMethod", "POST"));
        CodeSearchResult code = result("src/web/CodeController.java", "ask", 30, 45,
                "return codeService.askConversational();", Map.of("endpointRoute", "/api/code/ask", "httpMethod", "POST",
                        "deterministicEndpointBestMatch", true));

        assertThat(CodeEvidenceFactFidelity.missingReason(
                "Which controller handles the Code RAG ask API?", "CodeController.ask handles it [2].", List.of(generic, code)))
                .contains("/api/code/ask");
        assertThat(CodeEvidenceFactFidelity.promptFacts(
                "Which controller handles the Code RAG ask API?", List.of(generic, code)))
                .contains("POST /api/code/ask");
    }

    @Test
    void doesNotPromoteAnArbitrarySurvivingEndpointToAnExactFact() {
        CodeSearchResult unrelated = result("src/web/GeneralController.java", "ask", 10, 20,
                "return generalService.ask();", Map.of("endpointRoute", "/api/general/ask", "httpMethod", "POST"));

        assertThat(CodeEvidenceFactFidelity.missingReason(
                "Which controller handles the Code RAG ask API?", "The selected evidence is insufficient.", List.of(unrelated)))
                .isNull();
        assertThat(CodeEvidenceFactFidelity.promptFacts(
                "Which controller handles the Code RAG ask API?", List.of(unrelated)))
                .doesNotContain("/api/general/ask");
    }

    @Test
    void detectsAnIncompleteLiteralStateTransitionWithoutProjectSpecificNames() {
        CodeSearchResult update = result("src/ui/View.cs", "Refresh", 10, 20,
                "10: widget.Visible = false;\n11: widget.Location = next;\n12: widget.Visible = true;", Map.of());

        assertThat(CodeEvidenceFactFidelity.missingReason(
                "Explain the UI update flow", "It sets widget.Visible = false before moving it [1].", List.of(update)))
                .contains("widget.Visible = true;");
        assertThat(CodeEvidenceFactFidelity.missingReason(
                "Explain the UI update flow",
                "It sets widget.Visible = false, moves it, then widget.Visible = true [1].", List.of(update)))
                .isNull();
    }

    private CodeSearchResult result(String path, String method, int start, int end, String content, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Controller", method, "app", null, null, 1,
                start, end, content, 0.8, metadata);
    }
}
