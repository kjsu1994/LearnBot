package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIntelligenceIrTest {
    @Test
    void normalizationPreservesGraphIdentityAndAddsProvenance() {
        UUID chunkId = UUID.randomUUID();
        CodeGraph source = new CodeGraph(
                List.of(new CodeGraphNode(
                        "method:worker", "method", "work", "sample.Worker.work", "src/Worker.java",
                        chunkId, Map.of("custom", "kept"))),
                List.of(new CodeGraphEdge(
                        "method:caller", "method:worker", "CALLS", 0.98, chunkId,
                        Map.of("dispatch", "resolved")))
        );
        CodeAnalysisDiagnostic diagnostic = new CodeAnalysisDiagnostic(
                "JAVA_SEMANTIC", "Java", "SUCCESS", "SOURCE", 1, 1, 0,
                1, 0, 1, 1, 10, "ok", Map.of());

        CodeIntelligenceIr ir = CodeIntelligenceIr.fromAnalyzer(
                "java-test", "java", CodeIntelligenceAuthority.COMPILER_SEMANTIC,
                source, List.of(diagnostic), Map.of("adapterExtension", true));

        assertThat(ir.shadowReport().equivalent()).isTrue();
        assertThat(ir.graph().nodes()).extracting(CodeGraphNode::key)
                .containsExactly("method:worker");
        assertThat(ir.graph().edges()).extracting(CodeGraphEdge::type)
                .containsExactly("CALLS");
        assertThat(ir.graph().nodes().get(0).metadata())
                .containsEntry("custom", "kept")
                .containsEntry("codeIntelligenceAnalyzer", "java-test")
                .containsEntry("codeIntelligenceLanguage", "java")
                .containsEntry("codeIntelligenceAuthority", "COMPILER_SEMANTIC");
        assertThat(ir.diagnostics().get(0).metadata())
                .containsEntry("shadowEquivalent", true)
                .containsEntry("shadowSourceNodes", 1)
                .containsEntry("shadowIrEdges", 1);
    }

    @Test
    void authorityOrderingKeepsSemanticEvidenceAboveFallbacks() {
        assertThat(CodeIntelligenceAuthority.COMPILER_SEMANTIC.rank())
                .isGreaterThan(CodeIntelligenceAuthority.SCIP_SEMANTIC.rank());
        assertThat(CodeIntelligenceAuthority.SCIP_SEMANTIC.rank())
                .isGreaterThan(CodeIntelligenceAuthority.LSP_SEMANTIC.rank());
        assertThat(CodeIntelligenceAuthority.LSP_SEMANTIC.rank())
                .isGreaterThan(CodeIntelligenceAuthority.SYNTAX.rank());
        assertThat(CodeIntelligenceAuthority.SYNTAX.rank())
                .isGreaterThan(CodeIntelligenceAuthority.LEXICAL.rank());
        assertThat(CodeIntelligenceAuthority.LEXICAL.rank())
                .isGreaterThan(CodeIntelligenceAuthority.LLM_INFERRED.rank());
    }
}
