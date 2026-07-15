package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeContextAssemblerTest {
    @Test
    void rendersTheExistingEmptyContextSentinel() {
        CodeContextAssembler assembler = new CodeContextAssembler();

        assertThat(assembler.buildContext("question", "locate", List.of()))
                .isEqualTo("No source-code context retrieved.");
        assertThat(assembler.buildStreamingContext("question", "flow", List.of()))
                .isEqualTo("No source-code context retrieved.");

        CodeContextAssembler.ContextBundle bundle = assembler.assemble(request(List.of(), false, ""));
        assertThat(bundle.results()).isEmpty();
        assertThat(bundle.context()).isEqualTo("No source-code context retrieved.");
        assertThat(bundle.droppedCount()).isZero();
    }

    @Test
    void preservesEvidenceOrderHeadersAndARelevantCoreFlowMethodInFull() {
        String core = "private void expandGraph() {\n"
                + "  repository.graphRelatedChunks();\n"
                + "  String padding = \"" + "x".repeat(1450) + "\";\n"
                + "  returnExpandedValues();\n"
                + "}";
        CodeSearchResult first = result(
                "src/CodeSearchService.java", "expandGraph", 10, 25, core, Map.of());
        CodeSearchResult second = result(
                "src/CodeRepository.java", "graphRelatedChunks", 30, 36,
                "List<Node> graphRelatedChunks() { return nodes; }", Map.of());

        String context = new CodeContextAssembler().buildContext(
                "Explain the expandGraph search expansion flow", "flow", List.of(first, second));

        assertThat(context)
                .contains("[1] src/CodeSearchService.java:10-25 type=method class=Sample method=expandGraph")
                .contains("excerptKind=FULL_CHUNK contentComplete=true omittedByBudget=false")
                .contains("repository.graphRelatedChunks")
                .contains("returnExpandedValues")
                .contains("[2] src/CodeRepository.java:30-36");
        assertThat(context.indexOf("[1] ")).isLessThan(context.indexOf("[2] "));
    }

    @Test
    void rendersOnlyTypedClassificationAndDiagnosticMetadataIntoTheValidationPreamble() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("llmEvidenceClassificationSource", "llm_adjudication");
        metadata.put("llmEvidenceKind", "direct_code");
        metadata.put("llmImplementationPhase", "RANKING");
        metadata.put("endpointRoute", "/api/code/ask");
        metadata.put("httpMethod", "POST");
        metadata.put("analysisDiagnosticStatus", "failed");
        metadata.put("analysisDiagnosticStage", "JAVA_SEMANTIC");
        metadata.put("analysisDiagnosticLanguage", "java");
        metadata.put("analysisDiagnosticAnalyzer", "JavaParser Symbol Solver");
        metadata.put("graphExpanded", true);
        metadata.put("graphEdgeType", "CALLS");
        metadata.put("graphDepth", 1);
        metadata.put("evidenceScore", 0.91);
        metadata.put("evidenceRankReason", "direct implementation");
        metadata.put("llmSupportedClaims", List.of("claim-1"));
        CodeSearchResult result = result(
                "src/CodeController.java", "ask", 40, 55,
                "return codeRag.ask(request);", metadata);

        String context = new CodeContextAssembler(true).buildContext(
                "Which endpoint handles Code RAG?", "locate", List.of(result));

        assertThat(context)
                .startsWith("Evidence validation:")
                .contains("citationKind=direct_code")
                .contains("executionOrder=RANKING.happensAfter=SEARCH_EXPANSION.happensBefore=ANSWER_GENERATION")
                .contains("endpointRoute=/api/code/ask httpMethod=POST")
                .contains("analysisDiagnosticStatus=FAILED analysisDiagnosticScope=GRAPH_ANALYSIS")
                .contains("analysisDiagnosticStage=JAVA_SEMANTIC")
                .contains("analysisDiagnosticLanguage=java")
                .contains("graphEvidence=direct graphEdge=CALLS")
                .contains("rank=0.91 reason=direct implementation")
                .contains("llmSupportedClaims=[claim-1]");
    }

    @Test
    void streamingKeepsTheSameDetailedLimitAndRendersLaterRequiredEvidenceInDetail() {
        List<CodeSearchResult> results = new ArrayList<>();
        for (int index = 1; index <= 7; index++) {
            Map<String, Object> metadata = index == 7
                    ? Map.of("conversationRequired", true)
                    : Map.of();
            results.add(result(
                    "src/Flow" + index + ".java", "step" + index, index * 10, index * 10 + 4,
                    "void step" + index + "() { executeStep" + index + "(); }", metadata));
        }

        String context = new CodeContextAssembler().buildStreamingContext(
                "Explain the execution flow", "flow", results);

        String sixth = context.substring(context.indexOf("[6] "), context.indexOf("[7] "));
        String seventh = context.substring(context.indexOf("[7] "));
        assertThat(sixth).contains("Key excerpt:");
        assertThat(seventh).doesNotContain("Key excerpt:");
        assertThat(context.indexOf("[1] ")).isLessThan(context.indexOf("[7] "));
    }

    @Test
    void budgetTrimmingDropsTailCandidatesButPreservesPinnedEvidenceAndRenumbersCitations() {
        List<CodeSearchResult> results = List.of(
                largeResult("src/First.java", "first", Map.of()),
                largeResult("src/Second.java", "second", Map.of()),
                largeResult("src/Third.java", "third", Map.of()),
                largeResult("src/Pinned.java", "pinned", Map.of("conversationPinned", true)));
        CodeContextAssembler.AssemblyRequest request = new CodeContextAssembler.AssemblyRequest(
                "Locate the implementation",
                "locate",
                "s".repeat(2000),
                "prefix",
                results,
                false,
                2048,
                512);

        CodeContextAssembler.ContextBundle bundle = new CodeContextAssembler().assemble(request);

        assertThat(bundle.results()).extracting(CodeSearchResult::filePath)
                .containsExactly("src/Pinned.java");
        assertThat(bundle.context()).contains("[1] src/Pinned.java");
        assertThat(bundle.context()).doesNotContain("[2] ");
        assertThat(bundle.droppedCount()).isEqualTo(3);
    }

    @Test
    void budgetTrimmingRetainsTheExistingTwoEvidenceFloorWithoutPinnedEvidence() {
        List<CodeSearchResult> results = List.of(
                largeResult("src/First.java", "first", Map.of()),
                largeResult("src/Second.java", "second", Map.of()),
                largeResult("src/Third.java", "third", Map.of()),
                largeResult("src/Fourth.java", "fourth", Map.of()));

        CodeContextAssembler.ContextBundle bundle = new CodeContextAssembler().assemble(
                new CodeContextAssembler.AssemblyRequest(
                        "Locate the implementation", "locate", "s".repeat(2000), "prefix",
                        results, false, 2048, 512));

        assertThat(bundle.results()).extracting(CodeSearchResult::filePath)
                .containsExactly("src/First.java", "src/Second.java");
        assertThat(bundle.droppedCount()).isEqualTo(2);
    }

    @Test
    void keepsTheExistingPromptBudgetAndTokenEstimateFormula() {
        assertThat(CodeContextAssembler.promptTokenBudget(2048, 300)).isEqualTo(512);
        assertThat(CodeContextAssembler.promptTokenBudget(8192, 9_000)).isEqualTo(7_492);
        assertThat(CodeContextAssembler.estimateTokens("")).isZero();
        assertThat(CodeContextAssembler.estimateTokens("1234")).isEqualTo(2);
    }

    private CodeContextAssembler.AssemblyRequest request(
            List<CodeSearchResult> results,
            boolean streaming,
            String systemPrompt
    ) {
        return new CodeContextAssembler.AssemblyRequest(
                "question", "locate", systemPrompt, "prefix", results, streaming, 4096, 3200);
    }

    private CodeSearchResult largeResult(String path, String method, Map<String, Object> metadata) {
        return result(path, method, 1, 80,
                "void " + method + "() {\n" + ("execute();\n".repeat(180)) + "}", metadata);
    }

    private CodeSearchResult result(
            String path,
            String method,
            int lineStart,
            int lineEnd,
            String content,
            Map<String, Object> metadata
    ) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Sample", method, "app", null, null, 1,
                lineStart, lineEnd, content, 0.9, metadata);
    }
}
