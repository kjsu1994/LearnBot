package com.learnbot.dto;

public record LocalAgentPatchExecutionReadinessCheck(
        String key,
        boolean passed,
        String message
) {
}
