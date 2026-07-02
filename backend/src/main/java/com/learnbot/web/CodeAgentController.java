package com.learnbot.web;

import com.learnbot.dto.CodeAgentApplyRequest;
import com.learnbot.dto.CodeAgentApplyResponse;
import com.learnbot.dto.CodeAgentLocalPatchRequest;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopPreviewRequest;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.CodeAgentMutationPolicyResponse;
import com.learnbot.dto.CodeAgentPatchRequest;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanRequest;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.dto.CodeAgentRollbackRequest;
import com.learnbot.dto.CodeAgentRollbackResponse;
import com.learnbot.dto.CodeAgentTestRequest;
import com.learnbot.dto.CodeAgentTestResponse;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewRequest;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopReleaseReviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopApprovalRequestPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopFinalResultPublicationPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopM8EntryReadinessResponse;
import com.learnbot.dto.loop.CodeAgentLoopObservationContinuationRequest;
import com.learnbot.dto.loop.CodeAgentLoopObservationContinuationResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalRequestResponse;
import com.learnbot.dto.loop.CodeAgentLoopPatchApprovalPayloadRequest;
import com.learnbot.dto.loop.CodeAgentLoopSelectedToolEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopSideEffectBoundaryResponse;
import com.learnbot.dto.loop.CodeAgentLoopToolSelectionResponse;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeAgentApplyService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodeIndexingService;
import com.learnbot.service.agentloop.CodeAgentLoopRunnerService;
import com.learnbot.service.agentloop.CodeAgentLoopToolSelectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/code-agent")
public class CodeAgentController {
    private final CodeAgentService codeAgentService;
    private final CodeAgentApplyService codeAgentApplyService;
    private final CodeAgentLocalPatchRequestService localPatchRequestService;
    private final CodeAgentLoopPreviewService loopPreviewService;
    private final CodeAgentLoopRunnerService loopRunnerService;
    private final CodeAgentLoopToolSelectionService loopToolSelectionService;
    private final CodeIndexingService indexingService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;
    private final LearnBotProperties properties;

    public CodeAgentController(
            CodeAgentService codeAgentService,
            CodeAgentApplyService codeAgentApplyService,
            CodeAgentLocalPatchRequestService localPatchRequestService,
            CodeAgentLoopPreviewService loopPreviewService,
            CodeAgentLoopRunnerService loopRunnerService,
            CodeAgentLoopToolSelectionService loopToolSelectionService,
            CodeIndexingService indexingService,
            AuthService authService,
            CurrentUserProvider currentUserProvider,
            LearnBotProperties properties
    ) {
        this.codeAgentService = codeAgentService;
        this.codeAgentApplyService = codeAgentApplyService;
        this.localPatchRequestService = localPatchRequestService;
        this.loopPreviewService = loopPreviewService;
        this.loopRunnerService = loopRunnerService;
        this.loopToolSelectionService = loopToolSelectionService;
        this.indexingService = indexingService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
    }

    @PostMapping("/plan")
    CodeAgentPlanResponse plan(@Valid @RequestBody CodeAgentPlanRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentService.plan(
                request.repositoryId(),
                selectedSpaceId,
                authService.accessibleSpaceIds(user),
                request.instruction(),
                request.limit()
        );
    }

    @PostMapping("/patch")
    CodeAgentPatchResponse patch(@Valid @RequestBody CodeAgentPatchRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentService.patch(
                request.repositoryId(),
                selectedSpaceId,
                authService.accessibleSpaceIds(user),
                request.instruction(),
                request.targetFiles()
        );
    }

    @GetMapping("/mutation-policy")
    CodeAgentMutationPolicyResponse mutationPolicy() {
        boolean serverLocalEnabled = properties.getCode().isServerLocalMutationEnabled();
        return new CodeAgentMutationPolicyResponse(
                AgentExecutionTarget.USER_LOCAL_AGENT,
                false,
                serverLocalEnabled,
                List.of(
                        LocalAgentToolName.PATCH_APPLY,
                        LocalAgentToolName.COMMAND_RUN_ALLOWED,
                        LocalAgentToolName.ROLLBACK_RESTORE
                ),
                serverLocalEnabled
                        ? List.of("Server-local mutation is enabled for prototype/admin/debug use only.")
                        : List.of("Server-local mutation is disabled. Normal user-owned changes must wait for the Local Agent mutation path."),
                "Patch proposals are available now. Applying patches, running tests, and rollback for user-owned workspaces are reserved for the USER_LOCAL_AGENT path and are not enabled yet."
        );
    }

    @PostMapping("/local-patch-request")
    LocalAgentToolExecutionResponse localPatchRequest(@Valid @RequestBody CodeAgentLocalPatchRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return localPatchRequestService.prepare(
                request.repositoryId(),
                selectedSpaceId,
                user.id(),
                request.agentId(),
                request.workspaceId(),
                request.loopId(),
                request.instruction(),
                request.diff(),
                request.targetFiles()
        );
    }

    @PostMapping("/loop/preview")
    CodeAgentLoopPreviewResponse loopPreview(@Valid @RequestBody CodeAgentLoopPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return loopPreviewService.preview(
                user.id(),
                request.repositoryId(),
                selectedSpaceId,
                request.instruction(),
                request.maxSteps()
        );
    }

    @GetMapping("/loop/timelines")
    List<CodeAgentLoopTimelineSummary> loopTimelines(
            @RequestParam UUID repositoryId,
            @RequestParam(required = false) Integer limit
    ) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, repositoryId);
        authService.requireSpace(user, repositorySpaceId);
        return loopPreviewService.recentTimelines(user.id(), repositoryId, limit);
    }

    @GetMapping("/loop/next-action")
    CodeAgentLoopNextActionResponse loopNextAction(
            @RequestParam UUID repositoryId,
            @RequestParam(required = false) UUID loopId
    ) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, repositoryId);
        authService.requireSpace(user, repositorySpaceId);
        return loopPreviewService.nextAction(user.id(), repositoryId, loopId);
    }

    @PostMapping("/loop/runner/preview")
    CodeAgentLoopRunnerPreviewResponse loopRunnerPreview(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopRunnerService.previewNextStep(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/enqueue-read-only")
    CodeAgentLoopRunnerEnqueueResponse loopRunnerEnqueueReadOnly(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopRunnerService.enqueueReadOnlyNextStep(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/release-review")
    CodeAgentLoopReleaseReviewResponse loopRunnerReleaseReview(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopRunnerService.reviewReleaseGate(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/final-result-publication-preview")
    CodeAgentLoopFinalResultPublicationPreviewResponse loopRunnerFinalResultPublicationPreview(
            @Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request
    ) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopRunnerService.previewFinalResultPublication(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/m8-entry-readiness")
    CodeAgentLoopM8EntryReadinessResponse loopRunnerM8EntryReadiness(
            @Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request
    ) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopRunnerService.previewM8EntryReadiness(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/select-tool-preview")
    CodeAgentLoopToolSelectionResponse loopRunnerSelectToolPreview(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopToolSelectionService.selectNextToolPreview(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/enqueue-selected-read-only")
    CodeAgentLoopSelectedToolEnqueueResponse loopRunnerEnqueueSelectedReadOnly(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopToolSelectionService.enqueueSelectedReadOnlyNextStep(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/continue-after-observation")
    CodeAgentLoopObservationContinuationResponse loopRunnerContinueAfterObservation(
            @Valid @RequestBody CodeAgentLoopObservationContinuationRequest request
    ) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopToolSelectionService.continueAfterReadOnlyObservation(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId(),
                request.requestId()
        );
    }

    @PostMapping("/loop/runner/side-effect-boundary-preview")
    CodeAgentLoopSideEffectBoundaryResponse loopRunnerSideEffectBoundaryPreview(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopToolSelectionService.previewSideEffectBoundary(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/patch-approval-preview")
    CodeAgentLoopApprovalRequestPreviewResponse loopRunnerPatchApprovalPreview(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopToolSelectionService.previewPatchApprovalRequest(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/patch-approval-request")
    CodeAgentLoopPatchApprovalRequestResponse loopRunnerPatchApprovalRequest(@Valid @RequestBody CodeAgentLoopRunnerPreviewRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return loopToolSelectionService.createPatchApprovalRequest(
                user.id(),
                request.repositoryId(),
                request.loopId(),
                request.agentId(),
                request.workspaceId()
        );
    }

    @PostMapping("/loop/runner/validated-patch-approval-request")
    CodeAgentLoopPatchApprovalRequestResponse loopRunnerValidatedPatchApprovalRequest(@Valid @RequestBody CodeAgentLoopPatchApprovalPayloadRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return loopToolSelectionService.createValidatedPatchApprovalRequest(
                user.id(),
                request.repositoryId(),
                selectedSpaceId,
                request.loopId(),
                request.agentId(),
                request.workspaceId(),
                request.instruction(),
                request.diff(),
                request.targetFiles()
        );
    }

    @PostMapping("/apply")
    CodeAgentApplyResponse apply(@Valid @RequestBody CodeAgentApplyRequest request) {
        requireServerLocalMutationEnabled();
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentApplyService.apply(
                request.repositoryId(),
                selectedSpaceId,
                user.id(),
                request.instruction(),
                request.diff(),
                request.targetFiles()
        );
    }

    @PostMapping("/rollback")
    CodeAgentRollbackResponse rollback(@Valid @RequestBody CodeAgentRollbackRequest request) {
        requireServerLocalMutationEnabled();
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentApplyService.rollback(request.repositoryId(), selectedSpaceId, user.id(), request.patchSessionId());
    }

    @PostMapping("/test")
    CodeAgentTestResponse test(@Valid @RequestBody CodeAgentTestRequest request) {
        requireServerLocalMutationEnabled();
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentApplyService.runAllowedTest(
                request.repositoryId(),
                selectedSpaceId,
                user.id(),
                request.patchSessionId(),
                request.commandKey()
        );
    }

    private void requireServerLocalMutationEnabled() {
        if (!properties.getCode().isServerLocalMutationEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Server-local Patch Agent apply/test/rollback is disabled. User-owned file changes must use the Local Agent path."
            );
        }
    }
}
