package com.learnbot.web;

import com.learnbot.dto.AuthResponse;
import com.learnbot.dto.LoginRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    @Test
    void webLoginKeepsTokensInHttpOnlyCookies() {
        var auth = mock(AuthService.class);
        var result = new AuthResponse("access", OffsetDateTime.now().plusHours(1), "refresh", OffsetDateTime.now().plusDays(1), null, null, true);
        when(auth.login("reader", "password", true)).thenReturn(result);
        var response = new MockHttpServletResponse();
        var body = new AuthController(auth, mock(CurrentUserProvider.class)).login(new LoginRequest("reader", null, "password", true), new MockHttpServletRequest(), response);
        assertThat(body.token()).isNull();
        assertThat(body.refreshToken()).isNull();
        assertThat(response.getCookie("learnbot_access_token").getValue()).isEqualTo("access");
        assertThat(response.getCookie("learnbot_access_token").isHttpOnly()).isTrue();
        assertThat(response.getCookie("learnbot_refresh_token").getValue()).isEqualTo("refresh");
    }
}
