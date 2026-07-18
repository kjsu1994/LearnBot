package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                "Describe expandGraph", "method", List.of(first, second));

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
                "src/TaskController.java", "handle", 40, 55,
                "return processor.handle(request);", metadata);

        String context = new CodeContextAssembler(true).buildContext(
                "Which method handles the submitted task?", "locate", List.of(result));

        assertThat(context)
                .startsWith("Evidence validation:")
                .contains("classification metadata describes what each excerpt can directly support")
                .contains("citationKind=direct_code")
                .doesNotContain(
                        "endpointRoute=", "httpMethod=", "executionOrder=", "RANKING",
                        "SEARCH_EXPANSION", "ANSWER_GENERATION", "GRAPH_STORAGE", "graph persistence",
                        "Code RAG", "LearnBot")
                .contains("analysisDiagnosticStatus=FAILED analysisDiagnosticScope=GRAPH_ANALYSIS")
                .contains("analysisDiagnosticStage=JAVA_SEMANTIC")
                .contains("analysisDiagnosticLanguage=java")
                .contains("Diagnostic metadata reports analysis scope, stage, language, status, and authority")
                .contains("graphEvidence=direct graphEdge=CALLS")
                .contains("rank=0.91 reason=direct implementation")
                .contains("llmSupportedClaims=[claim-1]");
    }

    @Test
    void rendersGenericCodeIntelligenceDiagnosticMetadataForAnUnknownLanguage() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisDiagnosticStatus", "partial");
        metadata.put("analysisDiagnosticStage", "symbol resolution");
        metadata.put("codeIntelligenceLanguage", "python");
        metadata.put("codeIntelligenceAnalyzer", "Acme SCIP\nCustom Analyzer");
        metadata.put("codeIntelligenceAuthority", "SCIP_SEMANTIC");
        CodeSearchResult result = result(
                "src/worker.py", "run", 10, 18,
                "def run():\n    return resolve_symbol()", metadata);

        String context = new CodeContextAssembler().buildContext(
                "How was this symbol analyzed?", "method", List.of(result));

        assertThat(context)
                .startsWith("Evidence validation:")
                .contains("analysisDiagnosticStatus=PARTIAL analysisDiagnosticScope=GRAPH_ANALYSIS")
                .contains("analysisDiagnosticStage=SYMBOL_RESOLUTION")
                .contains("analysisDiagnosticLanguage=python")
                .contains("analysisDiagnosticAnalyzer=Acme SCIP Custom Analyzer")
                .contains("analysisDiagnosticAuthority=SCIP_SEMANTIC");
    }

    @Test
    void sourceNamesAndLegacyParserMetadataDoNotSynthesizeDiagnosticsWithoutAStatus() {
        CodeSearchResult javaSource = result(
                "src/RoslynBridge.java", "inspect", 10, 18,
                "void inspect() { analyzeRoslynWpfXaml(); }",
                Map.of("language", "java", "parser", "javaparser"));
        CodeSearchResult csharpSource = result(
                "src/JavaParserBridge.cs", "inspect", 20, 28,
                "void Inspect() { AnalyzeJavaParserAndWinForms(); }",
                Map.of("language", "csharp", "analyzer", "Roslyn"));

        String context = new CodeContextAssembler().buildContext(
                "Explain the analyzer bridges", "method", List.of(javaSource, csharpSource));

        assertThat(context)
                .doesNotStartWith("Evidence validation:")
                .doesNotContain(
                        "analysisDiagnosticStatus=",
                        "analysisDiagnosticScope=",
                        "analysisDiagnosticStage=",
                        "analysisDiagnosticLanguage=",
                        "analysisDiagnosticAnalyzer=",
                        "analysisDiagnosticAuthority=");
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
    void budgetTrimmingPreservesTypedRequiredEvidenceAtTheFrontAndTail() {
        CodeSearchResult first = largeResult("src/First.java", "first", Map.of());
        CodeSearchResult requiredFront = largeResult("src/RequiredFront.java", "requiredFront", Map.of());
        CodeSearchResult middle = largeResult("src/Middle.java", "middle", Map.of());
        CodeSearchResult requiredTail = largeResult("src/RequiredTail.java", "requiredTail", Map.of());
        List<CodeSearchResult> results = List.of(first, requiredFront, middle, requiredTail);

        CodeContextAssembler.ContextBundle bundle = new CodeContextAssembler().assemble(
                new CodeContextAssembler.AssemblyRequest(
                        "Locate the implementation", "locate", "s".repeat(2000), "prefix",
                        results, false, 2048, 512,
                        Set.of(CodeEvidenceId.from(requiredFront), CodeEvidenceId.from(requiredTail))));

        assertThat(bundle.results()).extracting(CodeSearchResult::filePath)
                .containsExactly("src/RequiredFront.java", "src/RequiredTail.java");
        assertThat(bundle.context())
                .contains("[1] src/RequiredFront.java", "[2] src/RequiredTail.java")
                .doesNotContain("src/First.java", "src/Middle.java");
        assertThat(bundle.droppedCount()).isEqualTo(2);
    }

    @Test
    void deterministicEndpointFlagsCannotSpoofRequiredContextRetention() {
        CodeSearchResult first = largeResult("src/First.java", "first", Map.of());
        CodeSearchResult second = largeResult("src/Second.java", "second", Map.of());
        CodeSearchResult spoofed = largeResult("src/Spoofed.java", "spoofed", Map.of(
                "deterministicEndpointEvidence", true,
                "deterministicEndpointBestMatch", true));
        CodeSearchResult tail = largeResult("src/Tail.java", "tail", Map.of());

        CodeContextAssembler.ContextBundle bundle = new CodeContextAssembler().assemble(
                new CodeContextAssembler.AssemblyRequest(
                        "Locate the implementation", "locate", "s".repeat(2000), "prefix",
                        List.of(first, second, spoofed, tail), false, 2048, 512));

        assertThat(bundle.results()).extracting(CodeSearchResult::filePath)
                .containsExactly("src/First.java", "src/Second.java")
                .doesNotContain("src/Spoofed.java");
        assertThat(bundle.context()).doesNotContain("src/Spoofed.java");
        assertThat(bundle.droppedCount()).isEqualTo(2);
    }

    @Test
    void bundleResultsContainOnlyTheExcerptRenderedUnderThePromptBudget() {
        String content = "visible.settings.currentValue = provider.resolveValue(user);\n"
                + "visible currentValue configuration context\n".repeat(160)
                + "internal.secret.resultValue = forbiddenSource.compute(hiddenInput);";
        CodeSearchResult source = result(
                "src/VisibleConfiguration.java", "configureVisible", 10, 180, content, Map.of());

        CodeContextAssembler.ContextBundle bundle = new CodeContextAssembler().assemble(
                new CodeContextAssembler.AssemblyRequest(
                        "How does configureVisible assign visible currentValue?",
                        "method",
                        "system prompt",
                        "question prefix",
                        List.of(source),
                        false,
                        2048,
                        512));

        assertThat(bundle.results()).hasSize(1);
        CodeSearchResult rendered = bundle.results().get(0);
        assertThat(rendered.content())
                .contains("visible.settings.currentValue = provider.resolveValue(user);")
                .doesNotContain("internal.secret.resultValue");
        assertThat(rendered.lineStart()).isEqualTo(10);
        assertThat(rendered.lineEnd()).isLessThan(180);
        assertThat(CodeEvidenceId.from(rendered)).isEqualTo(CodeEvidenceId.from(source));
        assertThat(rendered.metadata())
                .containsEntry("sourceLineStart", 10)
                .containsEntry("sourceLineEnd", 180)
                .containsEntry("actualLineStart", rendered.lineStart())
                .containsEntry("actualLineEnd", rendered.lineEnd())
                .containsEntry("contentComplete", false)
                .containsEntry("omittedByBudget", true);
        assertThat(bundle.context())
                .contains("[1] src/VisibleConfiguration.java:10-" + rendered.lineEnd())
                .contains("sourceLines=10-180")
                .endsWith(rendered.content());
    }

    @Test
    void incompletePromptExcerptDoesNotRetainWholeSourceValidationClaims() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("llmDirectRead", true);
        metadata.put("llmRequestedLineStart", 100);
        metadata.put("llmRequestedLineEnd", 102);
        metadata.put("llmValidatedEvidence", true);
        metadata.put("llmValidatedEvidenceGroup", List.of("response_storage"));
        metadata.put("llmSupportedClaims", List.of("the response is persisted"));
        metadata.put("llmNotSupportedClaims", List.of("the response is discarded"));
        metadata.put("contentComplete", true);
        metadata.put("actualLineStart", 100);
        metadata.put("actualLineEnd", 223);
        String content = "void inspect() {\n"
                + "  validateInput();\n"
                + "  return;\n"
                + "  noopWithLongPaddingForPromptBudget();\n".repeat(120)
                + "  responseRepository.save(response);";
        CodeSearchResult source = result(
                "src/ResponseService.java", "inspect", 100, 223, content, metadata);

        CodeContextAssembler.ContextBundle bundle = new CodeContextAssembler().assemble(
                new CodeContextAssembler.AssemblyRequest(
                        "Read lines 100 through 102", "method", "system", "prefix",
                        List.of(source), false, 4096, 3200));

        CodeSearchResult rendered = bundle.results().get(0);
        assertThat(rendered.content())
                .contains("validateInput")
                .doesNotContain("responseRepository.save");
        assertThat(rendered.lineStart()).isEqualTo(100);
        assertThat(rendered.lineEnd()).isEqualTo(102);
        assertThat(rendered.metadata())
                .containsEntry("sourceLineStart", 100)
                .containsEntry("sourceLineEnd", 223)
                .containsEntry("sourceActualLineStart", 100)
                .containsEntry("sourceActualLineEnd", 223)
                .containsEntry("actualLineStart", 100)
                .containsEntry("actualLineEnd", 102)
                .containsEntry("contentComplete", false)
                .containsEntry("llmValidatedEvidence", false)
                .doesNotContainKeys(
                        "llmValidatedEvidenceGroup", "llmSupportedClaims", "llmNotSupportedClaims");
        assertThat(bundle.context())
                .contains("[1] src/ResponseService.java:100-102")
                .contains("sourceLines=100-223 excerptLines=100-102")
                .doesNotContain("llmSupportedClaims")
                .doesNotContain("the response is persisted");
        assertThat(source.metadata())
                .containsEntry("llmValidatedEvidence", true)
                .containsEntry("contentComplete", true);
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
