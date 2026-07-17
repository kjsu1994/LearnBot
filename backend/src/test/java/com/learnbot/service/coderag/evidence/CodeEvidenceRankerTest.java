package com.learnbot.service.coderag.evidence;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceRankerTest {

    @Test
    void legacyQuestionSynonymsDoNotAddEvidenceScore() {
        CodeEvidenceRanker ranker = ranker();

        assertNoExpansion(ranker, "login", "auth authentication signin");
        assertNoExpansion(ranker, "index", "repository chunk embedding");
        assertNoExpansion(ranker, "admin", "role authority");
    }

    @Test
    void incidentalToolAndAnnotationNamesDoNotAddScoreOrFlowOrder() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult decorated = result(
                "src/ZDecoratedHandler.java", "method", "traceNode", null, null, 0.4,
                "trace request flow @ArbitraryMarker(\"item\") parser marker",
                Map.of("parser", "custom_parser"));
        CodeSearchResult plain = result(
                "src/APlain.java", "method", "traceNode", null, null, 0.4,
                "trace request flow ordinary marker", Map.of());

        assertThat(score(ranker, "trace request flow", CodeQuestionMode.CALL_FLOW, decorated))
                .isEqualTo(score(ranker, "trace request flow", CodeQuestionMode.CALL_FLOW, plain));
        assertThat(ranker.flowRank(decorated)).isEqualTo(Integer.MAX_VALUE);
        assertThat(ranker.flowRank(plain)).isEqualTo(Integer.MAX_VALUE);
        assertThat(ranker.rank("trace request flow", CodeQuestionMode.CALL_FLOW, List.of(decorated))
                .get(0).metadata().get("evidenceRankReason").toString())
                .doesNotContain("endpoint");
    }

    @Test
    void sqlSyntaxDoesNotCreateAQuestionSpecificBoost() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult sql = result(
                "src/ZSqlNamed.java", "method", "inspect", null, null, 0.4,
                "storage ledger SELECT value FROM facts; INSERT INTO facts VALUES (?)", Map.of());
        CodeSearchResult plain = result(
                "src/APlain.java", "method", "inspect", null, null, 0.4,
                "storage ledger ordinary operation", Map.of());

        assertThat(score(ranker, "storage ledger", CodeQuestionMode.REASONING, sql))
                .isEqualTo(score(ranker, "storage ledger", CodeQuestionMode.REASONING, plain));
    }

    @Test
    void flowRankUsesOnlyTypedNonNegativeIntegralMetadata() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult ordered = candidate("src/Z.java", Map.of("executionOrder", 0));
        CodeSearchResult depth = candidate("src/Y.java", Map.of("graphDepth", 2));
        CodeSearchResult stringValue = candidate("src/X.java",
                Map.of("executionOrder", "0", "graphDepth", "1"));
        CodeSearchResult fractional = candidate("src/W.java", Map.of("executionOrder", 1.5));

        assertThat(ranker.flowRank(ordered)).isZero();
        assertThat(ranker.flowRank(depth)).isEqualTo(2);
        assertThat(ranker.flowRank(stringValue)).isEqualTo(Integer.MAX_VALUE);
        assertThat(ranker.flowRank(fractional)).isEqualTo(Integer.MAX_VALUE);
        assertThat(score(ranker, "trace delta", CodeQuestionMode.CALL_FLOW, ordered))
                .isGreaterThan(score(ranker, "trace delta", CodeQuestionMode.CALL_FLOW, depth));
        assertThat(score(ranker, "trace delta", CodeQuestionMode.CALL_FLOW, depth))
                .isGreaterThan(score(ranker, "trace delta", CodeQuestionMode.CALL_FLOW, stringValue));
    }

    @Test
    void genericGraphSignalsAndAuthorityIncreaseScoreWithoutRelationNameWeighting() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult plain = candidate("src/Plain.java", Map.of());
        CodeSearchResult parserNamed = candidate("src/Parser.java", Map.of("parser", "custom_parser"));
        CodeSearchResult syntax = candidate("src/Syntax.java", Map.of("codeIntelligenceAuthority", "SYNTAX"));
        CodeSearchResult compiler = candidate("src/Compiler.java", Map.of("codeIntelligenceAuthority", "COMPILER_SEMANTIC"));
        CodeSearchResult graphAlpha = candidate("src/GraphAlpha.java", Map.of(
                "graphEdgeType", "RELATION_ALPHA",
                "graphDepth", 1,
                "graphPathScore", 0.9,
                "graphEvidenceKind", "direct",
                "codeIntelligenceAuthority", "COMPILER_SEMANTIC"));
        CodeSearchResult graphBeta = candidate("src/GraphBeta.java", Map.of(
                "graphEdgeType", "RELATION_BETA",
                "graphDepth", 1,
                "graphPathScore", 0.9,
                "graphEvidenceKind", "direct",
                "codeIntelligenceAuthority", "COMPILER_SEMANTIC"));
        CodeSearchResult weakGraph = candidate("src/WeakGraph.java", Map.of(
                "graphEdgeType", "RELATION_GAMMA",
                "graphDepth", 3,
                "graphPathScore", 0.2,
                "graphEvidenceKind", "candidate"));

        double plainScore = score(ranker, "trace delta", CodeQuestionMode.REASONING, plain);
        assertThat(score(ranker, "trace delta", CodeQuestionMode.REASONING, parserNamed)).isEqualTo(plainScore);
        assertThat(score(ranker, "trace delta", CodeQuestionMode.REASONING, syntax)).isGreaterThan(plainScore);
        assertThat(score(ranker, "trace delta", CodeQuestionMode.REASONING, compiler))
                .isGreaterThan(score(ranker, "trace delta", CodeQuestionMode.REASONING, syntax));
        assertThat(score(ranker, "trace delta", CodeQuestionMode.REASONING, graphAlpha))
                .isEqualTo(score(ranker, "trace delta", CodeQuestionMode.REASONING, graphBeta))
                .isGreaterThan(score(ranker, "trace delta", CodeQuestionMode.REASONING, weakGraph))
                .isGreaterThan(plainScore);
        assertThat(ranker.rank("trace delta", CodeQuestionMode.REASONING, List.of(graphAlpha)).get(0)
                .metadata().get("graphReliability")).isEqualTo("strong");
        assertThat(ranker.rank("trace delta", CodeQuestionMode.REASONING, List.of(graphAlpha)).get(0)
                .metadata().get("evidenceRankReason").toString())
                .contains("graph evidence")
                .doesNotContain("RELATION_ALPHA");
    }

    @Test
    void sourceRolePolicyDoesNotChangeWhenQuestionMentionsTests() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult testEvidence = candidate("src/Fixture.java", Map.of("sourceRole", "test"));
        CodeSearchResult mainEvidence = candidate("src/Fixture.java", Map.of("sourceRole", "main"));

        assertThat(score(ranker, "trace tests", CodeQuestionMode.REASONING, testEvidence))
                .isEqualTo(score(ranker, "trace production", CodeQuestionMode.REASONING, testEvidence));
        assertThat(score(ranker, "trace delta", CodeQuestionMode.REASONING, mainEvidence))
                .isGreaterThan(score(ranker, "trace delta", CodeQuestionMode.REASONING, testEvidence));
    }

    @Test
    void untypedPresentationChunkNamesAreNeutralButObservedIdentityIsUseful() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult presentationOnly = result(
                "src/ZPresentationView.data", "presentation_control", null, null, null, 0.4,
                "trace interaction", Map.of());
        CodeSearchResult plain = result(
                "src/APlain.txt", "opaque", null, null, null, 0.4,
                "trace interaction", Map.of());
        CodeSearchResult observed = result(
                "src/Observed.txt", "opaque", null, "ActionControl", "Activated", 0.4,
                "trace interaction", Map.of());

        double plainScore = score(ranker, "trace interaction", CodeQuestionMode.UI_EVENT, plain);
        assertThat(score(ranker, "trace interaction", CodeQuestionMode.UI_EVENT, presentationOnly)).isEqualTo(plainScore);
        assertThat(score(ranker, "trace interaction", CodeQuestionMode.UI_EVENT, observed)).isGreaterThan(plainScore);
    }

    @Test
    void relationNamesDoNotChangeStableFileOrdering() {
        CodeEvidenceRanker ranker = ranker();
        CodeSearchResult z = candidate("src/Zeta.java", Map.of("graphEdgeType", "RELATION_ZETA"));
        CodeSearchResult a = candidate("src/Alpha.java", Map.of("graphEdgeType", "RELATION_ALPHA"));

        assertThat(score(ranker, "trace delta", CodeQuestionMode.REASONING, z))
                .isEqualTo(score(ranker, "trace delta", CodeQuestionMode.REASONING, a));
        assertThat(ranker.rank("trace delta", CodeQuestionMode.REASONING, List.of(z, a)))
                .extracting(CodeSearchResult::filePath)
                .containsExactly("src/Alpha.java", "src/Zeta.java");
    }

    private void assertNoExpansion(CodeEvidenceRanker ranker, String question, String legacyTerms) {
        CodeSearchResult legacyNamed = result(
                "src/ZLegacy.java", "method", "inspect", null, null, 0.4, legacyTerms, Map.of());
        CodeSearchResult neutral = result(
                "src/APlain.java", "method", "inspect", null, null, 0.4, "unrelated tokens", Map.of());
        assertThat(score(ranker, question, CodeQuestionMode.REASONING, legacyNamed))
                .isEqualTo(score(ranker, question, CodeQuestionMode.REASONING, neutral));
    }

    private CodeEvidenceRanker ranker() {
        return new CodeEvidenceRanker(new LearnBotProperties());
    }

    private double score(CodeEvidenceRanker ranker, String question, CodeQuestionMode mode, CodeSearchResult result) {
        return ranker.score(ranker.rank(question, mode, List.of(result)).get(0));
    }

    private CodeSearchResult candidate(String filePath, Map<String, Object> metadata) {
        return result(filePath, "method", "traceNode", null, null, 0.4, "trace delta", metadata);
    }

    private CodeSearchResult result(
            String filePath,
            String chunkType,
            String symbol,
            String control,
            String event,
            double searchScore,
            String content,
            Map<String, Object> metadata
    ) {
        Map<String, Object> fullMetadata = new LinkedHashMap<>();
        fullMetadata.put("sourceRole", "main");
        fullMetadata.putAll(metadata);
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", filePath,
                chunkType, symbol, symbol == null ? null : "Example", symbol, null, control, event,
                0, 1, 20, content, searchScore, Map.copyOf(fullMetadata));
    }
}
