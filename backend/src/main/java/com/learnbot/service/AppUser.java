package com.learnbot.service;

import java.util.UUID;

public record AppUser(
        UUID id,
        String email,
        String displayName,
        String role,
        String status
) {
    public boolean isMaster() {
        return "MASTER".equals(role);
    }

    public boolean isAdmin() {
        return isMaster() || "ADMIN".equals(role);
    }
}
