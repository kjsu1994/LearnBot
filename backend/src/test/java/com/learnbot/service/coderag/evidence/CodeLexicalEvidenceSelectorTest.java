package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeLexicalEvidenceSelectorTest {
    @Test
    void prefersTheSubstantiveOverloadMatchingThePlannerClaim() {
        CodeSearchResult wrapper = result("chatResult", 120, 122,
                "return chatResult(systemPrompt, userPrompt, role);");
        CodeSearchResult implementation = result("chatResult", 140, 174,
                "List<Settings> candidates = candidates(role); for (int index = 0; index < candidates.size(); index++) { tryCall(); } ");
        CodeSearchResult unrelated = result("embed", 80, 110, "return embedding;");

        List<CodeSearchResult> ranked = CodeLexicalEvidenceSelector.rank(
                "Determine whether a failed chat call tries another candidate",
                List.of(wrapper, unrelated, implementation), 3);

        assertThat(ranked).extracting(CodeSearchResult::lineStart).startsWith(140);
        assertThat(ranked).allSatisfy(result -> assertThat(result.metadata())
                .containsKeys("retrievalIntentScore", "evidenceRankReason")
                .doesNotContainKey("deterministicLexicalCandidate"));
    }

    private CodeSearchResult result(String method, int start, int end, String content) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/app/Client.java",
                "method", method, "Client", method, "app", null, null, 1,
                start, end, content, 0.8, Map.of());
    }
}
