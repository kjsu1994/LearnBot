package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceSelectionPolicyTest {
    @Test
    void retainsAnExactEndpointSeedOutsideTheScoreOnlyCut() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        ranked.add(result("src/First.java", Map.of()));
        ranked.add(result("src/Second.java", Map.of()));
        CodeSearchResult endpoint = result("src/ApiController.java", Map.of("deterministicEndpointEvidence", true));
        ranked.add(endpoint);

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(ranked, 2);

        assertThat(selected).hasSize(2).contains(endpoint);
    }

    @Test
    void keepsAllProvenanceRequiredEvidenceWhenItExceedsTheScoreCap() {
        CodeSearchResult endpoint = result("src/ApiController.java", Map.of("deterministicEndpointEvidence", true));
        CodeSearchResult validated = result("src/Service.java", Map.of("llmValidatedEvidence", true));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(List.of(endpoint, validated), 1);

        assertThat(selected).containsExactly(endpoint, validated);
    }

    private CodeSearchResult result(String path, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", "test", "Test", "test", "app", null, null, 1,
                1, 20, "void test() {}", 0.8, metadata);
    }
}
