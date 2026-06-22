package com.learnbot.web;

import com.learnbot.dto.AuthResponse;
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

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String ACCESS_TOKEN_COOKIE_NAME = "learnbot_access_token";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "learnbot_refresh_token";
    private static final String COOKIE_PATH = "/api";

    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

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
}
