package com.learnbot.web;

import com.learnbot.dto.AuthResponse;
import com.learnbot.dto.CliDeviceSessionPlanRequest;
import com.learnbot.dto.CliDeviceSessionPlanResponse;
import com.learnbot.dto.CliDeviceSessionCreatePlanRequest;
import com.learnbot.dto.CliDeviceSessionCreatePlanResponse;
import com.learnbot.dto.CliDeviceSessionClaimPlanRequest;
import com.learnbot.dto.CliDeviceSessionClaimPlanResponse;
import com.learnbot.dto.CliDeviceSessionClaimResultPlanRequest;
import com.learnbot.dto.CliDeviceSessionClaimResultPlanResponse;
import com.learnbot.dto.LoginRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.security.UnauthorizedException;
import com.learnbot.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String ACCESS_TOKEN_COOKIE_NAME = "learnbot_access_token";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "learnbot_refresh_token";
    private static final String COOKIE_PATH = "/api";
    private static final int CLI_DEVICE_SESSION_TTL_SECONDS = 600;
    private static final int CLI_DEVICE_SESSION_POLL_SECONDS = 5;

    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingCliDeviceSession> pendingCliDeviceSessions = new ConcurrentHashMap<>();

    public AuthController(AuthService authService, CurrentUserProvider currentUserProvider) {
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
                      HttpServletResponse servletResponse) {
        AuthResponse authResponse = authService.login(request.identifier(), request.password(), request.rememberLogin());
        setCookie(
                servletResponse,
                ACCESS_TOKEN_COOKIE_NAME,
                authResponse.token(),
                authResponse.expiresAt(),
                servletRequest,
                false
        );
        setCookie(
                servletResponse,
                REFRESH_TOKEN_COOKIE_NAME,
                authResponse.refreshToken(),
                authResponse.refreshExpiresAt(),
                servletRequest,
                request.rememberLogin()
        );
        return withoutTokens(authResponse);
    }

    @PostMapping("/cli-login")
    AuthResponse cliLogin(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.identifier(), request.password(), request.rememberLogin());
    }

    @PostMapping("/cli-device-session/plan")
    CliDeviceSessionPlanResponse cliDeviceSessionPlan(@RequestBody(required = false) CliDeviceSessionPlanRequest request) {
        return new CliDeviceSessionPlanResponse(
                "learnbot.server.auth.cli-device-session-plan.v1",
                "DISABLED_PREVIEW",
                "POST",
                "/api/auth/cli-device-session/claim",
                "/settings/local-agent",
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(
                        "POST /api/auth/cli-device-session/plan",
                        "POST /api/auth/cli-device-session/create/plan",
                        "POST /api/auth/cli-device-session/create",
                        "POST /api/auth/cli-device-session/claim/plan",
                        "POST /api/auth/cli-device-session/claim",
                        "POST /api/auth/cli-device-session/claim-result/plan",
                        "GET /api/auth/me"
                ),
                List.of("CLI device/session claim is disabled until browser approval and session storage are implemented."),
                "This read-only plan keeps CLI web-user authentication separate from Local Agent pairing tokens before any device code, claim token, access token, refresh token, or cookie persistence is enabled."
        );
    }

    @PostMapping("/cli-device-session/create/plan")
    CliDeviceSessionCreatePlanResponse cliDeviceSessionCreatePlan(@RequestBody(required = false) CliDeviceSessionCreatePlanRequest request) {
        return new CliDeviceSessionCreatePlanResponse(
                "learnbot.server.auth.cli-device-session-create-plan.v1",
                "DISABLED_PREVIEW",
                "POST",
                "/api/auth/cli-device-session/create",
                "/settings/local-agent",
                "/settings/local-agent/device",
                "XXXX-XXXX",
                8,
                600,
                5,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(
                        "POST /api/auth/cli-device-session/create",
                        "GET /settings/local-agent/device",
                        "POST /api/auth/cli-device-session/claim/plan",
                        "POST /api/auth/cli-device-session/claim",
                        "POST /api/auth/cli-device-session/claim-result/plan"
                ),
                List.of("CLI device-code creation is disabled until browser approval, server-side pending-session storage, and encrypted local session storage are implemented."),
                "This read-only create plan fixes the future device-code response shape without creating a device code, user code, claim token, access token, refresh token, cookie, or local session artifact."
        );
    }

    @PostMapping("/cli-device-session/create")
    Map<String, Object> cliDeviceSessionCreate(@RequestBody(required = false) CliDeviceSessionCreatePlanRequest request) {
        cleanupExpiredCliDeviceSessions();
        String deviceCode = randomSecret();
        String userCode = newUserCode();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(CLI_DEVICE_SESSION_TTL_SECONDS);
        pendingCliDeviceSessions.put(deviceCode, new PendingCliDeviceSession(
                deviceCode,
                userCode,
                request == null ? null : request.clientName(),
                request == null ? null : request.cliVersion(),
                expiresAt,
                null,
                null,
                false
        ));
        return Map.of(
                "schema", "learnbot.server.auth.cli-device-session-create.v1",
                "status", "PENDING_BROWSER_APPROVAL",
                "deviceCode", deviceCode,
                "userCode", userCode,
                "expiresAt", expiresAt.toString(),
                "intervalSeconds", CLI_DEVICE_SESSION_POLL_SECONDS,
                "verificationUriPath", "/settings/local-agent/device",
                "verificationUriCompletePath", "/settings/local-agent/device?user_code=" + userCode,
                "browserApprovalRequired", true,
                "tokenSecretPrinted", false
        );
    }

    @PostMapping("/cli-device-session/claim/plan")
    CliDeviceSessionClaimPlanResponse cliDeviceSessionClaimPlan(@RequestBody(required = false) CliDeviceSessionClaimPlanRequest request) {
        return new CliDeviceSessionClaimPlanResponse(
                "learnbot.server.auth.cli-device-session-claim-plan.v1",
                "DISABLED_PREVIEW",
                "POST",
                "/api/auth/cli-device-session/claim",
                false,
                false,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                List.of("serverUrl", "accessToken", "refreshToken", "expiresAt", "refreshExpiresAt"),
                Map.of(
                        "schema", "learnbot.local-agent.web-session-artifact.v1",
                        "serverUrl", "<server-url>",
                        "encryptedAccessToken", "<encrypted-access-token>",
                        "encryptedRefreshToken", "<encrypted-refresh-token>",
                        "expiresAt", "<expires-at>",
                        "refreshExpiresAt", "<refresh-expires-at>",
                        "createdAt", "<created-at>",
                        "encryption", Map.of(
                                "required", true,
                                "provider", "LOCAL_OS_SECRET_STORE_OR_DPAPI",
                                "plaintextTokenSerializationAllowed", false
                        )
                ),
                List.of(
                        "learnbot session status",
                        "learnbot session claim-result-plan",
                        "learnbot fix --goal \"<goal>\" --workspace <workspace> --repository-id <repository-id> --server-plan"
                ),
                List.of("CLI session claim and local web-session artifact storage are disabled until browser approval, polling, and encrypted storage are implemented."),
                "This read-only claim plan describes the future CLI polling and local web-session artifact boundary without issuing tokens, accepting Local Agent credentials, writing local files, or persisting cookies."
        );
    }

    @PostMapping("/cli-device-session/claim")
    Map<String, Object> cliDeviceSessionClaim(@RequestBody(required = false) Map<String, Object> request) {
        cleanupExpiredCliDeviceSessions();
        String userCode = stringValue(request, "userCode");
        if (userCode == null || userCode.isBlank()) {
            throw new IllegalArgumentException("userCode is required.");
        }
        PendingCliDeviceSession session = pendingCliDeviceSessions.values().stream()
                .filter(item -> userCode.equalsIgnoreCase(item.userCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Device session was not found or expired."));
        if (session.consumed()) {
            throw new IllegalArgumentException("Device session was already used.");
        }
        if (session.expiresAt().isBefore(OffsetDateTime.now())) {
            pendingCliDeviceSessions.remove(session.deviceCode());
            throw new IllegalArgumentException("Device session expired.");
        }
        var user = currentUserProvider.currentUser();
        pendingCliDeviceSessions.put(session.deviceCode(), session.approvedBy(user.id(), OffsetDateTime.now()));
        return Map.of(
                "schema", "learnbot.server.auth.cli-device-session-claim.v1",
                "status", "APPROVED",
                "userCode", session.userCode(),
                "approved", true,
                "tokenSecretPrinted", false
        );
    }

    @PostMapping("/cli-device-session/claim-result/plan")
    CliDeviceSessionClaimResultPlanResponse cliDeviceSessionClaimResultPlan(@RequestBody(required = false) CliDeviceSessionClaimResultPlanRequest request) {
        return new CliDeviceSessionClaimResultPlanResponse(
                "learnbot.server.auth.cli-device-session-claim-result-plan.v1",
                "DISABLED_PREVIEW",
                "POST",
                "/api/auth/cli-device-session/claim-result",
                false,
                false,
                true,
                true,
                false,
                true,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                "%USERPROFILE%\\.learnbot\\web-session.json",
                List.of("claimStatus", "serverUrl", "accessToken", "refreshToken", "expiresAt", "refreshExpiresAt"),
                List.of("schema", "serverUrl", "encryptedAccessToken", "encryptedRefreshToken", "expiresAt", "refreshExpiresAt", "createdAt", "encryption"),
                Map.of(
                        "schema", "learnbot.local-agent.web-session-artifact-writer-plan.v1",
                        "preconditions", List.of(
                                "browser-approved claim result",
                                "access token present",
                                "refresh token present",
                                "expiresAt present",
                                "refreshExpiresAt present",
                                "local OS secret store or DPAPI available"
                        ),
                        "artifactBodyPreview", Map.of(
                                "schema", "learnbot.local-agent.web-session-artifact.v1",
                                "serverUrl", "<server-url>",
                                "encryptedAccessToken", "<encrypted-access-token>",
                                "encryptedRefreshToken", "<encrypted-refresh-token>",
                                "expiresAt", "<expires-at>",
                                "refreshExpiresAt", "<refresh-expires-at>",
                                "createdAt", "<created-at>",
                                "encryption", Map.of(
                                        "required", true,
                                        "provider", "LOCAL_OS_SECRET_STORE_OR_DPAPI",
                                        "plaintextTokenSerializationAllowed", false
                                )
                        ),
                        "write", Map.of(
                                "enabled", false,
                                "atomicReplaceRequired", true,
                                "plaintextTokenSerializationAllowed", false,
                                "path", "%USERPROFILE%\\.learnbot\\web-session.json"
                        )
                ),
                List.of(
                        "learnbot session status",
                        "learnbot session server-plan-readiness",
                        "learnbot fix --goal \"<goal>\" --workspace <workspace> --repository-id <repository-id> --server-plan"
                ),
                List.of("Encrypted web-session artifact writing is disabled until browser-approved claim results, OS-backed encryption, atomic write, read/decrypt validation, and refresh handling are implemented."),
                "This read-only claim-result plan fixes the artifact writer preflight contract without accepting tokens, serializing plaintext secrets, writing local files, persisting cookies, or using Local Agent pairing credentials."
        );
    }

    @PostMapping("/cli-device-session/claim-result")
    Map<String, Object> cliDeviceSessionClaimResult(@RequestBody(required = false) Map<String, Object> request) {
        cleanupExpiredCliDeviceSessions();
        String deviceCode = stringValue(request, "deviceCode");
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode is required.");
        }
        PendingCliDeviceSession session = pendingCliDeviceSessions.get(deviceCode);
        if (session == null || session.expiresAt().isBefore(OffsetDateTime.now())) {
            pendingCliDeviceSessions.remove(deviceCode);
            return Map.of(
                    "schema", "learnbot.server.auth.cli-device-session-claim-result.v1",
                    "status", "EXPIRED_OR_NOT_FOUND",
                    "approved", false,
                    "tokenSecretPrinted", false
            );
        }
        if (session.consumed()) {
            return Map.of(
                    "schema", "learnbot.server.auth.cli-device-session-claim-result.v1",
                    "status", "CONSUMED",
                    "approved", false,
                    "tokenSecretPrinted", false
            );
        }
        if (session.approvedUserId() == null) {
            return Map.of(
                    "schema", "learnbot.server.auth.cli-device-session-claim-result.v1",
                    "status", "PENDING_BROWSER_APPROVAL",
                    "approved", false,
                    "intervalSeconds", CLI_DEVICE_SESSION_POLL_SECONDS,
                    "expiresAt", session.expiresAt().toString(),
                    "tokenSecretPrinted", false
            );
        }
        AuthResponse issued = authService.issueCliSession(session.approvedUserId());
        pendingCliDeviceSessions.put(deviceCode, session.markConsumed());
        return Map.of(
                "schema", "learnbot.server.auth.cli-device-session-claim-result.v1",
                "status", "APPROVED",
                "approved", true,
                "serverUrl", "",
                "accessToken", issued.token(),
                "refreshToken", issued.refreshToken(),
                "expiresAt", issued.expiresAt().toString(),
                "refreshExpiresAt", issued.refreshExpiresAt().toString(),
                "tokenSecretPrinted", false
        );
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@RequestHeader(name = "X-Refresh-Token", required = false) String refreshTokenHeader,
                         HttpServletRequest servletRequest,
                         HttpServletResponse servletResponse) {
        String refreshToken = resolveRefreshToken(servletRequest, refreshTokenHeader);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Session is invalid or expired.");
        }
        AuthResponse authResponse = authService.refreshSession(refreshToken);
        setCookie(
                servletResponse,
                ACCESS_TOKEN_COOKIE_NAME,
                authResponse.token(),
                authResponse.expiresAt(),
                servletRequest,
                false
        );
        setCookie(
                servletResponse,
                REFRESH_TOKEN_COOKIE_NAME,
                authResponse.refreshToken(),
                authResponse.refreshExpiresAt(),
                servletRequest,
                Boolean.TRUE.equals(authResponse.rememberLogin())
        );
        return withoutTokens(authResponse);
    }

    @GetMapping("/me")
    AuthResponse me() {
        return authService.currentSession(currentUserProvider.currentUser());
    }

    @PostMapping("/logout")
    void logout(@RequestHeader(name = "Authorization", required = false) String authorization,
                @RequestHeader(name = "X-Refresh-Token", required = false) String refreshTokenHeader,
                HttpServletRequest servletRequest,
                HttpServletResponse servletResponse) {
        String accessToken = extractAccessTokenFromAuthorizationHeader(authorization);
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = extractCookie(servletRequest, ACCESS_TOKEN_COOKIE_NAME);
        }
        String refreshToken = resolveRefreshToken(servletRequest, refreshTokenHeader);
        authService.logout(accessToken, refreshToken, currentUserProvider.currentUser());
        clearCookie(servletResponse, ACCESS_TOKEN_COOKIE_NAME, servletRequest.isSecure());
        clearCookie(servletResponse, REFRESH_TOKEN_COOKIE_NAME, servletRequest.isSecure());
    }

    private void setCookie(HttpServletResponse response,
                          String cookieName,
                          String token,
                          java.time.OffsetDateTime expiresAt,
                          HttpServletRequest servletRequest,
                          boolean persistent) {
        if (token == null || token.isBlank()) {
            return;
        }
        Cookie cookie = new Cookie(cookieName, token);
        cookie.setHttpOnly(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setSecure(servletRequest.isSecure());
        cookie.setAttribute("SameSite", servletRequest.isSecure() ? "Strict" : "Lax");
        if (persistent && expiresAt != null) {
            cookie.setMaxAge((int) Math.max(1, java.time.Duration.between(java.time.OffsetDateTime.now(), expiresAt).getSeconds()));
        } else {
            cookie.setMaxAge(-1);
        }
        response.addCookie(cookie);
    }

    private void clearCookie(HttpServletResponse response, String cookieName, boolean secure) {
        Cookie cookie = new Cookie(cookieName, "");
        cookie.setHttpOnly(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setSecure(secure);
        cookie.setAttribute("SameSite", secure ? "Strict" : "Lax");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String resolveRefreshToken(HttpServletRequest request, String refreshTokenHeader) {
        if (refreshTokenHeader != null && !refreshTokenHeader.isBlank()) {
            return refreshTokenHeader;
        }
        return extractCookie(request, REFRESH_TOKEN_COOKIE_NAME);
    }

    private String extractCookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String extractAccessTokenFromAuthorizationHeader(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private AuthResponse withoutTokens(AuthResponse authResponse) {
        return new AuthResponse(null, null, null, null, authResponse.user(), authResponse.spaces(), null);
    }

    private void cleanupExpiredCliDeviceSessions() {
        OffsetDateTime now = OffsetDateTime.now();
        pendingCliDeviceSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newUserCode() {
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .replace('O', 'A')
                .replace('0', 'B')
                .replace('I', 'C')
                .replace('l', 'D')
                .toUpperCase();
        return raw.substring(0, 4) + "-" + raw.substring(4, 8);
    }

    private String stringValue(Map<String, Object> request, String key) {
        if (request == null || request.get(key) == null) {
            return null;
        }
        return String.valueOf(request.get(key)).trim();
    }

    private record PendingCliDeviceSession(
            String deviceCode,
            String userCode,
            String clientName,
            String cliVersion,
            OffsetDateTime expiresAt,
            UUID approvedUserId,
            OffsetDateTime approvedAt,
            boolean consumed
    ) {
        PendingCliDeviceSession approvedBy(UUID userId, OffsetDateTime now) {
            return new PendingCliDeviceSession(deviceCode, userCode, clientName, cliVersion, expiresAt, userId, now, consumed);
        }

        PendingCliDeviceSession markConsumed() {
            return new PendingCliDeviceSession(deviceCode, userCode, clientName, cliVersion, expiresAt, approvedUserId, approvedAt, true);
        }
    }
}
