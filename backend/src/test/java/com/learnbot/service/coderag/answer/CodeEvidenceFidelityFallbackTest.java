package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceFidelityFallbackTest {
    @Test
    void preservesMethodNamesRoutesAndLiteralStateTransitionsFromEvidence() {
        CodeSearchResult endpoint = result("src/web/FeatureController.java", "submit",
                "@PostMapping(\"/submit\") void submit() { service.process(); }",
                Map.of("endpointRoute", "/api/feature/submit", "httpMethod", "POST",
                        "deterministicEndpointBestMatch", true));
        CodeSearchResult update = result("src/ui/FeatureView.cs", "UpdateMarker",
                "marker.Visible = false; marker.Location = target; marker.Visible = true;",
                Map.of("llmChecklistGroupRequired", true));

        String answer = CodeEvidenceFidelityFallback.answer(List.of(endpoint, update), "missing exact fact");

        assertThat(answer)
                .contains("/api/feature/submit", "UpdateMarker")
                .contains("marker.Visible = false", "marker.Location = target", "marker.Visible = true")
                .contains("[1]", "[2]");
    }

    private CodeSearchResult result(String path, String method, String content, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Feature", method, "app", null, null, 1,
                10, 25, content, 0.8, metadata);
    }
}
