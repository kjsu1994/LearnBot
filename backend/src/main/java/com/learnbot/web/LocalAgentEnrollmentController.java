package com.learnbot.web;

import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.repository.LocalAgentDeviceRepository;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentAuditService;
import com.learnbot.service.LocalAgentCredentialRotationService;
import com.learnbot.service.LocalAgentDevice;
import com.learnbot.service.LocalAgentEnrollmentService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentVersionPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/local-agents")
public class LocalAgentEnrollmentController {
    private final LocalAgentEnrollmentService enrollmentService;
    private final LocalAgentCredentialRotationService rotationService;
    private final LocalAgentDeviceRepository deviceRepository;
    private final LocalAgentAuthService authService;
    private final LocalAgentGatewayService gatewayService;
    private final LocalAgentVersionPolicy versionPolicy;
    private final CurrentUserProvider currentUserProvider;
    private final LocalAgentAuditService auditService;

    public LocalAgentEnrollmentController(
            LocalAgentEnrollmentService enrollmentService,
            LocalAgentCredentialRotationService rotationService,
            LocalAgentDeviceRepository deviceRepository,
            LocalAgentAuthService authService,
            LocalAgentGatewayService gatewayService,
            LocalAgentVersionPolicy versionPolicy,
            CurrentUserProvider currentUserProvider
    ) {
        this(enrollmentService, rotationService, deviceRepository, authService, gatewayService,
                versionPolicy, currentUserProvider, LocalAgentAuditService.noop());
    }

    @Autowired
    public LocalAgentEnrollmentController(
            LocalAgentEnrollmentService enrollmentService,
            LocalAgentCredentialRotationService rotationService,
            LocalAgentDeviceRepository deviceRepository,
            LocalAgentAuthService authService,
            LocalAgentGatewayService gatewayService,
            LocalAgentVersionPolicy versionPolicy,
            CurrentUserProvider currentUserProvider,
            LocalAgentAuditService auditService
    ) {
        this.enrollmentService = enrollmentService;
        this.rotationService = rotationService;
        this.deviceRepository = deviceRepository;
        this.authService = authService;
        this.gatewayService = gatewayService;
        this.versionPolicy = versionPolicy;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
    }

    @PostMapping("/enrollments")
    LocalAgentEnrollmentService.CreateResponse createEnrollment(
            @Valid @RequestBody LocalAgentEnrollmentService.CreateRequest request,
            HttpServletRequest servletRequest
    ) {
        return enrollmentService.create(request, clientKey(servletRequest));
    }

    @PostMapping("/enrollments/exchange")
    LocalAgentEnrollmentService.ExchangeResponse exchangeEnrollment(
            @Valid @RequestBody LocalAgentEnrollmentService.ExchangeRequest request,
            HttpServletRequest servletRequest
    ) {
        return enrollmentService.exchange(request, clientKey(servletRequest));
    }

    @PostMapping("/enrollments/lookup")
    LocalAgentEnrollmentService.EnrollmentView lookupEnrollment(
            @Valid @RequestBody LocalAgentEnrollmentService.LookupRequest request,
            HttpServletRequest servletRequest
    ) {
        UUID userId = currentUserProvider.currentUser().id();
        return enrollmentService.lookup(userId, request, clientKey(servletRequest));
    }

    @PostMapping("/enrollments/{enrollmentId}/decision")
    LocalAgentEnrollmentService.DecisionResponse decideEnrollment(
            @PathVariable UUID enrollmentId,
            @Valid @RequestBody LocalAgentEnrollmentService.DecisionRequest request
    ) {
        return enrollmentService.decide(currentUserProvider.currentUser().id(), enrollmentId, request);
    }

    @PostMapping("/enrollments/{enrollmentId}/confirm")
    LocalAgentEnrollmentService.ConfirmationResponse confirmEnrollment(
            @PathVariable UUID enrollmentId,
            @RequestHeader(name = "X-Local-Agent-Token", required = false) String candidateToken
    ) {
        return enrollmentService.confirm(enrollmentId, candidateToken);
    }

    @GetMapping
    List<DeviceResponse> devices() {
        UUID userId = currentUserProvider.currentUser().id();
        return deviceRepository.listRegisteredByUser(userId).stream().map(device -> toResponse(userId, device)).toList();
    }

    @DeleteMapping("/{agentId}")
    @Transactional
    ResponseEntity<Void> revokeDevice(@PathVariable UUID agentId) {
        UUID userId = currentUserProvider.currentUser().id();
        var device = deviceRepository.findActiveByUserAndAgent(userId, agentId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        authService.revokeAgent(userId, agentId);
        deviceRepository.revoke(userId, agentId, OffsetDateTime.now());
        gatewayService.disconnect(userId, agentId);
        syncSelection(userId);
        auditService.deviceRevokedByUser(userId, device);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/self")
    @Transactional
    public ResponseEntity<Void> revokeSelf(
            @RequestHeader(name = "X-Local-Agent-Token", required = false) String agentToken
    ) {
        var token = authService.authenticate(agentToken);
        var device = deviceRepository.findActiveByUserAndAgent(token.userId(), token.agentId()).orElse(null);
        authService.revokeAgent(token.userId(), token.agentId());
        deviceRepository.revoke(token.userId(), token.agentId(), OffsetDateTime.now());
        gatewayService.disconnect(token.userId(), token.agentId());
        syncSelection(token.userId());
        auditService.deviceSelfRevoked(token, device);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{agentId}/selection")
    @Transactional
    ResponseEntity<DeviceResponse> selectDevice(@PathVariable UUID agentId) {
        UUID userId = currentUserProvider.currentUser().id();
        if (!deviceRepository.selectForUser(userId, agentId, OffsetDateTime.now())) {
            return ResponseEntity.notFound().build();
        }
        gatewayService.select(userId, agentId);
        return deviceRepository.findActiveByUserAndAgent(userId, agentId)
                .map(device -> {
                    auditService.deviceSelected(userId, device);
                    return ResponseEntity.ok(toResponse(userId, device));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/self/credential-rotations")
    LocalAgentCredentialRotationService.RotationResponse rotateCredential(
            @RequestHeader(name = "X-Local-Agent-Token", required = false) String currentToken
    ) {
        return rotationService.initiate(currentToken);
    }

    @PostMapping("/self/credential-rotations/{rotationId}/confirm")
    LocalAgentCredentialRotationService.ConfirmationResponse confirmCredentialRotation(
            @PathVariable UUID rotationId,
            @RequestHeader(name = "X-Local-Agent-Token", required = false) String candidateToken
    ) {
        return rotationService.confirm(rotationId, candidateToken);
    }

    private DeviceResponse toResponse(UUID userId, LocalAgentDevice device) {
        var status = gatewayService.status(userId, device.agentId());
        var update = versionPolicy.evaluate(device.agentVersion());
        List<String> capabilities = status.agentId() == null ? device.capabilities() : status.capabilities();
        List<LocalAgentWorkspaceSummary> workspaces = status.agentId() == null ? device.workspaces() : status.workspaces();
        return new DeviceResponse(
                device.agentId(), device.installationId(), device.label(), device.clientName(), device.machineName(),
                device.osName(), device.osVersion(), device.architecture(), device.agentVersion(),
                device.selectedAt() != null,
                status.agentId() == null ? LocalAgentConnectionState.DISCONNECTED : status.state(),
                device.approvedAt(), device.lastSeenAt(), capabilities, workspaces,
                device.configuredTransport(), device.activeTransport(), device.webSocketFailureCount(),
                device.nextWebSocketRetryAt(), update.latestVersion(), update.minimumVersion(),
                update.updateState(), update.updateUri()
        );
    }

    private String clientKey(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private void syncSelection(UUID userId) {
        deviceRepository.findSelectedByUser(userId).ifPresentOrElse(
                selected -> gatewayService.select(userId, selected.agentId()),
                () -> gatewayService.clearSelection(userId)
        );
    }

    public record DeviceResponse(
            UUID agentId,
            UUID installationId,
            String label,
            String clientName,
            String machineName,
            String osName,
            String osVersion,
            String architecture,
            String version,
            boolean selected,
            LocalAgentConnectionState state,
            OffsetDateTime approvedAt,
            OffsetDateTime lastSeenAt,
            List<String> capabilities,
            List<LocalAgentWorkspaceSummary> workspaces,
            String configuredTransport,
            String activeTransport,
            int webSocketFailureCount,
            OffsetDateTime nextWebSocketRetryAt,
            String latestVersion,
            String minimumVersion,
            String updateState,
            String updateUri
    ) { }
}
