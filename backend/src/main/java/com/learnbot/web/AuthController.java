package com.learnbot.web;

import com.learnbot.dto.AuthResponse;
import com.learnbot.dto.LoginRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(AuthService authService, CurrentUserProvider currentUserProvider) {
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @GetMapping("/me")
    AuthResponse me() {
        return authService.currentSession(currentUserProvider.currentUser());
    }

    @PostMapping("/logout")
    void logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        authService.logout(authorization, currentUserProvider.currentUser());
    }
}

