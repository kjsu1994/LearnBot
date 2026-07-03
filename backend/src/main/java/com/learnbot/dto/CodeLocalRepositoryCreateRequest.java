package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CodeLocalRepositoryCreateRequest(
        @NotBlank String localPath,
        String name,
        String branch,
        String headCommit,
        String gitRemote,
        UUID workspaceId,
        UUID spaceId
) {
}
