package com.learnbot.web;

import com.learnbot.dto.LocalAgentHeartbeatRequest;
import com.learnbot.dto.LocalAgentApprovalDecision;
import com.learnbot.dto.LocalAgentApprovalDecisionRequest;
import com.learnbot.dto.LocalAgentApprovedExecutionFlowInspectionRequest;
import com.learnbot.dto.LocalAgentApprovedExecutionFlowReleaseAttemptInspectionRequest;
import com.learnbot.dto.LocalAgentPairingTokenRequest;
import com.learnbot.dto.LocalAgentPairingTokenResponse;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentReadOnlyToolRequest;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentTokenSummary;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.security.UnauthorizedException;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToolGatewayService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/local-agents")
public class LocalAgentController {
    private final LocalAgentGatewayService gatewayService;
    private final LocalAgentAuthService authService;
    private final LocalAgentToolGatewayService toolGatewayService;
    private final CurrentUserProvider currentUserProvider;

    public LocalAgentController(
            LocalAgentGatewayService gatewayService,
            LocalAgentAuthService authService,
            LocalAgentToolGatewayService toolGatewayService,
            CurrentUserProvider currentUserProvider
    ) {
        this.gatewayService = gatewayService;
        this.authService = authService;
        this.toolGatewayService = toolGatewayService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/status")
    LocalAgentStatusResponse status() {
        return gatewayService.status(currentUserProvider.currentUser().id());
    }

    @PostMapping("/pairing-token")
    LocalAgentPairingTokenResponse issuePairingToken(@Valid @RequestBody(required = false) LocalAgentPairingTokenRequest request) {
        String label = request == null ? null : request.label();
        return authService.issueToken(currentUserProvider.currentUser().id(), label);
    }

    @GetMapping("/tokens")
    List<LocalAgentTokenSummary> tokens() {
        return authService.listTokens(currentUserProvider.currentUser().id());
    }

    @DeleteMapping("/tokens/{tokenId}")
    ResponseEntity<Void> revokeToken(@PathVariable UUID tokenId) {
        boolean revoked = authService.revokeToken(currentUserProvider.currentUser().id(), tokenId);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/heartbeat")
    LocalAgentStatusResponse heartbeat(
            @RequestHeader(name = "X-Local-Agent-Token", required = false) String agentToken,
            @Valid @RequestBody LocalAgentHeartbeatRequest request
    ) {
        UUID userId = resolveHeartbeatUserId(agentToken, request);
        gatewayService.registerHeartbeat(
                userId,
                request.agentId(),
                request.version(),
                request.capabilities() == null
                        ? List.of()
                        : request.capabilities().stream().map(LocalAgentToolName::wireName).toList(),
                request.workspaces(),
                request.configuredTransport(),
                request.activeTransport(),
                request.webSocketFailureCount(),
                request.nextWebSocketRetryAt()
        );
        return gatewayService.status(userId);
    }

    @GetMapping("/tools/next")
    ResponseEntity<LocalAgentQueuedToolRequest> nextTool(
            @RequestHeader(name = "X-Local-Agent-Token") String agentToken
    ) {
        var token = authService.authenticate(agentToken);
        return toolGatewayService.claimNext(token.userId(), token.agentId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/tools/read-only")
    LocalAgentQueuedToolRequest enqueueReadOnlyTool(@Valid @RequestBody LocalAgentReadOnlyToolRequest request) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.enqueueReadOnly(new LocalAgentToolRequest(
                UUID.randomUUID(),
                user.id(),
                request.agentId(),
                request.workspaceId(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                request.toolName(),
                request.input(),
                null,
                null,
                null
        ));
    }

    @GetMapping("/tools/{requestId}")
    ResponseEntity<LocalAgentToolExecutionResponse> toolExecution(@PathVariable UUID requestId) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.findForUser(user.id(), requestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tools/{requestId}/approval")
    LocalAgentToolExecutionResponse decideToolApproval(
            @PathVariable UUID requestId,
            @Valid @RequestBody LocalAgentApprovalDecisionRequest request
    ) {
        var user = currentUserProvider.currentUser();
        if (request.decision() == LocalAgentApprovalDecision.APPROVE) {
            return toolGatewayService.approveHeld(user.id(), requestId);
        }
        return toolGatewayService.deny(user.id(), requestId);
    }

    @GetMapping("/tools/{requestId}/readiness")
    LocalAgentPatchExecutionReadinessResponse toolReadiness(@PathVariable UUID requestId) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.inspectPatchExecutionReadiness(user.id(), requestId);
    }

    @PostMapping("/tools/approved-execution-flow/inspection")
    Map<String, Object> inspectApprovedExecutionFlow(
            @Valid @RequestBody LocalAgentApprovedExecutionFlowInspectionRequest request
    ) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.inspectApprovedExecutionFlow(user.id(), request.requestIds());
    }

    @PostMapping("/tools/approved-execution-flow/inspection/by-release-attempt")
    Map<String, Object> inspectApprovedExecutionFlowForReleaseAttempt(
            @Valid @RequestBody LocalAgentApprovedExecutionFlowReleaseAttemptInspectionRequest request
    ) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.inspectApprovedExecutionFlowForReleaseAttempt(user.id(), request.releaseAttemptId());
    }

    @PostMapping("/tools/{requestId}/dry-run")
    LocalAgentQueuedToolRequest enqueuePatchDryRun(@PathVariable UUID requestId) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.enqueuePatchDryRun(user.id(), requestId);
    }

    @PostMapping("/tools/{requestId}/fresh-observations")
    List<LocalAgentQueuedToolRequest> enqueueReleaseAttemptFreshObservations(@PathVariable UUID requestId) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.enqueueReleaseAttemptFreshObservations(user.id(), requestId);
    }

    @PostMapping("/tools/{requestId}/release")
    LocalAgentPatchReleaseBoundaryResponse releasePatchExecution(@PathVariable UUID requestId) {
        var user = currentUserProvider.currentUser();
        return toolGatewayService.inspectPatchReleaseBoundary(user.id(), requestId);
    }

    @PostMapping("/tools/{requestId}/response")
    ResponseEntity<Void> completeTool(
            @RequestHeader(name = "X-Local-Agent-Token") String agentToken,
            @PathVariable UUID requestId,
            @Valid @RequestBody LocalAgentToolResponse response
    ) {
        var token = authService.authenticate(agentToken);
        if (!requestId.equals(response.requestId())) {
            throw new IllegalArgumentException("Path request id does not match response request id.");
        }
        if (!token.userId().equals(response.userId()) || !token.agentId().equals(response.agentId())) {
            throw new UnauthorizedException("Local Agent token does not match the tool response.");
        }
        toolGatewayService.complete(response);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveHeartbeatUserId(String agentToken, LocalAgentHeartbeatRequest request) {
        if (agentToken == null || agentToken.isBlank()) {
            return currentUserProvider.currentUser().id();
        }
        var token = authService.authenticate(agentToken);
        if (!token.agentId().equals(request.agentId())) {
            throw new UnauthorizedException("Local Agent token does not match the heartbeat agent.");
        }
        return token.userId();
    }
}
