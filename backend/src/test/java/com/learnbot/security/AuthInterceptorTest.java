package com.learnbot.security;

import com.learnbot.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/refresh"})
    void webSessionEntryPointsRemainPublic(String path) {
        var auth = mock(AuthService.class);
        assertThat(new AuthInterceptor(auth).preHandle(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/cli-login", "/api/auth/cli-device-session/create", "/api/local-agents/enrollments", "/api/local-agents/self", "/api/local-agents/tools/next", "/api/code/ask", "/api/rag/ask"})
    void agentHeaderCannotBypassWebAuthentication(String path) {
        var auth = mock(AuthService.class);
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Local-Agent-Token", "retired-token");
        assertThatThrownBy(() -> new AuthInterceptor(auth).preHandle(request, new MockHttpServletResponse(), new Object())).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(auth);
    }

    @Test
    void ragStillAuthenticatesWebCookie() {
        var auth = mock(AuthService.class);
        var request = new MockHttpServletRequest("POST", "/api/code/ask");
        request.setCookies(new Cookie("learnbot_access_token", "web-token"));
        assertThat(new AuthInterceptor(auth).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verify(auth).authenticateToken("web-token");
    }
}
