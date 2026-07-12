package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.nio.file.Path;
import java.util.List;

public interface CodeIntelligenceAnalyzerAdapter {
    String analyzerId();

    String languageId();

    String diagnosticStage();

    String displayName();

    String mode();

    CodeIntelligenceAuthority authority();

    boolean supports(List<CodeSearchResult> chunks);

    CodeIntelligenceIr analyze(Path repositoryRoot, List<CodeSearchResult> chunks);
}
