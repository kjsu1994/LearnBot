package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AuthResponse(
        String token,
        OffsetDateTime expiresAt,
        String refreshToken,
        OffsetDateTime refreshExpiresAt,
        UserSummary user,
        List<SpaceSummary> spaces
) {
}
