package com.learnbot.web;

import com.learnbot.dto.AuditLogSummary;
import com.learnbot.dto.InviteUserRequest;
import com.learnbot.dto.SpaceCreateRequest;
import com.learnbot.dto.SpaceMemberRequest;
import com.learnbot.dto.SpaceSummary;
import com.learnbot.dto.UserSummary;
import com.learnbot.repository.SecurityRepository;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AppUser;
import com.learnbot.service.AuditService;
import com.learnbot.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final SecurityRepository securityRepository;
    private final CurrentUserProvider currentUserProvider;

    public AdminController(
            AuthService authService,
            AuditService auditService,
            SecurityRepository securityRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.authService = authService;
        this.auditService = auditService;
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
                request.email(),
                request.displayName(),
                request.initialPassword(),
                request.role(),
                request.spaceId(),
                request.spaceRole()
        );
        return authService.toSummary(user);
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

    @PostMapping("/spaces/{spaceId}/members")
    void addMember(@PathVariable UUID spaceId, @Valid @RequestBody SpaceMemberRequest request) {
        authService.addSpaceMember(currentUserProvider.currentUser(), spaceId, request.userId(), request.role());
    }

    @GetMapping("/audit-logs")
    List<AuditLogSummary> auditLogs(@RequestParam(required = false) Integer limit) {
        authService.requireAdmin(currentUserProvider.currentUser());
        return auditService.list(limit);
    }
}
