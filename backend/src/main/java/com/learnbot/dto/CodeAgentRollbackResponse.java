package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record CodeAgentRollbackResponse(
        UUID patchSessionId,
        boolean rolledBack,
        List<String> restoredFiles,
        List<String> warnings
) {
}
