package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceRankerTest {

    @Test
    void ranksSqlResourceAccessAboveGenericStorageEvidence() {
        CodeEvidenceRanker ranker = new CodeEvidenceRanker(new LearnBotProperties());
        CodeSearchResult storageWriter = result(
                "backend/src/main/java/com/example/repository/GraphRepository.java",
                "method",
                "replaceGraph",
                0.30,
                """
                        void replaceGraph() {
                            jdbc.update(\"INSERT INTO code_graph_nodes (id, node_key) VALUES (?, ?)\");
                            jdbc.update(\"INSERT INTO code_graph_edges (id, source_node_id) VALUES (?, ?)\");
                        }
                        """
        );
        CodeSearchResult genericStorage = result(
                "backend/src/main/java/com/example/service/StorageRetentionService.java",
                "method",
                "cleanupStorage",
                0.52,
                "void cleanupStorage() { vacuumAnalyzeBestEffort(\"code_graph_nodes\"); }"
        );

        List<CodeSearchResult> ranked = ranker.rank(
                "How are code_graph_nodes and code_graph_edges stored during indexing?",
                CodeRagService.CodeQuestionMode.REASONING,
                List.of(genericStorage, storageWriter)
        );

        assertThat(ranked).first().extracting(CodeSearchResult::methodName).isEqualTo("replaceGraph");
        assertThat(ranked.get(0).metadata().get("evidenceRankReason"))
                .asString()
                .contains("resource/table access evidence");
    }

    @Test
    void doesNotBoostLongIdentifierWithoutAccessIntentOrSqlEvidence() {
        CodeEvidenceRanker ranker = new CodeEvidenceRanker(new LearnBotProperties());
        CodeSearchResult directMethod = result(
                "backend/src/main/java/com/example/service/AnswerService.java",
                "method",
                "askPrioritized",
                0.45,
                "void askPrioritized() { buildContext(); generateAnswer(); }"
        );
        CodeSearchResult unrelatedLongIdentifier = result(
                "backend/src/main/java/com/example/service/OtherService.java",
                "method",
                "other",
                0.44,
                "void other() { String marker = \"askPrioritized\"; }"
        );

        List<CodeSearchResult> ranked = ranker.rank(
                "How does askPrioritized generate an answer?",
                CodeRagService.CodeQuestionMode.REASONING,
                List.of(unrelatedLongIdentifier, directMethod)
        );

        assertThat(ranked).first().extracting(CodeSearchResult::methodName).isEqualTo("askPrioritized");
        assertThat(ranked.get(1).metadata().get("evidenceRankReason"))
                .asString()
                .doesNotContain("resource/table access evidence");
    }

    @Test
    void prefersControllerWhoseEndpointMappingMatchesRequestedApiPath() {
        CodeEvidenceRanker ranker = new CodeEvidenceRanker(new LearnBotProperties());
        CodeSearchResult wrongController = result(
                "backend/src/main/java/com/example/web/RagController.java",
                "method",
                "askStream",
                0.72,
                """
                        @RestController
                        @RequestMapping("/api/rag")
                        class RagController {
                            @PostMapping("/ask/stream")
                            Object askStream() { return ragService.ask(); }
                        }
                        """
        );
        CodeSearchResult matchingController = result(
                "backend/src/main/java/com/example/web/CodeController.java",
                "method",
                "ask",
                0.55,
                """
                        @RestController
                        @RequestMapping("/api/code")
                        class CodeController {
                            @PostMapping("/ask")
                            Object ask() { return codeRagService.ask(); }
                        }
                        """
        );

        List<CodeSearchResult> ranked = ranker.rank(
                "How does /api/code/ask flow from Controller to Service?",
                CodeRagService.CodeQuestionMode.CALL_FLOW,
                List.of(wrongController, matchingController)
        );

        assertThat(ranked).first().extracting(CodeSearchResult::filePath)
                .isEqualTo("backend/src/main/java/com/example/web/CodeController.java");
        assertThat(ranked.get(0).metadata().get("evidenceRankReason"))
                .asString()
                .contains("endpoint path aligns with question");
        assertThat(ranked.get(1).metadata().get("evidenceRankReason"))
                .asString()
                .contains("endpoint mismatch");
    }

    @Test
    void deprioritizesTimingHelperOnlyForRuntimeFlowQuestions() {
        CodeEvidenceRanker ranker = new CodeEvidenceRanker(new LearnBotProperties());
        CodeSearchResult timingHelper = result(
                "backend/src/main/java/com/example/service/RagService.java",
                "method",
                "addGraphExpansionMs",
                0.70,
                "void addGraphExpansionMs(long value) { graphExpansionMs += value; }"
        );
        CodeSearchResult implementation = result(
                "backend/src/main/java/com/example/service/CodeSearchService.java",
                "method",
                "expandGraph",
                0.48,
                "List<Result> expandGraph() { return repository.graphRelatedChunks(); }"
        );

        List<CodeSearchResult> flowRanked = ranker.rank(
                "Explain graph expansion flow in the request pipeline",
                CodeRagService.CodeQuestionMode.CALL_FLOW,
                List.of(timingHelper, implementation)
        );
        assertThat(flowRanked).first().extracting(CodeSearchResult::methodName).isEqualTo("expandGraph");

        List<CodeSearchResult> metricRanked = ranker.rank(
                "Where is graph expansion timing metric accumulated?",
                CodeRagService.CodeQuestionMode.REASONING,
                List.of(timingHelper, implementation)
        );
        assertThat(metricRanked).first().extracting(CodeSearchResult::methodName).isEqualTo("addGraphExpansionMs");
    }

    private CodeSearchResult result(String filePath, String chunkType, String methodName, double score, String content) {
        UUID chunkId = UUID.randomUUID();
        return new CodeSearchResult(
                chunkId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "repo",
                filePath,
                chunkType,
                methodName,
                "Example",
                methodName,
                null,
                null,
                null,
                0,
                1,
                20,
                content,
                score,
                Map.of()
        );
    }
}
