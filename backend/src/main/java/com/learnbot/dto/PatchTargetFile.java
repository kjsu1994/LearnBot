package com.learnbot.dto;

public record PatchTargetFile(
        String path,
        String reason
) {
}
