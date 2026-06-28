package com.learnbot.security;

import com.learnbot.service.AppUser;
import com.learnbot.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if ("/api/auth/login".equals(path) || "/api/auth/refresh".equals(path)) {
            return true;
        }
        if (isLocalAgentTokenEndpoint(path) && hasLocalAgentToken(request)) {
            return true;
        }
        String token = extractBearerHeaderToken(request.getHeader("Authorization"));
        if (token == null || token.isBlank()) {
            token = extractCookie(request, "learnbot_access_token");
        }
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Authentication is required.");
        }
        AppUser user = authService.authenticateToken(token);
        request.setAttribute(CurrentUserProvider.REQUEST_ATTRIBUTE, user);
        return true;
    }

    private String extractBearerHeaderToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private boolean hasLocalAgentToken(HttpServletRequest request) {
        String token = request.getHeader("X-Local-Agent-Token");
        return token != null && !token.isBlank();
    }

    private boolean isLocalAgentTokenEndpoint(String path) {
        return "/api/local-agents/heartbeat".equals(path)
                || "/api/local-agents/ws".equals(path)
                || path.startsWith("/api/local-agents/tools/");
    }

    private String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
