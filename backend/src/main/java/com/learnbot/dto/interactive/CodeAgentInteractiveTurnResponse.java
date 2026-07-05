package com.learnbot.dto.interactive;

import com.learnbot.dto.CodeAskResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentInteractiveTurnResponse(
        String schema,
        UUID conversationId,
        UUID turnId,
        String intent,
        String command,
        String goal,
        String answer,
        CodeAskResponse ragAnswer,
        boolean shouldRunCommand,
        boolean mutationRequiresApproval,
        boolean contextRequired,
        List<String> targetFiles,
        List<Map<String, Object>> toolPlan,
        Map<String, Object> metadata,
        List<String> warnings
) {
}
