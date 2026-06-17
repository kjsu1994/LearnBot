package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.SpaceSummary;
import com.learnbot.repository.SecurityRepository;
import com.learnbot.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private final SecurityRepository repository = mock(SecurityRepository.class);
    private final AuthService service = new AuthService(repository, new PasswordHasher(), new LearnBotProperties());

    @Test
    void preventsCurrentAdminFromChangingOwnSystemRole() {
        UUID adminId = UUID.randomUUID();
        AppUser admin = user(adminId, "admin@example.com", "ADMIN");
        when(repository.findUserById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.updateUser(admin, adminId, admin.email(), "Admin", "USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시스템 권한");

        verify(repository, never()).updateUser(eq(adminId), anyString(), anyString(), anyString());
    }

    @Test
    void preventsLastAdminFromBeingDowngraded() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AppUser actor = user(actorId, "actor@example.com", "ADMIN");
        AppUser target = user(targetId, "target@example.com", "ADMIN");
        when(repository.findUserById(targetId)).thenReturn(Optional.of(target));
        when(repository.countActiveAdmins()).thenReturn(1);

        assertThatThrownBy(() -> service.updateUser(actor, targetId, target.email(), "Target", "USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("마지막 관리자");

        verify(repository, never()).updateUser(eq(targetId), anyString(), anyString(), anyString());
    }

    @Test
    void resetPasswordUpdatesHashAndRevokesSessions() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AppUser actor = user(actorId, "actor@example.com", "ADMIN");
        AppUser target = user(targetId, "target@example.com", "USER");
        when(repository.findUserById(targetId)).thenReturn(Optional.of(target));

        service.resetUserPassword(actor, targetId, "temporary-password");

        verify(repository).updatePasswordHash(eq(targetId), anyString());
        verify(repository).revokeSessionsForUser(targetId);
        verify(repository).createAuditLog(eq(actorId), eq("USER_PASSWORD_RESET"), eq("USER"), eq(targetId.toString()), eq(null), anyString(), eq(Map.of("loginId", target.email())));
    }

    @Test
    void preventsRemovingLastSpaceFromRegularUser() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        AppUser actor = user(actorId, "actor@example.com", "ADMIN");
        AppUser target = user(targetId, "target@example.com", "USER");
        when(repository.findUserById(targetId)).thenReturn(Optional.of(target));
        when(repository.canAccessSpace(actor, spaceId)).thenReturn(true);
        when(repository.findSpace(spaceId)).thenReturn(Optional.of(new SpaceSummary(spaceId, "Space", "", "OWNER", OffsetDateTime.now())));
        when(repository.findSpaceMemberRole(spaceId, targetId)).thenReturn(Optional.of("MEMBER"));
        when(repository.countSpaceMemberships(targetId)).thenReturn(1);

        assertThatThrownBy(() -> service.removeSpaceMember(actor, spaceId, targetId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("마지막 공간 권한");

        verify(repository, never()).removeSpaceMember(spaceId, targetId);
    }

    @Test
    void changingUserLoginIdRevokesExistingSessions() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AppUser actor = user(actorId, "actor@example.com", "ADMIN");
        AppUser target = user(targetId, "target@example.com", "USER");
        when(repository.findUserById(targetId)).thenReturn(Optional.of(target));
        when(repository.findUserByEmail("renamed@example.com")).thenReturn(Optional.empty());

        service.updateUser(actor, targetId, "renamed@example.com", "Target", "USER");

        verify(repository).updateUser(targetId, "renamed@example.com", "Target", "USER");
        verify(repository).revokeSessionsForUser(targetId);
    }

    @Test
    void preventsDuplicateLoginIdWhenUpdatingUser() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        AppUser actor = user(actorId, "actor@example.com", "ADMIN");
        AppUser target = user(targetId, "target@example.com", "USER");
        AppUser existing = user(existingId, "taken@example.com", "USER");
        when(repository.findUserById(targetId)).thenReturn(Optional.of(target));
        when(repository.findUserByEmail("taken@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateUser(actor, targetId, "taken@example.com", "Target", "USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");

        verify(repository, never()).updateUser(eq(targetId), anyString(), anyString(), anyString());
    }

    @Test
    void preventsChangingSpaceRoleForAdminUser() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        AppUser actor = user(actorId, "actor@example.com", "ADMIN");
        AppUser target = user(targetId, "admin@example.com", "ADMIN");
        when(repository.findUserById(targetId)).thenReturn(Optional.of(target));
        when(repository.canAccessSpace(actor, spaceId)).thenReturn(true);

        assertThatThrownBy(() -> service.updateUserSpaceRole(actor, targetId, spaceId, "MEMBER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("공간");

        verify(repository, never()).addSpaceMember(eq(spaceId), eq(targetId), anyString());
    }

    private AppUser user(UUID id, String email, String role) {
        return new AppUser(id, email, email, role, "ACTIVE");
    }
}
