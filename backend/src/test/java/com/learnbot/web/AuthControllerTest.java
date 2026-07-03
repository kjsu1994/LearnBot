package com.learnbot.web;

import com.learnbot.dto.CliDeviceSessionPlanRequest;
import com.learnbot.dto.CliDeviceSessionCreatePlanRequest;
import com.learnbot.dto.CliDeviceSessionClaimPlanRequest;
import com.learnbot.dto.CliDeviceSessionClaimResultPlanRequest;
import com.learnbot.dto.AuthResponse;
import com.learnbot.dto.LoginRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AppUser;
import com.learnbot.service.AuthService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    @Test
    void cliLoginReturnsTokensForEncryptedLocalStorage() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService, mock(CurrentUserProvider.class));
        AuthResponse response = new AuthResponse(
                "access-token",
                OffsetDateTime.now().plusHours(1),
                "refresh-token",
                OffsetDateTime.now().plusDays(30),
                null,
                null,
                true
        );
        when(authService.login("jinsu.kim", "password", true)).thenReturn(response);

        AuthResponse actual = controller.cliLogin(new LoginRequest("jinsu.kim", null, "password", true));

        assertThat(actual.token()).isEqualTo("access-token");
        assertThat(actual.refreshToken()).isEqualTo("refresh-token");
        assertThat(actual.rememberLogin()).isTrue();
        verify(authService).login("jinsu.kim", "password", true);
    }

    @Test
    void cliDeviceSessionCreateClaimAndClaimResultIssueTokensOnlyAfterBrowserApproval() {
        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.issueCliSession(userId)).thenReturn(new AuthResponse(
                "access-token",
                OffsetDateTime.now().plusHours(1),
                "refresh-token",
                OffsetDateTime.now().plusDays(1),
                null,
                null,
                false
        ));
        AuthController controller = new AuthController(authService, currentUserProvider);

        var created = controller.cliDeviceSessionCreate(new CliDeviceSessionCreatePlanRequest("learnbot", "0.1.0"));

        assertThat(created).containsEntry("schema", "learnbot.server.auth.cli-device-session-create.v1");
        assertThat(created).containsEntry("status", "PENDING_BROWSER_APPROVAL");
        assertThat(created).containsEntry("tokenSecretPrinted", false);
        assertThat(created).containsKeys("deviceCode", "userCode", "verificationUriCompletePath");
        assertThat(created).doesNotContainValue("access-token");
        assertThat(created).doesNotContainValue("refresh-token");

        var pending = controller.cliDeviceSessionClaimResult(Map.of("deviceCode", created.get("deviceCode")));
        assertThat(pending).containsEntry("status", "PENDING_BROWSER_APPROVAL");
        assertThat(pending).containsEntry("approved", false);
        assertThat(pending).doesNotContainKey("accessToken");

        var claimed = controller.cliDeviceSessionClaim(Map.of("userCode", created.get("userCode")));
        assertThat(claimed).containsEntry("status", "APPROVED");
        assertThat(claimed).containsEntry("approved", true);
        assertThat(claimed).containsEntry("tokenSecretPrinted", false);
        assertThat(claimed).doesNotContainValue("access-token");

        var approved = controller.cliDeviceSessionClaimResult(Map.of("deviceCode", created.get("deviceCode")));
        assertThat(approved).containsEntry("status", "APPROVED");
        assertThat(approved).containsEntry("approved", true);
        assertThat(approved).containsEntry("accessToken", "access-token");
        assertThat(approved).containsEntry("refreshToken", "refresh-token");
        assertThat(approved).containsEntry("tokenSecretPrinted", false);

        var consumed = controller.cliDeviceSessionClaimResult(Map.of("deviceCode", created.get("deviceCode")));
        assertThat(consumed).containsEntry("status", "CONSUMED");
        assertThat(consumed).doesNotContainKey("accessToken");
    }

    @Test
    void cliDeviceSessionPlanIsDisabledAndDoesNotIssueTokens() {
        AuthController controller = new AuthController(mock(AuthService.class), mock(CurrentUserProvider.class));

        var response = controller.cliDeviceSessionPlan(new CliDeviceSessionPlanRequest("learnbot", "0.1.0"));

        assertThat(response.schema()).isEqualTo("learnbot.server.auth.cli-device-session-plan.v1");
        assertThat(response.status()).isEqualTo("DISABLED_PREVIEW");
        assertThat(response.method()).isEqualTo("POST");
        assertThat(response.endpoint()).isEqualTo("/api/auth/cli-device-session/claim");
        assertThat(response.browserAuthorizePath()).isEqualTo("/settings/local-agent");
        assertThat(response.enabled()).isFalse();
        assertThat(response.networkCallEnabled()).isFalse();
        assertThat(response.deviceCodeIssuanceEnabled()).isFalse();
        assertThat(response.userCodeCreated()).isFalse();
        assertThat(response.browserApprovalRequired()).isTrue();
        assertThat(response.sessionClaimEnabled()).isFalse();
        assertThat(response.accessTokenIssued()).isFalse();
        assertThat(response.refreshTokenIssued()).isFalse();
        assertThat(response.cookiePersistenceEnabled()).isFalse();
        assertThat(response.localAgentTokenAccepted()).isFalse();
        assertThat(response.tokenSecretPrinted()).isFalse();
        assertThat(response.followUpEndpoints()).contains(
                "POST /api/auth/cli-device-session/claim/plan",
                "POST /api/auth/cli-device-session/claim",
                "GET /api/auth/me"
        );
        assertThat(response.blockers()).contains("CLI device/session claim is disabled until browser approval and session storage are implemented.");
        assertThat(response.reason()).contains("separate from Local Agent pairing tokens");
    }

    @Test
    void cliDeviceSessionCreatePlanIsDisabledAndDescribesFutureDeviceCodeShape() {
        AuthController controller = new AuthController(mock(AuthService.class), mock(CurrentUserProvider.class));

        var response = controller.cliDeviceSessionCreatePlan(new CliDeviceSessionCreatePlanRequest("learnbot", "0.1.0"));

        assertThat(response.schema()).isEqualTo("learnbot.server.auth.cli-device-session-create-plan.v1");
        assertThat(response.status()).isEqualTo("DISABLED_PREVIEW");
        assertThat(response.method()).isEqualTo("POST");
        assertThat(response.endpoint()).isEqualTo("/api/auth/cli-device-session/create");
        assertThat(response.browserAuthorizePath()).isEqualTo("/settings/local-agent");
        assertThat(response.verificationUriPath()).isEqualTo("/settings/local-agent/device");
        assertThat(response.userCodeFormat()).isEqualTo("XXXX-XXXX");
        assertThat(response.userCodeLength()).isEqualTo(8);
        assertThat(response.expiresInSeconds()).isEqualTo(600);
        assertThat(response.pollingIntervalSeconds()).isEqualTo(5);
        assertThat(response.enabled()).isFalse();
        assertThat(response.networkCallEnabled()).isFalse();
        assertThat(response.deviceCodeIssuanceEnabled()).isFalse();
        assertThat(response.deviceCodeIssued()).isFalse();
        assertThat(response.userCodeCreated()).isFalse();
        assertThat(response.browserApprovalRequired()).isTrue();
        assertThat(response.claimPollingEnabled()).isFalse();
        assertThat(response.sessionClaimEnabled()).isFalse();
        assertThat(response.accessTokenIssued()).isFalse();
        assertThat(response.refreshTokenIssued()).isFalse();
        assertThat(response.cookiePersistenceEnabled()).isFalse();
        assertThat(response.localAgentTokenAccepted()).isFalse();
        assertThat(response.deviceCodeSecretPrinted()).isFalse();
        assertThat(response.tokenSecretPrinted()).isFalse();
        assertThat(response.followUpEndpoints()).contains(
                "POST /api/auth/cli-device-session/create",
                "GET /settings/local-agent/device",
                "POST /api/auth/cli-device-session/claim"
        );
        assertThat(response.blockers()).contains("CLI device-code creation is disabled until browser approval, server-side pending-session storage, and encrypted local session storage are implemented.");
        assertThat(response.reason()).contains("without creating a device code");
    }

    @Test
    void cliDeviceSessionClaimPlanIsDisabledAndDoesNotPersistSessionArtifacts() {
        AuthController controller = new AuthController(mock(AuthService.class), mock(CurrentUserProvider.class));

        var response = controller.cliDeviceSessionClaimPlan(new CliDeviceSessionClaimPlanRequest("device-code", "learnbot", "0.1.0"));

        assertThat(response.schema()).isEqualTo("learnbot.server.auth.cli-device-session-claim-plan.v1");
        assertThat(response.status()).isEqualTo("DISABLED_PREVIEW");
        assertThat(response.method()).isEqualTo("POST");
        assertThat(response.endpoint()).isEqualTo("/api/auth/cli-device-session/claim");
        assertThat(response.enabled()).isFalse();
        assertThat(response.networkCallEnabled()).isFalse();
        assertThat(response.deviceCodeRequired()).isTrue();
        assertThat(response.browserApprovalRequired()).isTrue();
        assertThat(response.claimPollingEnabled()).isFalse();
        assertThat(response.sessionClaimEnabled()).isFalse();
        assertThat(response.accessTokenIssued()).isFalse();
        assertThat(response.refreshTokenIssued()).isFalse();
        assertThat(response.localSessionArtifactWriteEnabled()).isFalse();
        assertThat(response.localSessionArtifactEncryptedRequired()).isTrue();
        assertThat(response.cookiePersistenceEnabled()).isFalse();
        assertThat(response.localAgentTokenAccepted()).isFalse();
        assertThat(response.tokenSecretPrinted()).isFalse();
        assertThat(response.requiredClientStorageFields()).contains("accessToken", "refreshToken", "expiresAt");
        assertThat(response.webSessionArtifactBodyPreview()).containsEntry("schema", "learnbot.local-agent.web-session-artifact.v1");
        assertThat(response.webSessionArtifactBodyPreview()).containsEntry("encryptedAccessToken", "<encrypted-access-token>");
        assertThat(response.webSessionArtifactBodyPreview()).containsEntry("encryptedRefreshToken", "<encrypted-refresh-token>");
        assertThat(response.webSessionArtifactBodyPreview()).doesNotContainValue("device-code");
        assertThat(response.webSessionArtifactBodyPreview().get("encryption").toString()).contains("plaintextTokenSerializationAllowed=false");
        assertThat(response.followUpCommands()).contains("learnbot session status");
        assertThat(response.blockers()).contains("CLI session claim and local web-session artifact storage are disabled until browser approval, polling, and encrypted storage are implemented.");
        assertThat(response.reason()).contains("without issuing tokens");
    }

    @Test
    void cliDeviceSessionClaimResultPlanIsDisabledAndDoesNotWriteArtifact() {
        AuthController controller = new AuthController(mock(AuthService.class), mock(CurrentUserProvider.class));

        var response = controller.cliDeviceSessionClaimResultPlan(new CliDeviceSessionClaimResultPlanRequest("APPROVED", "learnbot", "0.1.0"));

        assertThat(response.schema()).isEqualTo("learnbot.server.auth.cli-device-session-claim-result-plan.v1");
        assertThat(response.status()).isEqualTo("DISABLED_PREVIEW");
        assertThat(response.method()).isEqualTo("POST");
        assertThat(response.endpoint()).isEqualTo("/api/auth/cli-device-session/claim-result");
        assertThat(response.enabled()).isFalse();
        assertThat(response.networkCallEnabled()).isFalse();
        assertThat(response.browserApprovalRequired()).isTrue();
        assertThat(response.claimResultRequired()).isTrue();
        assertThat(response.claimResultAccepted()).isFalse();
        assertThat(response.accessTokenRequired()).isTrue();
        assertThat(response.refreshTokenRequired()).isTrue();
        assertThat(response.plaintextTokenSerializationAllowed()).isFalse();
        assertThat(response.localSessionArtifactWriteEnabled()).isFalse();
        assertThat(response.localSessionArtifactEncryptedRequired()).isTrue();
        assertThat(response.artifactWriterPreflightEnabled()).isFalse();
        assertThat(response.artifactWriterExecutionEnabled()).isFalse();
        assertThat(response.tokenRefreshEnabled()).isFalse();
        assertThat(response.cookiePersistenceEnabled()).isFalse();
        assertThat(response.localAgentTokenAccepted()).isFalse();
        assertThat(response.tokenSecretPrinted()).isFalse();
        assertThat(response.requiredClaimResultFields()).contains("accessToken", "refreshToken", "expiresAt");
        assertThat(response.requiredArtifactFields()).contains("encryptedAccessToken", "encryptedRefreshToken", "encryption");
        assertThat(response.artifactWriterPlanPreview()).containsEntry("schema", "learnbot.local-agent.web-session-artifact-writer-plan.v1");
        assertThat(response.artifactWriterPlanPreview().toString()).contains("plaintextTokenSerializationAllowed=false");
        assertThat(response.artifactWriterPlanPreview().toString()).contains("encryptedAccessToken");
        assertThat(response.artifactWriterPlanPreview()).doesNotContainValue("APPROVED");
        assertThat(response.followUpCommands()).contains("learnbot session server-plan-readiness");
        assertThat(response.blockers()).contains("Encrypted web-session artifact writing is disabled until browser-approved claim results, OS-backed encryption, atomic write, read/decrypt validation, and refresh handling are implemented.");
        assertThat(response.reason()).contains("without accepting tokens");
    }
}
