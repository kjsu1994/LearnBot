package com.learnbot.dto;

import java.util.List;

public record PatchValidationResult(
        boolean valid,
        List<String> warnings
) {
}
