package com.learnbot.service;

import com.learnbot.repository.LocalAgentDeviceRepository;
import com.learnbot.repository.LocalAgentEnrollmentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class LocalAgentEnrollmentService {
    private static final int TTL_SECONDS = 600;
    private static final int INITIAL_POLL_SECONDS = 5;
    private static final int RATE_LIMIT_ATTEMPTS = 10;
    private static final int EXCHANGE_RATE_LIMIT_ATTEMPTS = 300;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);
    private static final char[] USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final LocalAgentEnrollmentRepository repository;
    private final LocalAgentDeviceRepository deviceRepository;
    private final LocalAgentAuthService authService;
    private final LocalAgentAuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalAgentEnrollmentService(
            LocalAgentEnrollmentRepository repository,
            LocalAgentDeviceRepository deviceRepository,
            LocalAgentAuthService authService
    ) {
        this(repository, deviceRepository, authService, LocalAgentAuditService.noop());
    }

    @Autowired
    public LocalAgentEnrollmentService(
            LocalAgentEnrollmentRepository repository,
            LocalAgentDeviceRepository deviceRepository,
            LocalAgentAuthService authService,
            LocalAgentAuditService auditService
    ) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional
    public CreateResponse create(CreateRequest request, String clientKey) {
        OffsetDateTime now = OffsetDateTime.now();
        checkRateLimit("enrollment-create", clientKey, now, RATE_LIMIT_ATTEMPTS);
        String deviceCode = randomSecret();
        String userCode = randomUserCode();
        LocalAgentEnrollment enrollment = new LocalAgentEnrollment(
                UUID.randomUUID(), UUID.randomUUID(), null, LocalAgentEnrollmentState.PENDING,
                clean(request.label(), 120), clean(request.clientName(), 120), clean(request.machineName(), 120),
                clean(request.osName(), 80), clean(request.osVersion(), 80), clean(request.architecture(), 32),
                clean(request.agentVersion(), 64), request.installationId(), INITIAL_POLL_SECONDS, 0, null,
                now.plusSeconds(TTL_SECONDS), null, null, null,
                null, null, null, null, now
        );
        repository.create(enrollment, LocalAgentSecretHasher.sha256(deviceCode),
                LocalAgentSecretHasher.sha256(normalizeUserCode(userCode)));
        return new CreateResponse(
                "learnbot.local-agent.enrollment-create.v1", "PENDING_BROWSER_APPROVAL",
                deviceCode, userCode, enrollment.expiresAt(), INITIAL_POLL_SECONDS,
                "/settings/local-agent/connect",
                "/settings/local-agent/connect?user_code=" + userCode
        );
    }

    @Transactional
    public EnrollmentView lookup(UUID userId, LookupRequest request, String clientKey) {
        OffsetDateTime now = OffsetDateTime.now();
        checkRateLimit("enrollment-lookup", userId.toString(), now, RATE_LIMIT_ATTEMPTS);
        LocalAgentEnrollment enrollment = repository.findByUserCodeHash(
                        LocalAgentSecretHasher.sha256(normalizeUserCode(request.userCode())))
                .orElseThrow(() -> new IllegalArgumentException("Enrollment code was not found or expired."));
        if (enrollment.expiresAt().isBefore(now)
                && (enrollment.state() == LocalAgentEnrollmentState.PENDING
                || enrollment.state() == LocalAgentEnrollmentState.APPROVED)) {
            repository.expire(enrollment.id(), now);
            enrollment = withState(enrollment, LocalAgentEnrollmentState.EXPIRED);
        }
        if (enrollment.userId() != null && !userId.equals(enrollment.userId())) {
            throw new IllegalArgumentException("Enrollment code was not found or expired.");
        }
        return view(enrollment);
    }

    @Transactional
    public DecisionResponse decide(UUID userId, UUID enrollmentId, DecisionRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentEnrollment enrollment = repository.findByIdForUpdate(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment was not found."));
        if (enrollment.expiresAt().isBefore(now)) {
            repository.expire(enrollment.id(), now);
            throw new IllegalArgumentException("Enrollment expired.");
        }
        boolean changed = request.decision() == Decision.APPROVE
                ? repository.approve(enrollment.id(), userId, now)
                : repository.deny(enrollment.id(), userId, now);
        if (!changed) throw new IllegalArgumentException("Enrollment is no longer pending.");
        auditService.enrollmentDecision(
                userId, enrollment.id(), enrollment.agentId(), enrollment.installationId(),
                enrollment.agentVersion(), request.decision().name()
        );
        return new DecisionResponse(
                "learnbot.local-agent.enrollment-decision.v1",
                request.decision() == Decision.APPROVE ? "APPROVED" : "DENIED",
                enrollment.id(), enrollment.agentId(), now
        );
    }

    @Transactional
    public ExchangeResponse exchange(ExchangeRequest request, String clientKey) {
        OffsetDateTime now = OffsetDateTime.now();
        checkRateLimit("enrollment-exchange", clientKey, now, EXCHANGE_RATE_LIMIT_ATTEMPTS);
        LocalAgentEnrollment enrollment = repository.findByDeviceCodeHashForUpdate(
                        LocalAgentSecretHasher.sha256(request.deviceCode()))
                .orElse(null);
        if (enrollment == null) return exchangeStatus("INVALID_DEVICE_CODE", null, null, null, 0);
        if (enrollment.expiresAt().isBefore(now)) {
            repository.expire(enrollment.id(), now);
            return exchangeStatus("EXPIRED_TOKEN", enrollment.agentId(), null, null, 0);
        }
        if (enrollment.lastPolledAt() != null
                && now.isBefore(enrollment.lastPolledAt().plusSeconds(enrollment.pollIntervalSeconds()))) {
            int retryAfter = repository.recordSlowPoll(enrollment.id(), now);
            return exchangeStatus("SLOW_DOWN", enrollment.agentId(), null, null, retryAfter);
        }
        repository.recordPoll(enrollment.id(), now);
        if (enrollment.state() == LocalAgentEnrollmentState.PENDING) {
            return exchangeStatus("AUTHORIZATION_PENDING", enrollment.agentId(), null, null,
                    enrollment.pollIntervalSeconds());
        }
        if (enrollment.state() == LocalAgentEnrollmentState.DENIED) {
            return exchangeStatus("ACCESS_DENIED", enrollment.agentId(), null, null, 0);
        }
        if (enrollment.state() == LocalAgentEnrollmentState.CONSUMED) {
            return exchangeStatus("ALREADY_CONSUMED", enrollment.agentId(), null, null, 0);
        }
        if (enrollment.state() != LocalAgentEnrollmentState.APPROVED || enrollment.userId() == null) {
            return exchangeStatus("EXPIRED_TOKEN", enrollment.agentId(), null, null, 0);
        }
        var candidate = authService.prepareCredential(enrollment.agentId());
        OffsetDateTime confirmBy = enrollment.expiresAt().isBefore(now.plusMinutes(10))
                ? enrollment.expiresAt()
                : now.plusMinutes(10);
        if (!repository.stageCandidate(
                enrollment.id(), candidate.tokenId(), LocalAgentSecretHasher.sha256(candidate.token()),
                candidate.expiresAt(), confirmBy, now)) {
            throw new IllegalStateException("Enrollment candidate credential could not be staged atomically.");
        }
        return new ExchangeResponse(
                "learnbot.local-agent.enrollment-exchange.v1", "APPROVED", enrollment.agentId(),
                candidate.token(), candidate.expiresAt(), 0, enrollment.id(), confirmBy, true
        );
    }

    @Transactional
    public ConfirmationResponse confirm(UUID enrollmentId, String candidateRawToken) {
        if (candidateRawToken == null || candidateRawToken.isBlank()) {
            throw new com.learnbot.security.UnauthorizedException("Candidate Local Agent token is required.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentEnrollment enrollment = repository.findCandidateForUpdate(
                        enrollmentId, LocalAgentSecretHasher.sha256(candidateRawToken))
                .orElseThrow(() -> new com.learnbot.security.UnauthorizedException(
                        "Enrollment confirmation is invalid or expired."));
        if (enrollment.state() == LocalAgentEnrollmentState.CONSUMED) {
            LocalAgentToken active = authService.authenticate(candidateRawToken);
            if (!enrollment.candidateTokenId().equals(active.id())
                    || !enrollment.userId().equals(active.userId())
                    || !enrollment.agentId().equals(active.agentId())) {
                throw new com.learnbot.security.UnauthorizedException(
                        "Enrollment confirmation is invalid or expired.");
            }
            return confirmation(enrollment, enrollment.consumedAt());
        }
        if (enrollment.state() != LocalAgentEnrollmentState.APPROVED
                || enrollment.userId() == null
                || enrollment.credentialConfirmBy() == null
                || !enrollment.credentialConfirmBy().isAfter(now)) {
            throw new com.learnbot.security.UnauthorizedException("Enrollment confirmation is invalid or expired.");
        }
        deviceRepository.lockUserForUpdate(enrollment.userId());
        for (LocalAgentDevice previous : deviceRepository.findOtherActiveByInstallation(
                enrollment.userId(), enrollment.installationId(), enrollment.agentId())) {
            authService.revokeAgent(enrollment.userId(), previous.agentId());
            deviceRepository.revoke(enrollment.userId(), previous.agentId(), now);
        }
        deviceRepository.upsertEnrollment(enrollment, now);
        authService.activateCredential(
                enrollment.userId(), enrollment.agentId(), enrollment.label(), enrollment.candidateTokenId(),
                candidateRawToken, enrollment.candidateExpiresAt()
        );
        if (!repository.consume(enrollment.id(), enrollment.userId(), now)) {
            throw new IllegalStateException("Enrollment could not be consumed atomically.");
        }
        return confirmation(enrollment, now);
    }

    private void checkRateLimit(String scope, String key, OffsetDateTime now, int maximumAttempts) {
        repository.cleanupRateLimits(now.minusDays(1));
        var result = repository.consumeRateLimit(scope, LocalAgentSecretHasher.sha256(key), now,
                now.minus(RATE_LIMIT_WINDOW));
        if (result.attemptCount() > maximumAttempts) {
            long retryAfter = Math.max(1, Duration.between(now,
                    result.windowStartedAt().plus(RATE_LIMIT_WINDOW)).toSeconds());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many Local Agent enrollment attempts. Retry after " + retryAfter + " seconds.");
        }
    }

    private ExchangeResponse exchangeStatus(String status, UUID agentId, String token,
                                              OffsetDateTime expiresAt, int retryAfterSeconds) {
        return new ExchangeResponse("learnbot.local-agent.enrollment-exchange.v1", status,
                agentId, token, expiresAt, retryAfterSeconds, null, null, false);
    }

    private ConfirmationResponse confirmation(LocalAgentEnrollment enrollment, OffsetDateTime confirmedAt) {
        return new ConfirmationResponse(
                "learnbot.local-agent.enrollment-confirmation.v1", "CONFIRMED", enrollment.id(),
                enrollment.agentId(), confirmedAt, enrollment.candidateExpiresAt()
        );
    }

    private EnrollmentView view(LocalAgentEnrollment enrollment) {
        return new EnrollmentView(
                "learnbot.local-agent.enrollment-view.v1", enrollment.id(), enrollment.agentId(),
                enrollment.state().name(), enrollment.label(), enrollment.clientName(), enrollment.machineName(),
                enrollment.osName(), enrollment.osVersion(), enrollment.architecture(), enrollment.agentVersion(),
                enrollment.installationId(), enrollment.createdAt(), enrollment.expiresAt()
        );
    }

    private LocalAgentEnrollment withState(LocalAgentEnrollment value, LocalAgentEnrollmentState state) {
        return new LocalAgentEnrollment(value.id(), value.agentId(), value.userId(), state, value.label(),
                value.clientName(), value.machineName(), value.osName(), value.osVersion(), value.architecture(),
                value.agentVersion(), value.installationId(), value.pollIntervalSeconds(), value.pollViolationCount(),
                value.lastPolledAt(), value.expiresAt(), value.approvedAt(), value.deniedAt(), value.consumedAt(),
                value.candidateTokenId(), value.candidateExpiresAt(), value.credentialConfirmBy(),
                value.credentialIssuedAt(),
                value.createdAt());
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomUserCode() {
        StringBuilder result = new StringBuilder(9);
        for (int index = 0; index < 8; index++) {
            if (index == 4) result.append('-');
            result.append(USER_CODE_ALPHABET[secureRandom.nextInt(USER_CODE_ALPHABET.length)]);
        }
        return result.toString();
    }

    private String normalizeUserCode(String value) {
        if (value == null) throw new IllegalArgumentException("userCode is required.");
        String normalized = value.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z2-9]{8}")) throw new IllegalArgumentException("userCode is invalid.");
        return normalized;
    }

    private String clean(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    public record CreateRequest(
            @Size(max = 120) String label,
            @Size(max = 120) String clientName,
            @NotBlank @Size(max = 120) String machineName,
            @NotBlank @Size(max = 80) String osName,
            @Size(max = 80) String osVersion,
            @NotBlank @Size(max = 32) String architecture,
            @NotBlank @Size(max = 64) String agentVersion,
            @NotNull UUID installationId
    ) { }

    public record CreateResponse(String schema, String status, String deviceCode, String userCode,
                                 OffsetDateTime expiresAt, int intervalSeconds, String verificationUriPath,
                                 String verificationUriCompletePath) { }

    public record LookupRequest(@NotBlank String userCode) { }

    public record EnrollmentView(String schema, UUID enrollmentId, UUID agentId, String status, String label,
                                 String clientName, String machineName, String osName, String osVersion,
                                 String architecture, String agentVersion, UUID installationId,
                                 OffsetDateTime requestedAt, OffsetDateTime expiresAt) { }

    public enum Decision { APPROVE, DENY }

    public record DecisionRequest(@NotNull Decision decision) { }

    public record DecisionResponse(String schema, String status, UUID enrollmentId, UUID agentId,
                                   OffsetDateTime decidedAt) { }

    public record ExchangeRequest(@NotBlank String deviceCode) { }

    public record ExchangeResponse(String schema, String status, UUID agentId, String token,
                                   OffsetDateTime expiresAt, int retryAfterSeconds, UUID enrollmentId,
                                   OffsetDateTime confirmBy, boolean confirmationRequired) { }

    public record ConfirmationResponse(String schema, String status, UUID enrollmentId, UUID agentId,
                                       OffsetDateTime confirmedAt, OffsetDateTime expiresAt) { }
}
