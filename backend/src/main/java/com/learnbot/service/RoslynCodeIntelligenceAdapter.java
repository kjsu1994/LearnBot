package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class RoslynCodeIntelligenceAdapter implements CodeIntelligenceAnalyzerAdapter {
    private final RoslynSemanticGraphAnalyzer analyzer;

    public RoslynCodeIntelligenceAdapter(RoslynSemanticGraphAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public String analyzerId() {
        return "roslyn-workspace";
    }

    @Override
    public String languageId() {
        return "csharp";
    }

    @Override
    public String diagnosticStage() {
        return "CSHARP_ROSLYN";
    }

    @Override
    public String displayName() {
        return "Roslyn";
    }

    @Override
    public String mode() {
        return "AUTO";
    }

    @Override
    public CodeIntelligenceAuthority authority() {
        return CodeIntelligenceAuthority.COMPILER_SEMANTIC;
    }

    @Override
    public boolean supports(List<CodeSearchResult> chunks) {
        return chunks != null && chunks.stream().anyMatch(result -> {
            String language = CodeLanguageCatalog.languageForPath(result == null ? "" : result.filePath());
            return "csharp".equals(language) || "xaml".equals(language) || "razor".equals(language);
        });
    }

    @Override
    public CodeIntelligenceIr analyze(Path repositoryRoot, List<CodeSearchResult> chunks) {
        if (analyzer == null) {
            return skipped("Analyzer is unavailable.");
        }
        if (!supports(chunks)) {
            return skipped("No C# or related UI source chunks found.");
        }
        CodeGraphAnalysisResult result = analyzer.analyzeWithDiagnostics(repositoryRoot, chunks);
        return CodeIntelligenceIr.fromAnalyzer(
                analyzerId(), languageId(), authority(), result.graph(), List.of(result.diagnostic()), Map.of());
    }

    private CodeIntelligenceIr skipped(String reason) {
        return CodeIntelligenceIr.fromAnalyzer(
                analyzerId(), languageId(), authority(), new CodeGraph(List.of(), List.of()),
                List.of(CodeAnalysisDiagnostic.skipped(diagnosticStage(), displayName(), mode(), reason)), Map.of());
    }
}
