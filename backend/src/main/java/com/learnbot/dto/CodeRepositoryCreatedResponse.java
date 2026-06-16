package com.learnbot.dto;

import java.util.UUID;

public record CodeRepositoryCreatedResponse(
        UUID id,
        UUID spaceId,
        String name,
        String gitUrl,
        String branch,
        String authType,
        String status,
        String lastIndexedCommit,
        boolean credentialStored
) {
}
