package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessCheck;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentPatchReleaseAttemptEvidenceRequirement;
import com.learnbot.dto.LocalAgentPatchReleaseAttemptModel;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentFailureCode;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import com.learnbot.repository.LocalAgentMutationObservationIntakeRepository;
import com.learnbot.repository.LocalAgentPatchReleaseAttemptRepository;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import com.learnbot.service.localagent.LocalAgentAcknowledgementSaveHandoffBuilder;
import com.learnbot.service.localagent.LocalAgentApprovedExecutionFlowContract;
import com.learnbot.service.localagent.LocalAgentFinalAnswerPublicationHandoffBuilder;
import com.learnbot.service.localagent.LocalAgentFinalMutationReportDraftBuilder;
import com.learnbot.service.localagent.LocalAgentFinalMutationReportSummaryBuilder;
import com.learnbot.service.localagent.LocalAgentMutationResultClassifier;
import com.learnbot.service.localagent.LocalAgentRagFreshnessMarkerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LocalAgentToolGatewayService {
    private final LocalAgentToolExecutionRepository repository;
    private final LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository;
    private final LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository;
    private final CodeAgentLoopTimelineRepository loopTimelineRepository;
    private final LocalAgentGatewayService gatewayService;
    private final LocalAgentToolPusher toolPusher;
    private final LearnBotProperties properties;
    private final LocalAgentPostExecutionObservationGateBuilder postExecutionObservationGateBuilder =
            new LocalAgentPostExecutionObservationGateBuilder();
    private final LocalAgentObservationAcceptanceGateBuilder observationAcceptanceGateBuilder =
            new LocalAgentObservationAcceptanceGateBuilder();
    private final LocalAgentResultIntakePersistenceGateBuilder resultIntakePersistenceGateBuilder =
            new LocalAgentResultIntakePersistenceGateBuilder();
    private final LocalAgentRollbackFallbackGateBuilder rollbackFallbackGateBuilder =
            new LocalAgentRollbackFallbackGateBuilder();
    private final LocalAgentRagFreshnessGateBuilder ragFreshnessGateBuilder =
            new LocalAgentRagFreshnessGateBuilder();
    private final LocalAgentResultAggregationGateBuilder resultAggregationGateBuilder =
            new LocalAgentResultAggregationGateBuilder();
    private final LocalAgentPublicationGateBuilder publicationGateBuilder =
            new LocalAgentPublicationGateBuilder();
    private final LocalAgentFinalAnswerGenerationGateBuilder generationGateBuilder =
            new LocalAgentFinalAnswerGenerationGateBuilder();
    private final LocalAgentFinalAnswerCompletionGateBuilder completionGateBuilder =
            new LocalAgentFinalAnswerCompletionGateBuilder();
    private final LocalAgentFinalAnswerPersistenceGateBuilder persistenceGateBuilder =
            new LocalAgentFinalAnswerPersistenceGateBuilder();
    private final LocalAgentFinalAnswerConversationSaveGateBuilder conversationSaveGateBuilder =
            new LocalAgentFinalAnswerConversationSaveGateBuilder();
    private final LocalAgentFinalAnswerUserVisibleCompletionGateBuilder userVisibleCompletionGateBuilder =
            new LocalAgentFinalAnswerUserVisibleCompletionGateBuilder();
    private final LocalAgentFinalResponseHandoffGateBuilder finalResponseHandoffGateBuilder =
            new LocalAgentFinalResponseHandoffGateBuilder();
    private final LocalAgentFinalAnswerDeliveryGateBuilder deliveryGateBuilder =
            new LocalAgentFinalAnswerDeliveryGateBuilder();
    private final LocalAgentFinalAnswerDeliveryReceiptGateBuilder deliveryReceiptGateBuilder =
            new LocalAgentFinalAnswerDeliveryReceiptGateBuilder();
    private final LocalAgentWriteHelperSafetyGateBuilder writeHelperSafetyGateBuilder =
            new LocalAgentWriteHelperSafetyGateBuilder();
    private final LocalAgentMutationExecutionReadinessBoundaryBuilder mutationExecutionReadinessBoundaryBuilder =
            new LocalAgentMutationExecutionReadinessBoundaryBuilder();
    private final LocalAgentMutationToolRunnerBoundaryBuilder mutationToolRunnerBoundaryBuilder =
            new LocalAgentMutationToolRunnerBoundaryBuilder();
    private final LocalAgentMutationResultCompletionBoundaryBuilder mutationResultCompletionBoundaryBuilder =
            new LocalAgentMutationResultCompletionBoundaryBuilder();

    public LocalAgentToolGatewayService(
            LocalAgentToolExecutionRepository repository,
            LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository,
            LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository,
            CodeAgentLoopTimelineRepository loopTimelineRepository,
            LocalAgentGatewayService gatewayService,
            LocalAgentToolPusher toolPusher
    ) {
        this(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher,
                new LearnBotProperties()
        );
    }

    @Autowired
    public LocalAgentToolGatewayService(
            LocalAgentToolExecutionRepository repository,
            LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository,
            LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository,
            CodeAgentLoopTimelineRepository loopTimelineRepository,
            LocalAgentGatewayService gatewayService,
            LocalAgentToolPusher toolPusher,
            LearnBotProperties properties
    ) {
        this.repository = repository;
        this.mutationObservationIntakeRepository = mutationObservationIntakeRepository;
        this.releaseAttemptRepository = releaseAttemptRepository;
        this.loopTimelineRepository = loopTimelineRepository;
        this.gatewayService = gatewayService;
        this.toolPusher = toolPusher;
        this.properties = properties;
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
            appendAgentUnavailableStopOutcome(request);
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
                && request.toolName() != LocalAgentToolName.WORKSPACE_TREE
                && request.toolName() != LocalAgentToolName.WORKSPACE_SEARCH
                && request.toolName() != LocalAgentToolName.GIT_STATUS
                && request.toolName() != LocalAgentToolName.GIT_DIFF) {
            throw new IllegalArgumentException("Only workspace.tree, workspace.search, file.read, git.status, and git.diff can be queued through this read-only path.");
        }
        LocalAgentQueuedToolRequest queued = enqueue(request);
        UUID repositoryId = repositoryId(request.input());
        if (repositoryId != null) {
            loopTimelineRepository.appendReadOnlyRequestQueued(
                    request.userId(),
                    repositoryId,
                    loopId(request.input()),
                    queued.requestId(),
                    request
            );
        }
        return queued;
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
            appendAgentUnavailableStopOutcome(request);
            throw new IllegalStateException("Local Agent is not connected.");
        }
        if (request.workspaceId() != null && !gatewayService.hasApprovedWorkspace(request.userId(), request.workspaceId())) {
            throw new IllegalStateException("Workspace is not approved by the Local Agent.");
        }
        UUID requestId = UUID.randomUUID();
        LocalAgentToolExecution execution = repository.create(requestId, request);
        appendLoopApprovalRequestCreatedEvent(execution);
        return toResponse(execution);
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
                .map(this::appendLoopApprovalDecisionEvent)
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
                .map(this::appendLoopApprovalDecisionEvent)
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

        Optional<LocalAgentPatchReleaseAttempt> latestReleaseAttempt = Optional
                .ofNullable(releaseAttemptRepository.findLatestForSourceRequest(userId, execution.id()))
                .flatMap(item -> item);
        Map<String, Object> input = execution.input();
        Map<String, Object> repositoryVerification = latestRepositoryVerification(userId, execution.id(), latestReleaseAttempt)
                .orElse(null);
        Map<String, Object> latestPatchDryRunOutput = latestPatchDryRunOutput(userId, execution.id(), latestReleaseAttempt)
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
                "approvalRequestIdPresent",
                hasText(input.get("approvalRequestId")),
                "A persisted approval request id must be present before a patch mutation can be released."
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
        boolean releaseEnabled = patchExecutionReleaseEnabled();
        checks.add(check(
                "releaseGateEnabled",
                releaseEnabled,
                releaseEnabled
                        ? "Patch execution release is enabled for the guarded Local Agent release path."
                        : "Patch execution release remains disabled until the guarded Local Agent release path is explicitly enabled."
        ));

        boolean ready = checks.stream().allMatch(LocalAgentPatchExecutionReadinessCheck::passed);
        List<String> warnings = ready
                ? List.of()
                : checks.stream()
                .filter(item -> !item.passed())
                .map(LocalAgentPatchExecutionReadinessCheck::message)
                .toList();
        Map<String, Object> patchReleaseReadiness = patchReleaseReadiness(
                input,
                checks,
                latestPatchDryRunOutput,
                snapshotReadiness,
                rollbackReadiness,
                workspaceVerification
        );
        LocalAgentPatchReleaseAttemptModel releaseAttemptModel = releaseAttemptModel(
                latestReleaseAttempt,
                input,
                repositoryVerification,
                latestPatchDryRunOutput,
                patchReleaseReadiness,
                rollbackReadiness,
                status,
                workspaceVerification
        );
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

    private Optional<Map<String, Object>> latestRepositoryVerification(
            UUID userId,
            UUID sourceRequestId,
            Optional<LocalAgentPatchReleaseAttempt> latestReleaseAttempt
    ) {
        if (latestReleaseAttempt.isPresent()) {
            LocalAgentPatchReleaseAttempt attempt = latestReleaseAttempt.get();
            Optional<Map<String, Object>> linked = Optional
                    .ofNullable(repository.findLatestRepositoryVerificationForReleaseAttempt(userId, sourceRequestId, attempt.id()))
                    .flatMap(item -> item);
            if (linked.isPresent()) {
                return linked.map(item -> withObservationLinkage(item, sourceRequestId, attempt.id(), "RELEASE_ATTEMPT_LINKED"));
            }
        }
        return Optional
                .ofNullable(repository.findLatestRepositoryVerificationForSourceRequest(userId, sourceRequestId))
                .flatMap(item -> item)
                .map(item -> withObservationLinkage(
                        item,
                        sourceRequestId,
                        latestReleaseAttempt.map(LocalAgentPatchReleaseAttempt::id).orElse(null),
                        latestReleaseAttempt.isPresent() ? "SOURCE_ONLY_FALLBACK" : "SOURCE_ONLY"
                ));
    }

    private Optional<Map<String, Object>> latestPatchDryRunOutput(
            UUID userId,
            UUID sourceRequestId,
            Optional<LocalAgentPatchReleaseAttempt> latestReleaseAttempt
    ) {
        if (latestReleaseAttempt.isPresent()) {
            LocalAgentPatchReleaseAttempt attempt = latestReleaseAttempt.get();
            Optional<Map<String, Object>> linked = Optional
                    .ofNullable(repository.findLatestPatchDryRunOutputForReleaseAttempt(userId, sourceRequestId, attempt.id()))
                    .flatMap(item -> item);
            if (linked.isPresent()) {
                return linked.map(item -> withObservationLinkage(item, sourceRequestId, attempt.id(), "RELEASE_ATTEMPT_LINKED"));
            }
        }
        return Optional
                .ofNullable(repository.findLatestPatchDryRunOutputForSourceRequest(userId, sourceRequestId))
                .flatMap(item -> item)
                .map(item -> withObservationLinkage(
                        item,
                        sourceRequestId,
                        latestReleaseAttempt.map(LocalAgentPatchReleaseAttempt::id).orElse(null),
                        latestReleaseAttempt.isPresent() ? "SOURCE_ONLY_FALLBACK" : "SOURCE_ONLY"
                ));
    }

    private Map<String, Object> withObservationLinkage(
            Map<String, Object> observation,
            UUID sourceRequestId,
            UUID releaseAttemptId,
            String status
    ) {
        Map<String, Object> result = new LinkedHashMap<>(observation);
        Map<String, Object> linkage = new LinkedHashMap<>();
        linkage.put("status", status);
        linkage.put("sourceRequestId", sourceRequestId);
        linkage.put("releaseAttemptId", releaseAttemptId);
        linkage.put("releaseAttemptLinked", "RELEASE_ATTEMPT_LINKED".equals(status));
        linkage.put("sourceOnlyFallback", "SOURCE_ONLY_FALLBACK".equals(status));
        result.put("observationLinkage", linkage);
        return result;
    }

    @Transactional
    public LocalAgentPatchReleaseBoundaryResponse inspectPatchReleaseBoundary(UUID userId, UUID requestId) {
        LocalAgentPatchExecutionReadinessResponse readiness = inspectPatchExecutionReadiness(userId, requestId);
        boolean preconditionsPassed = Boolean.TRUE.equals(readiness.patchExecutionGate().get("preconditionsPassed"));
        if (preconditionsPassed) {
            createDisabledReleaseAttemptIfMissing(userId, requestId, readiness);
            readiness = inspectPatchExecutionReadiness(userId, requestId);
        }
        Map<String, Object> latestAttempt = readiness.releaseAttemptModel().latestAttempt();
        Map<String, Object> releaseEnablementChecklist = latestAttempt.get("releaseEnablementChecklist") instanceof Map<?, ?> checklist
                ? copyMap(checklist)
                : Map.of();
        boolean releaseEnabled = patchExecutionReleaseEnabled();
        boolean releaseAttemptReadyForClaim = releaseAttemptReadyForClaim(readiness);
        boolean readyToRelease = preconditionsPassed && releaseEnabled && releaseAttemptReadyForClaim;
        List<String> blockingReasons = releaseBoundaryBlockingReasons(readiness, releaseEnablementChecklist, preconditionsPassed, releaseAttemptReadyForClaim, releaseEnabled);
        LocalAgentPatchReleaseBoundaryResponse boundary = new LocalAgentPatchReleaseBoundaryResponse(
                requestId,
                readyToRelease
                        ? "RELEASE_READY_FOR_EXECUTION"
                        : preconditionsPassed
                        ? (releaseEnabled ? "RELEASE_WAITING_FOR_FRESH_EVIDENCE" : "RELEASE_REFUSED_GATE_DISABLED")
                        : "RELEASE_REFUSED_PRECONDITIONS_BLOCKED",
                readyToRelease ? "CALL_RELEASE_FOR_EXECUTION" : "REFUSAL_ONLY",
                releaseEnabled,
                readyToRelease,
                readyToRelease,
                readyToRelease,
                false,
                readyToRelease,
                readyToRelease,
                readyToRelease,
                false,
                false,
                false,
                blockingReasons,
                readyToRelease
                        ? "Fresh release-attempt evidence is complete and the release gate is enabled; call release-for-execution to make the approved patch claimable."
                        : preconditionsPassed
                        ? (releaseEnabled
                        ? "Release action is waiting for fresh release-attempt-linked evidence before the held patch can become claimable."
                        : "Release action is modeled, but the release gate is disabled so the held patch remains non-claimable.")
                        : "Release action is modeled, but readiness prerequisites are blocked.",
                readiness.patchExecutionGate(),
                releaseEnablementChecklist,
                readiness.releaseAttemptModel()
        );
        if (!readyToRelease) {
            appendLoopReleaseBoundaryRefusalEvent(userId, requestId, boundary);
        }
        return boundary;
    }

    private List<String> releaseBoundaryBlockingReasons(
            LocalAgentPatchExecutionReadinessResponse readiness,
            Map<String, Object> releaseEnablementChecklist,
            boolean preconditionsPassed,
            boolean releaseAttemptReadyForClaim,
            boolean releaseEnabled
    ) {
        List<String> reasons = new ArrayList<>();
        if (!preconditionsPassed) {
            reasons.add("patch execution preconditions are incomplete");
        }
        if (preconditionsPassed && !releaseAttemptReadyForClaim) {
            reasons.add("fresh release-attempt-linked evidence is required before claim");
        }
        if (releaseEnablementChecklist.get("blockingKeys") instanceof List<?> blockingKeys && !blockingKeys.isEmpty()) {
            reasons.add("release enablement checklist is blocked: " + blockingKeys.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", ")));
        }
        if (readiness.releaseAttemptModel().latestAttempt().isEmpty()) {
            reasons.add("no disabled release attempt envelope exists yet");
        }
        if (!releaseEnabled) {
            reasons.add("release gate is disabled");
        }
        if (!preconditionsPassed || !releaseAttemptReadyForClaim || !releaseEnabled) {
            reasons.add("held patch request remains non-claimable");
        }
        if (!preconditionsPassed || !releaseAttemptReadyForClaim || !releaseEnabled) {
            reasons.add("Local Agent request creation and push remain disabled");
        }
        return reasons;
    }

    @Transactional
    public LocalAgentToolExecutionResponse releaseHeldPatchForExecution(UUID userId, UUID requestId) {
        LocalAgentPatchExecutionReadinessResponse readiness = inspectPatchExecutionReadiness(userId, requestId);
        Map<String, Object> gate = readiness.patchExecutionGate();
        if (!Boolean.TRUE.equals(gate.get("preconditionsPassed"))) {
            throw new IllegalStateException("Patch execution gate is not ready.");
        }
        if (!releaseAttemptReadyForClaim(readiness)) {
            createDisabledReleaseAttemptIfMissing(userId, requestId, readiness);
            throw new IllegalStateException("Patch execution release requires fresh release-attempt-linked evidence before claim.");
        }
        if (!patchExecutionReleaseEnabled()) {
            createDisabledReleaseAttemptIfMissing(userId, requestId, readiness);
            throw new IllegalStateException("Patch execution release is disabled.");
        }
        LocalAgentToolExecution source = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent patch request was not found."));
        Map<String, Object> latestAttempt = readiness.releaseAttemptModel().latestAttempt();
        UUID releaseAttemptId = releaseAttemptId(latestAttempt)
                .orElseThrow(() -> new IllegalStateException("A release attempt with linked fresh evidence is required before patch mutation release."));
        Map<String, Object> linkedDryRun = latestPatchDryRunOutput(userId, requestId, Optional.of(new LocalAgentPatchReleaseAttempt(
                releaseAttemptId,
                requestId,
                source.sessionId(),
                source.userId(),
                source.agentId(),
                source.workspaceId(),
                String.valueOf(latestAttempt.get("status")),
                Boolean.TRUE.equals(latestAttempt.get("claimable")),
                readiness.releaseAttemptModel().staleWindowSeconds(),
                Map.of(),
                List.of(),
                null,
                null,
                null
        ))).orElseThrow(() -> new IllegalStateException("Linked patch dry-run output is required before release."));
        Map<String, Object> mutationInput = LocalAgentPatchMutationInputBuilder.build(
                source.input(),
                linkedDryRun,
                requestId,
                releaseAttemptId
        );
        LocalAgentToolExecution released = repository.releaseApprovedHeldPatchWithMutationInput(
                        requestId,
                        userId,
                        mutationInput,
                        "Patch execution release gate passed. Request is now claimable by the selected Local Agent."
                )
                .orElseThrow(() -> new IllegalArgumentException("Held patch request is no longer releasable."));
        createApprovedExecutionSequenceRowsIfEnabled(released, mutationInput, releaseAttemptId);
        return toResponse(released);
    }

    private void createApprovedExecutionSequenceRowsIfEnabled(
            LocalAgentToolExecution releasedPatch,
            Map<String, Object> mutationInput,
            UUID releaseAttemptId
    ) {
        if (!approvedExecutionSequenceCreationEnabled()) {
            return;
        }
        String manifestId = stringValue(mutationInput.get("manifestId"));
        if (manifestId == null || manifestId.isBlank()) {
            throw new IllegalStateException("Approved execution sequence requires a managed snapshot manifest id.");
        }
        String approvalRequestId = stringValue(mutationInput.get("approvalRequestId"));
        String releasedApprovalRequestId = stringValue(releasedPatch.input().get("approvalRequestId"));
        if (approvalRequestId == null || approvalRequestId.isBlank()
                || releasedApprovalRequestId == null || releasedApprovalRequestId.isBlank()
                || !approvalRequestId.equals(releasedApprovalRequestId)) {
            throw new IllegalStateException("Approved execution sequence requires a matching persisted approval request id.");
        }
        UUID sourceRequestId = releasedPatch.id();
        OffsetDateTime baseCreatedAt = OffsetDateTime.now();
        List<LocalAgentToolRequest> followUpRequests = List.of(
                approvedSequenceRequest(
                        releasedPatch,
                        LocalAgentToolName.COMMAND_RUN_ALLOWED,
                        approvedCommandInput(releasedPatch, sourceRequestId, releaseAttemptId),
                        baseCreatedAt.plusNanos(1_000_000),
                        "Approved release follow-up command.runAllowed verification row. Local Agent polling must claim it after patch.apply."
                ),
                approvedSequenceRequest(
                        releasedPatch,
                        LocalAgentToolName.GIT_STATUS,
                        approvedSequenceCommonInput(releasedPatch, sourceRequestId, releaseAttemptId),
                        baseCreatedAt.plusNanos(2_000_000),
                        "Approved release follow-up git.status observation row. Local Agent polling must claim it after command.runAllowed."
                )
        );
        followUpRequests.forEach(request -> repository.create(UUID.randomUUID(), request));
    }

    private LocalAgentToolRequest approvedSequenceRequest(
            LocalAgentToolExecution releasedPatch,
            LocalAgentToolName toolName,
            Map<String, Object> input,
            OffsetDateTime createdAt,
            String warning
    ) {
        return new LocalAgentToolRequest(
                releasedPatch.sessionId(),
                releasedPatch.userId(),
                releasedPatch.agentId(),
                releasedPatch.workspaceId(),
                releasedPatch.executionTarget(),
                toolName,
                input,
                LocalAgentApprovalState.APPROVED,
                createdAt,
                List.of(warning, "Created by guarded patch execution release; final publication and acknowledgement save remain disabled.")
        );
    }

    private Map<String, Object> approvedCommandInput(
            LocalAgentToolExecution releasedPatch,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        Map<String, Object> input = approvedSequenceCommonInput(releasedPatch, sourceRequestId, releaseAttemptId);
        input.put("commandId", stringValue(releasedPatch.input().getOrDefault("commandId", "dotnet.version")));
        input.put("timeoutSeconds", numberOrDefault(releasedPatch.input().get("timeoutSeconds"), 30));
        input.put("maxOutputBytes", numberOrDefault(releasedPatch.input().get("maxOutputBytes"), 4096));
        return Map.copyOf(input);
    }

    private Map<String, Object> approvedRollbackInput(
            LocalAgentToolExecution releasedPatch,
            UUID sourceRequestId,
            UUID releaseAttemptId,
            String manifestId
    ) {
        Map<String, Object> input = approvedSequenceCommonInput(releasedPatch, sourceRequestId, releaseAttemptId);
        input.put("manifestId", manifestId);
        input.put("snapshotManifestId", manifestId);
        return Map.copyOf(input);
    }

    private Map<String, Object> approvedSequenceCommonInput(
            LocalAgentToolExecution releasedPatch,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sessionId", releasedPatch.sessionId().toString());
        input.put("userId", releasedPatch.userId().toString());
        input.put("agentId", releasedPatch.agentId().toString());
        input.put("workspaceId", releasedPatch.workspaceId().toString());
        input.put("sourceRequestId", sourceRequestId.toString());
        input.put("releaseAttemptId", releaseAttemptId.toString());
        input.put("approvalRequestId", stringValue(releasedPatch.input().get("approvalRequestId")));
        input.put("releaseExecutionSequenceSchema", "learnbot.local-agent.approved-execution-sequence.v1");
        input.put("publicationEnabled", false);
        input.put("acknowledgementSaveEnabled", false);
        input.put("followUpMutationEnabled", false);
        return input;
    }

    private Optional<UUID> releaseAttemptId(Map<String, Object> latestAttempt) {
        if (latestAttempt == null || latestAttempt.isEmpty()) {
            return Optional.empty();
        }
        Object value = latestAttempt.get("id");
        if (value instanceof UUID id) {
            return Optional.of(id);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Optional.of(UUID.fromString(text));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private boolean releaseAttemptReadyForClaim(LocalAgentPatchExecutionReadinessResponse readiness) {
        if (readiness == null || readiness.releaseAttemptModel() == null) {
            return false;
        }
        Map<String, Object> latestAttempt = readiness.releaseAttemptModel().latestAttempt();
        if (latestAttempt == null || latestAttempt.isEmpty()) {
            return false;
        }
        if (!releaseAttemptId(latestAttempt).isPresent()) {
            return false;
        }
        if (latestAttempt.get("releaseAttemptFinalReadiness") instanceof Map<?, ?> finalReadiness
                && Boolean.TRUE.equals(finalReadiness.get("ready"))
                && "READY_RELEASE_DISABLED".equals(finalReadiness.get("status"))
                && "ALL_LINKED_RELEASE_DISABLED".equals(finalReadiness.get("evidenceCompletenessStatus"))) {
            return true;
        }
        return false;
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
        UUID attemptId = UUID.randomUUID();
        releaseAttemptRepository.createDisabled(
                attemptId,
                source,
                readiness.releaseAttemptModel().staleWindowSeconds(),
                disabledReleaseAttemptEvidence(readiness, source, attemptId),
                List.of("Patch execution release is disabled; attempt remains non-claimable.")
        );
    }

    private Map<String, Object> disabledReleaseAttemptEvidence(
            LocalAgentPatchExecutionReadinessResponse readiness,
            LocalAgentToolExecution source,
            UUID attemptId
    ) {
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
        evidence.put("freshObservationEnqueueEnabled", false);
        evidence.put("freshObservationRequestTemplates", disabledFreshObservationRequestTemplates(source, attemptId));
        evidence.put("freshObservationEnqueueBoundary", disabledFreshObservationEnqueueBoundary(source, attemptId));
        evidence.put("claimable", false);
        evidence.put("message", "Disabled release attempt envelope captured visible readiness evidence without making the held patch claimable.");
        return evidence;
    }

    private Map<String, Object> disabledFreshObservationEnqueueBoundary(LocalAgentToolExecution source, UUID attemptId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "DISABLED_RELEASE_GATE");
        result.put("enqueueEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimableAfterEnqueue", false);
        result.put("mutationAllowed", false);
        result.put("sourceRequestId", source.id());
        result.put("releaseAttemptId", attemptId);
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("plannedRequests", disabledFreshObservationRequestTemplates(source, attemptId));
        result.put("requiredBeforeEnablement", List.of(
                "Enable the explicit patch execution release gate.",
                "Create fresh read-only git.status observations after the release attempt exists.",
                "Create fresh non-mutating patch.apply dry-run observations after repository verification.",
                "Keep the approved-held patch request non-claimable until linked fresh evidence passes.",
                "Never enqueue patch mutation, test, or rollback restore from this boundary while disabled."
        ));
        result.put("message", "Fresh observation enqueue boundary is modeled for audit only; no Local Agent tool request is created or pushed while the release gate is disabled.");
        return result;
    }

    private List<Map<String, Object>> disabledFreshObservationRequestTemplates(LocalAgentToolExecution source, UUID attemptId) {
        return List.of(
                freshObservationRequestTemplate(
                        "repositoryVerification",
                        source,
                        attemptId,
                        LocalAgentToolName.GIT_STATUS,
                        LocalAgentApprovalState.NOT_REQUIRED,
                        freshRepositoryVerificationInput(source, attemptId),
                        List.of("Fresh release-attempt repository observation template only. Enqueue is disabled.")
                ),
                freshObservationRequestTemplate(
                        "patchDryRun",
                        source,
                        attemptId,
                        LocalAgentToolName.PATCH_APPLY,
                        LocalAgentApprovalState.APPROVED,
                        freshPatchDryRunInput(source, attemptId),
                        List.of("Fresh release-attempt patch dry-run template only. Mutation and enqueue remain disabled.")
                )
        );
    }

    private Map<String, Object> freshRepositoryVerificationInput(LocalAgentToolExecution source, UUID attemptId) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (source.input().get("sourceRepository") instanceof Map<?, ?> sourceRepository) {
            input.put("sourceRepository", copyMap(sourceRepository));
        }
        if (source.input().get("localWorkspace") instanceof Map<?, ?> localWorkspace) {
            input.put("localWorkspace", copyMap(localWorkspace));
        }
        input.put("sourceRequestId", source.id().toString());
        input.put("releaseAttemptId", attemptId.toString());
        input.put("freshObservationOnly", true);
        return input;
    }

    private Map<String, Object> freshPatchDryRunInput(LocalAgentToolExecution source, UUID attemptId) {
        Map<String, Object> input = new LinkedHashMap<>(source.input());
        input.put("dryRunOnly", true);
        input.put("mutationAllowed", false);
        input.put("sourceRequestId", source.id().toString());
        input.put("releaseAttemptId", attemptId.toString());
        input.put("freshObservationOnly", true);
        return input;
    }

    private Map<String, Object> freshObservationRequestTemplate(
            String key,
            LocalAgentToolExecution source,
            UUID attemptId,
            LocalAgentToolName toolName,
            LocalAgentApprovalState approvalState,
            Map<String, Object> input,
            List<String> warnings
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", "TEMPLATE_DISABLED");
        result.put("enqueueEnabled", false);
        result.put("claimableAfterEnqueue", false);
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("toolName", toolName.wireName());
        result.put("approvalState", approvalState.name());
        result.put("sessionId", source.sessionId());
        result.put("userId", source.userId());
        result.put("agentId", source.agentId());
        result.put("workspaceId", source.workspaceId());
        result.put("sourceRequestId", source.id());
        result.put("releaseAttemptId", attemptId);
        result.put("input", input);
        result.put("warnings", warnings);
        return result;
    }

    @Transactional
    public LocalAgentQueuedToolRequest enqueuePatchDryRun(UUID userId, UUID requestId) {
        LocalAgentToolExecution execution = approvedHeldPatchSource(userId, requestId, "Dry-run dispatch");

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
    public List<LocalAgentQueuedToolRequest> enqueueReleaseAttemptFreshObservations(UUID userId, UUID requestId) {
        LocalAgentToolExecution source = approvedHeldPatchSource(userId, requestId, "Fresh observation dispatch");
        LocalAgentPatchReleaseAttempt attempt = disabledReleaseAttemptForFreshObservation(userId, source);
        if (!source.id().equals(attempt.sourceRequestId())
                || !source.sessionId().equals(attempt.sessionId())
                || !source.userId().equals(attempt.userId())
                || !source.agentId().equals(attempt.agentId())
                || !source.workspaceId().equals(attempt.workspaceId())) {
            throw new IllegalStateException("Release attempt does not match the approved-held patch request.");
        }
        if (!gatewayService.isConnected(source.userId(), source.agentId())) {
            throw new IllegalStateException("Local Agent is not connected.");
        }
        if (source.workspaceId() != null && !gatewayService.hasApprovedWorkspace(source.userId(), source.workspaceId())) {
            throw new IllegalStateException("Workspace is not approved by the Local Agent.");
        }

        LocalAgentToolRequest repositoryObservationRequest = new LocalAgentToolRequest(
                source.sessionId(),
                source.userId(),
                source.agentId(),
                source.workspaceId(),
                source.executionTarget(),
                LocalAgentToolName.GIT_STATUS,
                freshRepositoryVerificationInput(source, attempt.id()),
                LocalAgentApprovalState.NOT_REQUIRED,
                null,
                List.of("Fresh release-attempt git.status observation. Read-only; the source patch request stays held.")
        );
        List<String> patchWarnings = new ArrayList<>(source.requestWarnings());
        patchWarnings.add("Fresh release-attempt patch dry-run observation. dryRunOnly=true, mutationAllowed=false, and the source request stays held.");
        LocalAgentToolRequest patchDryRunRequest = new LocalAgentToolRequest(
                source.sessionId(),
                source.userId(),
                source.agentId(),
                source.workspaceId(),
                source.executionTarget(),
                LocalAgentToolName.PATCH_APPLY,
                freshPatchDryRunInput(source, attempt.id()),
                LocalAgentApprovalState.APPROVED,
                null,
                patchWarnings
        );

        List<LocalAgentToolRequest> requests = List.of(repositoryObservationRequest, patchDryRunRequest);
        List<LocalAgentQueuedToolRequest> queued = new ArrayList<>();
        for (LocalAgentToolRequest request : requests) {
            LocalAgentToolExecution execution = repository.create(UUID.randomUUID(), request);
            queued.add(toQueuedRequest(execution));
        }
        queued.forEach(toolPusher::sendToolRequest);
        appendLoopFreshObservationRequestsEnqueuedEvent(source, attempt.id(), queued);
        return List.copyOf(queued);
    }

    private LocalAgentPatchReleaseAttempt disabledReleaseAttemptForFreshObservation(
            UUID userId,
            LocalAgentToolExecution source
    ) {
        Optional<LocalAgentPatchReleaseAttempt> existing = latestDisabledNonClaimableAttempt(userId, source.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        inspectPatchReleaseBoundary(userId, source.id());
        return latestDisabledNonClaimableAttempt(userId, source.id())
                .orElseThrow(() -> new IllegalStateException("A disabled non-claimable release attempt is required before fresh observations can be queued."));
    }

    private Optional<LocalAgentPatchReleaseAttempt> latestDisabledNonClaimableAttempt(UUID userId, UUID requestId) {
        return Optional
                .ofNullable(releaseAttemptRepository.findLatestForSourceRequest(userId, requestId))
                .flatMap(item -> item)
                .filter(candidate -> LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS.equals(candidate.status()))
                .filter(candidate -> !candidate.claimable());
    }

    @Transactional
    public Optional<LocalAgentQueuedToolRequest> claimNext(UUID userId, UUID agentId) {
        List<LocalAgentToolExecution> timedOut = repository.expireTimedOutLeases();
        if (timedOut != null) {
            timedOut.forEach(this::appendLeaseTimeoutStopOutcome);
        }
        return repository.claimNext(userId, agentId)
                .map(this::toQueuedRequest);
    }

    @Transactional
    public void complete(LocalAgentToolResponse response) {
        LocalAgentToolResponse enriched = enrichRepositoryVerification(response);
        enriched = enrichMutationResultIntakeCandidate(enriched);
        repository.complete(enriched);
        persistAcceptedMutationObservation(enriched);
        appendLoopObservationEvent(enriched);
        appendLoopFreshObservationEvidenceCompleteEvent(enriched);
        appendLoopApprovedExecutionFlowCompletedEvent(enriched);
    }

    public Optional<LocalAgentToolExecutionResponse> findForUser(UUID userId, UUID requestId) {
        return repository.find(requestId)
                .filter(execution -> execution.userId().equals(userId))
                .map(this::toResponse);
    }

    public List<LocalAgentToolExecutionResponse> findPendingApprovalsForUser(UUID userId, int limit) {
        return repository.findPendingApprovalsForUser(userId, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    public Map<String, Object> inspectApprovedExecutionFlow(UUID userId, List<UUID> requestIds) {
        List<UUID> safeRequestIds = requestIds == null ? List.of() : requestIds;
        List<LocalAgentToolExecution> executions = safeRequestIds.stream()
                .map(requestId -> repository.find(requestId)
                        .filter(execution -> execution.userId().equals(userId))
                        .orElseThrow(() -> new IllegalArgumentException("Local Agent tool execution was not found.")))
                .peek(this::requireCompletedApprovedExecutionFlowRow)
                .toList();
        return approvedExecutionFlowSummary(executions, List.copyOf(safeRequestIds), null, "callerProvidedRequestIds");
    }

    public Map<String, Object> inspectApprovedExecutionFlowForReleaseAttempt(UUID userId, UUID releaseAttemptId) {
        if (releaseAttemptId == null) {
            throw new IllegalArgumentException("Release attempt id is required.");
        }
        List<LocalAgentToolExecution> executions = repository.findCompletedApprovedExecutionFlowRowsForReleaseAttempt(userId, releaseAttemptId);
        executions.forEach(this::requireCompletedApprovedExecutionFlowRow);
        List<UUID> requestIds = executions.stream().map(LocalAgentToolExecution::id).toList();
        return approvedExecutionFlowSummary(executions, requestIds, releaseAttemptId, "durableCompletedRows");
    }

    private Map<String, Object> approvedExecutionFlowSummary(
            List<LocalAgentToolExecution> executions,
            List<UUID> requestIds,
            UUID releaseAttemptId,
            String requestIdSource
    ) {
        List<LocalAgentApprovedExecutionFlowContract.Step> steps = executions.stream()
                .map(this::approvedExecutionFlowStep)
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>(LocalAgentApprovedExecutionFlowContract.summarize(steps));
        summary.put("readModelOnly", true);
        summary.put("repositoryBacked", true);
        summary.put("requestIds", List.copyOf(requestIds));
        summary.put("requestIdSource", requestIdSource);
        if (releaseAttemptId != null) {
            summary.put("releaseAttemptId", releaseAttemptId);
        }
        summary.put("message", "Approved Local Agent execution-flow rows were inspected as a read-only service model; production request creation, push, claim, result intake, acknowledgement save, orchestration, and follow-up mutation remain disabled.");
        return summary;
    }

    private void requireCompletedApprovedExecutionFlowRow(LocalAgentToolExecution execution) {
        if (execution.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException("Approved execution-flow inspection requires USER_LOCAL_AGENT rows.");
        }
        if (execution.approvalState() != LocalAgentApprovalState.APPROVED) {
            throw new IllegalArgumentException("Approved execution-flow inspection requires approved rows.");
        }
        if (!isTerminal(execution.status()) || execution.finishedAt() == null) {
            throw new IllegalArgumentException("Approved execution-flow inspection requires completed terminal rows.");
        }
    }

    private boolean isTerminal(LocalAgentToolStatus status) {
        return switch (status) {
            case SUCCEEDED, FAILED, REJECTED, TIMED_OUT, DISCONNECTED, CANCELLED -> true;
            case PENDING, APPROVAL_REQUIRED, APPROVED, APPROVED_HELD, RUNNING -> false;
        };
    }

    private LocalAgentApprovedExecutionFlowContract.Step approvedExecutionFlowStep(LocalAgentToolExecution execution) {
        return new LocalAgentApprovedExecutionFlowContract.Step(
                new LocalAgentToolResponse(
                        execution.sessionId(),
                        execution.id(),
                        execution.userId(),
                        execution.agentId(),
                        execution.workspaceId(),
                        execution.executionTarget(),
                        execution.toolName(),
                        execution.status(),
                        execution.output(),
                        execution.failureCode(),
                        execution.error(),
                        execution.startedAt(),
                        execution.finishedAt(),
                        execution.responseWarnings()
                ),
                execution.input()
        );
    }

    private LocalAgentPatchExecutionReadinessCheck check(String key, boolean passed, String message) {
        return new LocalAgentPatchExecutionReadinessCheck(key, passed, message);
    }

    private Map<String, Object> patchReleaseReadiness(
            Map<String, Object> sourceInput,
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
                "approvalRequestPersisted",
                checkPassed(checks, "approvalRequestIdPresent"),
                "Persisted approval request id must be visible before mutation release."
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
                        && Boolean.FALSE.equals(dryRunOutput.get("mutationApplied"))
                        && dryRunContextMatches(dryRunOutput),
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
        boolean releaseEnabled = patchExecutionReleaseEnabled();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", prerequisitesPassed
                ? (releaseEnabled ? "PRECONDITIONS_READY_RELEASE_ENABLED" : "PRECONDITIONS_READY_RELEASE_DISABLED")
                : "BLOCKED");
        result.put("preconditionsPassed", prerequisitesPassed);
        result.put("releaseGateEnabled", releaseEnabled);
        result.put("mutationEnabled", false);
        result.put("blocking", !prerequisitesPassed || !releaseEnabled);
        result.put("approvalPersistence", approvalPersistenceSummary(sourceInput));
        result.put("message", prerequisitesPassed
                ? (releaseEnabled
                ? "All pre-apply safety prerequisites are visible; release still requires fresh release-attempt-linked evidence before claim."
                : "All pre-apply safety prerequisites are visible, but patch execution remains disabled by the release gate.")
                : "Patch execution prerequisites are incomplete.");
        result.put("prerequisites", prerequisites);
        return result;
    }

    private boolean dryRunContextMatches(Map<String, Object> dryRunOutput) {
        if (dryRunOutput == null || !(dryRunOutput.get("files") instanceof List<?> files) || files.isEmpty()) {
            return false;
        }
        return files.stream()
                .filter(Map.class::isInstance)
                .map(item -> copyMap((Map<?, ?>) item))
                .allMatch(file -> Boolean.TRUE.equals(file.get("contextMatches")));
    }

    private Map<String, Object> patchExecutionGate(
            Map<String, Object> patchReleaseReadiness,
            Map<String, Object> dryRunOutput,
            LocalAgentPatchReleaseAttemptModel releaseAttemptModel
    ) {
        boolean preconditionsPassed = Boolean.TRUE.equals(patchReleaseReadiness.get("preconditionsPassed"));
        boolean releaseEnabled = patchExecutionReleaseEnabled();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", preconditionsPassed
                ? (releaseEnabled ? "INTERNAL_PRECONDITIONS_READY_GATE_ENABLED" : "INTERNAL_PRECONDITIONS_READY_GATE_DISABLED")
                : "BLOCKED");
        result.put("preconditionsPassed", preconditionsPassed);
        result.put("releaseGateEnabled", releaseEnabled);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("mutationEnabled", false);
        result.put("blocking", true);
        result.put("approvalPersistence", patchReleaseReadiness.get("approvalPersistence"));
        result.put("sourceRequestRelationship", dryRunOutput != null
                ? "LINKED_DRY_RUN_OUTPUT_OBSERVED"
                : "NOT_OBSERVED");
        result.put("message", preconditionsPassed
                ? (releaseEnabled
                ? "Internal patch write prerequisites are visible; held requests remain non-claimable until the release endpoint links fresh evidence."
                : "Internal patch write prerequisites are visible, but held requests cannot be claimed and the Local Agent write helper is not enabled.")
                : "Internal patch write prerequisites are incomplete; held requests remain non-claimable.");
        result.put("preReleaseRevalidation", preReleaseRevalidation(dryRunOutput));
        result.put("releaseAttemptModel", releaseAttemptModelMap(releaseAttemptModel));
        List<String> requiredBeforeEnablement = new ArrayList<>();
        if (!releaseEnabled) {
            requiredBeforeEnablement.add("Enable explicit backend release gate.");
        }
        requiredBeforeEnablement.add("Make approved-held patch requests claimable only through the release path.");
        requiredBeforeEnablement.add("Connect Local Agent patch.apply to the guarded write helper.");
        requiredBeforeEnablement.add("Keep rollback.restore validation and user approval mandatory.");
        requiredBeforeEnablement.add("Emit post-write hash observations and run allowlisted verification before final answer.");
        result.put("requiredBeforeEnablement", requiredBeforeEnablement);
        return result;
    }

    private Map<String, Object> approvalPersistenceSummary(Map<String, Object> sourceInput) {
        Map<String, Object> input = sourceInput == null ? Map.of() : sourceInput;
        Object approvalRequestId = input.get("approvalRequestId");
        boolean approvalRequestIdPresent = approvalRequestId instanceof String text && !text.isBlank();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.patch-approval-persistence.v1");
        result.put("approvalRequestId", approvalRequestId);
        result.put("approvalRequestIdPresent", approvalRequestIdPresent);
        result.put("approvalPersistenceRequired", Boolean.TRUE.equals(input.get("approvalPersistenceRequired")));
        result.put("approvalPersisted", Boolean.TRUE.equals(input.get("approvalPersisted")));
        result.put("releaseBlockingReason", approvalRequestIdPresent
                ? "Fresh Local Agent release evidence is required before the approved patch can become claimable."
                : "Persisted approval request id is missing, so mutation release is blocked.");
        return result;
    }

    private LocalAgentPatchReleaseAttemptModel releaseAttemptModel(
            Optional<LocalAgentPatchReleaseAttempt> latestAttempt,
            Map<String, Object> sourceInput,
            Map<String, Object> repositoryVerification,
            Map<String, Object> latestPatchDryRunOutput,
            Map<String, Object> patchReleaseReadiness,
            Map<String, Object> rollbackReadiness,
            LocalAgentStatusResponse status,
            Map<String, Object> workspaceVerification
    ) {
        Map<String, Object> latestAttemptMap = latestAttempt
                .map(attempt -> releaseAttemptSummary(
                        attempt,
                        sourceInput,
                        repositoryVerification,
                        latestPatchDryRunOutput,
                        patchReleaseReadiness,
                        rollbackReadiness,
                        status,
                        workspaceVerification
                ))
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

    private Map<String, Object> releaseAttemptSummary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> sourceInput,
            Map<String, Object> repositoryVerification,
            Map<String, Object> latestPatchDryRunOutput,
            Map<String, Object> patchReleaseReadiness,
            Map<String, Object> rollbackReadiness,
            LocalAgentStatusResponse status,
            Map<String, Object> workspaceVerification
    ) {
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
        result.put("expiresAt", releaseAttemptExpiresAt(attempt));
        result.put("ageSeconds", releaseAttemptAgeSeconds(attempt));
        result.put("freshnessStatus", releaseAttemptFreshnessStatus(attempt));
        result.put("stale", "STALE".equals(releaseAttemptFreshnessStatus(attempt)));
        result.put("freshObservationRequirements", releaseAttemptFreshObservationRequirements(attempt));
        result.put("freshObservationRequestPlan", releaseAttemptFreshObservationRequestPlan(attempt));
        List<Map<String, Object>> freshObservationEvidenceStatus = releaseAttemptFreshObservationEvidenceStatus(
                attempt,
                repositoryVerification,
                latestPatchDryRunOutput
        );
        result.put("freshObservationEvidenceStatus", freshObservationEvidenceStatus);
        Map<String, Object> freshObservationEvidenceCompleteness = releaseAttemptFreshObservationEvidenceCompleteness(
                attempt,
                freshObservationEvidenceStatus
        );
        result.put("freshObservationEvidenceCompleteness", freshObservationEvidenceCompleteness);
        Map<String, Object> releaseAttemptFinalReadiness = releaseAttemptFinalReadiness(
                attempt,
                freshObservationEvidenceCompleteness,
                patchReleaseReadiness
        );
        result.put("releaseAttemptFinalReadiness", releaseAttemptFinalReadiness);
        result.put("releaseAttemptDisplaySummary", releaseAttemptDisplaySummary(
                attempt,
                freshObservationEvidenceCompleteness,
                releaseAttemptFinalReadiness
        ));
        List<Map<String, Object>> mutationSequencePlan = releaseAttemptMutationExecutionSequencePlan(attempt);
        result.put("localAgentMutationExecutionSequencePlan", mutationSequencePlan);
        Map<String, Object> postMutationResultContract = releaseAttemptPostMutationResultContract(attempt);
        result.put("postMutationResultContract", postMutationResultContract);
        Map<String, Object> mutationResultIntakeBoundary = releaseAttemptMutationResultIntakeBoundary(
                attempt,
                postMutationResultContract
        );
        result.put("mutationResultIntakeBoundary", mutationResultIntakeBoundary);
        Map<String, Object> acceptedMutationObservationSummary = releaseAttemptAcceptedMutationObservationSummary(attempt);
        result.put("acceptedMutationObservationSummary", acceptedMutationObservationSummary);
        Map<String, Object> finalMutationReportContract = releaseAttemptFinalMutationReportContract(
                attempt,
                postMutationResultContract,
                rollbackReadiness,
                acceptedMutationObservationSummary
        );
        result.put("finalMutationReportContract", finalMutationReportContract);
        Map<String, Object> acceptedMutationObservationReadiness = releaseAttemptAcceptedMutationObservationReadiness(attempt);
        result.put("acceptedMutationObservationReadiness", acceptedMutationObservationReadiness);
        Map<String, Object> mutationResultAggregationPlan = releaseAttemptMutationResultAggregationPlan(
                attempt,
                postMutationResultContract,
                finalMutationReportContract,
                acceptedMutationObservationSummary
        );
        result.put("mutationResultAggregationPlan", mutationResultAggregationPlan);
        Map<String, Object> finalMutationReportSummary = LocalAgentFinalMutationReportSummaryBuilder.build(
                attempt,
                acceptedMutationObservationSummary,
                acceptedMutationObservationReadiness
        );
        result.put("finalMutationReportSummary", finalMutationReportSummary);
        Map<String, Object> ragFreshnessMarker = LocalAgentRagFreshnessMarkerBuilder.build(
                attempt,
                sourceInput == null ? Map.of() : sourceInput,
                finalMutationReportSummary
        );
        result.put("ragFreshnessMarker", ragFreshnessMarker);
        Map<String, Object> finalAnswerPublicationHandoff = LocalAgentFinalAnswerPublicationHandoffBuilder.build(
                attempt,
                finalMutationReportSummary,
                ragFreshnessMarker
        );
        result.put("finalAnswerPublicationHandoff", finalAnswerPublicationHandoff);
        Map<String, Object> acknowledgementSaveHandoff = LocalAgentAcknowledgementSaveHandoffBuilder.build(
                attempt,
                finalAnswerPublicationHandoff
        );
        result.put("acknowledgementSaveHandoff", acknowledgementSaveHandoff);
        Map<String, Object> finalMutationReportDraft = LocalAgentFinalMutationReportDraftBuilder.build(
                attempt,
                mutationResultAggregationPlan,
                finalMutationReportContract,
                acceptedMutationObservationSummary
        );
        result.put("finalMutationReportDraft", finalMutationReportDraft);
        Map<String, Object> finalMutationReportFinalizationBoundary = releaseAttemptFinalMutationReportFinalizationBoundary(
                attempt,
                releaseAttemptFinalReadiness,
                postMutationResultContract,
                finalMutationReportContract,
                acceptedMutationObservationSummary
        );
        result.put("finalMutationReportFinalizationBoundary", finalMutationReportFinalizationBoundary);
        Map<String, Object> finalAnswerPublicationBoundary = releaseAttemptFinalAnswerPublicationBoundary(
                attempt,
                releaseAttemptFinalReadiness,
                finalMutationReportContract,
                mutationResultAggregationPlan,
                finalMutationReportDraft,
                acceptedMutationObservationSummary
        );
        result.put("finalAnswerPublicationBoundary", finalAnswerPublicationBoundary);
        Map<String, Object> releaseEnablementChecklist = releaseAttemptEnablementChecklist(
                attempt,
                releaseAttemptFinalReadiness,
                mutationSequencePlan,
                postMutationResultContract,
                rollbackReadiness
        );
        result.put("releaseEnablementChecklist", releaseEnablementChecklist);
        Map<String, Object> mutationDispatchEnvelopeContract = releaseAttemptMutationDispatchEnvelopeContract(
                attempt,
                mutationSequencePlan,
                postMutationResultContract,
                rollbackReadiness
        );
        result.put("mutationDispatchEnvelopeContract", mutationDispatchEnvelopeContract);
        Map<String, Object> mutationDispatchPreflightBoundary = releaseAttemptMutationDispatchPreflightBoundary(
                attempt,
                status,
                workspaceVerification,
                mutationDispatchEnvelopeContract
        );
        result.put("mutationDispatchPreflightBoundary", mutationDispatchPreflightBoundary);
        Map<String, Object> mutationDispatchDecisionModel = releaseAttemptMutationDispatchDecisionModel(
                attempt,
                mutationDispatchEnvelopeContract,
                mutationDispatchPreflightBoundary
        );
        result.put("mutationDispatchDecisionModel", mutationDispatchDecisionModel);
        Map<String, Object> mutationRequestBlueprint = releaseAttemptMutationRequestBlueprint(
                attempt,
                mutationDispatchEnvelopeContract,
                mutationDispatchDecisionModel,
                postMutationResultContract
        );
        result.put("mutationRequestBlueprint", mutationRequestBlueprint);
        Map<String, Object> mutationRequestCreationGate = releaseAttemptMutationRequestCreationGate(
                attempt,
                mutationRequestBlueprint
        );
        result.put("mutationRequestCreationGate", mutationRequestCreationGate);
        Map<String, Object> mutationRequestPushGate = releaseAttemptMutationRequestPushGate(
                attempt,
                mutationRequestCreationGate
        );
        result.put("mutationRequestPushGate", mutationRequestPushGate);
        Map<String, Object> mutationRequestClaimGate = releaseAttemptMutationRequestClaimGate(
                attempt,
                mutationRequestPushGate
        );
        result.put("mutationRequestClaimGate", mutationRequestClaimGate);
        Map<String, Object> mutationExecutionGate = releaseAttemptMutationExecutionGate(
                attempt,
                mutationRequestClaimGate
        );
        result.put("mutationExecutionGate", mutationExecutionGate);
        Map<String, Object> mutationWriteHelperSafetyGate = writeHelperSafetyGateBuilder.build(
                attempt,
                mutationExecutionGate
        );
        result.put("mutationWriteHelperSafetyGate", mutationWriteHelperSafetyGate);
        Map<String, Object> mutationPostExecutionObservationGate = postExecutionObservationGateBuilder.build(
                attempt,
                mutationExecutionGate
        );
        result.put("mutationPostExecutionObservationGate", mutationPostExecutionObservationGate);
        Map<String, Object> mutationObservationAcceptanceGate = observationAcceptanceGateBuilder.build(
                attempt,
                mutationPostExecutionObservationGate
        );
        result.put("mutationObservationAcceptanceGate", mutationObservationAcceptanceGate);
        Map<String, Object> mutationResultIntakePersistenceGate = resultIntakePersistenceGateBuilder.build(
                attempt,
                mutationObservationAcceptanceGate,
                acceptedMutationObservationSummary,
                acceptedMutationObservationReadiness
        );
        result.put("mutationResultIntakePersistenceGate", mutationResultIntakePersistenceGate);
        Map<String, Object> mutationRollbackFallbackGate = rollbackFallbackGateBuilder.build(
                attempt,
                mutationResultIntakePersistenceGate
        );
        result.put("mutationRollbackFallbackGate", mutationRollbackFallbackGate);
        Map<String, Object> mutationRagFreshnessGate = ragFreshnessGateBuilder.build(
                attempt,
                mutationRollbackFallbackGate,
                acceptedMutationObservationSummary
        );
        result.put("mutationRagFreshnessGate", mutationRagFreshnessGate);
        Map<String, Object> mutationResultAggregationGate = resultAggregationGateBuilder.build(
                attempt,
                mutationRagFreshnessGate,
                acceptedMutationObservationReadiness
        );
        result.put("mutationResultAggregationGate", mutationResultAggregationGate);
        Map<String, Object> mutationPublicationGate = publicationGateBuilder.build(
                attempt,
                mutationResultAggregationGate
        );
        result.put("mutationPublicationGate", mutationPublicationGate);
        Map<String, Object> mutationFinalAnswerGenerationGate = generationGateBuilder.build(
                attempt,
                mutationPublicationGate,
                finalAnswerPublicationBoundary
        );
        result.put("mutationFinalAnswerGenerationGate", mutationFinalAnswerGenerationGate);
        Map<String, Object> mutationFinalAnswerCompletionGate = completionGateBuilder.build(
                attempt,
                mutationFinalAnswerGenerationGate
        );
        result.put("mutationFinalAnswerCompletionGate", mutationFinalAnswerCompletionGate);
        Map<String, Object> mutationFinalAnswerPersistenceGate = persistenceGateBuilder.build(
                attempt,
                mutationFinalAnswerCompletionGate
        );
        result.put("mutationFinalAnswerPersistenceGate", mutationFinalAnswerPersistenceGate);
        Map<String, Object> mutationFinalAnswerConversationSaveGate = conversationSaveGateBuilder.build(
                attempt,
                mutationFinalAnswerPersistenceGate
        );
        result.put("mutationFinalAnswerConversationSaveGate", mutationFinalAnswerConversationSaveGate);
        Map<String, Object> mutationFinalAnswerUserVisibleCompletionGate = userVisibleCompletionGateBuilder.build(
                attempt,
                mutationFinalAnswerConversationSaveGate
        );
        result.put("mutationFinalAnswerUserVisibleCompletionGate", mutationFinalAnswerUserVisibleCompletionGate);
        Map<String, Object> mutationFinalResponseHandoffGate = finalResponseHandoffGateBuilder.build(
                attempt,
                mutationFinalAnswerUserVisibleCompletionGate
        );
        result.put("mutationFinalResponseHandoffGate", mutationFinalResponseHandoffGate);
        Map<String, Object> mutationFinalAnswerDeliveryGate = deliveryGateBuilder.build(
                attempt,
                mutationFinalResponseHandoffGate
        );
        result.put("mutationFinalAnswerDeliveryGate", mutationFinalAnswerDeliveryGate);
        Map<String, Object> mutationFinalAnswerDeliveryReceiptGate = deliveryReceiptGateBuilder.build(
                attempt,
                mutationFinalAnswerDeliveryGate
        );
        result.put("mutationFinalAnswerDeliveryReceiptGate", mutationFinalAnswerDeliveryReceiptGate);
        Map<String, Object> mutationCompletionSummary = releaseAttemptMutationCompletionSummary(
                attempt,
                releaseAttemptFinalReadiness,
                mutationSequencePlan,
                mutationResultIntakeBoundary,
                mutationResultAggregationPlan,
                finalMutationReportDraft,
                finalMutationReportContract,
                finalMutationReportFinalizationBoundary,
                finalAnswerPublicationBoundary,
                releaseEnablementChecklist,
                rollbackReadiness,
                postMutationResultContract,
                mutationDispatchEnvelopeContract,
                mutationDispatchPreflightBoundary,
                mutationDispatchDecisionModel,
                mutationRequestBlueprint,
                mutationRequestCreationGate,
                mutationRequestPushGate,
                mutationRequestClaimGate,
                mutationExecutionGate,
                mutationWriteHelperSafetyGate,
                mutationPostExecutionObservationGate,
                mutationObservationAcceptanceGate,
                mutationResultIntakePersistenceGate,
                mutationRollbackFallbackGate,
                mutationRagFreshnessGate,
                mutationResultAggregationGate,
                mutationPublicationGate,
                mutationFinalAnswerGenerationGate,
                mutationFinalAnswerCompletionGate,
                mutationFinalAnswerPersistenceGate,
                mutationFinalAnswerConversationSaveGate,
                mutationFinalAnswerUserVisibleCompletionGate,
                mutationFinalResponseHandoffGate,
                mutationFinalAnswerDeliveryGate,
                mutationFinalAnswerDeliveryReceiptGate
        );
        result.put("mutationCompletionSummary", mutationCompletionSummary);
        Map<String, Object> mutationHandoffSummary = releaseAttemptMutationHandoffSummary(
                attempt,
                mutationCompletionSummary
        );
        result.put("mutationHandoffSummary", mutationHandoffSummary);
        Map<String, Object> mutationExecutionReadinessBoundary = mutationExecutionReadinessBoundaryBuilder.build(
                attempt,
                mutationHandoffSummary,
                mutationExecutionGate,
                mutationWriteHelperSafetyGate
        );
        result.put("mutationExecutionReadinessBoundary", mutationExecutionReadinessBoundary);
        Map<String, Object> mutationToolRunnerBoundary = mutationToolRunnerBoundaryBuilder.build(
                attempt,
                mutationExecutionReadinessBoundary,
                mutationExecutionGate
        );
        result.put("mutationToolRunnerBoundary", mutationToolRunnerBoundary);
        result.put("mutationResultCompletionBoundary", mutationResultCompletionBoundaryBuilder.build(
                attempt,
                mutationToolRunnerBoundary,
                mutationPostExecutionObservationGate
        ));
        return result;
    }

    private Map<String, Object> releaseAttemptAcceptedMutationObservationReadiness(LocalAgentPatchReleaseAttempt attempt) {
        Optional<Map<String, Object>> latest = Optional
                .ofNullable(mutationObservationIntakeRepository.findLatestAcceptedMutationObservationForReleaseAttempt(
                        attempt.userId(),
                        attempt.sourceRequestId(),
                        attempt.id()
                ))
                .flatMap(item -> item)
                .or(() -> Optional
                        .ofNullable(repository.findLatestAcceptedMutationObservationForReleaseAttempt(
                                attempt.userId(),
                                attempt.sourceRequestId(),
                                attempt.id()
                        ))
                        .flatMap(item -> item));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.accepted-mutation-observation-readiness.v1");
        result.put("status", latest.isPresent() ? "OBSERVED_INTAKE_DISABLED" : "MISSING_INTAKE_DISABLED");
        result.put("observed", latest.isPresent());
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("acceptedObservationPersistenceEnabled", false);
        result.put("resultIntakeEnabled", false);
        result.put("resultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowed", false);
        result.put("latestObservation", latest.orElse(Map.of()));
        result.put("message", latest.isPresent()
                ? "Latest accepted mutation observation is visible for audit, but dedicated intake persistence, aggregation, publication, acknowledgement save, RAG freshness update, and mutation remain disabled."
                : "No accepted mutation observation is available for this release attempt; intake persistence, aggregation, publication, acknowledgement save, RAG freshness update, and mutation remain disabled.");
        return result;
    }

    private Map<String, Object> releaseAttemptAcceptedMutationObservationSummary(LocalAgentPatchReleaseAttempt attempt) {
        List<Map<String, Object>> observations = mutationObservationIntakeRepository.findAcceptedMutationObservationsForReleaseAttempt(
                attempt.userId(),
                attempt.sourceRequestId(),
                attempt.id()
        );
        if (observations == null) {
            observations = List.of();
        }
        Map<String, Integer> byToolName = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        int acceptedCount = 0;
        int rejectedCount = 0;
        int terminalFailureAcceptedCount = 0;
        for (Map<String, Object> observation : observations) {
            String toolName = String.valueOf(observation.getOrDefault("toolName", "UNKNOWN"));
            byToolName.put(toolName, byToolName.getOrDefault(toolName, 0) + 1);
            String status = String.valueOf(observation.getOrDefault("status", "UNKNOWN"));
            byStatus.put(status, byStatus.getOrDefault(status, 0) + 1);
            if (Boolean.TRUE.equals(observation.get("accepted"))) {
                acceptedCount++;
            }
            if (status.startsWith("REJECTED_")) {
                rejectedCount++;
            }
            if ("ACCEPTED_TERMINAL_FAILURE".equals(status)) {
                terminalFailureAcceptedCount++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.accepted-mutation-observation-summary.v1");
        result.put("status", observations.isEmpty() ? "MISSING_OBSERVATIONS_DISABLED" : "OBSERVED_SUMMARY_DISABLED");
        result.put("observed", !observations.isEmpty());
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("publicationGateSchema", "learnbot.local-agent.mutation-publication-gate.v1");
        result.put("publicationGateStatus", "REFUSED_PUBLICATION_DISABLED");
        result.put("publicationGateSessionId", attempt.sessionId());
        result.put("publicationGateUserId", attempt.userId());
        result.put("publicationGateAgentId", attempt.agentId());
        result.put("publicationGateWorkspaceId", attempt.workspaceId());
        result.put("observationCount", observations.size());
        result.put("acceptedCount", acceptedCount);
        result.put("rejectedCount", rejectedCount);
        result.put("terminalFailureAcceptedCount", terminalFailureAcceptedCount);
        result.put("toolObservationCounts", byToolName);
        result.put("statusObservationCounts", byStatus);
        result.put("observations", observations);
        result.put("aggregationEnabled", false);
        result.put("finalReportGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowed", false);
        result.put("message", observations.isEmpty()
                ? "No durable accepted mutation observations are available for summary; aggregation, final reporting, publication, acknowledgement save, RAG freshness update, and mutation remain disabled."
                : "Durable accepted mutation observations are summarized for audit only; aggregation, final reporting, publication, acknowledgement save, RAG freshness update, and mutation remain disabled.");
        return result;
    }

    private void persistAcceptedMutationObservation(LocalAgentToolResponse response) {
        LocalAgentToolExecution execution = repository.find(response.requestId()).orElse(null);
        if (execution == null) {
            return;
        }
        mutationObservationIntakeRepository.saveAcceptedObservation(response, execution.input());
    }

    private Map<String, Object> releaseAttemptMutationDispatchDecisionModel(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationDispatchEnvelopeContract,
            Map<String, Object> mutationDispatchPreflightBoundary
    ) {
        List<Map<String, Object>> readinessInputs = List.of(
                mutationDispatchDecisionInput(
                        "mutationDispatchEnvelopeContract",
                        "READY_DISPATCH_DISABLED".equals(mutationDispatchEnvelopeContract.get("status")),
                        String.valueOf(mutationDispatchEnvelopeContract.getOrDefault("status", "UNKNOWN")),
                        "The future dispatch envelope must define ordered tools, approvals, rollback, and freshness obligations."
                ),
                mutationDispatchDecisionInput(
                        "mutationDispatchPreflightBoundary",
                        "READY_PREFLIGHT_DISABLED".equals(mutationDispatchPreflightBoundary.get("status")),
                        String.valueOf(mutationDispatchPreflightBoundary.getOrDefault("status", "UNKNOWN")),
                        "The future dispatch preflight must confirm the selected Local Agent, approved workspace, required capabilities, and envelope readiness."
                ),
                mutationDispatchDecisionInput(
                        "releaseGateEnabled",
                        false,
                        "DISABLED",
                        "The backend release gate is still disabled, so no held patch can become claimable."
                ),
                mutationDispatchDecisionInput(
                        "dispatchDecisionEnabled",
                        false,
                        "DISABLED",
                        "The final dispatch decision switch is still disabled, so no Local Agent mutation request can be created."
                )
        );
        boolean readinessInputsPassed = readinessInputs.stream()
                .filter(item -> !"releaseGateEnabled".equals(item.get("key")) && !"dispatchDecisionEnabled".equals(item.get("key")))
                .allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        List<String> blockingKeys = new ArrayList<>(readinessInputs.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        if (!blockingKeys.contains("releaseGateEnabled")) {
            blockingKeys.add("releaseGateEnabled");
        }
        if (!blockingKeys.contains("dispatchDecisionEnabled")) {
            blockingKeys.add("dispatchDecisionEnabled");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-dispatch-decision.v1");
        result.put("status", readinessInputsPassed ? "REFUSED_DISPATCH_DISABLED" : "BLOCKED_DISPATCH_DISABLED");
        result.put("decision", "REFUSE_DISPATCH");
        result.put("readinessInputsPassed", readinessInputsPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("envelopeStatus", mutationDispatchEnvelopeContract.getOrDefault("status", "UNKNOWN"));
        result.put("preflightStatus", mutationDispatchPreflightBoundary.getOrDefault("status", "UNKNOWN"));
        result.put("dispatchDecisionEnabled", false);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("readinessInputs", readinessInputs);
        result.put("blockingKeys", blockingKeys);
        result.put("userVisibleRefusalMessage", readinessInputsPassed
                ? "Local Agent mutation dispatch is modeled and preflight-ready, but execution is disabled until the release gate and dispatch decision switch are explicitly enabled."
                : "Local Agent mutation dispatch is disabled because required readiness inputs are incomplete.");
        result.put("message", readinessInputsPassed
                ? "Dispatch decision refuses mutation dispatch by policy: release gate, request creation, push, claim, and mutation remain disabled."
                : "Dispatch decision cannot proceed because dispatch readiness inputs are incomplete and dispatch remains disabled.");
        return result;
    }

    private Map<String, Object> mutationDispatchDecisionInput(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("releaseGateEnabled", false);
        result.put("dispatchDecisionEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> releaseAttemptMutationRequestBlueprint(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationDispatchEnvelopeContract,
            Map<String, Object> mutationDispatchDecisionModel,
            Map<String, Object> postMutationResultContract
    ) {
        boolean decisionRefused = "REFUSED_DISPATCH_DISABLED".equals(mutationDispatchDecisionModel.get("status"))
                && "REFUSE_DISPATCH".equals(mutationDispatchDecisionModel.get("decision"));
        List<Map<String, Object>> orderedToolRequests = mutationDispatchBlueprintToolRequests(
                attempt,
                mutationDispatchEnvelopeContract,
                postMutationResultContract
        );
        List<String> blockingKeys = new ArrayList<>();
        Object decisionBlockingKeys = mutationDispatchDecisionModel.get("blockingKeys");
        if (decisionBlockingKeys instanceof List<?> keys) {
            keys.stream().map(String::valueOf).forEach(blockingKeys::add);
        } else {
            blockingKeys.add("mutationDispatchDecisionModel");
        }
        for (String key : List.of("requestCreationEnabled", "pushEnabled", "claimEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-request-blueprint.v1");
        result.put("status", decisionRefused ? "REFUSED_REQUEST_CREATION_DISABLED" : "BLOCKED_REQUEST_BLUEPRINT_DISABLED");
        result.put("prerequisitesPassed", decisionRefused);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceDecisionSchema", mutationDispatchDecisionModel.get("schema"));
        result.put("sourceDecisionStatus", mutationDispatchDecisionModel.get("status"));
        result.put("sourceDecision", mutationDispatchDecisionModel.get("decision"));
        result.put("sourceEnvelopeSchema", mutationDispatchEnvelopeContract.get("schema"));
        result.put("sourceEnvelopeStatus", mutationDispatchEnvelopeContract.get("status"));
        result.put("requestCreationMode", "BLUEPRINT_ONLY_DISABLED");
        result.put("orderedToolRequests", orderedToolRequests);
        result.put("expectedInputKeys", List.of(
                "sourceRequestId",
                "releaseAttemptId",
                "sessionId",
                "userId",
                "agentId",
                "workspaceId",
                "toolName",
                "approvalState",
                "input"
        ));
        result.put("expectedOutputKeys", postMutationResultContractExpectedOutcomes(postMutationResultContract).stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("approvalStates", orderedToolRequests.stream()
                .map(item -> Map.of(
                        "key", item.get("key"),
                        "toolName", item.get("toolName"),
                        "approvalState", item.get("approvalState")
                ))
                .toList());
        result.put("releaseGateEnabled", false);
        result.put("dispatchDecisionEnabled", false);
        result.put("requestBlueprintEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", decisionRefused
                ? "Local Agent mutation request blueprint is derived from the dispatch refusal, but request creation, push, claim, and mutation remain disabled."
                : "Local Agent mutation request blueprint is blocked because dispatch decision readiness is incomplete.");
        return result;
    }

    private Map<String, Object> releaseAttemptMutationRequestCreationGate(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationRequestBlueprint
    ) {
        boolean blueprintReady = "REFUSED_REQUEST_CREATION_DISABLED".equals(mutationRequestBlueprint.get("status"))
                && Boolean.TRUE.equals(mutationRequestBlueprint.get("prerequisitesPassed"));
        Object orderedToolRequestsValue = mutationRequestBlueprint.get("orderedToolRequests");
        int expectedRequestCount = orderedToolRequestsValue instanceof List<?> orderedToolRequests
                ? orderedToolRequests.size()
                : 0;
        int durableMutationExecutionRowCount = repository.countMutationEnabledExecutionRowsForReleaseAttempt(
                attempt.userId(),
                attempt.id()
        );
        List<Map<String, Object>> policyChecks = List.of(
                mutationRequestCreationPolicyCheck(
                        "mutationRequestBlueprint",
                        blueprintReady,
                        String.valueOf(mutationRequestBlueprint.getOrDefault("status", "UNKNOWN")),
                        "A disabled request blueprint must be present before creation can be considered."
                ),
                mutationRequestCreationPolicyCheck(
                        "releaseGateEnabled",
                        false,
                        "DISABLED",
                        "The release gate remains disabled, so no held patch can become claimable."
                ),
                mutationRequestCreationPolicyCheck(
                        "requestCreationPolicy",
                        false,
                        "DISABLED",
                        "The backend request creation policy is disabled for Local Agent mutation execution."
                ),
                mutationRequestCreationPolicyCheck(
                        "requestPersistence",
                        false,
                        "DISABLED",
                        "No Local Agent mutation tool execution row may be inserted while this gate is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of("requestCreationEnabled", "pushEnabled", "claimEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-request-creation-gate.v1");
        result.put("status", blueprintReady ? "REFUSED_CREATION_DISABLED" : "BLOCKED_CREATION_DISABLED");
        result.put("blueprintReady", blueprintReady);
        result.put("prerequisitesPassed", blueprintReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceBlueprintSchema", mutationRequestBlueprint.get("schema"));
        result.put("sourceBlueprintStatus", mutationRequestBlueprint.get("status"));
        result.put("releaseGateState", "DISABLED");
        result.put("requestCreationPolicy", "DISABLED_AUDIT_ONLY");
        result.put("expectedRequestCount", expectedRequestCount);
        result.put("durableMutationExecutionRowCount", durableMutationExecutionRowCount);
        result.put("persistedRequestCount", 0);
        result.put("pushedRequestCount", 0);
        result.put("claimableRequestCount", 0);
        result.put("policyChecks", policyChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", blueprintReady
                ? "Local Agent mutation request creation is explicitly refused: no execution rows are created, pushed, or made claimable while creation is disabled."
                : "Local Agent mutation request creation is blocked because the disabled request blueprint is incomplete.");
        return result;
    }

    private Map<String, Object> releaseAttemptMutationRequestPushGate(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationRequestCreationGate
    ) {
        boolean creationGateReady = "REFUSED_CREATION_DISABLED".equals(mutationRequestCreationGate.get("status"))
                && Boolean.TRUE.equals(mutationRequestCreationGate.get("prerequisitesPassed"));
        int expectedRequestCount = numericValue(mutationRequestCreationGate.get("expectedRequestCount"));
        int persistedRequestCount = numericValue(mutationRequestCreationGate.get("persistedRequestCount"));
        int pushedRequestCount = numericValue(mutationRequestCreationGate.get("pushedRequestCount"));
        int claimableRequestCount = numericValue(mutationRequestCreationGate.get("claimableRequestCount"));
        List<Map<String, Object>> policyChecks = List.of(
                mutationRequestPushPolicyCheck(
                        "mutationRequestCreationGate",
                        creationGateReady,
                        String.valueOf(mutationRequestCreationGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled creation gate must refuse persistence before push can be considered."
                ),
                mutationRequestPushPolicyCheck(
                        "transportPushPolicy",
                        false,
                        "DISABLED",
                        "Local Agent transport push is disabled for mutation requests."
                ),
                mutationRequestPushPolicyCheck(
                        "pusherInvocation",
                        false,
                        "DISABLED",
                        "LocalAgentToolPusher must not be called for disabled mutation requests."
                ),
                mutationRequestPushPolicyCheck(
                        "claimableTransition",
                        false,
                        "DISABLED",
                        "No pushed mutation request can become claimable while push is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of("pushEnabled", "requestCreationEnabled", "claimEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-request-push-gate.v1");
        result.put("status", creationGateReady ? "REFUSED_PUSH_DISABLED" : "BLOCKED_PUSH_DISABLED");
        result.put("creationGateReady", creationGateReady);
        result.put("prerequisitesPassed", creationGateReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceCreationGateSchema", mutationRequestCreationGate.get("schema"));
        result.put("sourceCreationGateStatus", mutationRequestCreationGate.get("status"));
        result.put("transportPushPolicy", "DISABLED_AUDIT_ONLY");
        result.put("pusherInvocationEnabled", false);
        result.put("expectedRequestCount", expectedRequestCount);
        result.put("persistedRequestCount", persistedRequestCount);
        result.put("pushedRequestCount", pushedRequestCount);
        result.put("claimableRequestCount", claimableRequestCount);
        result.put("policyChecks", policyChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushGateEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", creationGateReady
                ? "Local Agent mutation request push is explicitly refused: no transport push, claim transition, or mutation is enabled."
                : "Local Agent mutation request push is blocked because the disabled request creation gate is incomplete.");
        return result;
    }

    private Map<String, Object> mutationRequestPushPolicyCheck(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> releaseAttemptMutationRequestClaimGate(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationRequestPushGate
    ) {
        boolean pushGateReady = "REFUSED_PUSH_DISABLED".equals(mutationRequestPushGate.get("status"))
                && Boolean.TRUE.equals(mutationRequestPushGate.get("prerequisitesPassed"));
        int expectedRequestCount = numericValue(mutationRequestPushGate.get("expectedRequestCount"));
        int persistedRequestCount = numericValue(mutationRequestPushGate.get("persistedRequestCount"));
        int pushedRequestCount = numericValue(mutationRequestPushGate.get("pushedRequestCount"));
        int claimableRequestCount = numericValue(mutationRequestPushGate.get("claimableRequestCount"));
        int runningRequestCount = 0;
        List<Map<String, Object>> policyChecks = List.of(
                mutationRequestClaimPolicyCheck(
                        "mutationRequestPushGate",
                        pushGateReady,
                        String.valueOf(mutationRequestPushGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled push gate must refuse transport push before claim can be considered."
                ),
                mutationRequestClaimPolicyCheck(
                        "claimPolicy",
                        false,
                        "DISABLED",
                        "Local Agent mutation request claim is disabled."
                ),
                mutationRequestClaimPolicyCheck(
                        "claimNextInvocation",
                        false,
                        "DISABLED",
                        "repository.claimNext must not run for disabled mutation requests."
                ),
                mutationRequestClaimPolicyCheck(
                        "runningTransition",
                        false,
                        "DISABLED",
                        "No mutation request can move to RUNNING while claim is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of("claimEnabled", "pushEnabled", "requestCreationEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-request-claim-gate.v1");
        result.put("status", pushGateReady ? "REFUSED_CLAIM_DISABLED" : "BLOCKED_CLAIM_DISABLED");
        result.put("pushGateReady", pushGateReady);
        result.put("prerequisitesPassed", pushGateReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourcePushGateSchema", mutationRequestPushGate.get("schema"));
        result.put("sourcePushGateStatus", mutationRequestPushGate.get("status"));
        result.put("claimPolicy", "DISABLED_AUDIT_ONLY");
        result.put("claimNextInvocationEnabled", false);
        result.put("expectedRequestCount", expectedRequestCount);
        result.put("persistedRequestCount", persistedRequestCount);
        result.put("pushedRequestCount", pushedRequestCount);
        result.put("claimableRequestCount", claimableRequestCount);
        result.put("runningRequestCount", runningRequestCount);
        result.put("policyChecks", policyChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimGateEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", pushGateReady
                ? "Local Agent mutation request claim is explicitly refused: no claimNext call, claimable transition, running transition, or mutation is enabled."
                : "Local Agent mutation request claim is blocked because the disabled request push gate is incomplete.");
        return result;
    }

    private Map<String, Object> mutationRequestClaimPolicyCheck(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("claimable", false);
        result.put("running", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> releaseAttemptMutationExecutionGate(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationRequestClaimGate
    ) {
        boolean claimGateReady = "REFUSED_CLAIM_DISABLED".equals(mutationRequestClaimGate.get("status"))
                && Boolean.TRUE.equals(mutationRequestClaimGate.get("prerequisitesPassed"));
        int expectedRequestCount = numericValue(mutationRequestClaimGate.get("expectedRequestCount"));
        int persistedRequestCount = numericValue(mutationRequestClaimGate.get("persistedRequestCount"));
        int pushedRequestCount = numericValue(mutationRequestClaimGate.get("pushedRequestCount"));
        int claimableRequestCount = numericValue(mutationRequestClaimGate.get("claimableRequestCount"));
        int runningRequestCount = numericValue(mutationRequestClaimGate.get("runningRequestCount"));
        int completedRequestCount = 0;
        List<Map<String, Object>> policyChecks = List.of(
                mutationExecutionPolicyCheck(
                        "mutationRequestClaimGate",
                        claimGateReady,
                        String.valueOf(mutationRequestClaimGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled claim gate must refuse claim and running transitions before execution can be considered."
                ),
                mutationExecutionPolicyCheck(
                        "executionPolicy",
                        false,
                        "DISABLED",
                        "Local Agent mutation tool execution is disabled."
                ),
                mutationExecutionPolicyCheck(
                        "toolRunnerInvocation",
                        false,
                        "DISABLED",
                        "No Local Agent tool runner may be invoked for disabled mutation execution."
                ),
                mutationExecutionPolicyCheck(
                        "writeHelperInvocation",
                        false,
                        "DISABLED",
                        "No Local Agent write helper may be called while mutation execution is disabled."
                ),
                mutationExecutionPolicyCheck(
                        "completionTransition",
                        false,
                        "DISABLED",
                        "No mutation request can move to a completed result while execution is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "executionEnabled",
                "writeHelperEnabled",
                "applyEnabled",
                "testEnabled",
                "rollbackRestoreEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-execution-gate.v1");
        result.put("status", claimGateReady ? "REFUSED_EXECUTION_DISABLED" : "BLOCKED_EXECUTION_DISABLED");
        result.put("claimGateReady", claimGateReady);
        result.put("prerequisitesPassed", claimGateReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceClaimGateSchema", mutationRequestClaimGate.get("schema"));
        result.put("sourceClaimGateStatus", mutationRequestClaimGate.get("status"));
        result.put("executionPolicy", "DISABLED_AUDIT_ONLY");
        result.put("toolRunnerInvocationEnabled", false);
        result.put("writeHelperInvocationEnabled", false);
        result.put("expectedRequestCount", expectedRequestCount);
        result.put("persistedRequestCount", persistedRequestCount);
        result.put("pushedRequestCount", pushedRequestCount);
        result.put("claimableRequestCount", claimableRequestCount);
        result.put("runningRequestCount", runningRequestCount);
        result.put("completedRequestCount", completedRequestCount);
        result.put("policyChecks", policyChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("executionGateEnabled", false);
        result.put("executionEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", claimGateReady
                ? "Local Agent mutation execution is explicitly refused: no tool runner, write helper, apply, test, rollback restore, RAG freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent mutation execution is blocked because the disabled request claim gate is incomplete.");
        return result;
    }

    private Map<String, Object> mutationExecutionPolicyCheck(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("executionEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("running", false);
        result.put("completed", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> mutationRequestCreationPolicyCheck(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        return result;
    }

    private List<Map<String, Object>> mutationDispatchBlueprintToolRequests(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationDispatchEnvelopeContract,
            Map<String, Object> postMutationResultContract
    ) {
        Object orderedToolSequence = mutationDispatchEnvelopeContract.get("orderedToolSequence");
        if (!(orderedToolSequence instanceof List<?> sequence)) {
            return List.of();
        }
        List<String> expectedOutputKeys = postMutationResultContractExpectedOutcomes(postMutationResultContract).stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList();
        return sequence.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> mutationDispatchBlueprintToolRequest(attempt, item, expectedOutputKeys))
                .toList();
    }

    private Map<String, Object> mutationDispatchBlueprintToolRequest(
            LocalAgentPatchReleaseAttempt attempt,
            Map<?, ?> sequenceItem,
            List<String> expectedOutputKeys
    ) {
        String key = String.valueOf(sequenceItem.get("key"));
        String toolName = String.valueOf(sequenceItem.get("toolName"));
        String approvalState = String.valueOf(sequenceItem.get("approvalState"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", sequenceItem.get("order"));
        result.put("key", key);
        result.put("status", "REQUEST_BLUEPRINT_DISABLED");
        result.put("toolName", toolName);
        result.put("approvalState", approvalState);
        result.put("sideEffectful", sequenceItem.get("sideEffectful"));
        result.put("rollbackFallback", sequenceItem.get("rollbackFallback"));
        result.put("expectedInput", mutationDispatchBlueprintExpectedInput(key, toolName));
        result.put("expectedOutputKeys", mutationDispatchBlueprintExpectedOutputKeys(key, expectedOutputKeys));
        result.put("expectedExecutionRow", mutationDispatchBlueprintExpectedExecutionRow(
                attempt,
                key,
                toolName,
                approvalState
        ));
        result.put("releaseGateEnabled", false);
        result.put("dispatchDecisionEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        return result;
    }

    private Map<String, Object> mutationDispatchBlueprintExpectedExecutionRow(
            LocalAgentPatchReleaseAttempt attempt,
            String key,
            String toolName,
            String approvalState
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.expected-mutation-execution-row.v1");
        result.put("requestIdAllocation", "SERVER_GENERATED_ON_CREATION");
        result.put("created", false);
        result.put("persisted", false);
        result.put("pushed", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("toolName", toolName);
        result.put("approvalState", approvalState);
        result.put("initialStatus", mutationDispatchBlueprintInitialStatus(approvalState));
        result.put("inputContract", mutationDispatchBlueprintInputContract(key));
        result.put("message", "This row is a disabled creation blueprint only; no Local Agent tool execution row is inserted, pushed, claimable, or executable.");
        return result;
    }

    private String mutationDispatchBlueprintInitialStatus(String approvalState) {
        if (LocalAgentApprovalState.APPROVED.name().equals(approvalState)) {
            return LocalAgentToolStatus.APPROVED.name();
        }
        if (LocalAgentApprovalState.REQUIRED.name().equals(approvalState)) {
            return LocalAgentToolStatus.APPROVAL_REQUIRED.name();
        }
        return LocalAgentToolStatus.PENDING.name();
    }

    private Map<String, Object> mutationDispatchBlueprintInputContract(String key) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceRequestIdRequired", true);
        result.put("releaseAttemptIdRequired", true);
        result.put("sessionIdRequired", true);
        result.put("workspaceIdRequired", true);
        result.put("createdFromApprovedHeldPatch", true);
        result.put("freshObservationOnly", false);
        result.put("dryRunOnly", false);
        result.put("mutationAllowedWhileCreationDisabled", false);
        result.put("mutationAllowedWhenGateOpens", !"postWriteObservation".equals(key));
        result.put("requiresRollbackFallbackLink", "rollbackFallback".equals(key));
        result.put("requiresAllowlistedCommand", "allowlistedVerification".equals(key));
        result.put("requiresPostWriteRepositoryObservation", "postWriteObservation".equals(key));
        return result;
    }

    private Map<String, Object> mutationDispatchBlueprintExpectedInput(String key, String toolName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolName", toolName);
        result.put("sourceRequestIdRequired", true);
        result.put("releaseAttemptIdRequired", true);
        result.put("workspaceIdRequired", true);
        result.put("approvalStateRequired", true);
        result.put("mutationAllowed", false);
        if ("patchApply".equals(key)) {
            result.put("dryRunOnly", false);
            result.put("requiresApprovedPatch", true);
        }
        if ("allowlistedVerification".equals(key)) {
            result.put("requiresAllowlistedCommand", true);
        }
        if ("rollbackFallback".equals(key)) {
            result.put("requiresExplicitRollbackApproval", true);
        }
        return result;
    }

    private List<String> mutationDispatchBlueprintExpectedOutputKeys(String key, List<String> expectedOutputKeys) {
        return switch (key) {
            case "patchApply" -> expectedOutputKeys.contains("patchApplyOutcome")
                    ? List.of("patchApplyOutcome")
                    : List.of();
            case "allowlistedVerification" -> expectedOutputKeys.contains("allowlistedVerificationOutcome")
                    ? List.of("allowlistedVerificationOutcome")
                    : List.of();
            case "postWriteObservation" -> expectedOutputKeys.contains("postWriteRepositoryObservation")
                    ? List.of("postWriteRepositoryObservation", "ragFreshnessMarker")
                    : List.of("ragFreshnessMarker");
            case "rollbackFallback" -> expectedOutputKeys.contains("rollbackFallbackOutcome")
                    ? List.of("rollbackFallbackOutcome")
                    : List.of();
            default -> List.of();
        };
    }

    private Map<String, Object> releaseAttemptMutationDispatchPreflightBoundary(
            LocalAgentPatchReleaseAttempt attempt,
            LocalAgentStatusResponse status,
            Map<String, Object> workspaceVerification,
            Map<String, Object> mutationDispatchEnvelopeContract
    ) {
        List<String> capabilities = status.capabilities() == null ? List.of() : status.capabilities();
        List<LocalAgentToolName> requiredTools = List.of(
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolName.COMMAND_RUN_ALLOWED,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolName.ROLLBACK_RESTORE
        );
        List<Map<String, Object>> capabilityChecks = requiredTools.stream()
                .map(tool -> mutationDispatchCapabilityCheck(tool, capabilities.contains(tool.wireName())))
                .toList();
        List<String> missingCapabilities = capabilityChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("toolName")))
                .toList();
        boolean agentConnected = status.state() == LocalAgentConnectionState.CONNECTED;
        boolean agentMatches = agentConnected && attempt.agentId() != null && attempt.agentId().equals(status.agentId());
        LocalAgentWorkspaceSummary workspace = approvedWorkspaceFromStatus(status, attempt.workspaceId());
        boolean approvedWorkspaceReady = workspace != null;
        boolean workspaceIdentityVerified = workspaceRepositoryVerified(workspaceVerification);
        boolean capabilitiesCovered = missingCapabilities.isEmpty();
        boolean dispatchEnvelopeReady = "READY_DISPATCH_DISABLED".equals(mutationDispatchEnvelopeContract.get("status"))
                && Boolean.TRUE.equals(mutationDispatchEnvelopeContract.get("prerequisitesPassed"));
        boolean prerequisitesPassed = agentMatches
                && approvedWorkspaceReady
                && workspaceIdentityVerified
                && capabilitiesCovered
                && dispatchEnvelopeReady;

        List<String> blockingKeys = new ArrayList<>();
        if (!agentMatches) {
            blockingKeys.add(agentConnected ? "agentMismatch" : "agentConnected");
        }
        if (!approvedWorkspaceReady) {
            blockingKeys.add("approvedWorkspaceReady");
        }
        if (!workspaceIdentityVerified) {
            blockingKeys.add("workspaceIdentityVerified");
        }
        if (!capabilitiesCovered) {
            blockingKeys.add("requiredToolCapabilities");
        }
        if (!dispatchEnvelopeReady) {
            blockingKeys.add("mutationDispatchEnvelopeContract");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-dispatch-preflight-boundary.v1");
        result.put("status", prerequisitesPassed ? "READY_PREFLIGHT_DISABLED" : "BLOCKED_PREFLIGHT_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("requestedAgentId", attempt.agentId());
        result.put("connectedAgentId", status.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("connectionState", status.state().name());
        result.put("agentConnected", agentConnected);
        result.put("agentMatches", agentMatches);
        result.put("agentVersion", status.version());
        result.put("configuredTransport", status.configuredTransport());
        result.put("activeTransport", status.activeTransport());
        result.put("lastSeenAt", status.lastSeenAt());
        result.put("approvedWorkspaceReady", approvedWorkspaceReady);
        if (workspace != null) {
            result.put("workspaceName", workspace.name());
            result.put("workspaceRootPath", workspace.rootPath());
            result.put("workspaceApproved", workspace.approved());
        }
        result.put("workspaceIdentityStatus", workspaceVerification == null
                ? "MISSING"
                : workspaceVerification.getOrDefault("status", "UNKNOWN"));
        result.put("workspaceIdentityVerified", workspaceIdentityVerified);
        result.put("requiredCapabilities", requiredTools.stream().map(LocalAgentToolName::wireName).toList());
        result.put("advertisedCapabilities", capabilities.stream().sorted().toList());
        result.put("capabilityChecks", capabilityChecks);
        result.put("missingCapabilities", missingCapabilities);
        result.put("capabilitiesCovered", capabilitiesCovered);
        result.put("dispatchEnvelopeStatus", mutationDispatchEnvelopeContract.getOrDefault("status", "UNKNOWN"));
        result.put("dispatchEnvelopePrerequisitesPassed", Boolean.TRUE.equals(mutationDispatchEnvelopeContract.get("prerequisitesPassed")));
        result.put("dispatchPreflightEnabled", false);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", prerequisitesPassed
                ? "Local Agent mutation dispatch preflight prerequisites are visible, but dispatch, request creation, push, claim, and mutation remain disabled."
                : "Local Agent mutation dispatch preflight prerequisites are incomplete, and dispatch remains disabled.");
        return result;
    }

    private Map<String, Object> mutationDispatchCapabilityCheck(LocalAgentToolName tool, boolean available) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolName", tool.wireName());
        result.put("available", available);
        result.put("passed", available);
        result.put("blocking", !available);
        result.put("sideEffectful", tool.isSideEffectful());
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        return result;
    }

    private LocalAgentWorkspaceSummary approvedWorkspaceFromStatus(LocalAgentStatusResponse status, UUID workspaceId) {
        if (workspaceId == null || status.workspaces() == null) {
            return null;
        }
        return status.workspaces().stream()
                .filter(workspace -> workspaceId.equals(workspace.workspaceId()) && workspace.approved())
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> releaseAttemptMutationDispatchEnvelopeContract(
            LocalAgentPatchReleaseAttempt attempt,
            List<Map<String, Object>> mutationSequencePlan,
            Map<String, Object> postMutationResultContract,
            Map<String, Object> rollbackReadiness
    ) {
        List<Map<String, Object>> expectedOutcomes = postMutationResultContractExpectedOutcomes(postMutationResultContract);
        boolean sequenceModeled = mutationSequencePlan.size() == 4 && mutationSequencePlan.stream()
                .allMatch(item -> "PLANNED_DISABLED".equals(item.get("status")));
        boolean resultContractModeled = expectedOutcomes.size() == 5;
        boolean rollbackModeled = rollbackReadiness != null && "RESTORE_VALIDATED".equals(rollbackReadiness.get("status"));
        boolean freshnessModeled = hasPostMutationOutcome(postMutationResultContract, "ragFreshnessMarker")
                && Boolean.FALSE.equals(postMutationResultContract.get("ragFreshnessUpdateEnabled"));
        boolean prerequisitesPassed = sequenceModeled && resultContractModeled && rollbackModeled && freshnessModeled;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-dispatch-envelope.v1");
        result.put("status", prerequisitesPassed ? "READY_DISPATCH_DISABLED" : "BLOCKED_DISPATCH_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("dispatchMode", "LOCAL_AGENT_TOOL_SEQUENCE");
        result.put("postMutationResultSchema", postMutationResultContract.get("schema"));
        result.put("expectedOutcomeKeys", expectedOutcomes.stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("orderedToolSequence", mutationSequencePlan.stream()
                .map(item -> Map.of(
                        "order", item.get("order"),
                        "key", item.get("key"),
                        "toolName", item.get("toolName"),
                        "approvalState", item.get("approvalState"),
                        "sideEffectful", item.get("sideEffectful"),
                        "rollbackFallback", item.get("rollbackFallback")
                ))
                .toList());
        result.put("requiredApprovals", mutationSequencePlan.stream()
                .map(item -> Map.of(
                        "key", item.get("key"),
                        "toolName", item.get("toolName"),
                        "approvalState", item.get("approvalState"),
                        "sideEffectful", item.get("sideEffectful")
                ))
                .toList());
        result.put("rollbackObligation", Map.of(
                "required", true,
                "status", rollbackReadiness == null ? "MISSING" : rollbackReadiness.getOrDefault("status", "UNKNOWN"),
                "toolName", LocalAgentToolName.ROLLBACK_RESTORE.wireName(),
                "rollbackRestoreEnabled", false
        ));
        result.put("ragFreshnessObligation", Map.of(
                "required", true,
                "status", freshnessModeled ? "MODELED_UPDATE_DISABLED" : "MISSING",
                "ragFreshnessUpdateEnabled", false,
                "message", "Local file changes must produce a partial reindex marker or explicit stale-index warning before final reporting."
        ));
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        List<String> blockingKeys = new ArrayList<>();
        if (!sequenceModeled) {
            blockingKeys.add("mutationExecutionSequencePlan");
        }
        if (!resultContractModeled) {
            blockingKeys.add("postMutationResultContract");
        }
        if (!rollbackModeled) {
            blockingKeys.add("rollbackReadiness");
        }
        if (!freshnessModeled) {
            blockingKeys.add("ragFreshnessRequirement");
        }
        result.put("blockingKeys", blockingKeys);
        result.put("message", prerequisitesPassed
                ? "Local Agent mutation dispatch envelope is modeled, but request creation, push, claim, and mutation remain disabled."
                : "Local Agent mutation dispatch envelope prerequisites are incomplete, and dispatch remains disabled.");
        return result;
    }

    private Map<String, Object> releaseAttemptMutationCompletionSummary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> finalReadiness,
            List<Map<String, Object>> mutationSequencePlan,
            Map<String, Object> mutationResultIntakeBoundary,
            Map<String, Object> mutationResultAggregationPlan,
            Map<String, Object> finalMutationReportDraft,
            Map<String, Object> finalMutationReportContract,
            Map<String, Object> finalMutationReportFinalizationBoundary,
            Map<String, Object> finalAnswerPublicationBoundary,
            Map<String, Object> releaseEnablementChecklist,
            Map<String, Object> rollbackReadiness,
            Map<String, Object> postMutationResultContract,
            Map<String, Object> mutationDispatchEnvelopeContract,
            Map<String, Object> mutationDispatchPreflightBoundary,
            Map<String, Object> mutationDispatchDecisionModel,
            Map<String, Object> mutationRequestBlueprint,
            Map<String, Object> mutationRequestCreationGate,
            Map<String, Object> mutationRequestPushGate,
            Map<String, Object> mutationRequestClaimGate,
            Map<String, Object> mutationExecutionGate,
            Map<String, Object> mutationWriteHelperSafetyGate,
            Map<String, Object> mutationPostExecutionObservationGate,
            Map<String, Object> mutationObservationAcceptanceGate,
            Map<String, Object> mutationResultIntakePersistenceGate,
            Map<String, Object> mutationRollbackFallbackGate,
            Map<String, Object> mutationRagFreshnessGate,
            Map<String, Object> mutationResultAggregationGate,
            Map<String, Object> mutationPublicationGate,
            Map<String, Object> mutationFinalAnswerGenerationGate,
            Map<String, Object> mutationFinalAnswerCompletionGate,
            Map<String, Object> mutationFinalAnswerPersistenceGate,
            Map<String, Object> mutationFinalAnswerConversationSaveGate,
            Map<String, Object> mutationFinalAnswerUserVisibleCompletionGate,
            Map<String, Object> mutationFinalResponseHandoffGate,
            Map<String, Object> mutationFinalAnswerDeliveryGate,
            Map<String, Object> mutationFinalAnswerDeliveryReceiptGate
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(mutationCompletionSummaryItem(
                "releaseAttemptReadiness",
                Boolean.TRUE.equals(finalReadiness.get("ready")),
                String.valueOf(finalReadiness.getOrDefault("status", "UNKNOWN")),
                "Latest release attempt must be fresh, complete, and based on passing patch preconditions."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationExecutionSequencePlan",
                mutationSequencePlan.size() == 4 && mutationSequencePlan.stream()
                        .allMatch(item -> "PLANNED_DISABLED".equals(item.get("status"))),
                "PLANNED_DISABLED",
                "Future Local Agent mutation steps must be modeled before completion can be summarized."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationResultIntakeBoundary",
                "READY_INTAKE_DISABLED".equals(mutationResultIntakeBoundary.get("status")),
                String.valueOf(mutationResultIntakeBoundary.getOrDefault("status", "UNKNOWN")),
                "Future Local Agent mutation result envelopes must have an intake boundary."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationResultAggregationPlan",
                "READY_AGGREGATION_DISABLED".equals(mutationResultAggregationPlan.get("status")),
                String.valueOf(mutationResultAggregationPlan.getOrDefault("status", "UNKNOWN")),
                "Accepted mutation outcomes must have an aggregation plan for the final mutation report."
        ));
        items.add(mutationCompletionSummaryItem(
                "finalMutationReportDraft",
                "READY_DRAFT_DISABLED".equals(finalMutationReportDraft.get("status")),
                String.valueOf(finalMutationReportDraft.getOrDefault("status", "UNKNOWN")),
                "A disabled final mutation report draft must be modeled before finalization can be considered."
        ));
        items.add(mutationCompletionSummaryItem(
                "finalMutationReportContract",
                finalMutationReportContractRequiredSections(finalMutationReportContract).size() == 7,
                String.valueOf(finalMutationReportContract.getOrDefault("status", "UNKNOWN")),
                "The final report contract must preserve changed files, verification, rollback, freshness, residual risk, and evidence."
        ));
        items.add(mutationCompletionSummaryItem(
                "finalMutationReportFinalizationBoundary",
                "READY_FINALIZATION_DISABLED".equals(finalMutationReportFinalizationBoundary.get("status")),
                String.valueOf(finalMutationReportFinalizationBoundary.getOrDefault("status", "UNKNOWN")),
                "Final report finalization must be ready before the final answer can be considered complete."
        ));
        items.add(mutationCompletionSummaryItem(
                "finalAnswerPublicationBoundary",
                "READY_PUBLICATION_DISABLED".equals(finalAnswerPublicationBoundary.get("status")),
                String.valueOf(finalAnswerPublicationBoundary.getOrDefault("status", "UNKNOWN")),
                "Final answer publication requirements must be ready while publication remains disabled."
        ));
        items.add(mutationCompletionSummaryItem(
                "releaseEnablementChecklist",
                "READY_ENABLEMENT_DISABLED".equals(releaseEnablementChecklist.get("status")),
                String.valueOf(releaseEnablementChecklist.getOrDefault("status", "UNKNOWN")),
                "Release enablement must summarize readiness, rollback, and freshness without making a request claimable."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationDispatchEnvelopeContract",
                "READY_DISPATCH_DISABLED".equals(mutationDispatchEnvelopeContract.get("status")),
                String.valueOf(mutationDispatchEnvelopeContract.getOrDefault("status", "UNKNOWN")),
                "Future Local Agent mutation dispatch must define source ids, ordered tools, approvals, result contract, rollback, and freshness obligations."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationDispatchPreflightBoundary",
                "READY_PREFLIGHT_DISABLED".equals(mutationDispatchPreflightBoundary.get("status")),
                String.valueOf(mutationDispatchPreflightBoundary.getOrDefault("status", "UNKNOWN")),
                "Future dispatch must confirm the selected Local Agent, approved workspace, required tool capabilities, and envelope readiness."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationDispatchDecisionModel",
                "REFUSED_DISPATCH_DISABLED".equals(mutationDispatchDecisionModel.get("status")),
                String.valueOf(mutationDispatchDecisionModel.getOrDefault("status", "UNKNOWN")),
                "Future dispatch must produce an explicit disabled refusal decision before request creation can be considered."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationRequestBlueprint",
                "REFUSED_REQUEST_CREATION_DISABLED".equals(mutationRequestBlueprint.get("status")),
                String.valueOf(mutationRequestBlueprint.getOrDefault("status", "UNKNOWN")),
                "Future request creation must have a disabled blueprint before any Local Agent mutation request can be created."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationRequestCreationGate",
                "REFUSED_CREATION_DISABLED".equals(mutationRequestCreationGate.get("status")),
                String.valueOf(mutationRequestCreationGate.getOrDefault("status", "UNKNOWN")),
                "Future request creation must pass through a disabled creation gate that refuses persistence, push, claim, and mutation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationRequestPushGate",
                "REFUSED_PUSH_DISABLED".equals(mutationRequestPushGate.get("status")),
                String.valueOf(mutationRequestPushGate.getOrDefault("status", "UNKNOWN")),
                "Future request push must pass through a disabled push gate that refuses transport push, claim, and mutation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationRequestClaimGate",
                "REFUSED_CLAIM_DISABLED".equals(mutationRequestClaimGate.get("status")),
                String.valueOf(mutationRequestClaimGate.getOrDefault("status", "UNKNOWN")),
                "Future request claim must pass through a disabled claim gate that refuses claimNext, running transition, and mutation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationExecutionGate",
                "REFUSED_EXECUTION_DISABLED".equals(mutationExecutionGate.get("status")),
                String.valueOf(mutationExecutionGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation execution must pass through a disabled execution gate that refuses tool runner, write helper, apply, test, rollback, freshness, aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationWriteHelperSafetyGate",
                "REFUSED_WRITE_HELPER_DISABLED".equals(mutationWriteHelperSafetyGate.get("status")),
                String.valueOf(mutationWriteHelperSafetyGate.getOrDefault("status", "UNKNOWN")),
                "Future Local Agent patch writes must pass through a disabled write-helper safety gate that requires workspace containment, snapshot, hash recheck, atomic rewrite, and rollback readiness."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationPostExecutionObservationGate",
                "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED".equals(mutationPostExecutionObservationGate.get("status")),
                String.valueOf(mutationPostExecutionObservationGate.getOrDefault("status", "UNKNOWN")),
                "Future post-execution observations must pass through a disabled gate that refuses completed-result capture, rollback fallback, freshness, aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationObservationAcceptanceGate",
                "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED".equals(mutationObservationAcceptanceGate.get("status")),
                String.valueOf(mutationObservationAcceptanceGate.getOrDefault("status", "UNKNOWN")),
                "Future completed observations must pass through a disabled acceptance gate that refuses intake persistence, rollback fallback, freshness, aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationResultIntakePersistenceGate",
                "REFUSED_INTAKE_PERSISTENCE_DISABLED".equals(mutationResultIntakePersistenceGate.get("status")),
                String.valueOf(mutationResultIntakePersistenceGate.getOrDefault("status", "UNKNOWN")),
                "Future accepted observations must pass through a disabled intake persistence gate that refuses persistence, rollback fallback, freshness, aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationRollbackFallbackGate",
                "REFUSED_ROLLBACK_FALLBACK_DISABLED".equals(mutationRollbackFallbackGate.get("status")),
                String.valueOf(mutationRollbackFallbackGate.getOrDefault("status", "UNKNOWN")),
                "Future rollback fallback handling must pass through a disabled rollback fallback gate that refuses rollback execution, freshness, aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationRagFreshnessGate",
                "REFUSED_RAG_FRESHNESS_DISABLED".equals(mutationRagFreshnessGate.get("status")),
                String.valueOf(mutationRagFreshnessGate.getOrDefault("status", "UNKNOWN")),
                "Future RAG freshness updates must pass through a disabled freshness gate that refuses index updates, aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationResultAggregationGate",
                "REFUSED_RESULT_AGGREGATION_DISABLED".equals(mutationResultAggregationGate.get("status")),
                String.valueOf(mutationResultAggregationGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation result aggregation must pass through a disabled aggregation gate that refuses aggregation, publication, and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationPublicationGate",
                "REFUSED_PUBLICATION_DISABLED".equals(mutationPublicationGate.get("status")),
                String.valueOf(mutationPublicationGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation publication must pass through a disabled publication gate that refuses publication and final-answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerGenerationGate",
                "REFUSED_FINAL_ANSWER_GENERATION_DISABLED".equals(mutationFinalAnswerGenerationGate.get("status")),
                String.valueOf(mutationFinalAnswerGenerationGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer generation must pass through a disabled final-answer gate that refuses answer generation."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerCompletionGate",
                "REFUSED_FINAL_ANSWER_COMPLETION_DISABLED".equals(mutationFinalAnswerCompletionGate.get("status")),
                String.valueOf(mutationFinalAnswerCompletionGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer completion must pass through a disabled completion gate that refuses answer completion and delivery."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerPersistenceGate",
                "REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED".equals(mutationFinalAnswerPersistenceGate.get("status")),
                String.valueOf(mutationFinalAnswerPersistenceGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer persistence must pass through a disabled persistence gate that refuses answer persistence and conversation save."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerConversationSaveGate",
                "REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED".equals(mutationFinalAnswerConversationSaveGate.get("status")),
                String.valueOf(mutationFinalAnswerConversationSaveGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer conversation save must pass through a disabled conversation-save gate that refuses conversation save and user-visible completion."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerUserVisibleCompletionGate",
                "REFUSED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED".equals(mutationFinalAnswerUserVisibleCompletionGate.get("status")),
                String.valueOf(mutationFinalAnswerUserVisibleCompletionGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer user-visible completion must pass through a disabled completion gate that refuses user-visible completion and final-response handoff."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalResponseHandoffGate",
                "REFUSED_FINAL_RESPONSE_HANDOFF_DISABLED".equals(mutationFinalResponseHandoffGate.get("status")),
                String.valueOf(mutationFinalResponseHandoffGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-response handoff must pass through a disabled handoff gate that refuses final-response handoff and final-answer delivery."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerDeliveryGate",
                "REFUSED_FINAL_ANSWER_DELIVERY_DISABLED".equals(mutationFinalAnswerDeliveryGate.get("status")),
                String.valueOf(mutationFinalAnswerDeliveryGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer delivery must pass through a disabled delivery gate that refuses final-answer delivery and delivery handoff."
        ));
        items.add(mutationCompletionSummaryItem(
                "mutationFinalAnswerDeliveryReceiptGate",
                "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED".equals(mutationFinalAnswerDeliveryReceiptGate.get("status")),
                String.valueOf(mutationFinalAnswerDeliveryReceiptGate.getOrDefault("status", "UNKNOWN")),
                "Future mutation final-answer delivery receipt must pass through a disabled receipt gate that refuses delivery receipt and acknowledgement."
        ));
        items.add(mutationCompletionSummaryItem(
                "acknowledgementSaveRefusal",
                "DISABLED_AUDIT_ONLY".equals(mutationFinalAnswerDeliveryReceiptGate.get("acknowledgementSavePolicy"))
                        && Boolean.FALSE.equals(mutationFinalAnswerDeliveryReceiptGate.get("acknowledgementSaveEnabled")),
                String.valueOf(mutationFinalAnswerDeliveryReceiptGate.getOrDefault("acknowledgementSavePolicy", "UNKNOWN")),
                "Future acknowledgement save must remain explicitly refused until final-answer delivery receipt is enabled."
        ));
        items.add(mutationCompletionSummaryItem(
                "rollbackReadiness",
                rollbackReadiness != null && "RESTORE_VALIDATED".equals(rollbackReadiness.get("status")),
                rollbackReadiness == null ? "MISSING" : String.valueOf(rollbackReadiness.getOrDefault("status", "UNKNOWN")),
                "Rollback readiness must be visible before a future mutation can be reported as safely complete."
        ));
        items.add(mutationCompletionSummaryItem(
                "ragFreshnessRequirement",
                hasPostMutationOutcome(postMutationResultContract, "ragFreshnessMarker")
                        && Boolean.FALSE.equals(postMutationResultContract.get("ragFreshnessUpdateEnabled")),
                "MODELED_UPDATE_DISABLED",
                "Completion must include an explicit RAG freshness marker while freshness updates remain disabled."
        ));

        boolean prerequisitesPassed = items.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-completion-summary.v1");
        result.put("status", prerequisitesPassed ? "READY_COMPLETION_DISABLED" : "BLOCKED_COMPLETION_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerDeliveryReceiptGateSchema", mutationFinalAnswerDeliveryReceiptGate.get("schema"));
        result.put("sourceFinalAnswerDeliveryReceiptGateStatus", mutationFinalAnswerDeliveryReceiptGate.get("status"));
        result.put("sourceFinalAnswerDeliveryReceiptGateSessionId", mutationFinalAnswerDeliveryReceiptGate.get("sessionId"));
        result.put("sourceFinalAnswerDeliveryReceiptGateUserId", mutationFinalAnswerDeliveryReceiptGate.get("userId"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAgentId", mutationFinalAnswerDeliveryReceiptGate.get("agentId"));
        result.put("sourceFinalAnswerDeliveryReceiptGateWorkspaceId", mutationFinalAnswerDeliveryReceiptGate.get("workspaceId"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAcknowledgementSavePolicy", mutationFinalAnswerDeliveryReceiptGate.get("acknowledgementSavePolicy"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAcknowledgementSaveEnabled", mutationFinalAnswerDeliveryReceiptGate.get("acknowledgementSaveEnabled"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationGateSchema", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateSchema"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationGateStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationGateSessionId", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateSessionId"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationGateUserId", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateUserId"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationGateAgentId", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateAgentId"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationGateWorkspaceId", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationGateWorkspaceId"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationBoundaryStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationBoundaryPrerequisitesPassed"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryDraftStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationBoundaryDraftStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryDraftSections", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationBoundaryDraftSections"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationSummaryStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGateAcceptedObservationSummaryStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGateAcceptedObservationCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationAcceptedCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGateAcceptedObservationAcceptedCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationRejectedCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGateAcceptedObservationRejectedCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGateMissingMutationResultRiskVisible", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGateMissingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerDeliveryReceiptGateStaleIndexRiskVisible", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGateStaleIndexRiskVisible"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationSummaryStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationAcceptedCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationAcceptedObservationRejectedCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationMissingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationStaleIndexRiskVisible", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationStaleIndexRiskVisible"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationToolName"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationVerificationStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationFinalAnswerDeliveryReceiptGate.get("sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerCompletionEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("items", items);
        result.put("blockingKeys", items.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("message", prerequisitesPassed
                ? "Local Agent mutation completion prerequisites are modeled, but execution, aggregation, publication, and final-answer generation remain disabled."
                : "Local Agent mutation completion prerequisites are incomplete, and execution, aggregation, publication, and final-answer generation remain disabled.");
        return result;
    }

    private Map<String, Object> mutationCompletionSummaryItem(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerCompletionEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> releaseAttemptMutationHandoffSummary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> completionSummary
    ) {
        boolean completionReady = "READY_COMPLETION_DISABLED".equals(completionSummary.get("status"))
                && Boolean.TRUE.equals(completionSummary.get("prerequisitesPassed"));
        @SuppressWarnings("unchecked")
        List<String> blockingKeys = completionSummary.get("blockingKeys") instanceof List<?>
                ? ((List<?>) completionSummary.get("blockingKeys")).stream().map(String::valueOf).toList()
                : List.of("mutationCompletionSummary");
        Map<String, Object> disabledControls = new LinkedHashMap<>();
        disabledControls.put("releaseGateEnabled", false);
        disabledControls.put("requestCreationEnabled", false);
        disabledControls.put("pushEnabled", false);
        disabledControls.put("claimEnabled", false);
        disabledControls.put("writeHelperEnabled", false);
        disabledControls.put("applyEnabled", false);
        disabledControls.put("testEnabled", false);
        disabledControls.put("rollbackRestoreEnabled", false);
        disabledControls.put("ragFreshnessUpdateEnabled", false);
        disabledControls.put("mutationResultAggregationEnabled", false);
        disabledControls.put("publicationEnabled", false);
        disabledControls.put("finalAnswerGenerationEnabled", false);
        disabledControls.put("finalAnswerCompletionEnabled", false);
        disabledControls.put("finalAnswerDeliveryEnabled", false);
        disabledControls.put("finalAnswerPersistenceEnabled", false);
        disabledControls.put("conversationTurnSaveEnabled", false);
        disabledControls.put("userVisibleCompletionEnabled", false);
        disabledControls.put("finalResponseHandoffEnabled", false);
        disabledControls.put("deliveryHandoffEnabled", false);
        disabledControls.put("deliveryReceiptEnabled", false);
        disabledControls.put("acknowledgementSaveEnabled", false);
        disabledControls.put("claimable", false);
        disabledControls.put("mutationAllowed", false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-handoff-summary.v1");
        result.put("status", completionReady ? "READY_HANDOFF_DISABLED" : "BLOCKED_HANDOFF_DISABLED");
        result.put("prerequisitesPassed", completionReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceCompletionSummaryStatus", completionSummary.get("status"));
        result.put("sourceCompletionSummarySchema", completionSummary.get("schema"));
        result.put("sourceCompletionPrerequisitesPassed", completionSummary.get("prerequisitesPassed"));
        result.put("sourceCompletionSummarySessionId", completionSummary.get("sessionId"));
        result.put("sourceCompletionSummaryUserId", completionSummary.get("userId"));
        result.put("sourceCompletionSummaryAgentId", completionSummary.get("agentId"));
        result.put("sourceCompletionSummaryWorkspaceId", completionSummary.get("workspaceId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateSchema", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateSchema"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateSessionId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateSessionId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateUserId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateUserId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAgentId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAgentId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateWorkspaceId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateWorkspaceId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSavePolicy", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAcknowledgementSavePolicy"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSaveEnabled", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAcknowledgementSaveEnabled"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationGateSchema", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateSchema"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationGateStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationGateSessionId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateSessionId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationGateUserId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateUserId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationGateAgentId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateAgentId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationGateWorkspaceId", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationGateWorkspaceId"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryDraftStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryDraftSections", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryDraftSections"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationSummaryStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationSummaryStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationAcceptedCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationRejectedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateAcceptedObservationRejectedCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateMissingMutationResultRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateMissingMutationResultRiskVisible"));
        result.put("sourceCompletionSummaryDeliveryReceiptGateStaleIndexRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGateStaleIndexRiskVisible"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationRejectedCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationMissingMutationResultRiskVisible"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationStaleIndexRiskVisible"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationToolName"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", completionSummary.get("sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("disabledControls", disabledControls);
        result.put("blockingKeys", completionReady ? List.of("releaseGateEnabled", "requestCreationEnabled", "pushEnabled", "claimEnabled", "mutationAllowed") : blockingKeys);
        result.put("handoffStages", List.of(
                mutationHandoffStage("dispatchDecision", "mutationDispatchDecisionModel", completionReady),
                mutationHandoffStage("requestCreation", "mutationRequestCreationGate", completionReady),
                mutationHandoffStage("transportPush", "mutationRequestPushGate", completionReady),
                mutationHandoffStage("agentClaim", "mutationRequestClaimGate", completionReady),
                mutationHandoffStage("toolExecution", "mutationExecutionGate", completionReady),
                mutationHandoffStage("resultIntake", "mutationResultIntakePersistenceGate", completionReady),
                mutationHandoffStage("finalResponse", "mutationFinalResponseHandoffGate", completionReady),
                mutationHandoffStage("deliveryReceipt", "mutationFinalAnswerDeliveryReceiptGate", completionReady),
                mutationHandoffStage("acknowledgementSave", "mutationFinalAnswerDeliveryReceiptGate", completionReady)
        ));
        result.put("message", completionReady
                ? "Local Agent mutation handoff prerequisites are modeled, but release, request creation, push, claim, execution, result handling, final response, delivery, and mutation remain disabled."
                : "Local Agent mutation handoff is blocked by incomplete disabled readiness inputs, and all handoff controls remain disabled.");
        return result;
    }

    private Map<String, Object> mutationHandoffStage(String key, String sourceGateKey, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("sourceGateKey", sourceGateKey);
        result.put("status", ready ? "MODELED_DISABLED" : "BLOCKED_DISABLED");
        result.put("passed", ready);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("executionEnabled", false);
        result.put("resultIntakeEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        return result;
    }

    private Map<String, Object> releaseAttemptEnablementChecklist(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> finalReadiness,
            List<Map<String, Object>> mutationSequencePlan,
            Map<String, Object> postMutationResultContract,
            Map<String, Object> rollbackReadiness
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(releaseEnablementChecklistItem(
                "finalReadiness",
                Boolean.TRUE.equals(finalReadiness.get("ready")),
                String.valueOf(finalReadiness.getOrDefault("status", "UNKNOWN")),
                "Final readiness must be fresh, complete, and based on passing patch preconditions."
        ));
        items.add(releaseEnablementChecklistItem(
                "localAgentMutationExecutionSequence",
                mutationSequencePlan.size() == 4 && mutationSequencePlan.stream()
                        .allMatch(item -> "PLANNED_DISABLED".equals(item.get("status"))),
                "PLANNED_DISABLED",
                "Future Local Agent patch apply, allowlisted verification, post-write observation, and rollback fallback steps must be modeled."
        ));
        items.add(releaseEnablementChecklistItem(
                "postMutationResultContract",
                postMutationResultContractExpectedOutcomes(postMutationResultContract).size() == 5,
                String.valueOf(postMutationResultContract.getOrDefault("status", "UNKNOWN")),
                "Future Local Agent results must include patch apply, verification, post-write observation, rollback fallback, and RAG freshness outcomes."
        ));
        items.add(releaseEnablementChecklistItem(
                "rollbackReadiness",
                rollbackReadiness != null && "RESTORE_VALIDATED".equals(rollbackReadiness.get("status")),
                rollbackReadiness == null ? "MISSING" : String.valueOf(rollbackReadiness.getOrDefault("status", "UNKNOWN")),
                "Rollback manifest must be structurally valid before any future release can make the held patch claimable."
        ));
        items.add(releaseEnablementChecklistItem(
                "ragFreshnessRequirement",
                hasPostMutationOutcome(postMutationResultContract, "ragFreshnessMarker")
                        && Boolean.FALSE.equals(postMutationResultContract.get("ragFreshnessUpdateEnabled")),
                "MODELED_UPDATE_DISABLED",
                "Future patch writes must mark code RAG freshness for partial reindex or explicit stale-index warning."
        ));

        boolean prerequisitesPassed = items.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.release-enablement-checklist.v1");
        result.put("status", prerequisitesPassed ? "READY_ENABLEMENT_DISABLED" : "BLOCKED_ENABLEMENT_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("releaseGateEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("items", items);
        result.put("blockingKeys", items.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("message", prerequisitesPassed
                ? "All modeled release enablement prerequisites are visible, but the release gate is disabled so no Local Agent mutation can be claimed."
                : "Release enablement prerequisites are incomplete or not fresh, and the release gate is disabled so no Local Agent mutation can be claimed.");
        return result;
    }

    private Map<String, Object> releaseEnablementChecklistItem(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("releaseGateEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        return result;
    }

    private List<Map<String, Object>> postMutationResultContractExpectedOutcomes(Map<String, Object> contract) {
        if (contract.get("expectedOutcomes") instanceof List<?> outcomes) {
            return outcomes.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> copyMap((Map<?, ?>) item))
                    .toList();
        }
        return List.of();
    }

    private boolean hasPostMutationOutcome(Map<String, Object> contract, String key) {
        return postMutationResultContractExpectedOutcomes(contract).stream()
                .anyMatch(item -> key.equals(item.get("key")));
    }

    private Map<String, Object> releaseAttemptMutationResultIntakeBoundary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> postMutationResultContract
    ) {
        List<Map<String, Object>> expectedOutcomes = postMutationResultContractExpectedOutcomes(postMutationResultContract);
        List<String> requiredOutcomeKeys = expectedOutcomes.stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList();
        List<Map<String, Object>> requirements = List.of(
                mutationResultIntakeRequirement(
                        "sourceRequestLink",
                        true,
                        "REQUIRED_DISABLED",
                        "Future mutation result envelopes must include the approved-held source request id."
                ),
                mutationResultIntakeRequirement(
                        "releaseAttemptLink",
                        true,
                        "REQUIRED_DISABLED",
                        "Future mutation result envelopes must include the release attempt id that made the held request claimable."
                ),
                mutationResultIntakeRequirement(
                        "expectedOutcomeKeys",
                        expectedOutcomes.size() == 5,
                        String.valueOf(postMutationResultContract.getOrDefault("status", "UNKNOWN")),
                        "Future mutation result envelopes must cover patch apply, verification, post-write observation, rollback fallback, and RAG freshness."
                ),
                mutationResultIntakeRequirement(
                        "mutationAppliedProof",
                        hasPostMutationOutcome(postMutationResultContract, "patchApplyOutcome"),
                        "PATCH_APPLY_RESULT_REQUIRED",
                        "Final completion must require patch.apply output with mutationApplied=true before claiming files changed."
                ),
                mutationResultIntakeRequirement(
                        "verificationAndRollbackDisclosure",
                        hasPostMutationOutcome(postMutationResultContract, "allowlistedVerificationOutcome")
                                && hasPostMutationOutcome(postMutationResultContract, "rollbackFallbackOutcome"),
                        "VERIFICATION_AND_ROLLBACK_REQUIRED",
                        "Verification failure, skipped verification, rollback execution, and rollback refusal must remain visible in final reporting."
                ),
                mutationResultIntakeRequirement(
                        "ragFreshnessDisclosure",
                        hasPostMutationOutcome(postMutationResultContract, "ragFreshnessMarker"),
                        "RAG_FRESHNESS_REQUIRED",
                        "Local file changes must produce an explicit RAG freshness marker or stale-index warning before final reporting."
                )
        );
        boolean prerequisitesPassed = requirements.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-result-intake-boundary.v1");
        result.put("status", prerequisitesPassed ? "READY_INTAKE_DISABLED" : "BLOCKED_INTAKE_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("postMutationResultSchema", postMutationResultContract.get("schema"));
        result.put("requiredOutcomeKeys", requiredOutcomeKeys);
        result.put("acceptedTerminalStatuses", List.of(
                LocalAgentToolStatus.SUCCEEDED.name(),
                LocalAgentToolStatus.FAILED.name(),
                LocalAgentToolStatus.REJECTED.name(),
                LocalAgentToolStatus.TIMED_OUT.name(),
                LocalAgentToolStatus.DISCONNECTED.name()
        ));
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("requirements", requirements);
        result.put("blockingKeys", requirements.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("message", prerequisitesPassed
                ? "Future Local Agent mutation result intake requirements are modeled, but result aggregation and final-answer generation remain disabled."
                : "Future Local Agent mutation result intake requirements are incomplete, and result aggregation remains disabled.");
        return result;
    }

    private Map<String, Object> mutationResultIntakeRequirement(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> releaseAttemptFinalMutationReportContract(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> postMutationResultContract,
            Map<String, Object> rollbackReadiness,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        List<String> expectedOutcomeKeys = postMutationResultContractExpectedOutcomes(postMutationResultContract).stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.final-mutation-report.v1");
        result.put("status", "CONTRACT_DISABLED");
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("postMutationResultSchema", postMutationResultContract.get("schema"));
        result.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        result.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("acceptedMutationObservationCount", acceptedMutationObservationSummary.get("observationCount"));
        result.put("acceptedMutationObservationAcceptedCount", acceptedMutationObservationSummary.get("acceptedCount"));
        result.put("acceptedMutationObservationRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        result.put("acceptedMutationObservationTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        result.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        result.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
        result.put("expectedOutcomeKeys", expectedOutcomeKeys);
        result.put("rollbackReadinessStatus", rollbackReadiness == null ? "UNKNOWN" : rollbackReadiness.getOrDefault("status", "UNKNOWN"));
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("acceptedObservationAggregationEnabled", false);
        result.put("message", "Future final mutation report is modeled for audit only; no Local Agent mutation, verification, rollback, RAG freshness update, or final-answer generation is enabled.");
        result.put("requiredSections", List.of(
                finalMutationReportSection(
                        "changedFiles",
                        "patchApplyOutcome",
                        "Changed file paths, before/after hashes, and mutationApplied=true from the guarded Local Agent patch apply result."
                ),
                finalMutationReportSection(
                        "verificationOutcome",
                        "allowlistedVerificationOutcome",
                        "Allowlisted test/build command status, command label, exit code, duration, and failure summary if verification fails."
                ),
                finalMutationReportSection(
                        "postWriteRepositoryObservation",
                        "postWriteRepositoryObservation",
                        "Read-only git.status repository identity and working-tree state after patch writes and verification."
                ),
                finalMutationReportSection(
                        "rollbackState",
                        "rollbackFallbackOutcome",
                        "Snapshot manifest id, rollback readiness, rollback execution result when used, and whether manual recovery remains required."
                ),
                finalMutationReportSection(
                        "ragFreshnessState",
                        "ragFreshnessMarker",
                        "Partial reindex marker or explicit stale-index warning after user-local file changes."
                ),
                finalMutationReportSection(
                        "residualRisks",
                        null,
                        "Remaining risks, skipped checks, failed checks, disconnected-agent conditions, and user follow-up required before trusting the change."
                ),
                finalMutationReportSection(
                        "evidenceAndCitations",
                        null,
                        "Original code evidence, patch validation evidence, Local Agent observation ids, and citations that support the final answer."
                )
        ));
        result.put("answerQualityGuardrails", List.of(
                "Final answer must not claim files changed unless patchApplyOutcome reports mutationApplied=true.",
                "Final answer must report failed or skipped verification instead of hiding it.",
                "Final answer must include RAG freshness state when local files changed.",
                "Final answer must include rollback state and residual risk when apply or verification fails."
        ));
        return result;
    }

    private Map<String, Object> releaseAttemptMutationResultAggregationPlan(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> postMutationResultContract,
            Map<String, Object> finalMutationReportContract,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        List<String> outcomeKeys = postMutationResultContractExpectedOutcomes(postMutationResultContract).stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList();
        List<Map<String, Object>> requiredSections = finalMutationReportContractRequiredSections(finalMutationReportContract);
        List<Map<String, Object>> steps = List.of(
                mutationResultAggregationStep(
                        1,
                        "changedFiles",
                        "patchApplyOutcome",
                        "Require mutationApplied=true, changed file paths, and before/after hashes before reporting files changed."
                ),
                mutationResultAggregationStep(
                        2,
                        "verificationOutcome",
                        "allowlistedVerificationOutcome",
                        "Carry allowlisted verification status, skipped checks, failure summary, command label, exit code, and duration into the final report."
                ),
                mutationResultAggregationStep(
                        3,
                        "postWriteRepositoryObservation",
                        "postWriteRepositoryObservation",
                        "Carry post-write git status and repository identity observations into the final report."
                ),
                mutationResultAggregationStep(
                        4,
                        "rollbackState",
                        "rollbackFallbackOutcome",
                        "Carry rollback readiness, restore result, rollback refusal, and manual recovery requirements into the final report."
                ),
                mutationResultAggregationStep(
                        5,
                        "ragFreshnessState",
                        "ragFreshnessMarker",
                        "Carry partial reindex marker or stale-index warning into the final report."
                ),
                mutationResultAggregationStep(
                        6,
                        "residualRisks",
                        null,
                        "Derive residual risks from failed apply, failed or skipped verification, rollback refusal, stale index, disconnected agent, and missing observations."
                ),
                mutationResultAggregationStep(
                        7,
                        "evidenceAndCitations",
                        null,
                        "Attach source evidence, patch validation evidence, Local Agent observation ids, and citations used to support the final answer."
                )
        );
        boolean prerequisitesPassed = outcomeKeys.size() == 5 && requiredSections.size() == 7 && steps.size() == 7;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-result-aggregation-plan.v1");
        result.put("status", prerequisitesPassed ? "READY_AGGREGATION_DISABLED" : "BLOCKED_AGGREGATION_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("postMutationResultSchema", postMutationResultContract.get("schema"));
        result.put("finalMutationReportSchema", finalMutationReportContract.get("schema"));
        result.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        result.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("acceptedMutationObservationCount", acceptedMutationObservationSummary.get("observationCount"));
        result.put("acceptedMutationObservationAcceptedCount", acceptedMutationObservationSummary.get("acceptedCount"));
        result.put("acceptedMutationObservationRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        result.put("acceptedMutationObservationTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        result.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        result.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
        result.put("sourceOutcomeKeys", outcomeKeys);
        result.put("targetReportSections", requiredSections.stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("acceptedObservationAggregationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("steps", steps);
        result.put("blockingKeys", prerequisitesPassed ? List.of() : List.of("aggregationPrerequisites"));
        result.put("message", prerequisitesPassed
                ? "Future Local Agent mutation result aggregation is modeled, but aggregation and final-answer generation remain disabled."
                : "Future Local Agent mutation result aggregation prerequisites are incomplete, and aggregation remains disabled.");
        return result;
    }

    private Map<String, Object> mutationResultAggregationStep(
            int order,
            String targetSectionKey,
            String sourceOutcomeKey,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("targetSectionKey", targetSectionKey);
        result.put("status", "PLANNED_DISABLED");
        result.put("required", true);
        result.put("message", message);
        if (sourceOutcomeKey != null) {
            result.put("sourceOutcomeKey", sourceOutcomeKey);
        }
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        return result;
    }

    private Map<String, Object> finalMutationReportSection(String key, String sourceOutcomeKey, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", "REQUIRED_DISABLED");
        result.put("required", true);
        result.put("resultRequired", true);
        if (sourceOutcomeKey != null) {
            result.put("sourceOutcomeKey", sourceOutcomeKey);
        }
        result.put("message", message);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        return result;
    }

    private Map<String, Object> releaseAttemptFinalMutationReportFinalizationBoundary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> finalReadiness,
            Map<String, Object> postMutationResultContract,
            Map<String, Object> finalMutationReportContract,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        List<Map<String, Object>> requiredSections = finalMutationReportContractRequiredSections(finalMutationReportContract);
        List<String> guardrails = finalMutationReportContractGuardrails(finalMutationReportContract);
        int observationCount = numericValue(acceptedMutationObservationSummary.get("observationCount"));
        int acceptedObservationCount = numericValue(acceptedMutationObservationSummary.get("acceptedCount"));
        List<Map<String, Object>> requirements = List.of(
                finalizationRequirement(
                        "releaseAttemptReady",
                        Boolean.TRUE.equals(finalReadiness.get("ready")),
                        String.valueOf(finalReadiness.getOrDefault("status", "UNKNOWN")),
                        "Release attempt must be fresh, complete, and based on passing patch preconditions."
                ),
                finalizationRequirement(
                        "postMutationOutcomesModeled",
                        postMutationResultContractExpectedOutcomes(postMutationResultContract).size() == 5,
                        String.valueOf(postMutationResultContract.getOrDefault("status", "UNKNOWN")),
                        "Patch apply, verification, post-write observation, rollback fallback, and RAG freshness outcomes must be available."
                ),
                finalizationRequirement(
                        "finalReportSectionsModeled",
                        requiredSections.size() == 7,
                        String.valueOf(finalMutationReportContract.getOrDefault("status", "UNKNOWN")),
                        "Final report must include changed files, verification, repository observation, rollback, RAG freshness, residual risk, and evidence sections."
                ),
                finalizationRequirement(
                        "answerQualityGuardrailsModeled",
                        guardrails.size() >= 4,
                        "GUARDRAILS_MODELED",
                        "Final answer quality guardrails must prevent false completion, hidden verification failures, missing freshness state, and missing rollback risk."
                )
        );
        boolean prerequisitesPassed = requirements.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.finalization-boundary.v1");
        result.put("status", prerequisitesPassed ? "READY_FINALIZATION_DISABLED" : "BLOCKED_FINALIZATION_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        result.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("acceptedMutationObservationCount", observationCount);
        result.put("acceptedMutationObservationAcceptedCount", acceptedObservationCount);
        result.put("acceptedMutationObservationRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        result.put("acceptedMutationObservationTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        result.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        result.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
        result.put("missingMutationResultRiskVisible", observationCount == 0);
        result.put("staleIndexRiskVisible", acceptedObservationCount > 0);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("requirements", requirements);
        result.put("blockingKeys", requirements.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("message", prerequisitesPassed
                ? "Final report prerequisites are modeled, but final-answer generation remains disabled until real Local Agent mutation observations are available."
                : "Final report prerequisites are incomplete, and final-answer generation remains disabled.");
        return result;
    }

    private Map<String, Object> releaseAttemptFinalAnswerPublicationBoundary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> finalReadiness,
            Map<String, Object> finalMutationReportContract,
            Object aggregationPlan,
            Map<String, Object> finalMutationReportDraft,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        Map<String, Object> aggregation = aggregationPlan instanceof Map<?, ?> map ? copyMap(map) : Map.of();
        List<Map<String, Object>> requiredSections = finalMutationReportContractRequiredSections(finalMutationReportContract);
        List<Map<String, Object>> draftSections = finalMutationReportDraftSections(finalMutationReportDraft);
        List<String> guardrails = finalMutationReportContractGuardrails(finalMutationReportContract);
        int observationCount = numericValue(acceptedMutationObservationSummary.get("observationCount"));
        int acceptedObservationCount = numericValue(acceptedMutationObservationSummary.get("acceptedCount"));
        List<Map<String, Object>> requirements = List.of(
                publicationRequirement(
                        "releaseAttemptReady",
                        Boolean.TRUE.equals(finalReadiness.get("ready")),
                        String.valueOf(finalReadiness.getOrDefault("status", "UNKNOWN")),
                        "Publication requires fresh, complete, passing release-attempt readiness."
                ),
                publicationRequirement(
                        "aggregationPlanModeled",
                        "READY_AGGREGATION_DISABLED".equals(aggregation.get("status")),
                        String.valueOf(aggregation.getOrDefault("status", "UNKNOWN")),
                        "Publication requires an aggregation plan from Local Agent mutation outcomes to final report sections."
                ),
                publicationRequirement(
                        "finalReportContractModeled",
                        requiredSections.size() == 7,
                        String.valueOf(finalMutationReportContract.getOrDefault("status", "UNKNOWN")),
                        "Publication requires changed files, verification, repository observation, rollback, RAG freshness, residual risk, and evidence sections."
                ),
                publicationRequirement(
                        "finalReportDraftModeled",
                        "READY_DRAFT_DISABLED".equals(finalMutationReportDraft.get("status")) && draftSections.size() == 7,
                        String.valueOf(finalMutationReportDraft.getOrDefault("status", "UNKNOWN")),
                        "Publication requires the disabled final mutation report draft to map aggregation outcomes into report sections."
                ),
                publicationRequirement(
                        "answerQualityGuardrailsModeled",
                        guardrails.size() >= 4,
                        "GUARDRAILS_MODELED",
                        "Publication must preserve guardrails against false completion, hidden verification failures, missing freshness state, and missing rollback risk."
                )
        );
        boolean prerequisitesPassed = requirements.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.final-answer-publication-boundary.v1");
        result.put("status", prerequisitesPassed ? "READY_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("finalMutationReportSchema", finalMutationReportContract.get("schema"));
        result.put("aggregationPlanSchema", aggregation.get("schema"));
        result.put("finalMutationReportDraftSchema", finalMutationReportDraft.get("schema"));
        result.put("finalMutationReportDraftStatus", finalMutationReportDraft.get("status"));
        result.put("finalMutationReportDraftSections", draftSections.stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        result.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("acceptedMutationObservationCount", observationCount);
        result.put("acceptedMutationObservationAcceptedCount", acceptedObservationCount);
        result.put("acceptedMutationObservationRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        result.put("acceptedMutationObservationTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        result.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        result.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
        result.put("missingMutationResultRiskVisible", observationCount == 0);
        result.put("staleIndexRiskVisible", acceptedObservationCount > 0);
        result.put("requiredReportSections", requiredSections.stream()
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("answerQualityGuardrails", guardrails);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("requirements", requirements);
        result.put("blockingKeys", requirements.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        result.put("message", prerequisitesPassed
                ? "Final answer publication requirements are modeled, but publication and final-answer generation remain disabled."
                : "Final answer publication requirements are incomplete, and publication remains disabled.");
        return result;
    }

    private Map<String, Object> publicationRequirement(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> finalizationRequirement(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("message", message);
        return result;
    }

    private List<Map<String, Object>> finalMutationReportContractRequiredSections(Map<String, Object> contract) {
        if (contract.get("requiredSections") instanceof List<?> sections) {
            return sections.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> copyMap((Map<?, ?>) item))
                    .toList();
        }
        return List.of();
    }

    private List<Map<String, Object>> finalMutationReportDraftSections(Map<String, Object> draft) {
        if (draft.get("sections") instanceof List<?> sections) {
            return sections.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> copyMap((Map<?, ?>) item))
                    .toList();
        }
        return List.of();
    }

    private List<String> finalMutationReportContractGuardrails(Map<String, Object> contract) {
        if (contract.get("answerQualityGuardrails") instanceof List<?> guardrails) {
            return guardrails.stream()
                    .map(String::valueOf)
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> releaseAttemptPostMutationResultContract(LocalAgentPatchReleaseAttempt attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.post-mutation-result.v1");
        result.put("status", "CONTRACT_DISABLED");
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("message", "Future post-mutation result envelope is modeled for audit only; no Local Agent mutation, verification, rollback, or RAG freshness update is enabled.");
        result.put("expectedOutcomes", List.of(
                releaseAttemptPostMutationOutcome(
                        "patchApplyOutcome",
                        LocalAgentToolName.PATCH_APPLY,
                        "Patch application result, changed files, write hashes, and mutationApplied=true after guarded release.",
                        true,
                        false,
                        true
                ),
                releaseAttemptPostMutationOutcome(
                        "allowlistedVerificationOutcome",
                        LocalAgentToolName.COMMAND_RUN_ALLOWED,
                        "Allowlisted test or build result after patch application.",
                        true,
                        false,
                        true
                ),
                releaseAttemptPostMutationOutcome(
                        "postWriteRepositoryObservation",
                        LocalAgentToolName.GIT_STATUS,
                        "Read-only repository identity and working-tree observation after writes and verification.",
                        false,
                        false,
                        true
                ),
                releaseAttemptPostMutationOutcome(
                        "rollbackFallbackOutcome",
                        LocalAgentToolName.ROLLBACK_RESTORE,
                        "Rollback restore result if apply or verification fails and explicit rollback approval is present.",
                        true,
                        true,
                        false
                ),
                releaseAttemptPostMutationOutcome(
                        "ragFreshnessMarker",
                        null,
                        "Server-side marker that local files changed and code RAG requires partial reindex or stale-index warning.",
                        false,
                        false,
                        true
                )
        ));
        return result;
    }

    private Map<String, Object> releaseAttemptPostMutationOutcome(
            String key,
            LocalAgentToolName toolName,
            String message,
            boolean sideEffectful,
            boolean rollbackFallback,
            boolean requiredForSuccess
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", "EXPECTED_DISABLED");
        if (toolName != null) {
            result.put("toolName", toolName.wireName());
        }
        result.put("message", message);
        result.put("sideEffectful", sideEffectful);
        result.put("rollbackFallback", rollbackFallback);
        result.put("requiredForSuccess", requiredForSuccess);
        result.put("resultRequired", true);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        return result;
    }

    private List<Map<String, Object>> releaseAttemptMutationExecutionSequencePlan(LocalAgentPatchReleaseAttempt attempt) {
        return List.of(
                releaseAttemptMutationExecutionStep(
                        1,
                        "patchApply",
                        "Apply the approved patch in the user's Local Agent workspace after release makes the held request claimable.",
                        attempt,
                        LocalAgentToolName.PATCH_APPLY,
                        LocalAgentApprovalState.APPROVED,
                        true,
                        false
                ),
                releaseAttemptMutationExecutionStep(
                        2,
                        "allowlistedVerification",
                        "Run user-approved allowlisted test or build commands after patch application.",
                        attempt,
                        LocalAgentToolName.COMMAND_RUN_ALLOWED,
                        LocalAgentApprovalState.APPROVED,
                        true,
                        false
                ),
                releaseAttemptMutationExecutionStep(
                        3,
                        "postWriteObservation",
                        "Record read-only repository status after writes and verification complete.",
                        attempt,
                        LocalAgentToolName.GIT_STATUS,
                        LocalAgentApprovalState.NOT_REQUIRED,
                        false,
                        false
                ),
                releaseAttemptMutationExecutionStep(
                        4,
                        "rollbackFallback",
                        "Restore the managed snapshot only after explicit rollback approval if apply or verification fails.",
                        attempt,
                        LocalAgentToolName.ROLLBACK_RESTORE,
                        LocalAgentApprovalState.REQUIRED,
                        true,
                        true
                )
        );
    }

    private Map<String, Object> releaseAttemptMutationExecutionStep(
            int order,
            String key,
            String message,
            LocalAgentPatchReleaseAttempt attempt,
            LocalAgentToolName toolName,
            LocalAgentApprovalState approvalState,
            boolean sideEffectful,
            boolean rollbackFallback
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("key", key);
        result.put("status", "PLANNED_DISABLED");
        result.put("message", message);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("toolName", toolName.wireName());
        result.put("approvalState", approvalState.name());
        result.put("sideEffectful", sideEffectful);
        result.put("rollbackFallback", rollbackFallback);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimableAfterRelease", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        return result;
    }

    private Map<String, Object> releaseAttemptFinalReadiness(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> evidenceCompleteness,
            Map<String, Object> patchReleaseReadiness
    ) {
        String freshnessStatus = releaseAttemptFreshnessStatus(attempt);
        boolean stale = "STALE".equals(freshnessStatus);
        boolean evidenceComplete = Boolean.TRUE.equals(evidenceCompleteness.get("complete"));
        boolean preconditionsPassed = Boolean.TRUE.equals(patchReleaseReadiness.get("preconditionsPassed"));
        boolean readyButDisabled = !stale && evidenceComplete && preconditionsPassed;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", readyButDisabled ? "READY_RELEASE_DISABLED" : "BLOCKED_RELEASE_DISABLED");
        result.put("ready", readyButDisabled);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("freshnessStatus", freshnessStatus);
        result.put("stale", stale);
        result.put("evidenceComplete", evidenceComplete);
        result.put("patchPreconditionsPassed", preconditionsPassed);
        result.put("evidenceCompletenessStatus", evidenceCompleteness.get("status"));
        result.put("patchReleaseStatus", patchReleaseReadiness.get("status"));
        result.put("releaseGateEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("blockingReasons", releaseAttemptFinalBlockingReasons(stale, evidenceComplete, preconditionsPassed));
        result.put("message", readyButDisabled
                ? "Fresh evidence and pre-apply prerequisites are visible, but the release gate is disabled so the held patch remains non-claimable."
                : "The release attempt is blocked by stale, incomplete, or failed readiness evidence, and the release gate is disabled so the held patch remains non-claimable.");
        return result;
    }

    private List<String> releaseAttemptFinalBlockingReasons(
            boolean stale,
            boolean evidenceComplete,
            boolean preconditionsPassed
    ) {
        List<String> reasons = new ArrayList<>();
        if (stale) {
            reasons.add("release attempt evidence is stale");
        }
        if (!evidenceComplete) {
            reasons.add("fresh observation evidence is incomplete");
        }
        if (!preconditionsPassed) {
            reasons.add("patch release prerequisites are incomplete");
        }
        reasons.add("release gate is disabled");
        reasons.add("held patch request remains non-claimable");
        return reasons;
    }

    private Map<String, Object> releaseAttemptDisplaySummary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> evidenceCompleteness,
            Map<String, Object> finalReadiness
    ) {
        boolean linkedEvidenceComplete = "ALL_LINKED_RELEASE_DISABLED".equals(evidenceCompleteness.get("status"));
        boolean releaseReadyButDisabled = "READY_RELEASE_DISABLED".equals(finalReadiness.get("status"));
        Map<String, Object> disabledFlags = new LinkedHashMap<>();
        disabledFlags.put("releaseGateEnabled", false);
        disabledFlags.put("requestCreationEnabled", false);
        disabledFlags.put("pushEnabled", false);
        disabledFlags.put("claimEnabled", false);
        disabledFlags.put("writeHelperEnabled", false);
        disabledFlags.put("applyEnabled", false);
        disabledFlags.put("testEnabled", false);
        disabledFlags.put("rollbackRestoreEnabled", false);
        disabledFlags.put("ragFreshnessUpdateEnabled", false);
        disabledFlags.put("finalAnswerGenerationEnabled", false);
        disabledFlags.put("mutationAllowed", false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", releaseReadyButDisabled ? "READY_BUT_DISABLED_DISPLAY" : "BLOCKED_DISABLED_DISPLAY");
        result.put("show", linkedEvidenceComplete || releaseReadyButDisabled);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("linkedEvidenceComplete", linkedEvidenceComplete);
        result.put("releaseReadyButDisabled", releaseReadyButDisabled);
        result.put("evidenceStatus", evidenceCompleteness.get("status"));
        result.put("releaseReadinessStatus", finalReadiness.get("status"));
        result.put("patchPreconditionsPassed", finalReadiness.get("patchPreconditionsPassed"));
        result.put("evidenceComplete", finalReadiness.get("evidenceComplete"));
        result.put("linkedCount", evidenceCompleteness.get("linkedCount"));
        result.put("missingCount", evidenceCompleteness.get("missingCount"));
        result.put("sourceOnlyFallbackCount", evidenceCompleteness.get("sourceOnlyFallbackCount"));
        result.put("blockingCount", evidenceCompleteness.get("blockingCount"));
        result.put("disabledFlags", disabledFlags);
        result.put("blockingReasons", finalReadiness.get("blockingReasons"));
        result.put("message", releaseReadyButDisabled
                ? "Linked release evidence is complete and preconditions are ready, but every release and mutation control remains disabled."
                : "Release evidence is not executable; release and mutation controls remain disabled.");
        return result;
    }

    private Map<String, Object> releaseAttemptFreshObservationEvidenceCompleteness(
            LocalAgentPatchReleaseAttempt attempt,
            List<Map<String, Object>> evidenceStatus
    ) {
        List<String> linkedKeys = new ArrayList<>();
        List<String> missingKeys = new ArrayList<>();
        List<String> sourceOnlyFallbackKeys = new ArrayList<>();
        List<String> blockingKeys = new ArrayList<>();
        for (Map<String, Object> item : evidenceStatus) {
            String key = String.valueOf(item.get("key"));
            String status = String.valueOf(item.get("status"));
            if ("RELEASE_ATTEMPT_LINKED".equals(status)) {
                linkedKeys.add(key);
            } else if ("MISSING".equals(status)) {
                missingKeys.add(key);
            } else if ("SOURCE_ONLY_FALLBACK".equals(status)) {
                sourceOnlyFallbackKeys.add(key);
            }
            if (Boolean.TRUE.equals(item.get("blocking"))) {
                blockingKeys.add(key);
            }
        }

        boolean complete = !evidenceStatus.isEmpty() && linkedKeys.size() == evidenceStatus.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", complete ? "ALL_LINKED_RELEASE_DISABLED" : "INCOMPLETE_RELEASE_DISABLED");
        result.put("complete", complete);
        result.put("requiredCount", evidenceStatus.size());
        result.put("linkedCount", linkedKeys.size());
        result.put("missingCount", missingKeys.size());
        result.put("sourceOnlyFallbackCount", sourceOnlyFallbackKeys.size());
        result.put("blockingCount", blockingKeys.size());
        result.put("linkedKeys", linkedKeys);
        result.put("missingKeys", missingKeys);
        result.put("sourceOnlyFallbackKeys", sourceOnlyFallbackKeys);
        result.put("blockingKeys", blockingKeys);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", complete
                ? "All required fresh observations are linked to this release attempt, but the release gate is disabled so the held patch remains non-claimable."
                : "Required fresh observations are missing or source-only fallback, and the release gate is disabled so the held patch remains non-claimable.");
        return result;
    }

    private List<Map<String, Object>> releaseAttemptFreshObservationEvidenceStatus(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> repositoryVerification,
            Map<String, Object> latestPatchDryRunOutput
    ) {
        return List.of(
                releaseAttemptFreshObservationEvidenceStatus(
                        "repositoryVerification",
                        "Fresh read-only git.status repository verification linked to this release attempt.",
                        attempt,
                        repositoryVerification
                ),
                releaseAttemptFreshObservationEvidenceStatus(
                        "patchDryRun",
                        "Fresh non-mutating patch.apply dry-run linked to this release attempt.",
                        attempt,
                        latestPatchDryRunOutput
                )
        );
    }

    private Map<String, Object> releaseAttemptFreshObservationEvidenceStatus(
            String key,
            String message,
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> observation
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("requiredAfter", attempt.createdAt());
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        if (observation == null) {
            result.put("status", "MISSING");
            result.put("linked", false);
            result.put("sourceOnlyFallback", false);
            result.put("blocking", true);
            return result;
        }
        Map<String, Object> linkage = observationLinkage(observation);
        String status = String.valueOf(linkage.getOrDefault("status", "SOURCE_ONLY"));
        result.put("status", status);
        result.put("linked", Boolean.TRUE.equals(linkage.get("releaseAttemptLinked")));
        result.put("sourceOnlyFallback", Boolean.TRUE.equals(linkage.get("sourceOnlyFallback")));
        result.put("blocking", !"RELEASE_ATTEMPT_LINKED".equals(status));
        return result;
    }

    private Map<String, Object> observationLinkage(Map<String, Object> observation) {
        if (observation.get("observationLinkage") instanceof Map<?, ?> linkage) {
            return copyMap(linkage);
        }
        return Map.of("status", "SOURCE_ONLY");
    }

    private List<Map<String, Object>> releaseAttemptFreshObservationRequirements(LocalAgentPatchReleaseAttempt attempt) {
        OffsetDateTime createdAt = attempt.createdAt();
        return List.of(
                freshObservationRequirement(
                        "repositoryVerificationAfterAttempt",
                        "Fresh read-only git.status repository verification must complete after this release attempt is created.",
                        createdAt
                ),
                freshObservationRequirement(
                        "patchDryRunAfterAttempt",
                        "Fresh patch.apply dry-run must complete after this release attempt is created.",
                        createdAt
                ),
                freshObservationRequirement(
                        "snapshotCreatedAfterFreshDryRun",
                        "A managed Local Agent snapshot must be created by the fresh dry-run before patch writes are considered.",
                        createdAt
                ),
                freshObservationRequirement(
                        "rollbackValidatedAfterFreshSnapshot",
                        "Rollback manifest validation must be derived from the fresh created snapshot.",
                        createdAt
                ),
                freshObservationRequirement(
                        "userReleaseApprovalAfterFreshEvidence",
                        "User release approval must happen after fresh evidence is visible.",
                        createdAt
                )
        );
    }

    private Map<String, Object> freshObservationRequirement(String key, String message, OffsetDateTime requiredAfter) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", "REQUIRED_AFTER_RELEASE_ATTEMPT");
        result.put("required", true);
        result.put("passed", false);
        result.put("requiredAfter", requiredAfter);
        result.put("message", message);
        return result;
    }

    private List<Map<String, Object>> releaseAttemptFreshObservationRequestPlan(LocalAgentPatchReleaseAttempt attempt) {
        return List.of(
                freshObservationRequestPlan(
                        "repositoryVerification",
                        LocalAgentToolName.GIT_STATUS,
                        LocalAgentApprovalState.NOT_REQUIRED,
                        false,
                        false,
                        false,
                        "Queue a read-only git.status observation linked to this release attempt before any claimable transition is considered.",
                        attempt
                ),
                freshObservationRequestPlan(
                        "patchDryRun",
                        LocalAgentToolName.PATCH_APPLY,
                        LocalAgentApprovalState.APPROVED,
                        true,
                        false,
                        true,
                        "Queue a non-mutating patch.apply dry-run linked to this release attempt before any patch write is considered.",
                        attempt
                )
        );
    }

    private Map<String, Object> freshObservationRequestPlan(
            String key,
            LocalAgentToolName toolName,
            LocalAgentApprovalState approvalState,
            boolean dryRunOnly,
            boolean mutationAllowed,
            boolean requiresSnapshot,
            String message,
            LocalAgentPatchReleaseAttempt attempt
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", "PLANNED_DISABLED");
        result.put("enqueueEnabled", false);
        result.put("claimableAfterEnqueue", false);
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("toolName", toolName.wireName());
        result.put("approvalState", approvalState.name());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("releaseAttemptId", attempt.id());
        result.put("requiredAfter", attempt.createdAt());
        result.put("dryRunOnly", dryRunOnly);
        result.put("mutationAllowed", mutationAllowed);
        result.put("requiresSnapshot", requiresSnapshot);
        result.put("message", message);
        return result;
    }

    private OffsetDateTime releaseAttemptExpiresAt(LocalAgentPatchReleaseAttempt attempt) {
        return attempt.createdAt() == null ? null : attempt.createdAt().plusSeconds(attempt.staleWindowSeconds());
    }

    private Long releaseAttemptAgeSeconds(LocalAgentPatchReleaseAttempt attempt) {
        if (attempt.createdAt() == null) {
            return null;
        }
        return Math.max(0, Duration.between(attempt.createdAt(), OffsetDateTime.now()).getSeconds());
    }

    private String releaseAttemptFreshnessStatus(LocalAgentPatchReleaseAttempt attempt) {
        OffsetDateTime expiresAt = releaseAttemptExpiresAt(attempt);
        if (expiresAt == null) {
            return "UNKNOWN";
        }
        return OffsetDateTime.now().isAfter(expiresAt) ? "STALE" : "FRESH";
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
        return properties.getLocalAgent().isPatchExecutionReleaseEnabled();
    }

    private boolean approvedExecutionSequenceCreationEnabled() {
        return properties.getLocalAgent().isApprovedExecutionSequenceCreationEnabled();
    }

    private int numberOrDefault(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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
                || !Boolean.TRUE.equals(dryRunOutput.get("dryRun"))
                || !Boolean.FALSE.equals(dryRunOutput.get("mutationApplied"))) {
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
        if (dryRunOutput.get("observationLinkage") != null) {
            result.put("observationLinkage", dryRunOutput.get("observationLinkage"));
        }

        if (!Boolean.TRUE.equals(dryRunOutput.get("dryRun"))
                || !Boolean.FALSE.equals(dryRunOutput.get("mutationApplied"))) {
            result.put("status", "INVALID");
            result.put("message", "Snapshot readiness requires a non-mutating Local Agent dry-run observation with mutationApplied=false.");
            result.put("blocking", true);
            return result;
        }

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
            if (dryRunOutput.get("observationLinkage") != null) {
                result.put("observationLinkage", dryRunOutput.get("observationLinkage"));
            }
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
        if (dryRunOutput.get("observationLinkage") != null) {
            result.put("observationLinkage", dryRunOutput.get("observationLinkage"));
        }
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
        if (repositoryVerification == null) {
            return false;
        }
        if ("LOCAL_PATH_ONLY_MATCH".equals(repositoryVerification.get("status"))
                && repositoryVerification.get("pathCheck") instanceof Map<?, ?> pathCheck) {
            return "MATCH".equals(pathCheck.get("status"));
        }
        if (!"MATCH".equals(repositoryVerification.get("status"))) {
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
        Map<?, ?> localWorkspace = execution.input().get("localWorkspace") instanceof Map<?, ?> workspace ? workspace : Map.of();
        output.put("repositoryVerification", compareRepositoryIdentity(source, localWorkspace, response.output().get("repositoryIdentity"), response.status()));
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

    private LocalAgentToolResponse enrichMutationResultIntakeCandidate(LocalAgentToolResponse response) {
        LocalAgentToolExecution execution = repository.find(response.requestId()).orElse(null);
        if (execution == null) {
            return response;
        }
        return LocalAgentMutationResultClassifier.enrich(response, execution.input());
    }

    private void appendLoopObservationEvent(LocalAgentToolResponse response) {
        LocalAgentToolExecution execution = repository.find(response.requestId()).orElse(null);
        if (execution == null) {
            return;
        }
        UUID repositoryId = repositoryId(execution.input());
        if (repositoryId == null) {
            return;
        }
        UUID loopId = loopId(execution.input());
        loopTimelineRepository.appendObservationResult(response.userId(), repositoryId, loopId, response, execution.input());
        loopTimelineRepository.appendNextDecision(response.userId(), repositoryId, loopId, response, execution.input());
        appendObservationStopOutcome(response, repositoryId, loopId, execution.input());
    }

    private LocalAgentToolExecution appendLoopApprovalDecisionEvent(LocalAgentToolExecution execution) {
        UUID repositoryId = repositoryId(execution.input());
        if (repositoryId == null) {
            return execution;
        }
        loopTimelineRepository.appendApprovalDecision(
                execution.userId(),
                repositoryId,
                execution.id(),
                execution.sessionId(),
                execution.agentId(),
                execution.workspaceId(),
                execution.executionTarget(),
                execution.toolName(),
                execution.approvalState().name(),
                execution.status().name(),
                loopId(execution.input()),
                execution.input()
        );
        if (execution.approvalState() == LocalAgentApprovalState.DENIED) {
            loopTimelineRepository.appendApprovalDeniedStopOutcome(
                    execution.userId(),
                    repositoryId,
                    loopId(execution.input()),
                    execution.id(),
                    execution.sessionId(),
                    execution.agentId(),
                    execution.workspaceId(),
                    execution.approvalState().name(),
                    execution.status().name(),
                    execution.input()
            );
        }
        return execution;
    }

    private LocalAgentToolExecution appendLoopApprovalRequestCreatedEvent(LocalAgentToolExecution execution) {
        UUID repositoryId = repositoryId(execution.input());
        if (repositoryId == null) {
            return execution;
        }
        loopTimelineRepository.appendApprovalRequestCreated(
                execution.userId(),
                repositoryId,
                execution.id(),
                execution.sessionId(),
                execution.agentId(),
                execution.workspaceId(),
                execution.executionTarget(),
                execution.toolName(),
                execution.approvalState().name(),
                execution.status().name(),
                loopId(execution.input()),
                execution.input()
        );
        return execution;
    }

    private void appendLoopReleaseBoundaryRefusalEvent(
            UUID userId,
            UUID requestId,
            LocalAgentPatchReleaseBoundaryResponse boundary
    ) {
        LocalAgentToolExecution execution = repository.find(requestId).orElse(null);
        if (execution == null) {
            return;
        }
        UUID repositoryId = repositoryId(execution.input());
        if (repositoryId == null) {
            return;
        }
        loopTimelineRepository.appendReleaseBoundaryRefusal(
                userId,
                repositoryId,
                loopId(execution.input()),
                execution.sessionId(),
                execution.agentId(),
                execution.workspaceId(),
                execution.executionTarget(),
                execution.toolName(),
                boundary,
                execution.input()
        );
    }

    private void appendLoopFreshObservationRequestsEnqueuedEvent(
            LocalAgentToolExecution source,
            UUID releaseAttemptId,
            List<LocalAgentQueuedToolRequest> queued
    ) {
        UUID repositoryId = repositoryId(source.input());
        if (repositoryId == null) {
            return;
        }
        loopTimelineRepository.appendFreshObservationRequestsEnqueued(
                source.userId(),
                repositoryId,
                loopId(source.input()),
                source.id(),
                releaseAttemptId,
                source.sessionId(),
                source.agentId(),
                source.workspaceId(),
                source.executionTarget(),
                source.toolName(),
                queued,
                source.input()
        );
    }

    private void appendLoopFreshObservationEvidenceCompleteEvent(LocalAgentToolResponse response) {
        LocalAgentToolExecution execution = repository.find(response.requestId()).orElse(null);
        if (execution == null || !Boolean.TRUE.equals(execution.input().get("freshObservationOnly"))) {
            return;
        }
        UUID sourceRequestId = sourceRequestId(execution.input());
        UUID releaseAttemptId = releaseAttemptIdFromInput(execution.input());
        if (sourceRequestId == null || releaseAttemptId == null) {
            return;
        }
        LocalAgentToolExecution source = repository.find(sourceRequestId).orElse(null);
        UUID repositoryId = repositoryId(execution.input());
        UUID loopId = loopId(execution.input());
        if (repositoryId == null && source != null) {
            repositoryId = repositoryId(source.input());
        }
        if (loopId == null && source != null) {
            loopId = loopId(source.input());
        }
        if (repositoryId == null) {
            return;
        }
        Optional<LocalAgentPatchReleaseAttempt> attempt = Optional
                .ofNullable(releaseAttemptRepository.findLatestForSourceRequest(response.userId(), sourceRequestId))
                .flatMap(item -> item)
                .filter(candidate -> releaseAttemptId.equals(candidate.id()));
        if (attempt.isEmpty()) {
            return;
        }
        Map<String, Object> repositoryVerification = latestRepositoryVerification(
                response.userId(),
                sourceRequestId,
                attempt
        ).orElse(null);
        Map<String, Object> patchDryRunOutput = latestPatchDryRunOutput(
                response.userId(),
                sourceRequestId,
                attempt
        ).orElse(null);
        List<Map<String, Object>> evidenceStatus = releaseAttemptFreshObservationEvidenceStatus(
                attempt.get(),
                repositoryVerification,
                patchDryRunOutput
        );
        Map<String, Object> evidenceCompleteness = releaseAttemptFreshObservationEvidenceCompleteness(
                attempt.get(),
                evidenceStatus
        );
        if (!Boolean.TRUE.equals(evidenceCompleteness.get("complete"))) {
            return;
        }
        loopTimelineRepository.appendFreshObservationEvidenceComplete(
                response.userId(),
                repositoryId,
                loopId,
                sourceRequestId,
                releaseAttemptId,
                attempt.get().sessionId(),
                attempt.get().agentId(),
                attempt.get().workspaceId(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                evidenceCompleteness,
                evidenceStatus
        );
        appendLoopReleaseReadinessRefreshedEvent(response.userId(), sourceRequestId, repositoryId, loopId, attempt.get());
    }

    private void appendLoopReleaseReadinessRefreshedEvent(
            UUID userId,
            UUID sourceRequestId,
            UUID repositoryId,
            UUID loopId,
            LocalAgentPatchReleaseAttempt attempt
    ) {
        LocalAgentPatchExecutionReadinessResponse readiness;
        try {
            readiness = inspectPatchExecutionReadiness(userId, sourceRequestId);
        } catch (IllegalArgumentException ex) {
            return;
        }
        loopTimelineRepository.appendReleaseReadinessRefreshed(
                userId,
                repositoryId,
                loopId,
                sourceRequestId,
                attempt.id(),
                attempt.sessionId(),
                attempt.agentId(),
                attempt.workspaceId(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                readiness
        );
    }

    private void appendLoopApprovedExecutionFlowCompletedEvent(LocalAgentToolResponse response) {
        LocalAgentToolExecution execution = repository.find(response.requestId()).orElse(null);
        if (execution == null) {
            return;
        }
        UUID releaseAttemptId = releaseAttemptIdFromInput(execution.input());
        if (releaseAttemptId == null) {
            return;
        }
        List<LocalAgentToolExecution> executions = repository.findCompletedApprovedExecutionFlowRowsForReleaseAttempt(
                response.userId(),
                releaseAttemptId
        );
        if (executions.size() != 3 && executions.size() != 4) {
            return;
        }
        Map<String, Object> inspection = approvedExecutionFlowSummary(
                executions,
                executions.stream().map(LocalAgentToolExecution::id).toList(),
                releaseAttemptId,
                "durableCompletedRows"
        );
        if (!Boolean.TRUE.equals(inspection.get("ordered"))
                || !Boolean.TRUE.equals(inspection.get("identityConsistent"))
                || !Boolean.TRUE.equals(inspection.get("releaseAttemptLinked"))
                || !Boolean.TRUE.equals(inspection.get("approvalRequestLinked"))
                || !Boolean.TRUE.equals(inspection.get("allTerminal"))) {
            return;
        }
        LocalAgentToolExecution first = executions.get(0);
        UUID sourceRequestId = sourceRequestId(first.input());
        LocalAgentToolExecution source = sourceRequestId == null ? first : repository.find(sourceRequestId).orElse(first);
        UUID repositoryId = repositoryId(source.input());
        if (repositoryId == null) {
            repositoryId = repositoryId(first.input());
        }
        if (repositoryId == null) {
            return;
        }
        UUID loopId = loopId(source.input());
        if (loopId == null) {
            loopId = loopId(first.input());
        }
        Map<String, Object> finalResultHandoff = releaseAttemptRepository.find(releaseAttemptId)
                .filter(attempt -> attempt.userId().equals(response.userId()))
                .map(attempt -> approvedExecutionFlowFinalResultHandoff(attempt, source.input(), inspection))
                .orElse(Map.of());
        loopTimelineRepository.appendApprovedExecutionFlowCompleted(
                response.userId(),
                repositoryId,
                loopId,
                sourceRequestId,
                releaseAttemptId,
                first.sessionId(),
                first.agentId(),
                first.workspaceId(),
                inspection,
                finalResultHandoff
        );
    }

    private Map<String, Object> approvedExecutionFlowFinalResultHandoff(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> sourceInput,
            Map<String, Object> approvedFlowInspection
    ) {
        Map<String, Object> postRetryVerification = mapValue(
                approvedFlowInspection == null ? null : approvedFlowInspection.get("postRetryVerification")
        );
        Map<String, Object> acceptedSummary = releaseAttemptAcceptedMutationObservationSummary(attempt);
        Map<String, Object> acceptedReadiness = releaseAttemptAcceptedMutationObservationReadiness(attempt);
        Map<String, Object> finalMutationReportSummary = LocalAgentFinalMutationReportSummaryBuilder.build(
                attempt,
                acceptedSummary,
                acceptedReadiness
        );
        Map<String, Object> ragFreshnessMarker = LocalAgentRagFreshnessMarkerBuilder.build(
                attempt,
                sourceInput == null ? Map.of() : sourceInput,
                finalMutationReportSummary,
                postRetryVerification
        );
        Map<String, Object> publicationHandoff = LocalAgentFinalAnswerPublicationHandoffBuilder.build(
                attempt,
                finalMutationReportSummary,
                ragFreshnessMarker
        );
        Map<String, Object> acknowledgementHandoff = LocalAgentAcknowledgementSaveHandoffBuilder.build(
                attempt,
                publicationHandoff
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.code-agent.approved-execution-flow-final-result-handoff.v1");
        result.put("status", "READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED");
        result.put("releaseAttemptId", attempt.id().toString());
        result.put("sourceRequestId", attempt.sourceRequestId().toString());
        result.put("sessionId", attempt.sessionId().toString());
        result.put("userId", attempt.userId().toString());
        result.put("agentId", attempt.agentId().toString());
        result.put("workspaceId", attempt.workspaceId().toString());
        result.put("finalMutationReportSummaryStatus", finalMutationReportSummary.get("status"));
        result.put("finalMutationReportSummaryAvailable", finalMutationReportSummary.get("summaryAvailable"));
        result.put("acceptedMutationObserved", finalMutationReportSummary.get("acceptedMutationObserved"));
        result.put("acceptedMutationObservationCount", finalMutationReportSummary.get("acceptedMutationObservationCount"));
        result.put("acceptedMutationObservationAcceptedCount", finalMutationReportSummary.get("acceptedMutationObservationAcceptedCount"));
        result.put("postRetryVerification", postRetryVerification);
        result.put("postRetryVerificationObserved", postRetryVerification.get("observed"));
        result.put("postRetryVerificationPassed", postRetryVerification.get("passed"));
        result.put("postRetryVerificationApprovalLinked", postRetryVerification.get("approvalRequestLinked"));
        result.put("postRetryVerificationReleaseLinked", postRetryVerification.get("releaseAttemptLinked"));
        result.put("postRetryVerificationPartialReindexMarkerRequired", postRetryVerification.get("partialReindexMarkerRequired"));
        result.put("ragFreshnessMarkerStatus", ragFreshnessMarker.get("status"));
        result.put("staleIndexRiskVisible", ragFreshnessMarker.get("staleIndexRiskVisible"));
        result.put("finalAnswerMustDiscloseStaleIndex", ragFreshnessMarker.get("finalAnswerMustDiscloseStaleIndex"));
        result.put("targetFiles", ragFreshnessMarker.get("targetFiles"));
        Map<String, Object> partialReindexPlan = mapValue(ragFreshnessMarker.get("partialReindexPlan"));
        result.put("partialReindexPlan", partialReindexPlan);
        result.put("partialReindexPlanStatus", partialReindexPlan.get("status"));
        result.put("partialReindexPlanTargetFiles", partialReindexPlan.get("targetFiles"));
        result.put("partialReindexPlanFreshnessAction", partialReindexPlan.get("freshnessAction"));
        Map<String, Object> partialReindexEnqueueBoundary = mapValue(partialReindexPlan.get("partialReindexEnqueueBoundary"));
        result.put("partialReindexEnqueueBoundary", partialReindexEnqueueBoundary);
        result.put("partialReindexEnqueueBoundaryStatus", partialReindexEnqueueBoundary.get("status"));
        result.put("partialReindexEnqueueReady", partialReindexEnqueueBoundary.get("ready"));
        result.put("partialReindexRepositoryId", partialReindexEnqueueBoundary.get("repositoryId"));
        result.put("finalAnswerPublicationHandoffStatus", publicationHandoff.get("status"));
        result.put("finalAnswerPublicationHandoffAvailable", publicationHandoff.get("handoffAvailable"));
        result.put("staleIndexDisclosureModeled", publicationHandoff.get("staleIndexDisclosureModeled"));
        result.put("finalAnswerSections", publicationHandoff.get("finalAnswerSections"));
        result.put("acknowledgementSaveHandoffStatus", acknowledgementHandoff.get("status"));
        result.put("acknowledgementSaveHandoffAvailable", acknowledgementHandoff.get("handoffAvailable"));
        result.put("finalResultEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("partialReindexEnabled", false);
        result.put("followUpMutationEnabled", false);
        result.put("mutationEnabled", false);
        result.put("message", "Approved execution completed and audit-only final-result handoff context is modeled, but publication, final answer delivery, acknowledgement save, RAG freshness update, and follow-up mutation remain disabled.");
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private void appendAgentUnavailableStopOutcome(LocalAgentToolRequest request) {
        UUID repositoryId = repositoryId(request.input());
        if (repositoryId == null) {
            return;
        }
        loopTimelineRepository.appendAgentUnavailableStopOutcome(
                request.userId(),
                repositoryId,
                loopId(request.input()),
                request
        );
    }

    private void appendLeaseTimeoutStopOutcome(LocalAgentToolExecution execution) {
        UUID repositoryId = repositoryId(execution.input());
        if (repositoryId == null) {
            return;
        }
        loopTimelineRepository.appendTimedOutStopOutcome(
                execution.userId(),
                repositoryId,
                loopId(execution.input()),
                new LocalAgentToolResponse(
                        execution.sessionId(),
                        execution.id(),
                        execution.userId(),
                        execution.agentId(),
                        execution.workspaceId(),
                        execution.executionTarget(),
                        execution.toolName(),
                        LocalAgentToolStatus.TIMED_OUT,
                        Map.of("leaseTimedOut", true),
                        LocalAgentFailureCode.TIMEOUT,
                        execution.error(),
                        execution.startedAt(),
                        execution.finishedAt(),
                        execution.responseWarnings()
                ),
                execution.input()
        );
    }

    private void appendObservationStopOutcome(
            LocalAgentToolResponse response,
            UUID repositoryId,
            UUID loopId,
            Map<String, Object> requestInput
    ) {
        if (loopTimelineRepository.successfulPatchDryRunObservation(response)) {
            return;
        }
        switch (response.status()) {
            case SUCCEEDED -> {
            }
            case TIMED_OUT -> loopTimelineRepository.appendTimedOutStopOutcome(
                    response.userId(),
                    repositoryId,
                    loopId,
                    response,
                    requestInput
            );
            case CANCELLED -> loopTimelineRepository.appendCancellationStopOutcome(
                    response.userId(),
                    repositoryId,
                    loopId,
                    response,
                    requestInput
            );
            case DISCONNECTED -> loopTimelineRepository.appendDisconnectedStopOutcome(
                    response.userId(),
                    repositoryId,
                    loopId,
                    response,
                    requestInput
            );
            default -> loopTimelineRepository.appendToolFailedStopOutcome(
                    response.userId(),
                    repositoryId,
                    loopId,
                    response,
                    requestInput
            );
        }
    }

    private UUID repositoryId(Map<String, Object> input) {
        Object direct = input.get("repositoryId");
        if (direct instanceof String text && hasText(text)) {
            return UUID.fromString(text);
        }
        if (input.get("sourceRepository") instanceof Map<?, ?> source) {
            Object nested = source.get("id");
            if (nested instanceof String text && hasText(text)) {
                return UUID.fromString(text);
            }
        }
        return null;
    }

    private UUID loopId(Map<String, Object> input) {
        Object direct = input.get("loopId");
        if (direct instanceof String text && hasText(text)) {
            return UUID.fromString(text);
        }
        return null;
    }

    private UUID sourceRequestId(Map<String, Object> input) {
        Object direct = input.get("sourceRequestId");
        if (direct instanceof String text && hasText(text)) {
            return UUID.fromString(text);
        }
        return null;
    }

    private UUID releaseAttemptIdFromInput(Map<String, Object> input) {
        Object direct = input.get("releaseAttemptId");
        if (direct instanceof String text && hasText(text)) {
            return UUID.fromString(text);
        }
        return null;
    }

    private Map<String, Object> compareRepositoryIdentity(Map<?, ?> source, Map<?, ?> localWorkspace, Object identityValue, LocalAgentToolStatus status) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<?, ?> identity = identityValue instanceof Map<?, ?> map ? map : Map.of();
        addRepositoryCheck(checks, "branch", text(source.get("branch")), text(identity.get("branch")), true, false);
        addRepositoryCheck(checks, "head", text(source.get("lastIndexedCommit")), text(identity.get("headCommit")), false, false);
        addRepositoryCheck(checks, "remote", text(source.get("gitUrl")), text(identity.get("remoteUrl")), false, true);
        Map<String, Object> pathCheck = localPathCheck(source, localWorkspace);

        List<Map<String, Object>> considered = checks.stream()
                .filter(check -> !"SKIPPED".equals(check.get("status")))
                .toList();
        String resultStatus;
        String message;
        boolean blocking = true;
        if (status != LocalAgentToolStatus.SUCCEEDED) {
            resultStatus = "UNVERIFIED";
            message = "Local repository observation did not complete successfully.";
        } else if (considered.stream().anyMatch(check -> "MISMATCH".equals(check.get("status")))) {
            resultStatus = "MISMATCH";
            message = "Local workspace identity does not match the indexed repository metadata.";
        } else if (!considered.isEmpty() && considered.stream().noneMatch(check -> "UNKNOWN".equals(check.get("status")))) {
            resultStatus = "MATCH";
            message = "Observed local repository identity matches available indexed metadata.";
        } else if ("MATCH".equals(pathCheck.get("status"))) {
            resultStatus = "LOCAL_PATH_ONLY_MATCH";
            message = "Local folder has no complete git identity, but the approved Local Agent workspace path matches the registered local repository path.";
        } else {
            resultStatus = "UNVERIFIED";
            message = "Not enough local repository identity data to verify this workspace.";
        }
        return Map.of(
                "status", resultStatus,
                "blocking", blocking,
                "message", message,
                "checks", checks,
                "pathCheck", pathCheck
        );
    }

    private Map<String, Object> localPathCheck(Map<?, ?> source, Map<?, ?> localWorkspace) {
        String sourceType = text(source.get("sourceType"));
        String expected = text(source.get("localPath"));
        String actual = text(localWorkspace.get("rootPath"));
        if (!"LOCAL".equalsIgnoreCase(sourceType) || !hasText(expected) || !hasText(actual)) {
            return Map.of(
                    "key", "localPath",
                    "status", "SKIPPED",
                    "expected", expected,
                    "actual", actual
            );
        }
        boolean matched = normalizeLocalPath(expected).equalsIgnoreCase(normalizeLocalPath(actual));
        return Map.of(
                "key", "localPath",
                "status", matched ? "MATCH" : "MISMATCH",
                "expected", expected,
                "actual", actual
        );
    }

    private String normalizeLocalPath(String value) {
        try {
            return java.nio.file.Path.of(value).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ex) {
            return value.trim().replace('\\', '/').replaceAll("/+$", "");
        }
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

    private LocalAgentToolExecution approvedHeldPatchSource(UUID userId, UUID requestId, String action) {
        LocalAgentToolExecution execution = repository.find(requestId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("Local Agent patch request was not found."));
        if (execution.toolName() != LocalAgentToolName.PATCH_APPLY) {
            throw new IllegalArgumentException(action + " is available only for patch.apply requests.");
        }
        if (execution.executionTarget() != AgentExecutionTarget.USER_LOCAL_AGENT) {
            throw new IllegalArgumentException(action + " requires USER_LOCAL_AGENT target.");
        }
        if (execution.approvalState() != LocalAgentApprovalState.APPROVED
                || execution.status() != LocalAgentToolStatus.APPROVED_HELD) {
            throw new IllegalArgumentException("Patch dry-run requires an approved-held request.");
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
