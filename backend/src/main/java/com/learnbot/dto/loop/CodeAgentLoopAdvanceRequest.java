package com.learnbot.dto.loop;

import java.util.UUID;

public record CodeAgentLoopAdvanceRequest(
        UUID agentId,
        UUID workspaceId
) {
}
