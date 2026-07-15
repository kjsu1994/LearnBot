package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeObservedNavigationTest {
    @Test
    void extractsExplicitCallTargetsAndReferencedTypesWithoutKnowingTheProject() {
        CodeSearchResult result = result("ModelReply reply = modelClient.chatResult(prompt);\n"
                + "return repository.graphRelatedChunks(ids);");

        assertThat(CodeObservedNavigation.identifiers("model fallback graph traversal", List.of(result), 12))
                .contains("chatResult", "ModelReply", "graphRelatedChunks");
    }

    @Test
    void ignoresCapitalizedPromptWordsAndRetainsQualifiedClientTypesWithinABoundedSlate() {
        CodeSearchResult result = result("Always Explain Important Code Details.\n"
                + "OllamaClient.ChatResult reply = clientCall(prompt);\n"
                + "routeDecision.route(); retrieval.results(); contextBundle.context();");

        assertThat(CodeObservedNavigation.identifiers("model client failure", List.of(result), 4))
                .contains("OllamaClient")
                .doesNotContain("Always", "Explain", "Important");
    }

    private CodeSearchResult result(String content) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Service.java",
                "method", "run", "Service", "run", "app", null, null, 1,
                1, 20, content, 0.9, Map.of());
    }
}
