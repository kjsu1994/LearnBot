package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.dto.PatchValidationResult;
import com.learnbot.repository.CodeRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CodeAgentLocalPatchRequestService {
    private final CodePatchFileLoader fileLoader;
    private final PatchValidationService validationService;
    private final LocalAgentToolGatewayService toolGatewayService;
    private final CodeRepository codeRepository;
    private final LocalAgentGatewayService localAgentGatewayService;

    public CodeAgentLocalPatchRequestService(
            CodePatchFileLoader fileLoader,
            PatchValidationService validationService,
            LocalAgentToolGatewayService toolGatewayService,
            CodeRepository codeRepository,
            LocalAgentGatewayService localAgentGatewayService
    ) {
        this.fileLoader = fileLoader;
        this.validationService = validationService;
        this.toolGatewayService = toolGatewayService;
        this.codeRepository = codeRepository;
        this.localAgentGatewayService = localAgentGatewayService;
    }

    public LocalAgentToolExecutionResponse prepare(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            UUID loopId,
            String instruction,
            String diff,
            List<String> targetFiles
    ) {
        return prepare(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                instruction,
                diff,
                targetFiles,
                List.of()
        );
    }

    public LocalAgentToolExecutionResponse prepare(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            UUID loopId,
            String instruction,
            String diff,
            List<String> targetFiles,
            List<CodePatchFileLoader.LoadedPatchFile> observedFiles
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> normalizedTargets = fileLoader.normalizeRequestedPaths(targetFiles, warnings);
        PatchValidationResult validation = validationService.validate(diff, normalizedTargets);
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            throw new IllegalArgumentException("Patch did not pass server validation.");
        }
        ExpectedFiles expectedFiles = expectedFiles(repositoryId, normalizedTargets, observedFiles, warnings);
        if (expectedFiles.rows().isEmpty()) {
            throw new IllegalArgumentException("No safe indexed target files were available for patch.apply.");
        }
        CodeRepositoryRecord repository = codeRepository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
        LocalAgentWorkspaceSummary workspace = localAgentGatewayService.approvedWorkspace(userId, workspaceId)
                .orElse(null);
        warnings.add("Local workspace/repository identity is not verified yet. Patch execution release remains blocked until repository identity is matched against Local Agent workspace observations.");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", 1);
        input.put("repositoryId", repositoryId.toString());
        if (spaceId != null) {
            input.put("spaceId", spaceId.toString());
        }
        if (loopId != null) {
            input.put("loopId", loopId.toString());
        }
        input.put("sourceRepository", sourceRepositoryIdentity(repository));
        input.put("localWorkspace", localWorkspaceIdentity(workspaceId, workspace));
        input.put("workspaceVerification", Map.of(
                "status", "UNVERIFIED",
                "blocking", true,
                "reason", "The server has not yet matched this indexed repository to the selected Local Agent workspace checkout."
        ));
        input.put("instruction", safe(instruction));
        input.put("diff", safe(diff));
        input.put("targetFiles", List.copyOf(normalizedTargets));
        input.put("approvalRequestId", approvalRequestId(repositoryId, loopId, safe(diff), normalizedTargets));
        input.put("approvalPersistenceRequired", true);
        input.put("approvalPersisted", true);
        input.put("expectedFiles", expectedFiles.rows());
        input.put("expectedFileSource", expectedFiles.source());
        input.put("requiresSnapshot", true);
        input.put("snapshotPolicy", Map.of(
                "required", true,
                "scope", "TARGET_FILES",
                "location", "LOCAL_AGENT_MANAGED",
                "createBeforeMutation", true,
                "includeExpectedHashes", true
        ));
        input.put("rollbackPolicy", Map.of(
                "required", true,
                "tool", LocalAgentToolName.ROLLBACK_RESTORE.wireName(),
                "restoreScope", "SNAPSHOT_TARGET_FILES",
                "requiresUserApproval", true
        ));
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

    private ExpectedFiles expectedFiles(
            UUID repositoryId,
            List<String> normalizedTargets,
            List<CodePatchFileLoader.LoadedPatchFile> observedFiles,
            List<String> warnings
    ) {
        List<Map<String, Object>> observedRows = expectedFilesFromObservedReads(normalizedTargets, observedFiles);
        if (!observedRows.isEmpty() && observedRows.size() == normalizedTargets.size()) {
            warnings.add("Expected file hashes came from completed Local Agent file.read observations.");
            return new ExpectedFiles(observedRows, "local-agent-file-read");
        }
        CodePatchFileLoader.LoadResult loaded = fileLoader.load(repositoryId, normalizedTargets);
        warnings.addAll(loaded.warnings());
        List<Map<String, Object>> indexedRows = loaded.files().stream()
                .map(file -> expectedFileRow(file.path(), file.content()))
                .toList();
        return new ExpectedFiles(indexedRows, "indexed-loader");
    }

    private List<Map<String, Object>> expectedFilesFromObservedReads(
            List<String> normalizedTargets,
            List<CodePatchFileLoader.LoadedPatchFile> observedFiles
    ) {
        if (normalizedTargets == null || normalizedTargets.isEmpty() || observedFiles == null || observedFiles.isEmpty()) {
            return List.of();
        }
        Map<String, CodePatchFileLoader.LoadedPatchFile> byPath = new LinkedHashMap<>();
        for (CodePatchFileLoader.LoadedPatchFile file : observedFiles) {
            if (file == null || file.path() == null || file.content() == null) {
                continue;
            }
            String path = file.path().trim().replace('\\', '/').replaceAll("^/+", "");
            if (normalizedTargets.contains(path) && fileLoader.rejectionReason(path) == null) {
                byPath.put(path, file);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String target : normalizedTargets) {
            CodePatchFileLoader.LoadedPatchFile file = byPath.get(target);
            if (file == null) {
                return List.of();
            }
            rows.add(expectedFileRow(target, file.content()));
        }
        return List.copyOf(rows);
    }

    private Map<String, Object> expectedFileRow(String path, String content) {
        return Map.of(
                "path", path,
                "sha256", sha256(content),
                "bytes", content.getBytes(StandardCharsets.UTF_8).length
        );
    }

    public Map<String, Object> previewValidatedDryRunRequest(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            UUID loopId,
            Map<String, Object> validatedHandoff
    ) {
        Map<String, Object> handoff = validatedHandoff == null ? Map.of() : validatedHandoff;
        Map<String, Object> patchApplyInput = mapValue(handoff.get("patchApplyInput"));
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String sourceSchema = text(handoff.get("schema"));
        String sourceStatus = text(handoff.get("status"));
        String diff = safe(text(patchApplyInput.get("diff")));
        List<String> requestedTargets = stringList(patchApplyInput.get("targetFiles"));

        if (!"learnbot.local-agent.validated-revised-patch-dry-run-handoff.v1".equals(sourceSchema)) {
            blockers.add("validated handoff schema is missing or unsupported");
        }
        if (!sourceStatus.startsWith("READY")) {
            blockers.add("validated handoff is not ready for dry-run preview");
        }
        if (!Boolean.TRUE.equals(booleanValue(patchApplyInput.get("dryRunOnly")))) {
            blockers.add("patch.apply input must keep dryRunOnly=true");
        }
        if (Boolean.TRUE.equals(booleanValue(patchApplyInput.get("mutationAllowed")))) {
            blockers.add("patch.apply input must keep mutationAllowed=false");
        }
        if (diff.isBlank()) {
            blockers.add("patch.apply input must include a non-empty unified diff");
        }
        if (requestedTargets.isEmpty()) {
            blockers.add("patch.apply input must include targetFiles");
        }

        List<String> normalizedTargets = requestedTargets.isEmpty()
                ? List.of()
                : fileLoader.normalizeRequestedPaths(requestedTargets, warnings);
        if (!diff.isBlank() && !normalizedTargets.isEmpty()) {
            PatchValidationResult validation = validationService.validate(diff, normalizedTargets);
            warnings.addAll(validation.warnings());
            if (!validation.valid()) {
                blockers.add("patch did not pass server validation");
            }
        }

        CodeRepositoryRecord repository = codeRepository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
        LocalAgentWorkspaceSummary workspace = localAgentGatewayService.approvedWorkspace(userId, workspaceId)
                .orElse(null);

        List<Map<String, Object>> expectedFiles = List.of();
        if (!normalizedTargets.isEmpty()) {
            CodePatchFileLoader.LoadResult loaded = fileLoader.load(repositoryId, normalizedTargets);
            warnings.addAll(loaded.warnings());
            expectedFiles = loaded.files().stream()
                    .map(file -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", file.path());
                        row.put("sha256", sha256(file.content()));
                        row.put("bytes", file.content().getBytes(StandardCharsets.UTF_8).length);
                        return row;
                    })
                    .toList();
            if (expectedFiles.isEmpty()) {
                blockers.add("no safe indexed target files were available for patch.apply dry-run");
            }
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", 1);
        input.put("repositoryId", repositoryId.toString());
        if (spaceId != null) {
            input.put("spaceId", spaceId.toString());
        }
        if (loopId != null) {
            input.put("loopId", loopId.toString());
        }
        input.put("sourceRepository", sourceRepositoryIdentity(repository));
        input.put("localWorkspace", localWorkspaceIdentity(workspaceId, workspace));
        input.put("sourceHandoff", Map.of(
                "schema", sourceSchema,
                "status", sourceStatus,
                "consumed", blockers.isEmpty()
        ));
        input.put("diff", diff);
        input.put("targetFiles", List.copyOf(normalizedTargets));
        input.put("expectedFiles", expectedFiles);
        input.put("dryRunOnly", true);
        input.put("mutationAllowed", false);
        input.put("approvalRequiredBeforeMutation", true);
        input.put("sourceRequestId", text(patchApplyInput.get("sourceRequestId")));
        input.put("requiresSnapshot", true);
        input.put("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH");

        Map<String, Object> wouldBeRequest = new LinkedHashMap<>();
        wouldBeRequest.put("schema", "learnbot.server.validated-revised-patch-dry-run-request-preview.v1");
        wouldBeRequest.put("requestPersisted", false);
        wouldBeRequest.put("queueEnabled", false);
        wouldBeRequest.put("pushEnabled", false);
        wouldBeRequest.put("claimable", false);
        wouldBeRequest.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        wouldBeRequest.put("toolName", LocalAgentToolName.PATCH_APPLY.wireName());
        wouldBeRequest.put("approvalState", LocalAgentApprovalState.REQUIRED.name());
        wouldBeRequest.put("status", "APPROVAL_REQUIRED_PREVIEW");
        wouldBeRequest.put("dryRunOnly", true);
        wouldBeRequest.put("mutationAllowed", false);
        wouldBeRequest.put("approvalRequiredBeforeMutation", true);
        wouldBeRequest.put("userId", userId.toString());
        wouldBeRequest.put("agentId", agentId.toString());
        wouldBeRequest.put("workspaceId", workspaceId.toString());
        wouldBeRequest.put("input", input);

        Map<String, Object> approvalPrerequisites = new LinkedHashMap<>();
        approvalPrerequisites.put("approvalObjectPersisted", false);
        approvalPrerequisites.put("explicitUserApprovalRequiredBeforeMutation", true);
        approvalPrerequisites.put("freshDryRunRequired", true);
        approvalPrerequisites.put("snapshotRequired", true);
        approvalPrerequisites.put("repositoryVerificationRequired", true);
        approvalPrerequisites.put("releaseGateEnabled", false);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema", "learnbot.server.validated-revised-patch-dry-run-preview.v1");
        response.put("status", blockers.isEmpty() ? "READY_QUEUE_PREVIEW_DISABLED" : "BLOCKED");
        response.put("ready", blockers.isEmpty());
        response.put("queueEnabled", false);
        response.put("requestPersisted", false);
        response.put("claimable", false);
        response.put("dryRunOnly", true);
        response.put("mutationAllowed", false);
        response.put("blockers", List.copyOf(blockers));
        response.put("warnings", List.copyOf(warnings));
        response.put("approvalPrerequisites", approvalPrerequisites);
        response.put("wouldBeRequest", wouldBeRequest);
        return response;
    }

    public Map<String, Object> persistValidatedDryRunIntent(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            UUID loopId,
            Map<String, Object> validatedHandoff
    ) {
        Map<String, Object> preview = previewValidatedDryRunRequest(
                repositoryId,
                spaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                validatedHandoff
        );
        if (!Boolean.TRUE.equals(preview.get("ready"))) {
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("schema", "learnbot.server.validated-revised-patch-dry-run-intent.v1");
            blocked.put("status", "BLOCKED_PREVIEW_NOT_READY");
            blocked.put("ready", false);
            blocked.put("intentPersisted", false);
            blocked.put("requestPersisted", false);
            blocked.put("queueEnabled", false);
            blocked.put("pushEnabled", false);
            blocked.put("claimable", false);
            blocked.put("dryRunOnly", true);
            blocked.put("mutationAllowed", false);
            blocked.put("preview", preview);
            blocked.put("blockers", preview.getOrDefault("blockers", List.of()));
            return blocked;
        }

        Map<String, Object> wouldBeRequest = mapValue(preview.get("wouldBeRequest"));
        Map<String, Object> input = mapValue(wouldBeRequest.get("input"));
        List<String> targetFiles = stringList(input.get("targetFiles"));
        input.put("validatedDryRunIntent", true);
        input.put("dryRunIntentPersisted", true);
        input.put("approvalRequestId", approvalRequestId(repositoryId, loopId, safe(text(input.get("diff"))), targetFiles));
        input.put("approvalPersistenceRequired", true);
        input.put("approvalPersisted", true);
        input.put("requestPersisted", true);
        input.put("queueEnabled", false);
        input.put("pushEnabled", false);
        input.put("claimable", false);
        input.put("dryRunOnly", true);
        input.put("mutationAllowed", false);

        LocalAgentToolExecutionResponse persisted = toolGatewayService.createApprovalRequest(new LocalAgentToolRequest(
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                input,
                LocalAgentApprovalState.REQUIRED,
                null,
                List.of("Validated revised patch dry-run intent is persisted for approval review only; queue, push, claim, and mutation remain disabled.")
        ));

        Map<String, Object> persistedSummary = new LinkedHashMap<>();
        persistedSummary.put("requestId", persisted.requestId().toString());
        persistedSummary.put("sessionId", persisted.sessionId().toString());
        persistedSummary.put("status", persisted.status().name());
        persistedSummary.put("approvalState", persisted.approvalState().name());
        persistedSummary.put("toolName", persisted.toolName().wireName());
        persistedSummary.put("executionTarget", persisted.executionTarget().name());
        persistedSummary.put("requestPersisted", true);
        persistedSummary.put("queueEnabled", false);
        persistedSummary.put("pushEnabled", false);
        persistedSummary.put("claimable", false);
        persistedSummary.put("dryRunOnly", true);
        persistedSummary.put("mutationAllowed", false);
        persistedSummary.put("approvalRequiredBeforeMutation", true);
        persistedSummary.put("input", persisted.input());
        persistedSummary.put("requestWarnings", persisted.requestWarnings());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema", "learnbot.server.validated-revised-patch-dry-run-intent.v1");
        response.put("status", "PERSISTED_APPROVAL_REQUIRED_NON_CLAIMABLE");
        response.put("ready", true);
        response.put("intentPersisted", true);
        response.put("requestPersisted", true);
        response.put("queueEnabled", false);
        response.put("pushEnabled", false);
        response.put("claimable", false);
        response.put("dryRunOnly", true);
        response.put("mutationAllowed", false);
        response.put("approvalRequiredBeforeMutation", true);
        response.put("preview", preview);
        response.put("persistedRequest", persistedSummary);
        response.put("blockers", List.of());
        return response;
    }

    public Map<String, Object> inspectValidatedDryRunIntentEligibility(UUID userId, UUID requestId) {
        LocalAgentToolExecutionResponse request = toolGatewayService.findForUser(userId, requestId)
                .orElseThrow(() -> new IllegalArgumentException("Validated dry-run intent request was not found."));
        return buildValidatedDryRunIntentEligibility(request);
    }

    public Map<String, Object> previewValidatedDryRunIntentClaimableDryRun(UUID userId, UUID requestId) {
        LocalAgentToolExecutionResponse request = toolGatewayService.findForUser(userId, requestId)
                .orElseThrow(() -> new IllegalArgumentException("Validated dry-run intent request was not found."));
        Map<String, Object> eligibility = buildValidatedDryRunIntentEligibility(request);
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        boolean prerequisitesPassed = Boolean.TRUE.equals(booleanValue(eligibility.get("prerequisitesPassed")));
        List<String> blockingKeys = stringList(eligibility.get("blockingKeys"));
        Map<String, Object> wouldBeInput = new LinkedHashMap<>();
        wouldBeInput.put("schemaVersion", 1);
        wouldBeInput.put("sourceIntentRequestId", request.requestId().toString());
        wouldBeInput.put("sourceIntentSessionId", request.sessionId().toString());
        putIfText(wouldBeInput, "repositoryId", text(input.get("repositoryId")));
        putIfText(wouldBeInput, "spaceId", text(input.get("spaceId")));
        putIfText(wouldBeInput, "loopId", text(input.get("loopId")));
        wouldBeInput.put("sourceRepository", mapValue(input.get("sourceRepository")));
        wouldBeInput.put("localWorkspace", mapValue(input.get("localWorkspace")));
        wouldBeInput.put("targetFiles", stringList(input.get("targetFiles")));
        wouldBeInput.put("expectedFiles", input.getOrDefault("expectedFiles", List.of()));
        wouldBeInput.put("sourceRequestId", text(input.get("sourceRequestId")));
        wouldBeInput.put("dryRunOnly", true);
        wouldBeInput.put("mutationAllowed", false);
        wouldBeInput.put("approvalRequiredBeforeMutation", true);
        wouldBeInput.put("claimableDryRunOnly", true);
        wouldBeInput.put("intentEligibilityStatus", eligibility.get("status"));

        Map<String, Object> wouldBeClaimableRequest = new LinkedHashMap<>();
        wouldBeClaimableRequest.put("schema", "learnbot.server.validated-revised-patch-claimable-dry-run-request-preview.v1");
        wouldBeClaimableRequest.put("status", prerequisitesPassed ? "READY_REQUEST_CREATION_DISABLED" : "BLOCKED_PREREQUISITES");
        wouldBeClaimableRequest.put("requestPersisted", false);
        wouldBeClaimableRequest.put("requestCreationEnabled", false);
        wouldBeClaimableRequest.put("queueEnabled", false);
        wouldBeClaimableRequest.put("pushEnabled", false);
        wouldBeClaimableRequest.put("claimEnabled", false);
        wouldBeClaimableRequest.put("claimable", false);
        wouldBeClaimableRequest.put("dryRunOnly", true);
        wouldBeClaimableRequest.put("mutationAllowed", false);
        wouldBeClaimableRequest.put("approvalBypassAllowed", false);
        wouldBeClaimableRequest.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        wouldBeClaimableRequest.put("toolName", LocalAgentToolName.PATCH_APPLY.wireName());
        wouldBeClaimableRequest.put("approvalState", LocalAgentApprovalState.REQUIRED.name());
        wouldBeClaimableRequest.put("sourceIntentRequestId", request.requestId().toString());
        wouldBeClaimableRequest.put("input", wouldBeInput);

        Map<String, Object> transitionGate = new LinkedHashMap<>();
        transitionGate.put("schema", "learnbot.server.validated-revised-patch-dry-run-transition-gate.v1");
        transitionGate.put("status", prerequisitesPassed ? "READY_TRANSITION_DISABLED" : "BLOCKED_TRANSITION_DISABLED");
        transitionGate.put("prerequisitesPassed", prerequisitesPassed);
        transitionGate.put("requestCreationEnabled", false);
        transitionGate.put("queueEnabled", false);
        transitionGate.put("pushEnabled", false);
        transitionGate.put("claimEnabled", false);
        transitionGate.put("claimable", false);
        transitionGate.put("dryRunOnly", true);
        transitionGate.put("mutationAllowed", false);
        transitionGate.put("approvalBypassAllowed", false);
        transitionGate.put("blockingKeys", blockingKeys);
        transitionGate.put("message", prerequisitesPassed
                ? "A future claimable non-mutating dry-run request can be shaped from this intent, but request creation, queue, push, claim, approval bypass, and mutation remain disabled."
                : "The persisted dry-run intent is not eligible for a future claimable non-mutating dry-run request.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema", "learnbot.server.validated-revised-patch-dry-run-transition-preview.v1");
        response.put("status", prerequisitesPassed ? "READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED" : "BLOCKED_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED");
        response.put("sourceIntentRequestId", request.requestId().toString());
        response.put("sourceIntentSessionId", request.sessionId().toString());
        response.put("agentId", request.agentId().toString());
        response.put("workspaceId", request.workspaceId().toString());
        response.put("prerequisitesPassed", prerequisitesPassed);
        response.put("requestPersisted", false);
        response.put("requestCreationEnabled", false);
        response.put("queueEnabled", false);
        response.put("pushEnabled", false);
        response.put("claimEnabled", false);
        response.put("claimable", false);
        response.put("dryRunOnly", true);
        response.put("mutationAllowed", false);
        response.put("approvalBypassAllowed", false);
        response.put("blockingKeys", blockingKeys);
        response.put("eligibility", eligibility);
        response.put("transitionGate", transitionGate);
        response.put("wouldBeClaimableDryRunRequest", wouldBeClaimableRequest);
        response.put("message", "This is a disabled transition preview only; it creates no request, queues nothing, pushes nothing, and makes no Local Agent work claimable.");
        return response;
    }

    public Map<String, Object> releaseValidatedDryRunIntentClaimableDryRun(UUID userId, UUID requestId) {
        Map<String, Object> preview = previewValidatedDryRunIntentClaimableDryRun(userId, requestId);
        boolean prerequisitesPassed = Boolean.TRUE.equals(booleanValue(preview.get("prerequisitesPassed")));
        List<String> blockingKeys = new ArrayList<>(stringList(preview.get("blockingKeys")));
        boolean requestCreationEnabled = false;
        if (!requestCreationEnabled) {
            blockingKeys.add("requestCreationEnabled");
        }

        Map<String, Object> releaseGate = new LinkedHashMap<>();
        releaseGate.put("schema", "learnbot.server.validated-revised-patch-claimable-dry-run-release-gate.v1");
        releaseGate.put("status", prerequisitesPassed ? "REFUSED_REQUEST_CREATION_DISABLED" : "BLOCKED_PREREQUISITES");
        releaseGate.put("prerequisitesPassed", prerequisitesPassed);
        releaseGate.put("requestCreationEnabled", false);
        releaseGate.put("queueEnabled", false);
        releaseGate.put("pushEnabled", false);
        releaseGate.put("claimEnabled", false);
        releaseGate.put("claimable", false);
        releaseGate.put("dryRunOnly", true);
        releaseGate.put("mutationAllowed", false);
        releaseGate.put("approvalBypassAllowed", false);
        releaseGate.put("blockingKeys", List.copyOf(blockingKeys));
        releaseGate.put("message", prerequisitesPassed
                ? "The persisted dry-run intent is eligible, but claimable dry-run request creation is disabled."
                : "The persisted dry-run intent is not eligible for claimable dry-run release.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema", "learnbot.server.validated-revised-patch-claimable-dry-run-release.v1");
        response.put("status", prerequisitesPassed ? "REFUSED_CLAIMABLE_DRY_RUN_CREATION_DISABLED" : "BLOCKED_CLAIMABLE_DRY_RUN_PREREQUISITES");
        response.put("sourceIntentRequestId", preview.get("sourceIntentRequestId"));
        response.put("sourceIntentSessionId", preview.get("sourceIntentSessionId"));
        response.put("agentId", preview.get("agentId"));
        response.put("workspaceId", preview.get("workspaceId"));
        response.put("prerequisitesPassed", prerequisitesPassed);
        response.put("requestPersisted", false);
        response.put("requestCreated", false);
        response.put("queued", false);
        response.put("pushed", false);
        response.put("requestCreationEnabled", false);
        response.put("queueEnabled", false);
        response.put("pushEnabled", false);
        response.put("claimEnabled", false);
        response.put("claimable", false);
        response.put("dryRunOnly", true);
        response.put("mutationAllowed", false);
        response.put("approvalBypassAllowed", false);
        response.put("releaseGate", releaseGate);
        response.put("transitionPreview", preview);
        response.put("wouldBeClaimableDryRunRequest", preview.get("wouldBeClaimableDryRunRequest"));
        response.put("blockingKeys", List.copyOf(blockingKeys));
        response.put("message", "Claimable non-mutating dry-run release is modeled as a guarded POST boundary, but request creation, queue, push, claim, approval bypass, and mutation remain disabled.");
        return response;
    }

    private Map<String, Object> buildValidatedDryRunIntentEligibility(LocalAgentToolExecutionResponse request) {
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(eligibilityCheck("patchApplyTool", request.toolName() == LocalAgentToolName.PATCH_APPLY, "Persisted intent must be a patch.apply Local Agent request."));
        checks.add(eligibilityCheck("userLocalAgentTarget", request.executionTarget() == AgentExecutionTarget.USER_LOCAL_AGENT, "Persisted intent must target the user's Local Agent."));
        checks.add(eligibilityCheck("approvalRequired", request.approvalState() == LocalAgentApprovalState.REQUIRED, "Intent must remain approval-required before any future dry-run release."));
        checks.add(eligibilityCheck("validatedDryRunIntent", Boolean.TRUE.equals(booleanValue(input.get("validatedDryRunIntent"))), "Intent must come from the validated revised-patch dry-run handoff."));
        checks.add(eligibilityCheck("intentPersisted", Boolean.TRUE.equals(booleanValue(input.get("dryRunIntentPersisted"))), "Intent must be durably persisted before review."));
        checks.add(eligibilityCheck("dryRunOnly", Boolean.TRUE.equals(booleanValue(input.get("dryRunOnly"))), "Future request must remain dry-run only."));
        checks.add(eligibilityCheck("mutationDisabled", !Boolean.TRUE.equals(booleanValue(input.get("mutationAllowed"))), "Mutation must remain disabled for this dry-run intent."));
        checks.add(eligibilityCheck("targetFilesPresent", !stringList(input.get("targetFiles")).isEmpty(), "Intent must carry target files."));
        checks.add(eligibilityCheck("diffPresent", !text(input.get("diff")).isBlank(), "Intent must carry the validated unified diff."));
        boolean prerequisitesPassed = checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
        List<String> blockingKeys = checks.stream()
                .filter(check -> !Boolean.TRUE.equals(check.get("passed")))
                .map(check -> text(check.get("key")))
                .toList();

        Map<String, Object> futureDryRunReleaseGate = new LinkedHashMap<>();
        futureDryRunReleaseGate.put("schema", "learnbot.server.validated-revised-patch-dry-run-release-gate.v1");
        futureDryRunReleaseGate.put("status", prerequisitesPassed ? "READY_RELEASE_DISABLED" : "BLOCKED_RELEASE_DISABLED");
        futureDryRunReleaseGate.put("prerequisitesPassed", prerequisitesPassed);
        futureDryRunReleaseGate.put("requestCreationEnabled", false);
        futureDryRunReleaseGate.put("queueEnabled", false);
        futureDryRunReleaseGate.put("pushEnabled", false);
        futureDryRunReleaseGate.put("claimEnabled", false);
        futureDryRunReleaseGate.put("claimable", false);
        futureDryRunReleaseGate.put("dryRunOnly", true);
        futureDryRunReleaseGate.put("mutationAllowed", false);
        futureDryRunReleaseGate.put("approvalBypassAllowed", false);
        futureDryRunReleaseGate.put("blockingKeys", blockingKeys);
        futureDryRunReleaseGate.put("message", prerequisitesPassed
                ? "Validated dry-run intent prerequisites are visible, but release to a claimable Local Agent dry-run remains disabled."
                : "Validated dry-run intent is missing required prerequisites and release remains disabled.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema", "learnbot.server.validated-revised-patch-dry-run-eligibility.v1");
        response.put("status", prerequisitesPassed ? "READY_DRY_RUN_RELEASE_DISABLED" : "BLOCKED_DRY_RUN_RELEASE_DISABLED");
        response.put("requestId", request.requestId().toString());
        response.put("sessionId", request.sessionId().toString());
        response.put("agentId", request.agentId().toString());
        response.put("workspaceId", request.workspaceId().toString());
        response.put("toolName", request.toolName().wireName());
        response.put("executionTarget", request.executionTarget().name());
        response.put("approvalState", request.approvalState().name());
        response.put("requestStatus", request.status().name());
        response.put("validatedDryRunIntent", Boolean.TRUE.equals(booleanValue(input.get("validatedDryRunIntent"))));
        response.put("dryRunIntentPersisted", Boolean.TRUE.equals(booleanValue(input.get("dryRunIntentPersisted"))));
        response.put("targetFiles", stringList(input.get("targetFiles")));
        response.put("requestPersisted", true);
        response.put("requestCreationEnabled", false);
        response.put("queueEnabled", false);
        response.put("pushEnabled", false);
        response.put("claimEnabled", false);
        response.put("claimable", false);
        response.put("dryRunOnly", true);
        response.put("mutationAllowed", false);
        response.put("approvalBypassAllowed", false);
        response.put("prerequisitesPassed", prerequisitesPassed);
        response.put("blockingKeys", blockingKeys);
        response.put("checks", checks);
        response.put("futureDryRunReleaseGate", futureDryRunReleaseGate);
        response.put("message", "This is a disabled eligibility read model only; it creates no request, pushes nothing, and makes no Local Agent work claimable.");
        return response;
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(safe(content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private String approvalRequestId(UUID repositoryId, UUID loopId, String diff, List<String> targetFiles) {
        String seed = repositoryId + "\n"
                + (loopId == null ? "" : loopId) + "\n"
                + String.join("\n", targetFiles == null ? List.of() : targetFiles) + "\n"
                + diff;
        return "apr-" + sha256(seed).substring(0, 16);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> sourceRepositoryIdentity(CodeRepositoryRecord repository) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("id", repository.id().toString());
        identity.put("name", safe(repository.name()));
        identity.put("sourceType", safe(repository.sourceType()));
        putIfText(identity, "sourceLabel", repository.sourceLabel());
        putIfText(identity, "sourceHash", repository.sourceHash());
        putIfText(identity, "gitUrl", repository.gitUrl());
        putIfText(identity, "branch", repository.branch());
        putIfText(identity, "lastIndexedCommit", repository.lastIndexedCommit());
        putIfText(identity, "localPath", repository.localPath());
        return identity;
    }

    private Map<String, Object> localWorkspaceIdentity(UUID workspaceId, LocalAgentWorkspaceSummary workspace) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("workspaceId", workspaceId.toString());
        if (workspace != null) {
            identity.put("name", safe(workspace.name()));
            identity.put("rootPath", safe(workspace.rootPath()));
            identity.put("approved", workspace.approved());
        } else {
            identity.put("approved", false);
        }
        return identity;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(Objects.toString(key, ""), item));
            return result;
        }
        return Map.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : item.toString().trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private Map<String, Object> eligibilityCheck(String key, boolean passed, String message) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("key", key);
        check.put("passed", passed);
        check.put("blocking", !passed);
        check.put("message", message);
        return check;
    }

    private record ExpectedFiles(List<Map<String, Object>> rows, String source) {
    }
}
