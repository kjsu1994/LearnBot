package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.PatchValidationResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeAgentLocalPatchRequestService {
    private final CodePatchFileLoader fileLoader;
    private final PatchValidationService validationService;
    private final LocalAgentToolGatewayService toolGatewayService;

    public CodeAgentLocalPatchRequestService(
            CodePatchFileLoader fileLoader,
            PatchValidationService validationService,
            LocalAgentToolGatewayService toolGatewayService
    ) {
        this.fileLoader = fileLoader;
        this.validationService = validationService;
        this.toolGatewayService = toolGatewayService;
    }

    public LocalAgentToolExecutionResponse prepare(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            String instruction,
            String diff,
            List<String> targetFiles
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> normalizedTargets = fileLoader.normalizeRequestedPaths(targetFiles, warnings);
        PatchValidationResult validation = validationService.validate(diff, normalizedTargets);
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            throw new IllegalArgumentException("Patch did not pass server validation.");
        }
        CodePatchFileLoader.LoadResult loaded = fileLoader.load(repositoryId, normalizedTargets);
        warnings.addAll(loaded.warnings());
        if (loaded.files().isEmpty()) {
            throw new IllegalArgumentException("No safe indexed target files were available for patch.apply.");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", 1);
        input.put("repositoryId", repositoryId.toString());
        if (spaceId != null) {
            input.put("spaceId", spaceId.toString());
        }
        input.put("instruction", safe(instruction));
        input.put("diff", safe(diff));
        input.put("targetFiles", List.copyOf(normalizedTargets));
        input.put("expectedFiles", loaded.files().stream()
                .map(file -> Map.of(
                        "path", file.path(),
                        "sha256", sha256(file.content()),
                        "bytes", file.content().getBytes(StandardCharsets.UTF_8).length
                ))
                .toList());
        input.put("requiresSnapshot", true);
        input.put("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH");

        return toolGatewayService.createApprovalRequest(new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                input,
                LocalAgentApprovalState.REQUIRED,
                null,
                List.copyOf(warnings)
        ));
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(safe(content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
