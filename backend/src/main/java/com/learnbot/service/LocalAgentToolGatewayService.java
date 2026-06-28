package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessCheck;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentPatchReleaseAttemptEvidenceRequirement;
import com.learnbot.dto.LocalAgentPatchReleaseAttemptModel;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.repository.LocalAgentPatchReleaseAttemptRepository;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class LocalAgentToolGatewayService {
    private final LocalAgentToolExecutionRepository repository;
    private final LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository;
    private final LocalAgentGatewayService gatewayService;
    private final LocalAgentToolPusher toolPusher;

    public LocalAgentToolGatewayService(
            LocalAgentToolExecutionRepository repository,
            LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository,
            LocalAgentGatewayService gatewayService,
            LocalAgentToolPusher toolPusher
    ) {
        this.repository = repository;
        this.releaseAttemptRepository = releaseAttemptRepository;
        this.gatewayService = gatewayService;
        this.toolPusher = toolPusher;
    }

    @Transactional
    public LocalAgentQueuedToolRequest enqueue(LocalAgentToolRequest request) {
        if (request.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException("Only USER_LOCAL_AGENT tool requests can be routed through the Local Agent gateway.");
        }
        if (request.toolName().isSideEffectful() && request.approvalState() != LocalAgentApprovalState.APPROVED) {
            throw new IllegalArgumentException("Side-effectful Local Agent tools must be approved before routing.");
        }
        if (!gatewayService.isConnected(request.userId(), request.agentId())) {
            throw new IllegalStateException("Local Agent is not connected.");
        }
        if (request.workspaceId() != null && !gatewayService.hasApprovedWorkspace(request.userId(), request.workspaceId())) {
            throw new IllegalStateException("Workspace is not approved by the Local Agent.");
        }
        UUID requestId = UUID.randomUUID();
        LocalAgentToolExecution execution = repository.create(requestId, request);
        LocalAgentQueuedToolRequest queued = toQueuedRequest(execution);
        toolPusher.sendToolRequest(queued);
        return queued;
    }

    @Transactional
    public LocalAgentQueuedToolRequest enqueueReadOnly(LocalAgentToolRequest request) {
        if (request.toolName() != LocalAgentToolName.FILE_READ
                && request.toolName() != LocalAgentToolName.GIT_STATUS
                && request.toolName() != LocalAgentToolName.GIT_DIFF) {
            throw new IllegalArgumentException("Only file.read, git.status, and git.diff can be queued through this read-only path.");
        }
        return enqueue(request);
    }

    @Transactional
    public LocalAgentToolExecutionResponse createApprovalRequest(LocalAgentToolRequest request) {
        if (request.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException("Only USER_LOCAL_AGENT tool requests can be prepared for Local Agent approval.");
        }
        if (!request.toolName().isSideEffectful()) {
            throw new IllegalArgumentException("Only side-effectful Local Agent tools require an approval request.");
        }
        if (request.approvalState() != LocalAgentApprovalState.REQUIRED) {
            throw new IllegalArgumentException("Approval requests must start with REQUIRED approval state.");
        }
        if (!gatewayService.isConnected(request.userId(), request.agentId())) {
            throw new IllegalStateException("Local Agent is not connected.");
        }
        if (request.workspaceId() != null && !gatewayService.hasApprovedWorkspace(request.userId(), request.workspaceId())) {
            throw new IllegalStateException("Workspace is not approved by the Local Agent.");
        }
        UUID requestId = UUID.randomUUID();
        return toResponse(repository.create(requestId, request));
    }

    @Transactional
    public LocalAgentToolExecutionResponse approveHeld(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = approvalCandidate(userId, requestId);
        return repository.updateApprovalDecision(
                        execution.id(),
                        userId,
                        LocalAgentApprovalState.APPROVED,
                        LocalAgentToolStatus.APPROVED_HELD,
                        "Approved by user. Execution remains held until Local Agent patch execution is enabled."
                )
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Local Agent approval request is no longer awaiting approval."));
    }

    @Transactional
    public LocalAgentToolExecutionResponse deny(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = approvalCandidate(userId, requestId);
        return repository.updateApprovalDecision(
                        execution.id(),
                        userId,
                        LocalAgentApprovalState.DENIED,
                        LocalAgentToolStatus.REJECTED,
                        "Denied by user before Local Agent execution."
                )
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Local Agent approval request is no longer awaiting approval."));
    }

    public LocalAgentPatchExecutionReadinessResponse inspectPatchExecutionReadiness(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent patch request was not found."));
        if (execution.toolName() != LocalAgentToolName.PATCH_APPLY) {
            throw new IllegalArgumentException("Execution readiness is available only for patch.apply requests.");
        }

        List<LocalAgentPatchExecutionReadinessCheck> checks = new ArrayList<>();
        checks.add(check(
                "approvedHeld",
                execution.approvalState() == LocalAgentApprovalState.APPROVED
                        && execution.status() == LocalAgentToolStatus.APPROVED_HELD,
                "Request must be approved and held before release."
        ));
        checks.add(check(
                "executionTarget",
                execution.executionTarget() == AgentExecutionTarget.USER_LOCAL_AGENT,
                "Patch must target USER_LOCAL_AGENT."
        ));

        LocalAgentStatusResponse status = gatewayService.status(userId);
        checks.add(check(
                "agentConnected",
                status.state() == LocalAgentConnectionState.CONNECTED
                        && execution.agentId() != null
                        && execution.agentId().equals(status.agentId()),
                "The selected Local Agent must be connected and match the request."
        ));
        checks.add(check(
                "workspaceApproved",
                execution.workspaceId() != null && gatewayService.hasApprovedWorkspace(userId, execution.workspaceId()),
                "The request workspace must still be approved by the Local Agent."
        ));
        checks.add(check(
                "patchCapability",
                status.capabilities().contains(LocalAgentToolName.PATCH_APPLY.wireName()),
                "The connected Local Agent must advertise patch.apply capability."
        ));
        checks.add(check(
                "rollbackCapability",
                status.capabilities().contains(LocalAgentToolName.ROLLBACK_RESTORE.wireName()),
                "The connected Local Agent must advertise rollback.restore capability before patch execution can be released."
        ));

        Map<String, Object> input = execution.input();
        Map<String, Object> repositoryVerification = repository
                .findLatestRepositoryVerificationForSourceRequest(userId, execution.id())
                .orElse(null);
        Map<String, Object> latestPatchDryRunOutput = repository
                .findLatestPatchDryRunOutputForSourceRequest(userId, execution.id())
                .orElse(null);
        Map<String, Object> snapshotReadiness = snapshotReadiness(latestPatchDryRunOutput);
        Map<String, Object> rollbackReadiness = rollbackReadiness(latestPatchDryRunOutput);
        Map<String, Object> workspaceVerification = effectiveWorkspaceVerification(
                input.get("workspaceVerification"),
                repositoryVerification
        );
        checks.add(check(
                "inputSchema",
                numberValue(input.get("schemaVersion")) == 1,
                "Patch request input schema must be version 1."
        ));
        checks.add(check(
                "diffPresent",
                hasText(input.get("diff")),
                "A validated unified diff must be present."
        ));
        checks.add(check(
                "targetFilesPresent",
                nonEmptyList(input.get("targetFiles")),
                "At least one target file must be present."
        ));
        checks.add(check(
                "expectedFilesPresent",
                hasExpectedFiles(input.get("expectedFiles")),
                "Expected file hashes must be present for context validation."
        ));
        checks.add(check(
                "snapshotRequired",
                Boolean.TRUE.equals(input.get("requiresSnapshot")),
                "A snapshot must be required before file writes."
        ));
        checks.add(check(
                "snapshotPolicy",
                validSnapshotPolicy(input.get("snapshotPolicy")),
                "Snapshot policy must require target-file snapshots managed by the Local Agent before mutation."
        ));
        checks.add(check(
                "rollbackPolicy",
                validRollbackPolicy(input.get("rollbackPolicy")),
                "Rollback policy must require rollback.restore for snapshot target files and user approval."
        ));
        checks.add(check(
                "snapshotManifestPreview",
                validSnapshotManifestPreview(latestPatchDryRunOutput),
                "Latest Local Agent dry-run must provide a managed snapshot manifest with schema, id, path, target files, and a matching snapshotCreated state."
        ));
        checks.add(check(
                "rollbackRestorePreconditions",
                validRollbackRestorePreconditions(latestPatchDryRunOutput),
                "Latest Local Agent dry-run must provide rollback restore preconditions before release can be considered."
        ));
        checks.add(check(
                "staleIndexPolicy",
                "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH".equals(input.get("staleIndexPolicy")),
                "Stale-index policy must require expected hash or context match."
        ));
        checks.add(check(
                "workspaceRepositoryVerified",
                workspaceRepositoryVerified(workspaceVerification),
                "The selected Local Agent workspace must be verified against the indexed repository identity before release."
        ));
        checks.add(check(
                "releaseGateEnabled",
                false,
                "Patch execution release remains disabled until Local Agent patch.apply and rollback safety tests are implemented."
        ));

        boolean ready = checks.stream().allMatch(LocalAgentPatchExecutionReadinessCheck::passed);
        List<String> warnings = ready
                ? List.of()
                : checks.stream()
                .filter(item -> !item.passed())
                .map(LocalAgentPatchExecutionReadinessCheck::message)
                .toList();
        Map<String, Object> patchReleaseReadiness = patchReleaseReadiness(
                checks,
                latestPatchDryRunOutput,
                snapshotReadiness,
                rollbackReadiness,
                workspaceVerification
        );
        Optional<LocalAgentPatchReleaseAttempt> latestReleaseAttempt = Optional
                .ofNullable(releaseAttemptRepository.findLatestForSourceRequest(userId, execution.id()))
                .flatMap(item -> item);
        LocalAgentPatchReleaseAttemptModel releaseAttemptModel = releaseAttemptModel(latestReleaseAttempt);
        Map<String, Object> patchExecutionGate = patchExecutionGate(patchReleaseReadiness, latestPatchDryRunOutput, releaseAttemptModel);
        return new LocalAgentPatchExecutionReadinessResponse(
                execution.id(),
                ready,
                List.copyOf(checks),
                warnings,
                ready
                        ? "Held patch request is ready to release."
                        : "Held patch request is not ready for Local Agent execution.",
                patchReleaseReadiness,
                patchExecutionGate,
                releaseAttemptModel,
                snapshotReadiness,
                rollbackReadiness,
                repositoryVerification,
                workspaceVerification
        );
    }

    @Transactional
    public LocalAgentToolExecutionResponse releaseHeldPatchForExecution(UUID userId, UUID requestId) {
        LocalAgentPatchExecutionReadinessResponse readiness = inspectPatchExecutionReadiness(userId, requestId);
        Map<String, Object> gate = readiness.patchExecutionGate();
        if (!Boolean.TRUE.equals(gate.get("preconditionsPassed"))) {
            throw new IllegalStateException("Patch execution gate is not ready.");
        }
        if (!patchExecutionReleaseEnabled()) {
            createDisabledReleaseAttemptIfMissing(userId, requestId, readiness);
            throw new IllegalStateException("Patch execution release is disabled.");
        }
        return repository.releaseApprovedHeldPatch(
                        requestId,
                        userId,
                        "Patch execution release gate passed. Request is now claimable by the selected Local Agent."
                )
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Held patch request is no longer releasable."));
    }

    private void createDisabledReleaseAttemptIfMissing(
            UUID userId,
            UUID requestId,
            LocalAgentPatchExecutionReadinessResponse readiness
    ) {
        Optional<LocalAgentPatchReleaseAttempt> latestAttempt = Optional
                .ofNullable(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId))
                .flatMap(item -> item);
        if (latestAttempt.isPresent()) {
            return;
        }
        LocalAgentToolExecution source = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent patch request was not found."));
        releaseAttemptRepository.createDisabled(
                UUID.randomUUID(),
                source,
                readiness.releaseAttemptModel().staleWindowSeconds(),
                disabledReleaseAttemptEvidence(readiness),
                List.of("Patch execution release is disabled; attempt remains non-claimable.")
        );
    }

    private Map<String, Object> disabledReleaseAttemptEvidence(LocalAgentPatchExecutionReadinessResponse readiness) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceRequestId", readiness.requestId());
        evidence.put("repositoryVerification", readiness.repositoryVerification());
        evidence.put("workspaceVerification", readiness.workspaceVerification());
        evidence.put("snapshotReadiness", readiness.snapshotReadiness());
        evidence.put("rollbackReadiness", readiness.rollbackReadiness());
        evidence.put("patchReleaseReadiness", readiness.patchReleaseReadiness());
        evidence.put("patchExecutionGate", Map.of(
                "status", readiness.patchExecutionGate().get("status"),
                "preconditionsPassed", readiness.patchExecutionGate().get("preconditionsPassed"),
                "releaseGateEnabled", false,
                "claimEnabled", false,
                "writeHelperEnabled", false,
                "mutationEnabled", false
        ));
        evidence.put("preReleaseRevalidation", readiness.patchExecutionGate().get("preReleaseRevalidation"));
        evidence.put("claimable", false);
        evidence.put("message", "Disabled release attempt envelope captured visible readiness evidence without making the held patch claimable.");
        return evidence;
    }

    @Transactional
    public LocalAgentQueuedToolRequest enqueuePatchDryRun(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent patch request was not found."));
        if (execution.toolName() != LocalAgentToolName.PATCH_APPLY) {
            throw new IllegalArgumentException("Dry-run dispatch is available only for patch.apply requests.");
        }
        if (execution.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException("Dry-run dispatch requires USER_LOCAL_AGENT target.");
        }
        if (execution.approvalState() != LocalAgentApprovalState.APPROVED
                || execution.status() != LocalAgentToolStatus.APPROVED_HELD) {
            throw new IllegalArgumentException("Patch dry-run requires an approved-held request.");
        }

        Map<String, Object> dryRunInput = new LinkedHashMap<>(execution.input());
        dryRunInput.put("dryRunOnly", true);
        dryRunInput.put("mutationAllowed", false);
        dryRunInput.put("sourceRequestId", execution.id().toString());

        List<String> warnings = new ArrayList<>(execution.requestWarnings());
        warnings.add("Dry-run clone of approved-held patch request. Mutation remains disabled and the source request stays held.");

        LocalAgentToolRequest dryRunRequest = new LocalAgentToolRequest(
                execution.sessionId(),
                execution.userId(),
                execution.agentId(),
                execution.workspaceId(),
                execution.executionTarget(),
                execution.toolName(),
                dryRunInput,
                LocalAgentApprovalState.APPROVED,
                null,
                warnings
        );
        return enqueue(dryRunRequest);
    }

    @Transactional
    public Optional<LocalAgentQueuedToolRequest> claimNext(UUID userId, UUID agentId) {
        return repository.claimNext(userId, agentId)
                .map(this::toQueuedRequest);
    }

    @Transactional
    public void complete(LocalAgentToolResponse response) {
        LocalAgentToolResponse enriched = enrichRepositoryVerification(response);
        repository.complete(enriched);
    }

    public Optional<LocalAgentToolExecutionResponse> findForUser(UUID userId, UUID requestId) {
        return repository.find(requestId)
                .filter(execution -> execution.userId().equals(userId))
                .map(this::toResponse);
    }

    private LocalAgentPatchExecutionReadinessCheck check(String key, boolean passed, String message) {
        return new LocalAgentPatchExecutionReadinessCheck(key, passed, message);
    }

    private Map<String, Object> patchReleaseReadiness(
            List<LocalAgentPatchExecutionReadinessCheck> checks,
            Map<String, Object> dryRunOutput,
            Map<String, Object> snapshotReadiness,
            Map<String, Object> rollbackReadiness,
            Map<String, Object> workspaceVerification
    ) {
        List<Map<String, Object>> prerequisites = new ArrayList<>();
        prerequisites.add(releasePrerequisite(
                "explicitApproval",
                checkPassed(checks, "approvedHeld"),
                "Patch request must be explicitly approved and held."
        ));
        prerequisites.add(releasePrerequisite(
                "repositoryVerified",
                workspaceRepositoryVerified(workspaceVerification),
                "Selected Local Agent workspace must match the indexed repository."
        ));
        prerequisites.add(releasePrerequisite(
                "hashOrContextPreflight",
                dryRunOutput != null
                        && Boolean.TRUE.equals(dryRunOutput.get("dryRun"))
                        && Boolean.TRUE.equals(dryRunOutput.get("preflightPassed"))
                        && Boolean.FALSE.equals(dryRunOutput.get("mutationApplied")),
                "Latest Local Agent dry-run must pass hash/context preflight without mutation."
        ));
        prerequisites.add(releasePrerequisite(
                "snapshotCreated",
                snapshotReadiness != null && "CREATED".equals(snapshotReadiness.get("status")),
                "Local Agent must create a managed target-file snapshot."
        ));
        prerequisites.add(releasePrerequisite(
                "rollbackManifestValidated",
                rollbackReadiness != null && "RESTORE_VALIDATED".equals(rollbackReadiness.get("status")),
                "Created snapshot manifest must be structurally valid for future rollback restore."
        ));
        prerequisites.add(releasePrerequisite(
                "patchCapability",
                checkPassed(checks, "patchCapability"),
                "Connected Local Agent must advertise patch.apply capability."
        ));
        prerequisites.add(releasePrerequisite(
                "rollbackCapability",
                checkPassed(checks, "rollbackCapability"),
                "Connected Local Agent must advertise rollback.restore capability."
        ));

        boolean prerequisitesPassed = prerequisites.stream()
                .allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", prerequisitesPassed ? "PRECONDITIONS_READY_RELEASE_DISABLED" : "BLOCKED");
        result.put("preconditionsPassed", prerequisitesPassed);
        result.put("releaseGateEnabled", false);
        result.put("mutationEnabled", false);
        result.put("blocking", true);
        result.put("message", prerequisitesPassed
                ? "All pre-apply safety prerequisites are visible, but patch execution remains disabled by the release gate."
                : "Patch execution prerequisites are incomplete and the release gate remains disabled.");
        result.put("prerequisites", prerequisites);
        return result;
    }

    private Map<String, Object> patchExecutionGate(
            Map<String, Object> patchReleaseReadiness,
            Map<String, Object> dryRunOutput,
            LocalAgentPatchReleaseAttemptModel releaseAttemptModel
    ) {
        boolean preconditionsPassed = Boolean.TRUE.equals(patchReleaseReadiness.get("preconditionsPassed"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", preconditionsPassed ? "INTERNAL_PRECONDITIONS_READY_GATE_DISABLED" : "BLOCKED");
        result.put("preconditionsPassed", preconditionsPassed);
        result.put("releaseGateEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("mutationEnabled", false);
        result.put("blocking", true);
        result.put("sourceRequestRelationship", dryRunOutput != null
                ? "LINKED_DRY_RUN_OUTPUT_OBSERVED"
                : "NOT_OBSERVED");
        result.put("message", preconditionsPassed
                ? "Internal patch write prerequisites are visible, but held requests cannot be claimed and the Local Agent write helper is not enabled."
                : "Internal patch write prerequisites are incomplete; held requests remain non-claimable.");
        result.put("preReleaseRevalidation", preReleaseRevalidation(dryRunOutput));
        result.put("releaseAttemptModel", releaseAttemptModelMap(releaseAttemptModel));
        result.put("requiredBeforeEnablement", List.of(
                "Enable explicit backend release gate.",
                "Make approved-held patch requests claimable only through the release path.",
                "Connect Local Agent patch.apply to the guarded write helper.",
                "Keep rollback.restore validation and user approval mandatory.",
                "Emit post-write hash observations and run allowlisted verification before final answer."
        ));
        return result;
    }

    private LocalAgentPatchReleaseAttemptModel releaseAttemptModel(Optional<LocalAgentPatchReleaseAttempt> latestAttempt) {
        Map<String, Object> latestAttemptMap = latestAttempt
                .map(this::releaseAttemptSummary)
                .orElse(Map.of());
        return new LocalAgentPatchReleaseAttemptModel(
                "learnbot.local-agent.patch-release-attempt.v1",
                latestAttempt.map(LocalAgentPatchReleaseAttempt::status).orElse("MODEL_ONLY_RELEASE_DISABLED"),
                latestAttempt.isPresent(),
                latestAttempt.map(LocalAgentPatchReleaseAttempt::claimable).orElse(false),
                latestAttempt.map(LocalAgentPatchReleaseAttempt::staleWindowSeconds).orElse(120),
                List.of(
                releaseAttemptEvidence("releaseAttemptId", "Server-generated release attempt id linking fresh observations.", true),
                releaseAttemptEvidence("sourceRequestId", "Approved-held patch request id being considered for release.", true),
                releaseAttemptEvidence("repositoryVerification", "Fresh read-only git.status observation linked to the release attempt.", true),
                releaseAttemptEvidence("patchDryRun", "Fresh dry-run patch.apply observation linked to the release attempt.", true),
                releaseAttemptEvidence("snapshotManifest", "Fresh dry-run output proving a created managed snapshot.", true),
                releaseAttemptEvidence("rollbackManifest", "Fresh rollback manifest validation derived from the created snapshot.", true),
                releaseAttemptEvidence("userReleaseApproval", "Explicit user approval for release after fresh evidence is visible.", true)
                ),
                latestAttemptMap,
                latestAttempt.isPresent()
                        ? "Latest release-attempt record is visible for audit only; held patch requests remain non-claimable while the release gate is disabled."
                        : "Future release attempts must create a fresh evidence envelope before an approved-held patch can become claimable."
        );
    }

    private Map<String, Object> releaseAttemptSummary(LocalAgentPatchReleaseAttempt attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("status", attempt.status());
        result.put("claimable", attempt.claimable());
        result.put("staleWindowSeconds", attempt.staleWindowSeconds());
        result.put("evidence", attempt.evidence());
        result.put("failureReasons", attempt.failureReasons());
        result.put("createdAt", attempt.createdAt());
        result.put("updatedAt", attempt.updatedAt());
        result.put("releasedAt", attempt.releasedAt());
        return result;
    }

    private LocalAgentPatchReleaseAttemptEvidenceRequirement releaseAttemptEvidence(String key, String description, boolean required) {
        return new LocalAgentPatchReleaseAttemptEvidenceRequirement(key, description, required);
    }

    private Map<String, Object> releaseAttemptModelMap(LocalAgentPatchReleaseAttemptModel model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", model.schema());
        result.put("status", model.status());
        result.put("created", model.created());
        result.put("claimable", model.claimable());
        result.put("staleWindowSeconds", model.staleWindowSeconds());
        result.put("requiredEvidence", model.requiredEvidence().stream()
                .map(this::releaseAttemptEvidenceMap)
                .toList());
        result.put("latestAttempt", model.latestAttempt());
        result.put("message", model.message());
        return result;
    }

    private Map<String, Object> releaseAttemptEvidenceMap(LocalAgentPatchReleaseAttemptEvidenceRequirement evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", evidence.key());
        item.put("description", evidence.description());
        item.put("required", evidence.required());
        return item;
    }

    private Map<String, Object> preReleaseRevalidation(Map<String, Object> dryRunOutput) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REQUIRED_BEFORE_RELEASE");
        result.put("required", true);
        result.put("passed", false);
        result.put("blockingEnablement", true);
        result.put("latestLinkedDryRunObserved", dryRunOutput != null);
        result.put("requiresFreshDryRunAfterReleaseAttempt", true);
        result.put("requiresFreshRepositoryVerificationAfterReleaseAttempt", true);
        result.put("requiresSnapshotCreatedAfterFreshDryRun", true);
        result.put("message", "A future release must run fresh Local Agent repository verification and dry-run/snapshot checks immediately before making the held request claimable.");
        return result;
    }

    private boolean patchExecutionReleaseEnabled() {
        return false;
    }

    private Map<String, Object> releasePrerequisite(String key, boolean passed, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("passed", passed);
        item.put("message", message);
        return item;
    }

    private boolean checkPassed(List<LocalAgentPatchExecutionReadinessCheck> checks, String key) {
        return checks.stream().anyMatch(check -> key.equals(check.key()) && check.passed());
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private boolean hasExpectedFiles(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return false;
        return list.stream().allMatch(item -> item instanceof Map<?, ?> file
                && hasText(file.get("path"))
                && hasText(file.get("sha256")));
    }

    private boolean workspaceRepositoryVerified(Object value) {
        return value instanceof Map<?, ?> verification
                && "VERIFIED".equals(verification.get("status"))
                && !Boolean.TRUE.equals(verification.get("blocking"));
    }

    private boolean validSnapshotPolicy(Object value) {
        return value instanceof Map<?, ?> policy
                && Boolean.TRUE.equals(policy.get("required"))
                && "TARGET_FILES".equals(policy.get("scope"))
                && "LOCAL_AGENT_MANAGED".equals(policy.get("location"))
                && Boolean.TRUE.equals(policy.get("createBeforeMutation"))
                && Boolean.TRUE.equals(policy.get("includeExpectedHashes"));
    }

    private boolean validRollbackPolicy(Object value) {
        return value instanceof Map<?, ?> policy
                && Boolean.TRUE.equals(policy.get("required"))
                && LocalAgentToolName.ROLLBACK_RESTORE.wireName().equals(policy.get("tool"))
                && "SNAPSHOT_TARGET_FILES".equals(policy.get("restoreScope"))
                && Boolean.TRUE.equals(policy.get("requiresUserApproval"));
    }

    private boolean validSnapshotManifestPreview(Map<String, Object> dryRunOutput) {
        if (dryRunOutput == null
                || !Boolean.TRUE.equals(dryRunOutput.get("dryRun"))) {
            return false;
        }
        if (!(dryRunOutput.get("snapshotObservation") instanceof Map<?, ?> snapshot)
                || !(snapshot.get("manifestPreview") instanceof Map<?, ?> manifest)) {
            return false;
        }
        boolean snapshotCreated = Boolean.TRUE.equals(dryRunOutput.get("snapshotCreated"));
        return "learnbot.local-agent.snapshot-manifest.v1".equals(manifest.get("schema"))
                && numberValue(manifest.get("version")) == 1
                && hasText(manifest.get("id"))
                && hasText(manifest.get("relativeManifestPath"))
                && "COPY_TARGET_FILES_BEFORE_MUTATION".equals(manifest.get("contentStrategy"))
                && Boolean.valueOf(snapshotCreated).equals(manifest.get("created"))
                && Boolean.valueOf(snapshotCreated).equals(manifest.get("writesPlanned"))
                && nonEmptyList(manifest.get("files"));
    }

    private Map<String, Object> snapshotReadiness(Map<String, Object> dryRunOutput) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dryRunOutput == null) {
            result.put("status", "MISSING");
            result.put("message", "No linked Local Agent patch dry-run observation is available.");
            result.put("blocking", true);
            return result;
        }

        boolean snapshotCreated = Boolean.TRUE.equals(dryRunOutput.get("snapshotCreated"));
        result.put("dryRun", dryRunOutput.get("dryRun"));
        result.put("preflightPassed", dryRunOutput.get("preflightPassed"));
        result.put("mutationApplied", dryRunOutput.get("mutationApplied"));
        result.put("snapshotCreated", snapshotCreated);

        if (!(dryRunOutput.get("snapshotObservation") instanceof Map<?, ?> snapshot)
                || !(snapshot.get("manifestPreview") instanceof Map<?, ?> manifest)) {
            result.put("status", "INVALID");
            result.put("message", "Latest Local Agent dry-run did not include a snapshot manifest observation.");
            result.put("blocking", true);
            return result;
        }

        Object files = manifest.get("files");
        result.put("manifestId", manifest.get("id"));
        result.put("relativeManifestPath", manifest.get("relativeManifestPath"));
        result.put("manifestCreated", Boolean.TRUE.equals(manifest.get("created")));
        result.put("writesPlanned", Boolean.TRUE.equals(manifest.get("writesPlanned")));
        result.put("writesCompleted", Boolean.TRUE.equals(manifest.get("writesCompleted")));
        result.put("fileCount", files instanceof List<?> list ? list.size() : 0);
        result.put("contentStrategy", manifest.get("contentStrategy"));

        if (!validSnapshotManifestPreview(dryRunOutput)) {
            result.put("status", "INVALID");
            result.put("message", "Latest Local Agent snapshot manifest is present but does not match the expected managed snapshot contract.");
            result.put("blocking", true);
            return result;
        }

        result.put("status", snapshotCreated ? "CREATED" : "PREVIEW_ONLY");
        result.put("blocking", !snapshotCreated);
        result.put("message", snapshotCreated
                ? "Local Agent created a managed target-file snapshot; patch execution release is still disabled by the release gate."
                : "Local Agent provided only preview snapshot evidence; run dry-run with snapshot creation support before patch execution release is considered.");
        return result;
    }

    private boolean validRollbackRestorePreconditions(Map<String, Object> dryRunOutput) {
        if (dryRunOutput == null
                || !Boolean.TRUE.equals(dryRunOutput.get("dryRun"))
                || !Boolean.FALSE.equals(dryRunOutput.get("mutationApplied"))) {
            return false;
        }
        if (!(dryRunOutput.get("rollbackObservation") instanceof Map<?, ?> rollback)) {
            return false;
        }
        return Boolean.FALSE.equals(rollback.get("restored"))
                && nonEmptyList(rollback.get("restorePreconditions"));
    }

    private Map<String, Object> rollbackReadiness(Map<String, Object> dryRunOutput) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dryRunOutput == null) {
            result.put("status", "MISSING");
            result.put("message", "No linked Local Agent patch dry-run observation is available for rollback validation.");
            result.put("blocking", true);
            return result;
        }
        if (!Boolean.TRUE.equals(dryRunOutput.get("dryRun"))
                || !Boolean.FALSE.equals(dryRunOutput.get("mutationApplied"))) {
            result.put("status", "INVALID");
            result.put("message", "Rollback validation requires a dry-run observation with mutationApplied=false.");
            result.put("blocking", true);
            return result;
        }
        if (!Boolean.TRUE.equals(dryRunOutput.get("snapshotCreated"))) {
            result.put("status", "PREVIEW_ONLY");
            result.put("message", "Rollback validation requires an actual created snapshot, not preview-only manifest evidence.");
            result.put("blocking", true);
            return result;
        }
        if (!(dryRunOutput.get("snapshotObservation") instanceof Map<?, ?> snapshot)
                || !(snapshot.get("manifestPreview") instanceof Map<?, ?> manifest)) {
            result.put("status", "INVALID");
            result.put("message", "Rollback validation requires a snapshot manifest observation.");
            result.put("blocking", true);
            return result;
        }
        if (!(manifest.get("files") instanceof List<?> files) || files.isEmpty()) {
            result.put("status", "INVALID");
            result.put("message", "Rollback validation requires snapshot manifest file entries.");
            result.put("blocking", true);
            return result;
        }
        if (!validRollbackRestorePreconditions(dryRunOutput) || !rollbackRequiresUserApproval(dryRunOutput)) {
            result.put("status", "INVALID");
            result.put("message", "Rollback validation requires restore preconditions and explicit user approval.");
            result.put("blocking", true);
            return result;
        }

        List<Map<String, Object>> fileChecks = new ArrayList<>();
        for (Object item : files) {
            if (!(item instanceof Map<?, ?> file)) {
                result.put("status", "INVALID");
                result.put("message", "Snapshot manifest contains a non-object file entry.");
                result.put("blocking", true);
                return result;
            }
            String path = stringValue(file.get("path"));
            String snapshotRelativePath = stringValue(file.get("snapshotRelativePath"));
            boolean targetPathSafe = safeWorkspaceRelativePath(path);
            boolean snapshotPathSafe = safeSnapshotRelativePath(snapshotRelativePath);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("path", path);
            check.put("snapshotRelativePath", snapshotRelativePath);
            check.put("targetPathSafe", targetPathSafe);
            check.put("snapshotPathSafe", snapshotPathSafe);
            fileChecks.add(check);
            if (!targetPathSafe || !snapshotPathSafe) {
                result.put("status", "INVALID");
                result.put("message", "Snapshot manifest contains a path that is not safe for future rollback restore.");
                result.put("blocking", true);
                result.put("fileChecks", fileChecks);
                return result;
            }
        }

        result.put("status", "RESTORE_VALIDATED");
        result.put("blocking", false);
        result.put("message", "Created snapshot manifest contains restorable workspace-relative targets and managed snapshot-relative sources. rollback.restore remains disabled.");
        result.put("fileCount", fileChecks.size());
        result.put("requiresUserApproval", true);
        result.put("fileChecks", fileChecks);
        return result;
    }

    private boolean rollbackRequiresUserApproval(Map<String, Object> dryRunOutput) {
        if (!(dryRunOutput.get("rollbackObservation") instanceof Map<?, ?> rollback)
                || !(rollback.get("restorePreconditions") instanceof List<?> preconditions)) {
            return false;
        }
        return preconditions.stream().anyMatch(item -> item instanceof Map<?, ?> condition
                && "userApprovalRequired".equals(condition.get("key"))
                && Boolean.TRUE.equals(condition.get("required")));
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private boolean safeWorkspaceRelativePath(String path) {
        return safeRelativePath(path);
    }

    private boolean safeSnapshotRelativePath(String path) {
        return path != null && path.startsWith("files/") && safeRelativePath(path);
    }

    private boolean safeRelativePath(String path) {
        if (!hasText(path) || path.contains(":") || path.startsWith("/") || path.startsWith("\\")) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> effectiveWorkspaceVerification(Object storedValue, Map<String, Object> repositoryVerification) {
        if (workspaceRepositoryVerified(storedValue)) {
            return copyMap(storedValue);
        }
        if (!trustedRepositoryMatch(repositoryVerification)) {
            return storedValue instanceof Map<?, ?> stored
                    ? copyMap(stored)
                    : Map.of(
                    "status", "UNVERIFIED",
                    "blocking", true,
                    "reason", "Repository/workspace identity has not been verified."
            );
        }
        return Map.of(
                "status", "VERIFIED",
                "blocking", false,
                "reason", "Latest read-only Local Agent repository observation matched the indexed repository metadata.",
                "source", "repositoryVerification"
        );
    }

    private boolean trustedRepositoryMatch(Map<String, Object> repositoryVerification) {
        if (repositoryVerification == null || !"MATCH".equals(repositoryVerification.get("status"))) {
            return false;
        }
        if (!(repositoryVerification.get("checks") instanceof List<?> checks)) {
            return false;
        }
        List<?> considered = checks.stream()
                .filter(item -> item instanceof Map<?, ?> check && !"SKIPPED".equals(check.get("status")))
                .toList();
        return !considered.isEmpty() && considered.stream().allMatch(item ->
                item instanceof Map<?, ?> check && "MATCH".equals(check.get("status")));
    }

    private Map<String, Object> copyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private LocalAgentToolResponse enrichRepositoryVerification(LocalAgentToolResponse response) {
        if (response.toolName() != LocalAgentToolName.GIT_STATUS) {
            return response;
        }
        LocalAgentToolExecution execution = repository.find(response.requestId()).orElse(null);
        if (execution == null || !(execution.input().get("sourceRepository") instanceof Map<?, ?> source)) {
            return response;
        }
        Map<String, Object> output = new LinkedHashMap<>(response.output());
        output.put("repositoryVerification", compareRepositoryIdentity(source, response.output().get("repositoryIdentity"), response.status()));
        return new LocalAgentToolResponse(
                response.sessionId(),
                response.requestId(),
                response.userId(),
                response.agentId(),
                response.workspaceId(),
                response.executionTarget(),
                response.toolName(),
                response.status(),
                output,
                response.failureCode(),
                response.error(),
                response.startedAt(),
                response.finishedAt(),
                response.warnings()
        );
    }

    private Map<String, Object> compareRepositoryIdentity(Map<?, ?> source, Object identityValue, LocalAgentToolStatus status) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<?, ?> identity = identityValue instanceof Map<?, ?> map ? map : Map.of();
        addRepositoryCheck(checks, "branch", text(source.get("branch")), text(identity.get("branch")), true, false);
        addRepositoryCheck(checks, "head", text(source.get("lastIndexedCommit")), text(identity.get("headCommit")), false, false);
        addRepositoryCheck(checks, "remote", text(source.get("gitUrl")), text(identity.get("remoteUrl")), false, true);

        List<Map<String, Object>> considered = checks.stream()
                .filter(check -> !"SKIPPED".equals(check.get("status")))
                .toList();
        String resultStatus;
        String message;
        boolean blocking = true;
        if (status != LocalAgentToolStatus.SUCCEEDED) {
            resultStatus = "UNVERIFIED";
            message = "Local repository observation did not complete successfully.";
        } else if (considered.isEmpty() || considered.stream().anyMatch(check -> "UNKNOWN".equals(check.get("status")))) {
            resultStatus = "UNVERIFIED";
            message = "Not enough local repository identity data to verify this workspace.";
        } else if (considered.stream().anyMatch(check -> "MISMATCH".equals(check.get("status")))) {
            resultStatus = "MISMATCH";
            message = "Local workspace identity does not match the indexed repository metadata.";
        } else {
            resultStatus = "MATCH";
            message = "Observed local repository identity matches available indexed metadata.";
        }
        return Map.of(
                "status", resultStatus,
                "blocking", blocking,
                "message", message,
                "checks", checks
        );
    }

    private void addRepositoryCheck(List<Map<String, Object>> checks, String key, String expected, String actual, boolean skipHead, boolean normalizeUrl) {
        if (skipHead && (!hasText(expected) || "HEAD".equalsIgnoreCase(expected))) {
            checks.add(Map.of("key", key, "status", "SKIPPED", "expected", expected, "actual", actual));
            return;
        }
        if (!hasText(expected) || !hasText(actual)) {
            checks.add(Map.of("key", key, "status", "UNKNOWN", "expected", expected, "actual", actual));
            return;
        }
        String left = normalizeUrl ? normalizeRepositoryUrl(expected) : expected.toLowerCase();
        String right = normalizeUrl ? normalizeRepositoryUrl(actual) : actual.toLowerCase();
        boolean matched = "head".equals(key)
                ? left.equals(right) || left.startsWith(right) || right.startsWith(left)
                : left.equals(right);
        checks.add(Map.of("key", key, "status", matched ? "MATCH" : "MISMATCH", "expected", expected, "actual", actual));
    }

    private String normalizeRepositoryUrl(String value) {
        return value.trim()
                .replaceFirst("(?i)^git@([^:]+):", "https://$1/")
                .replaceFirst("(?i)^ssh://git@", "https://")
                .replaceFirst("(?i)^https?://([^@/]+@)", "https://")
                .replaceFirst("(?i)\\.git$", "")
                .replaceAll("/+$", "")
                .toLowerCase();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private LocalAgentToolExecution approvalCandidate(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent approval request was not found."));
        if (execution.approvalState() != LocalAgentApprovalState.REQUIRED
                || execution.status() != LocalAgentToolStatus.APPROVAL_REQUIRED) {
            throw new IllegalArgumentException("Local Agent approval request is no longer awaiting approval.");
        }
        if (!execution.toolName().isSideEffectful()) {
            throw new IllegalArgumentException("Only side-effectful Local Agent tools can be approved or denied.");
        }
        return execution;
    }

    private LocalAgentQueuedToolRequest toQueuedRequest(LocalAgentToolExecution execution) {
        return new LocalAgentQueuedToolRequest(
                execution.id(),
                new LocalAgentToolRequest(
                        execution.sessionId(),
                        execution.userId(),
                        execution.agentId(),
                        execution.workspaceId(),
                        execution.executionTarget(),
                        execution.toolName(),
                        execution.input(),
                        execution.approvalState(),
                        execution.createdAt(),
                        execution.requestWarnings()
                )
        );
    }

    private LocalAgentToolExecutionResponse toResponse(LocalAgentToolExecution execution) {
        return new LocalAgentToolExecutionResponse(
                execution.id(),
                execution.sessionId(),
                execution.userId(),
                execution.agentId(),
                execution.workspaceId(),
                execution.executionTarget(),
                execution.toolName(),
                execution.approvalState(),
                execution.status(),
                execution.input(),
                execution.output(),
                execution.failureCode(),
                execution.error(),
                execution.requestWarnings(),
                execution.responseWarnings(),
                execution.createdAt(),
                execution.startedAt(),
                execution.finishedAt()
        );
    }
}
