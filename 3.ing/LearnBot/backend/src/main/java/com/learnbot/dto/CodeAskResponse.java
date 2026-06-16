package com.learnbot.dto;

import java.util.List;

public record CodeAskResponse(
        String mode,
        String answer,
        List<CodeEvidence> evidence
) {
}
