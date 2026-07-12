package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class JavaCodeIntelligenceAdapter implements CodeIntelligenceAnalyzerAdapter {
    private final JavaSemanticGraphAnalyzer analyzer;
    private final JavaClasspathResolver classpathResolver;

    public JavaCodeIntelligenceAdapter(
            JavaSemanticGraphAnalyzer analyzer,
            JavaClasspathResolver classpathResolver
    ) {
        this.analyzer = analyzer;
        this.classpathResolver = classpathResolver;
    }

    @Override
    public String analyzerId() {
        return "javaparser-symbol-solver";
    }

    @Override
    public String languageId() {
        return "java";
    }

    @Override
    public String diagnosticStage() {
        return "JAVA_SEMANTIC";
    }

    @Override
    public String displayName() {
        return "JavaParser Symbol Solver";
    }

    @Override
    public String mode() {
        return "SOURCE";
    }

    @Override
    public CodeIntelligenceAuthority authority() {
        return CodeIntelligenceAuthority.COMPILER_SEMANTIC;
    }

    @Override
    public boolean supports(List<CodeSearchResult> chunks) {
        return chunks != null && chunks.stream().anyMatch(result ->
                result != null && result.filePath() != null
                        && "java".equals(CodeLanguageCatalog.languageForPath(result.filePath())));
    }

    @Override
    public CodeIntelligenceIr analyze(Path repositoryRoot, List<CodeSearchResult> chunks) {
        if (analyzer == null) {
            return skipped("Analyzer is unavailable.");
        }
        if (!supports(chunks)) {
            return skipped("No Java source chunks found.");
        }
        List<CodeAnalysisDiagnostic> diagnostics = new ArrayList<>();
        JavaClasspathResolution classpath = classpathResolver == null
                ? new JavaClasspathResolution(List.of(), CodeAnalysisDiagnostic.skipped(
                        "JAVA_CLASSPATH", "Static dependency resolver", "CACHE_AND_ALLOWLIST", "Resolver is unavailable."))
                : classpathResolver.resolve(repositoryRoot);
        diagnostics.add(classpath.diagnostic());
        CodeGraphAnalysisResult result = analyzer.analyzeWithDiagnostics(repositoryRoot, chunks, classpath.jars());
        diagnostics.add(result.diagnostic());
        return CodeIntelligenceIr.fromAnalyzer(
                analyzerId(), languageId(), authority(), result.graph(), diagnostics,
                Map.of("dependencyJarCount", classpath.jars().size()));
    }

    private CodeIntelligenceIr skipped(String reason) {
        return CodeIntelligenceIr.fromAnalyzer(
                analyzerId(), languageId(), authority(), new CodeGraph(List.of(), List.of()),
                List.of(CodeAnalysisDiagnostic.skipped(diagnosticStage(), displayName(), mode(), reason)), Map.of());
    }
}
