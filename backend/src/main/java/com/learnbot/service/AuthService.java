package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AuthResponse;
import com.learnbot.dto.SpaceSummary;
import com.learnbot.dto.UserSummary;
import com.learnbot.repository.SecurityRepository;
import com.learnbot.security.ForbiddenException;
import com.learnbot.security.PasswordHasher;
import com.learnbot.security.UnauthorizedException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final SecurityRepository securityRepository;
    private final PasswordHasher passwordHasher;
    private final LearnBotProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(SecurityRepository securityRepository, PasswordHasher passwordHasher, LearnBotProperties properties) {
        this.securityRepository = securityRepository;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
    }

    @PostConstruct
    @Transactional
    void bootstrapAdmin() {
        if (securityRepository.countUsers() > 0) {
            return;
        }
        AppUser admin = securityRepository.createUser(
                cleanEmail(properties.getAuth().getBootstrapAdminEmail()),
                passwordHasher.hash(properties.getAuth().getBootstrapAdminPassword()),
                properties.getAuth().getBootstrapAdminName(),
                "ADMIN"
        );
        securityRepository.addSpaceMember(SecurityRepository.DEFAULT_SPACE_ID, admin.id(), "OWNER");
        securityRepository.createAuditLog(
                admin.id(),
                "BOOTSTRAP_ADMIN_CREATED",
                "USER",
                admin.id().toString(),
                SecurityRepository.DEFAULT_SPACE_ID,
                "Initial LearnBot admin account was created.",
                java.util.Map.of("email", admin.email())
        );
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        String cleanEmail = cleanEmail(email);
        String passwordHash = securityRepository.findPasswordHashByEmail(cleanEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password."));
        if (!passwordHasher.matches(password, passwordHash)) {
            throw new UnauthorizedException("Invalid email or password.");
        }
        AppUser user = securityRepository.findUserByEmail(cleanEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password."));
        String token = newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(properties.getAuth().getSessionHours());
        securityRepository.createSession(UUID.randomUUID(), user.id(), tokenHash(token), expiresAt);
        securityRepository.updateLastLogin(user.id());
        securityRepository.createAuditLog(user.id(), "LOGIN", "USER", user.id().toString(), null, "User logged in.", java.util.Map.of());
        return new AuthResponse(token, expiresAt, toSummary(user), securityRepository.listSpacesForUser(user));
    }

    public AppUser authenticateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Authentication is required.");
        }
        return securityRepository.findUserBySessionTokenHash(tokenHash(token))
                .orElseThrow(() -> new UnauthorizedException("Session is invalid or expired."));
    }

    public AuthResponse currentSession(AppUser user) {
        return new AuthResponse(null, null, toSummary(user), securityRepository.listSpacesForUser(user));
    }

    public void logout(String token, AppUser user) {
        if (token != null && token.startsWith("Bearer ")) {
            securityRepository.revokeSession(tokenHash(token.substring("Bearer ".length()).trim()));
        }
        securityRepository.createAuditLog(user.id(), "LOGOUT", "USER", user.id().toString(), null, "User logged out.", java.util.Map.of());
    }

    public AppUser inviteUser(AppUser actor, String email, String displayName, String initialPassword, String role, UUID spaceId, String spaceRole) {
        requireAdmin(actor);
        UUID resolvedSpaceId = resolveSpace(actor, spaceId);
        String cleanRole = normalizeUserRole(role);
        AppUser user = securityRepository.createUser(
                cleanEmail(email),
                passwordHasher.hash(initialPassword),
                displayName == null || displayName.isBlank() ? cleanEmail(email) : displayName.trim(),
                cleanRole
        );
        securityRepository.addSpaceMember(resolvedSpaceId, user.id(), normalizeSpaceRole(spaceRole));
        securityRepository.createAuditLog(actor.id(), "USER_INVITED", "USER", user.id().toString(), resolvedSpaceId, "User was invited.", java.util.Map.of("email", user.email()));
        return user;
    }

    public UUID createSpace(AppUser actor, String name, String description) {
        requireAdmin(actor);
        UUID spaceId = securityRepository.createSpace(name.trim(), description == null ? "" : description.trim(), actor.id());
        securityRepository.addSpaceMember(spaceId, actor.id(), "OWNER");
        securityRepository.createAuditLog(actor.id(), "SPACE_CREATED", "SPACE", spaceId.toString(), spaceId, "Space was created.", java.util.Map.of("name", name.trim()));
        return spaceId;
    }

    public void addSpaceMember(AppUser actor, UUID spaceId, UUID userId, String role) {
        requireAdmin(actor);
        requireSpace(actor, spaceId);
        securityRepository.addSpaceMember(spaceId, userId, normalizeSpaceRole(role));
        securityRepository.createAuditLog(actor.id(), "SPACE_MEMBER_UPDATED", "SPACE", spaceId.toString(), spaceId, "Space member was updated.", java.util.Map.of("userId", userId.toString()));
    }

    public List<SpaceSummary> listSpaces(AppUser user) {
        return securityRepository.listSpacesForUser(user);
    }

    public List<UUID> accessibleSpaceIds(AppUser user) {
        List<UUID> ids = securityRepository.accessibleSpaceIds(user);
        if (ids.isEmpty()) {
            throw new ForbiddenException("No accessible workspace is assigned.");
        }
        return ids;
    }

    public UUID resolveSpace(AppUser user, UUID requestedSpaceId) {
        if (requestedSpaceId != null) {
            requireSpace(user, requestedSpaceId);
            return requestedSpaceId;
        }
        List<UUID> ids = accessibleSpaceIds(user);
        return ids.get(0);
    }

    public void requireSpace(AppUser user, UUID spaceId) {
        if (!securityRepository.canAccessSpace(user, spaceId)) {
            throw new ForbiddenException("You do not have access to this workspace.");
        }
    }

    public void requireAdmin(AppUser user) {
        if (user == null || !user.isAdmin()) {
            throw new ForbiddenException("Administrator permission is required.");
        }
    }

    public UserSummary toSummary(AppUser user) {
        return new UserSummary(user.id(), user.email(), user.displayName(), user.role(), user.status());
    }

    private String normalizeUserRole(String role) {
        String clean = role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
        return clean.equals("ADMIN") ? "ADMIN" : "USER";
    }

    private String normalizeSpaceRole(String role) {
        String clean = role == null || role.isBlank() ? "MEMBER" : role.trim().toUpperCase(Locale.ROOT);
        return clean.equals("OWNER") ? "OWNER" : "MEMBER";
    }

    private String cleanEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Token hashing is unavailable.", ex);
        }
    }
}

