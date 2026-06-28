package com.learnbot.service;

import com.learnbot.dto.PatchApplySnapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentPatchSession(
        UUID id,
        UUID repositoryId,
        UUID spaceId,
        UUID userId,
        String instruction,
        String diff,
        List<String> targetFiles,
        List<PatchApplySnapshot> beforeSnapshots,
        Map<String, String> afterHashes,
        String status,
        List<String> warnings,
        List<Map<String, Object>> testResults
) {
}
