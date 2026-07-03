package com.learnbot.dto;

import java.util.List;
import java.util.Map;

public record CliDeviceSessionClaimPlanResponse(
        String schema,
        String status,
        String method,
        String endpoint,
        boolean enabled,
        boolean networkCallEnabled,
        boolean deviceCodeRequired,
        boolean browserApprovalRequired,
        boolean claimPollingEnabled,
        boolean sessionClaimEnabled,
        boolean accessTokenIssued,
        boolean refreshTokenIssued,
        boolean localSessionArtifactWriteEnabled,
        boolean localSessionArtifactEncryptedRequired,
        boolean cookiePersistenceEnabled,
        boolean localAgentTokenAccepted,
        boolean tokenSecretPrinted,
        List<String> requiredClientStorageFields,
        Map<String, Object> webSessionArtifactBodyPreview,
        List<String> followUpCommands,
        List<String> blockers,
        String reason
) {
}
