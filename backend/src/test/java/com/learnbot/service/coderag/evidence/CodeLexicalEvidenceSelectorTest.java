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

    @Test
    void auxiliaryPlannerSymbolCannotOverrideStrongerSemanticEvidenceByNamingItself() {
        CodeSearchResult semantic = result(
                "processModern", 20, 40, "void processModern() { executeCurrentFlow(); }");
        CodeSearchResult hypothesized = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/app/Client.java",
                "method", "processLegacy", "Client", "processLegacy", "app", null, null, 1,
                60, 80, "void processLegacy() { executeFallbackFlow(); }", 0.7, Map.of());

        List<CodeSearchResult> ranked = CodeLexicalEvidenceSelector.rank(
                "처리 흐름을 설명해줘",
                "processLegacy process modern flow",
                List.of(hypothesized, semantic), 2);

        assertThat(ranked).extracting(CodeSearchResult::methodName)
                .startsWith("processModern");
    }

    @Test
    void implementationLengthDoesNotBreakATieWithoutAnyLexicalMatch() {
        CodeSearchResult concise = result("accept", 10, 12, "return value;");
        CodeSearchResult longUnrelated = result(
                "broadcast", 30, 180, "void broadcast() { performManyUnrelatedSteps(); }");

        List<CodeSearchResult> ranked = CodeLexicalEvidenceSelector.rank(
                "대상 동작을 설명해줘", List.of(longUnrelated, concise), 2);

        assertThat(ranked).extracting(CodeSearchResult::methodName)
                .startsWith("accept");
    }

    private CodeSearchResult result(String method, int start, int end, String content) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/app/Client.java",
                "method", method, "Client", method, "app", null, null, 1,
                start, end, content, 0.8, Map.of());
    }
}
