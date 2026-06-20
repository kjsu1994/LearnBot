package com.learnbot.service;

public record CodeGraphAnalysisResult(
        CodeGraph graph,
        CodeAnalysisDiagnostic diagnostic
) {
}
