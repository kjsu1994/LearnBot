package com.learnbot.service;

public record GitAccessToken(
        String username,
        String token
) {
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public String effectiveUsername() {
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return "oauth2";
    }
}
