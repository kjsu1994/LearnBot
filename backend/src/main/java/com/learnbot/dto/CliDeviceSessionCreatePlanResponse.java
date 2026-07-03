package com.learnbot.dto;

import java.util.List;

public record CliDeviceSessionCreatePlanResponse(
        String schema,
        String status,
        String method,
        String endpoint,
        String browserAuthorizePath,
        String verificationUriPath,
        String userCodeFormat,
        int userCodeLength,
        int expiresInSeconds,
        int pollingIntervalSeconds,
        boolean enabled,
        boolean networkCallEnabled,
        boolean deviceCodeIssuanceEnabled,
        boolean deviceCodeIssued,
        boolean userCodeCreated,
        boolean browserApprovalRequired,
        boolean claimPollingEnabled,
        boolean sessionClaimEnabled,
        boolean accessTokenIssued,
        boolean refreshTokenIssued,
        boolean cookiePersistenceEnabled,
        boolean localAgentTokenAccepted,
        boolean deviceCodeSecretPrinted,
        boolean tokenSecretPrinted,
        List<String> followUpEndpoints,
        List<String> blockers,
        String reason
) {
}
