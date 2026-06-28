package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record CodeAgentTestResponse(
        UUID patchSessionId,
        String commandKey,
        boolean allowed,
        Integer exitCode,
        String summary,
        List<String> warnings
) {
}
