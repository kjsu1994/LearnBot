package com.learnbot.service.coderag.model;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAnalysisDiagnosticMetadataTest {
    @Test
    void explicitDiagnosticKeysWinAndUnknownLanguagesArePreserved() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisDiagnosticStatus", "partial");
        metadata.put("codeIntelligenceDiagnosticStatus", "success");
        metadata.put("diagnosticStatus", "failed");
        metadata.put("analysisDiagnosticScope", "repository analysis");
        metadata.put("analysisDiagnosticStage", "symbol resolution");
        metadata.put("analysisDiagnosticLanguage", "Python Experimental");
        metadata.put("codeIntelligenceLanguage", "rust");
        metadata.put("language", "java");
        metadata.put("analysisDiagnosticAnalyzer", "Acme SCIP\r\nCustom Analyzer");
        metadata.put("codeIntelligenceAnalyzer", "other-analyzer");
        metadata.put("analysisDiagnosticAuthority", "SCIP_SEMANTIC");
        metadata.put("codeIntelligenceAuthority", "LEXICAL");

        CodeAnalysisDiagnosticMetadata diagnostic = CodeAnalysisDiagnosticMetadata.from(
                result("src/worker.any", "content is not inspected", metadata));

        assertThat(diagnostic.present()).isTrue();
        assertThat(diagnostic.status()).isEqualTo("PARTIAL");
        assertThat(diagnostic.scope()).isEqualTo("REPOSITORY_ANALYSIS");
        assertThat(diagnostic.stage()).isEqualTo("SYMBOL_RESOLUTION");
        assertThat(diagnostic.language()).isEqualTo("python-experimental");
        assertThat(diagnostic.analyzer()).isEqualTo("Acme SCIP Custom Analyzer");
        assertThat(diagnostic.authority()).isEqualTo(CodeIntelligenceAuthority.SCIP_SEMANTIC);
    }

    @Test
    void codeIntelligenceKeysPrecedeGenericLegacyMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("codeIntelligenceDiagnosticStatus", "success");
        metadata.put("codeIntelligenceDiagnosticScope", "semantic analysis");
        metadata.put("codeIntelligenceDiagnosticStage", "index pass");
        metadata.put("codeIntelligenceLanguage", "rust");
        metadata.put("language", "java");
        metadata.put("codeIntelligenceAnalyzer", "custom-semantic-analyzer");
        metadata.put("parser", "legacy-parser");
        metadata.put("codeIntelligenceAuthority", "LSP_SEMANTIC");

        CodeAnalysisDiagnosticMetadata diagnostic = CodeAnalysisDiagnosticMetadata.from(
                result("src/worker.any", "", metadata));

        assertThat(diagnostic.status()).isEqualTo("SUCCESS");
        assertThat(diagnostic.scope()).isEqualTo("SEMANTIC_ANALYSIS");
        assertThat(diagnostic.stage()).isEqualTo("INDEX_PASS");
        assertThat(diagnostic.language()).isEqualTo("rust");
        assertThat(diagnostic.analyzer()).isEqualTo("custom-semantic-analyzer");
        assertThat(diagnostic.authority()).isEqualTo(CodeIntelligenceAuthority.LSP_SEMANTIC);
    }

    @Test
    void parserMetadataAndSourceIdentityCannotCreateADiagnosticWithoutExplicitStatus() {
        CodeAnalysisDiagnosticMetadata diagnostic = CodeAnalysisDiagnosticMetadata.from(result(
                "src/RoslynJavaParserBridge.cs",
                "WPF WinForms XAML JavaParser Roslyn",
                Map.of(
                        "language", "csharp",
                        "analyzer", "Roslyn",
                        "parser", "roslyn_semantic_model",
                        "codeIntelligenceAuthority", "COMPILER_SEMANTIC")));

        assertThat(diagnostic.present()).isFalse();
        assertThat(diagnostic.status()).isEmpty();
        assertThat(diagnostic.scope()).isEmpty();
        assertThat(diagnostic.stage()).isEmpty();
        assertThat(diagnostic.language()).isEmpty();
        assertThat(diagnostic.analyzer()).isEmpty();
        assertThat(diagnostic.authority()).isEqualTo(CodeIntelligenceAuthority.UNKNOWN);
    }

    @Test
    void unknownDiagnosticStatusFailsClosed() {
        CodeAnalysisDiagnosticMetadata diagnostic = CodeAnalysisDiagnosticMetadata.from(result(
                "src/worker.any",
                "",
                Map.of(
                        "analysisDiagnosticStatus", "BANANA",
                        "analysisDiagnosticScope", "GRAPH_ANALYSIS",
                        "analysisDiagnosticStage", "SYMBOL_RESOLUTION",
                        "codeIntelligenceLanguage", "python",
                        "codeIntelligenceAnalyzer", "custom-analyzer",
                        "codeIntelligenceAuthority", "SCIP_SEMANTIC")));

        assertThat(diagnostic.present()).isFalse();
        assertThat(diagnostic.status()).isEmpty();
        assertThat(diagnostic.scope()).isEmpty();
        assertThat(diagnostic.stage()).isEmpty();
        assertThat(diagnostic.language()).isEmpty();
        assertThat(diagnostic.analyzer()).isEmpty();
        assertThat(diagnostic.authority()).isEqualTo(CodeIntelligenceAuthority.UNKNOWN);
    }

    private CodeSearchResult result(String path, String content, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", "inspect", "Sample", "inspect", "app", null, null, 1,
                1, 10, content, 0.9, metadata);
    }
}
