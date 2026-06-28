package com.learnbot.dto;

import java.util.List;
import java.util.Map;

public record LocalAgentPatchReleaseAttemptModel(
        String schema,
        String status,
        boolean created,
        boolean claimable,
        int staleWindowSeconds,
        List<LocalAgentPatchReleaseAttemptEvidenceRequirement> requiredEvidence,
        Map<String, Object> latestAttempt,
        String message
) {
}
