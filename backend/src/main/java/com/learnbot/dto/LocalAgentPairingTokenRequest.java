package com.learnbot.dto;

import jakarta.validation.constraints.Size;

public record LocalAgentPairingTokenRequest(
        @Size(max = 120)
        String label
) {
}
