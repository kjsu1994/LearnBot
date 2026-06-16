package com.learnbot.service;

import java.util.UUID;

public record AppUser(
        UUID id,
        String email,
        String displayName,
        String role,
        String status
) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}

