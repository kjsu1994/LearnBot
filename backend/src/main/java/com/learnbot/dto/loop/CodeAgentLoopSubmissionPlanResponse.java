package com.learnbot.dto.loop;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopSubmissionPlanResponse(
        String schema,
        UUID repositoryId,
        UUID spaceId,
        UUID agentId,
        UUID workspaceId,
        String instruction,
        int maxSteps,
        String method,
        String endpoint,
        Map<String, Object> bodyPreview,
        Map<String, Object> patchDryRunApprovalHandoffPlan,
        Map<String, Object> patchDryRunApprovalReviewPreview,
        List<String> followUpEndpoints,
        boolean readyForDisabledPlan,
        boolean enabled,
        boolean networkCallEnabled,
        boolean requestCreationEnabled,
        boolean serverConversationCreationEnabled,
        boolean loopPreviewExecutionEnabled,
        boolean mutationEnabled,
        boolean testExecutionEnabled,
        boolean rollbackExecutionEnabled,
        boolean finalPublicationEnabled,
        boolean partialReindexEnabled,
        boolean requiresAuthenticatedWebSession,
        boolean requiresRepositoryAuthorization,
        String reason
) {
}
