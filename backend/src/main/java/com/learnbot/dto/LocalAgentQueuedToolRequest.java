package com.learnbot.dto;

import java.util.UUID;

public record LocalAgentQueuedToolRequest(
        UUID requestId,
        LocalAgentToolRequest request
) {
    public LocalAgentQueuedToolRequest {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId is required.");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required.");
        }
    }
}
