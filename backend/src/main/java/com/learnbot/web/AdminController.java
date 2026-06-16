package com.learnbot.web;

import com.learnbot.dto.AuditLogSummary;
import com.learnbot.dto.AdminSettingsResponse;
import com.learnbot.dto.AdminSettingsUpdateRequest;
import com.learnbot.dto.InviteUserRequest;
import com.learnbot.dto.SpaceCreateRequest;
import com.learnbot.dto.SpaceMemberRequest;
import com.learnbot.dto.SpaceSummary;
import com.learnbot.dto.SpaceUpdateRequest;
import com.learnbot.dto.UserSummary;
import com.learnbot.repository.SecurityRepository;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AdminSettingsService;
import com.learnbot.service.AppUser;
import com.learnbot.service.AuditService;
import com.learnbot.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService authService;
    private final AuditService auditService;
    private final AdminSettingsService adminSettingsService;
    private final SecurityRepository securityRepository;
    private final CurrentUserProvider currentUserProvider;

    public AdminController(
            AuthService authService,
            AuditService auditService,
            AdminSettingsService adminSettingsService,
            SecurityRepository securityRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.authService = authService;
        this.auditService = auditService;
        this.adminSettingsService = adminSettingsService;
        this.securityRepository = securityRepository;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/users")
    List<UserSummary> users() {
        authService.requireAdmin(currentUserProvider.currentUser());
        return securityRepository.listUsers();
    }

    @PostMapping("/users")
    UserSummary invite(@Valid @RequestBody InviteUserRequest request) {
        AppUser actor = currentUserProvider.currentUser();
        AppUser user = authService.inviteUser(
                actor,
                request.identifier(),
                request.displayName(),
                request.initialPassword(),
                request.role(),
                request.spaceId(),
                request.spaceRole()
        );
        return authService.toSummary(user);
    }

    @DeleteMapping("/users/{userId}")
    void deleteUser(@PathVariable UUID userId) {
        authService.deleteUser(currentUserProvider.currentUser(), userId);
    }

    @GetMapping("/spaces")
    List<SpaceSummary> spaces() {
        AppUser user = currentUserProvider.currentUser();
        authService.requireAdmin(user);
        return authService.listSpaces(user);
    }

    @PostMapping("/spaces")
    Map<String, UUID> createSpace(@Valid @RequestBody SpaceCreateRequest request) {
        UUID id = authService.createSpace(currentUserProvider.currentUser(), request.name(), request.description());
        return Map.of("id", id);
    }

    @PatchMapping("/spaces/{spaceId}")
    void updateSpace(@PathVariable UUID spaceId, @Valid @RequestBody SpaceUpdateRequest request) {
        authService.updateSpace(currentUserProvider.currentUser(), spaceId, request.name(), request.description());
    }

    @DeleteMapping("/spaces/{spaceId}")
    void deleteSpace(@PathVariable UUID spaceId) {
        authService.deleteSpace(currentUserProvider.currentUser(), spaceId);
    }

    @PostMapping("/spaces/{spaceId}/members")
    void addMember(@PathVariable UUID spaceId, @Valid @RequestBody SpaceMemberRequest request) {
        authService.addSpaceMember(currentUserProvider.currentUser(), spaceId, request.userId(), request.role());
    }

    @GetMapping("/audit-logs")
    List<AuditLogSummary> auditLogs(@RequestParam(required = false) Integer limit) {
        authService.requireAdmin(currentUserProvider.currentUser());
        return auditService.list(limit);
    }

    @GetMapping("/settings")
    AdminSettingsResponse settings() {
        authService.requireAdmin(currentUserProvider.currentUser());
        return adminSettingsService.current();
    }

    @PatchMapping("/settings")
    AdminSettingsResponse updateSettings(@Valid @RequestBody AdminSettingsUpdateRequest request) {
        AppUser user = currentUserProvider.currentUser();
        authService.requireAdmin(user);
        return adminSettingsService.update(user, request.respectRobotsTxt(), request.allowedDomains());
    }
}
