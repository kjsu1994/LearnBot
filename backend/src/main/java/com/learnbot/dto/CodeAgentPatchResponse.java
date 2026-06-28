package com.learnbot.dto;

import java.util.List;

public record CodeAgentPatchResponse(
        String summary,
        List<PatchFileDiff> files,
        String riskLevel,
        List<String> warnings,
        List<String> testSuggestions,
        boolean valid
) {
}
