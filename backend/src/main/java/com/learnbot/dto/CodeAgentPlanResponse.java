package com.learnbot.dto;

import java.util.List;

public record CodeAgentPlanResponse(
        String intent,
        String summary,
        List<PatchTargetFile> targetFiles,
        List<String> changePlan,
        String riskLevel,
        boolean needsMoreContext,
        List<String> warnings,
        List<CodeEvidence> evidence
) {
}
