package com.learnbot.service.coderag.diagnostics;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeEvidenceRanker;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeRagDiagnosticsBuilderTest {

    @Test
    void emitsOnlyUnconditionalDiagnosticsForAMinimalLocateAnswer() {
        CodeEvidenceRanker ranker = mock(CodeEvidenceRanker.class);
        CodeRagDiagnosticsBuilder builder = new CodeRagDiagnosticsBuilder(ranker);
        CodeSearchResult evidence = result("src/main/java/app/Service.java", "method", Map.of("parser", "javaparser"));

        List<String> diagnostics = builder.build(new CodeRagDiagnosticsBuilder.Request(
                CodeQuestionMode.LOCATE,
                List.of(evidence),
                List.of(evidence),
                "Located in Service.run [1].",
                "stop",
                false,
                false,
                false,
                false,
                false,
                false,
                new CodeRagDiagnosticsBuilder.CitationTrace(1, 0, 100, ""),
                CodeRagDiagnosticsBuilder.QualityTrace.empty(),
                null,
                0,
                null,
                null
        ));

        assertThat(diagnostics).hasSize(3);
        assertThat(diagnostics.get(0)).contains("1").doesNotContain("RAG quality trace");
        assertThat(diagnostics.get(1)).isEqualTo(
                "RAG quality trace: answerChars=27, citedReferences=1, invalidCitationRefs=0, "
                        + "citationCoverage=100%, fallback=false, retry=false, continuation=false, doneReason=stop.");
        assertThat(diagnostics.get(2))
                .startsWith("Evidence selection: selected=1, budgetDropped=0")
                .contains("chunkTypes={method=1}")
                .contains("parsers={javaparser=1}")
                .endsWith("llmFollowUp=0.");
    }

    @Test
    void preservesCoreRouteAndConversationDiagnosticOrdering() {
        CodeEvidenceRanker ranker = mock(CodeEvidenceRanker.class);
        when(ranker.summarizeGraph(anyList())).thenReturn(new CodeEvidenceRanker.GraphReliabilitySummary(
                1, 1, 0, 0, Map.of("CALLS", 2)));
        when(ranker.debug()).thenReturn(false);
        CodeRagDiagnosticsBuilder builder = new CodeRagDiagnosticsBuilder(ranker);

        CodeSearchResult method = result(
                "src/main/java/app/Service.java",
                "method",
                Map.of(
                        "parser", "javaparser",
                        "sourceRole", "main",
                        "graphExpanded", true,
                        "evidenceScore", 0.91,
                        "llmFollowUpEvidence", true,
                        "conversationRequired", true
                ));
        CodeSearchResult overview = result(
                "src/main/java/app/RepositorySummary.md",
                "repository_summary",
                Map.of("parser", "summary", "sourceRole", "main"));
        RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true,
                false,
                "need persistence proof",
                List.of("storage"),
                List.of("repository write"),
                List.of("persistence"),
                List.of(),
                List.of(),
                List.of()
        );
        CodeRagDiagnosticsBuilder.RetrievalTrace retrieval = new CodeRagDiagnosticsBuilder.RetrievalTrace(
                new RagPipelineService.EvidenceAssessment(
                        false, 2, 0.91, 2, 0.7, List.of("one claim remains unresolved")),
                new RagPipelineService.QueryPlan(
                        RagPipelineService.Domain.CODE,
                        List.of("service flow", "repository write"),
                        true,
                        true,
                        false,
                        "expanded flow query"),
                new CodeRagDiagnosticsBuilder.DeterministicPlanTrace(
                        "flow", List.of("service flow", "repository write"), false),
                followUpPlan,
                1,
                2,
                2,
                "trace-1",
                "index-1",
                3,
                "NO_EVIDENCE_PROGRESS"
        );
        RagPipelineService.CodeRagRouteDecision routeDecision = new RagPipelineService.CodeRagRouteDecision(
                RagPipelineService.CodeRagRoute.CODE_OVERVIEW_FLOW,
                "flow",
                0.9,
                List.of("service flow"),
                "",
                "",
                "",
                "flow question",
                true,
                false
        );

        List<String> diagnostics = builder.build(new CodeRagDiagnosticsBuilder.Request(
                CodeQuestionMode.CALL_FLOW,
                List.of(method, overview),
                List.of(method, overview),
                "Service.run calls the repository [1].",
                "stop",
                false,
                false,
                false,
                true,
                true,
                true,
                new CodeRagDiagnosticsBuilder.CitationTrace(1, 0, 100, "all claims cited"),
                new CodeRagDiagnosticsBuilder.QualityTrace(true, "LLM answer quality trace: initialFailureReason=none."),
                retrieval,
                2,
                new CodeRagDiagnosticsBuilder.RouteTrace(routeDecision, false),
                new CodeRagDiagnosticsBuilder.ConversationTrace(
                        true, 1, 0, 0, "Explain the flow", "Explain Service.run and repository write")
        ));

        assertThat(diagnostics).hasSize(24);
        assertPrefixesAppearInOrder(diagnostics, List.of(
                "RAG quality trace:",
                "LLM answer quality trace:",
                "Citation support:",
                "Evidence selection:",
                "Code query planner:",
                "Code question mode was classified as CALL_FLOW",
                "RAG pipeline ran 1 LLM-planned Retrieval Iteration(s)",
                "Code evidence Retrieval Iteration planner:",
                "Code RAG trace:",
                "Code query rewrite status:",
                "RAG pipeline used LLM-planned query expansion",
                "Code evidence sufficiency check remained weak:",
                "Code GraphRAG expanded related evidence",
                "Graph evidence:",
                "Top graph edges:",
                "Code evidence was ranked with deterministic evidence scoring",
                "Answer self-check retried generation once",
                "Answer generation reached the model output limit",
                "Streaming answer was kept after self-check",
                "Agentic RAG route:",
                "대화 컨텍스트를 사용했습니다.",
                "후속 질문 검색용 독립 질문을 생성했습니다:",
                "이전 코드 근거를 직접 조회하지 못해"
        ));
        assertThat(diagnostics).anySatisfy(note -> assertThat(note)
                .isEqualTo("Top graph edges: CALLS=2."));
        assertThat(diagnostics).anySatisfy(note -> assertThat(note)
                .contains("route=CODE_OVERVIEW_FLOW")
                .contains("commitFallback=false")
                .contains("reason=flow question."));
    }

    private static void assertPrefixesAppearInOrder(List<String> values, List<String> prefixes) {
        int previous = -1;
        for (String prefix : prefixes) {
            int current = -1;
            for (int index = previous + 1; index < values.size(); index++) {
                if (values.get(index).startsWith(prefix)) {
                    current = index;
                    break;
                }
            }
            assertThat(current)
                    .as("diagnostic prefix %s after index %s in %s", prefix, previous, values)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    private static CodeSearchResult result(String path, String chunkType, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "repo",
                path,
                chunkType,
                "run",
                "Service",
                "run",
                "app",
                null,
                null,
                0,
                1,
                20,
                "void run() {}",
                0.91,
                metadata
        );
    }
}
