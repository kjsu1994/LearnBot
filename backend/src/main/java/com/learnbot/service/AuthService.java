package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminUserSummary;
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
import java.util.Map;
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
            ensureConfiguredMaster();
            return;
        }
        String adminLoginId = cleanLoginId(properties.getAuth().getBootstrapAdminEmail());
        AppUser admin = securityRepository.createUser(
                adminLoginId,
                passwordHasher.hash(properties.getAuth().getBootstrapAdminPassword()),
                properties.getAuth().getBootstrapAdminName(),
                adminLoginId.equals(masterLoginId()) ? "MASTER" : "ADMIN"
        );
        securityRepository.addSpaceMember(SecurityRepository.DEFAULT_SPACE_ID, admin.id(), "OWNER");
        securityRepository.createAuditLog(
                admin.id(),
                "BOOTSTRAP_ADMIN_CREATED",
                "USER",
                admin.id().toString(),
                SecurityRepository.DEFAULT_SPACE_ID,
                "Initial LearnBot admin account was created.",
                java.util.Map.of("loginId", admin.email())
        );
        ensureConfiguredMaster();
    }

    @Transactional
    public AuthResponse login(String loginId, String password) {
        String cleanLoginId = cleanLoginId(loginId);
        String passwordHash = securityRepository.findPasswordHashByEmail(cleanLoginId)
                .orElseThrow(() -> new UnauthorizedException("ID 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordHasher.matches(password, passwordHash)) {
            throw new UnauthorizedException("ID 또는 비밀번호가 올바르지 않습니다.");
        }
        AppUser user = securityRepository.findUserByEmail(cleanLoginId)
                .orElseThrow(() -> new UnauthorizedException("ID 또는 비밀번호가 올바르지 않습니다."));
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

    public List<AdminUserSummary> listAdminUsers(AppUser actor) {
        requireAdmin(actor);
        if (actor.isMaster()) {
            return securityRepository.listAdminUsers();
        }
        return securityRepository.listAdminUsersForSpaces(securityRepository.accessibleSpaceIds(actor));
    }

    public void logout(String token, AppUser user) {
        if (token != null && token.startsWith("Bearer ")) {
            securityRepository.revokeSession(tokenHash(token.substring("Bearer ".length()).trim()));
        }
        securityRepository.createAuditLog(user.id(), "LOGOUT", "USER", user.id().toString(), null, "User logged out.", java.util.Map.of());
    }

    public AppUser inviteUser(AppUser actor, String loginId, String displayName, String initialPassword, String role, UUID spaceId, String spaceRole) {
        requireAdmin(actor);
        UUID resolvedSpaceId = resolveAdminSpace(spaceId);
        requireManageableSpace(actor, resolvedSpaceId);
        String cleanRole = actor.isMaster() ? normalizeManagedUserRole(role, false) : "USER";
        String cleanLoginId = cleanLoginId(loginId);
        AppUser user = securityRepository.createUser(
                cleanLoginId,
                passwordHasher.hash(initialPassword),
                displayName == null || displayName.isBlank() ? cleanLoginId : displayName.trim(),
                cleanRole
        );
        securityRepository.addSpaceMember(resolvedSpaceId, user.id(), normalizeSpaceRole(spaceRole));
        securityRepository.createAuditLog(actor.id(), "USER_INVITED", "USER", user.id().toString(), resolvedSpaceId, "User was invited.", java.util.Map.of("loginId", user.email()));
        return user;
    }

    public AppUser updateUser(AppUser actor, UUID userId, String loginId, String displayName, String role) {
        requireAdmin(actor);
        AppUser target = activeUser(userId);
        requireManageableUser(actor, target);
        String cleanLoginId = loginId == null || loginId.isBlank() ? target.email() : cleanLoginId(loginId);
        String cleanDisplayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (cleanDisplayName == null) {
            throw new IllegalArgumentException("표시 이름은 필수입니다.");
        }
        String cleanRole = target.isMaster() ? "MASTER" : (actor.isMaster() ? normalizeManagedUserRole(role, false) : "USER");
        if (target.isMaster() && !target.email().equalsIgnoreCase(cleanLoginId)) {
            throw new IllegalArgumentException("MASTER account ID cannot be changed.");
        }
        if (actor.id().equals(userId) && !target.role().equals(cleanRole)) {
            throw new IllegalArgumentException("현재 로그인한 관리자 계정의 시스템 권한은 변경할 수 없습니다.");
        }
        boolean loginIdChanged = !target.email().equalsIgnoreCase(cleanLoginId);
        if (actor.id().equals(userId) && loginIdChanged) {
            throw new IllegalArgumentException("현재 로그인한 관리자 계정의 ID는 이 화면에서 변경할 수 없습니다.");
        }
        if (target.isAdmin() && "USER".equals(cleanRole) && securityRepository.countActiveAdmins() <= 1) {
            throw new IllegalArgumentException("마지막 관리자 계정은 USER로 변경할 수 없습니다.");
        }

        if (loginIdChanged) {
            securityRepository.findUserByEmail(cleanLoginId)
                    .filter(existing -> !existing.id().equals(userId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("이미 사용 중인 ID입니다.");
                    });
        }

        securityRepository.updateUser(userId, cleanLoginId, cleanDisplayName, cleanRole);
        if (loginIdChanged) {
            securityRepository.revokeSessionsForUser(userId);
        }
        securityRepository.createAuditLog(
                actor.id(),
                "USER_UPDATED",
                "USER",
                userId.toString(),
                null,
                "User account was updated.",
                Map.of(
                        "oldLoginId", target.email(),
                        "newLoginId", cleanLoginId,
                        "displayName", cleanDisplayName,
                        "oldRole", target.role(),
                        "newRole", cleanRole
                )
        );
        return securityRepository.findUserById(userId).orElseThrow();
    }

    public void resetUserPassword(AppUser actor, UUID userId, String newPassword) {
        requireAdmin(actor);
        if (actor.id().equals(userId)) {
            throw new IllegalArgumentException("현재 로그인한 관리자 계정의 비밀번호는 이 화면에서 재설정할 수 없습니다.");
        }
        AppUser target = activeUser(userId);
        requireManageableUser(actor, target);
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("새 비밀번호는 필수입니다.");
        }
        securityRepository.updatePasswordHash(userId, passwordHasher.hash(newPassword));
        securityRepository.revokeSessionsForUser(userId);
        securityRepository.createAuditLog(
                actor.id(),
                "USER_PASSWORD_RESET",
                "USER",
                userId.toString(),
                null,
                "User password was reset by an administrator.",
                Map.of("loginId", target.email())
        );
    }

    public UUID createSpace(AppUser actor, String name, String description) {
        requireMaster(actor);
        UUID spaceId = securityRepository.createSpace(name.trim(), description == null ? "" : description.trim(), actor.id());
        securityRepository.addSpaceMember(spaceId, actor.id(), "OWNER");
        securityRepository.createAuditLog(actor.id(), "SPACE_CREATED", "SPACE", spaceId.toString(), spaceId, "Space was created.", java.util.Map.of("name", name.trim()));
        return spaceId;
    }

    public void deleteUser(AppUser actor, UUID userId) {
        requireAdmin(actor);
        if (actor.id().equals(userId)) {
            throw new IllegalArgumentException("현재 로그인한 관리자 계정은 삭제할 수 없습니다.");
        }
        AppUser target = securityRepository.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        requireManageableUser(actor, target);
        if (target.isMaster()) {
            throw new IllegalArgumentException("MASTER account cannot be deleted.");
        }
        if ("DELETED".equals(target.status())) {
            return;
        }
        securityRepository.deactivateUser(userId);
        securityRepository.revokeSessionsForUser(userId);
        securityRepository.createAuditLog(actor.id(), "USER_DELETED", "USER", userId.toString(), null, "User was deleted.", java.util.Map.of("loginId", target.email()));
    }

    public void updateSpace(AppUser actor, UUID spaceId, String name, String description) {
        requireMaster(actor);
        securityRepository.findSpace(spaceId)
                .orElseThrow(() -> new IllegalArgumentException("공간을 찾을 수 없습니다."));
        String cleanName = name == null || name.isBlank() ? null : name.trim();
        if (cleanName == null) {
            throw new IllegalArgumentException("공간 이름은 필수입니다.");
        }
        String cleanDescription = description == null ? "" : description.trim();
        securityRepository.updateSpace(spaceId, cleanName, cleanDescription);
        securityRepository.createAuditLog(actor.id(), "SPACE_UPDATED", "SPACE", spaceId.toString(), spaceId, "Space was updated.", java.util.Map.of("name", cleanName));
    }

    public void deleteSpace(AppUser actor, UUID spaceId) {
        requireMaster(actor);
        if (SecurityRepository.DEFAULT_SPACE_ID.equals(spaceId)) {
            throw new IllegalArgumentException("기본 공간은 삭제할 수 없습니다.");
        }
        SpaceSummary target = securityRepository.findSpace(spaceId)
                .orElseThrow(() -> new IllegalArgumentException("공간을 찾을 수 없습니다."));
        securityRepository.deleteSpace(spaceId);
        securityRepository.createAuditLog(actor.id(), "SPACE_DELETED", "SPACE", spaceId.toString(), spaceId, "Space was deleted.", java.util.Map.of("name", target.name()));
    }

    public void addSpaceMember(AppUser actor, UUID spaceId, UUID userId, String role) {
        requireAdmin(actor);
        AppUser target = activeUser(userId);
        requireManageableUser(actor, target);
        requireManageableSpace(actor, spaceId);
        securityRepository.findSpace(spaceId)
                .orElseThrow(() -> new IllegalArgumentException("공간을 찾을 수 없습니다."));
        securityRepository.addSpaceMember(spaceId, userId, normalizeSpaceRole(role));
        securityRepository.createAuditLog(actor.id(), "SPACE_MEMBER_UPDATED", "SPACE", spaceId.toString(), spaceId, "Space member was updated.", Map.of("userId", userId.toString()));
    }

    public void updateUserSpaceRole(AppUser actor, UUID userId, UUID spaceId, String role) {
        requireAdmin(actor);
        AppUser target = activeUser(userId);
        requireManageableUser(actor, target);
        requireManageableSpace(actor, spaceId);
        securityRepository.findSpace(spaceId)
                .orElseThrow(() -> new IllegalArgumentException("공간을 찾을 수 없습니다."));
        String cleanRole = normalizeSpaceRole(role);
        String oldRole = securityRepository.findSpaceMemberRole(spaceId, userId).orElse("");
        securityRepository.addSpaceMember(spaceId, userId, cleanRole);
        securityRepository.createAuditLog(
                actor.id(),
                "USER_SPACE_ROLE_UPDATED",
                "SPACE",
                spaceId.toString(),
                spaceId,
                "User space role was updated.",
                Map.of(
                        "userId", userId.toString(),
                        "loginId", target.email(),
                        "oldRole", oldRole,
                        "newRole", cleanRole
                )
        );
    }

    public void removeSpaceMember(AppUser actor, UUID spaceId, UUID userId) {
        requireAdmin(actor);
        AppUser target = activeUser(userId);
        requireManageableUser(actor, target);
        requireManageableSpace(actor, spaceId);
        securityRepository.findSpace(spaceId)
                .orElseThrow(() -> new IllegalArgumentException("공간을 찾을 수 없습니다."));
        String oldRole = securityRepository.findSpaceMemberRole(spaceId, userId).orElse(null);
        if (oldRole == null) {
            return;
        }
        if (target.isMaster()) {
            throw new IllegalArgumentException("MASTER workspace assignment cannot be removed.");
        }
        if (securityRepository.countSpaceMemberships(userId) <= 1) {
            throw new IllegalArgumentException("사용자의 마지막 공간 권한은 제거할 수 없습니다.");
        }
        securityRepository.removeSpaceMember(spaceId, userId);
        securityRepository.createAuditLog(
                actor.id(),
                "USER_SPACE_ROLE_REMOVED",
                "SPACE",
                spaceId.toString(),
                spaceId,
                "User space role was removed.",
                Map.of(
                        "userId", userId.toString(),
                        "loginId", target.email(),
                        "oldRole", oldRole
                )
        );
    }

    public List<SpaceSummary> listSpaces(AppUser user) {
        return securityRepository.listSpacesForUser(user);
    }

    public List<SpaceSummary> listAllSpaces(AppUser user) {
        requireAdmin(user);
        return user.isMaster() ? securityRepository.listAllSpaces() : securityRepository.listSpacesForUser(user);
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

    private UUID resolveAdminSpace(UUID requestedSpaceId) {
        UUID resolvedSpaceId = requestedSpaceId == null ? SecurityRepository.DEFAULT_SPACE_ID : requestedSpaceId;
        securityRepository.findSpace(resolvedSpaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace was not found."));
        return resolvedSpaceId;
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

    public void requireMaster(AppUser user) {
        if (user == null || !user.isMaster()) {
            throw new ForbiddenException("MASTER permission is required.");
        }
    }

    public void requireAdminSpace(AppUser user, UUID spaceId) {
        requireAdmin(user);
        requireManageableSpace(user, spaceId);
    }

    public UserSummary toSummary(AppUser user) {
        return new UserSummary(user.id(), user.email(), user.displayName(), user.role(), user.status());
    }

    private AppUser activeUser(UUID userId) {
        AppUser target = securityRepository.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if ("DELETED".equals(target.status())) {
            throw new IllegalArgumentException("삭제된 사용자는 수정할 수 없습니다.");
        }
        return target;
    }

    private String normalizeManagedUserRole(String role, boolean allowMaster) {
        String clean = role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
        if (allowMaster && clean.equals("MASTER")) {
            return "MASTER";
        }
        return clean.equals("ADMIN") ? "ADMIN" : "USER";
    }

    private String normalizeSpaceRole(String role) {
        String clean = role == null || role.isBlank() ? "MEMBER" : role.trim().toUpperCase(Locale.ROOT);
        return clean.equals("OWNER") ? "OWNER" : "MEMBER";
    }

    private String cleanLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("ID is required.");
        }
        return loginId.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureConfiguredMaster() {
        String loginId = masterLoginId();
        securityRepository.findUserByEmail(loginId)
                .filter(user -> "ACTIVE".equals(user.status()))
                .filter(user -> !user.isMaster())
                .ifPresent(user -> securityRepository.updateUser(user.id(), user.email(), user.displayName(), "MASTER"));
    }

    private void requireManageableUser(AppUser actor, AppUser target) {
        if (actor.isMaster()) {
            return;
        }
        if (!"USER".equals(target.role())) {
            throw new ForbiddenException("Only lower-permission users can be managed.");
        }
        if (!securityRepository.usersShareActiveSpace(actor.id(), target.id())) {
            throw new ForbiddenException("User is outside the administrator's assigned workspace.");
        }
    }

    private void requireManageableSpace(AppUser actor, UUID spaceId) {
        if (actor.isMaster()) {
            return;
        }
        if (!securityRepository.userHasActiveSpace(actor.id(), spaceId)) {
            throw new ForbiddenException("Workspace is outside the administrator's assigned scope.");
        }
    }

    private String masterLoginId() {
        return cleanLoginId(properties.getAuth().getMasterLoginId());
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
