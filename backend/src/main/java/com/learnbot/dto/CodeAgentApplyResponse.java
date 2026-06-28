package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record CodeAgentApplyResponse(
        UUID patchSessionId,
        boolean applied,
        List<String> changedFiles,
        List<String> warnings,
        boolean rollbackAvailable
) {
}
