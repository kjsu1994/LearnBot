package com.learnbot.dto;

public record PatchFileDiff(
        String path,
        String diff
) {
}
