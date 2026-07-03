package com.learnbot.dto;

import java.util.List;

public record CliDeviceSessionPlanResponse(
        String schema,
        String status,
        String method,
        String endpoint,
        String browserAuthorizePath,
        boolean enabled,
        boolean networkCallEnabled,
        boolean deviceCodeIssuanceEnabled,
        boolean userCodeCreated,
        boolean browserApprovalRequired,
        boolean sessionClaimEnabled,
        boolean accessTokenIssued,
        boolean refreshTokenIssued,
        boolean cookiePersistenceEnabled,
        boolean localAgentTokenAccepted,
        boolean tokenSecretPrinted,
        List<String> followUpEndpoints,
        List<String> blockers,
        String reason
) {
}
