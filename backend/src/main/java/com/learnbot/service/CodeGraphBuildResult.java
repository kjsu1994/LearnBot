package com.learnbot.service;

import java.util.List;

public record CodeGraphBuildResult(
        CodeGraph graph,
        List<CodeAnalysisDiagnostic> diagnostics
) {
}
