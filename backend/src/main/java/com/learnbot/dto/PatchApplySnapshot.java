package com.learnbot.dto;

public record PatchApplySnapshot(
        String path,
        String beforeHash,
        String afterHash,
        String beforeContent
) {
}
