package com.learnbot.dto;

import java.util.List;
import java.util.Map;

public record CliDeviceSessionClaimResultPlanResponse(
        String schema,
        String status,
        String method,
        String endpoint,
        boolean enabled,
        boolean networkCallEnabled,
        boolean browserApprovalRequired,
        boolean claimResultRequired,
        boolean claimResultAccepted,
        boolean accessTokenRequired,
        boolean refreshTokenRequired,
        boolean plaintextTokenSerializationAllowed,
        boolean localSessionArtifactWriteEnabled,
        boolean localSessionArtifactEncryptedRequired,
        boolean artifactWriterPreflightEnabled,
        boolean artifactWriterExecutionEnabled,
        boolean tokenRefreshEnabled,
        boolean cookiePersistenceEnabled,
        boolean localAgentTokenAccepted,
        boolean tokenSecretPrinted,
        String localSessionArtifactPath,
        List<String> requiredClaimResultFields,
        List<String> requiredArtifactFields,
        Map<String, Object> artifactWriterPlanPreview,
        List<String> followUpCommands,
        List<String> blockers,
        String reason
) {
}
