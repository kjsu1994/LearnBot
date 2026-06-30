package com.learnbot.web;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLocalPatchRequest;
import com.learnbot.dto.CodeAgentLoopPreviewRequest;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.AppUser;
import com.learnbot.service.CodeAgentApplyService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodeIndexingService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentControllerTest {
    @Test
    void mutationPolicyDefaultsToUserLocalAgentBoundaryWithoutEnablingMutationTools() {
        LearnBotProperties properties = new LearnBotProperties();
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                mock(CodeAgentLoopPreviewService.class),
                mock(CodeIndexingService.class),
                mock(AuthService.class),
                mock(CurrentUserProvider.class),
                properties
        );

        var policy = controller.mutationPolicy();

        assertThat(policy.intendedExecutionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(policy.localAgentMutationEnabled()).isFalse();
        assertThat(policy.serverLocalMutationEnabled()).isFalse();
        assertThat(policy.futureLocalAgentTools()).containsExactly(
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolName.COMMAND_RUN_ALLOWED,
                LocalAgentToolName.ROLLBACK_RESTORE
        );
        assertThat(policy.message()).contains("Patch proposals are available");
    }

    @Test
    void loopPreviewResolvesRepositorySpaceAndDelegatesWithoutStartingMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);

        controller.loopPreview(new CodeAgentLoopPreviewRequest(
                repositoryId,
                requestedSpaceId,
                "fix this bug",
                7
        ));

        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).preview(userId, repositoryId, repositorySpaceId, "fix this bug", 7);
    }

    @Test
    void loopTimelinesResolveRepositorySpaceAndReturnAuditOnlyHistory() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                loopPreviewService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        List<CodeAgentLoopTimelineSummary> expected = List.of();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopPreviewService.recentTimelines(userId, repositoryId, 3)).thenReturn(expected);

        var result = controller.loopTimelines(repositoryId, 3);

        assertThat(result).isSameAs(expected);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopPreviewService).recentTimelines(userId, repositoryId, 3);
    }

    @Test
    void localPatchRequestCarriesLoopIdIntoPreparedApprovalRequest() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                mock(CodeAgentLoopPreviewService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(authService.resolveSpace(user, requestedSpaceId)).thenReturn(requestedSpaceId);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);

        controller.localPatchRequest(new CodeAgentLocalPatchRequest(
                repositoryId,
                requestedSpaceId,
                loopId,
                agentId,
                workspaceId,
                "fix this bug",
                "--- a/src/App.java\n+++ b/src/App.java\n",
                List.of("src/App.java")
        ));

        verify(authService).requireSpace(user, repositorySpaceId);
        verify(localPatchRequestService).prepare(
                repositoryId,
                repositorySpaceId,
                userId,
                agentId,
                workspaceId,
                loopId,
                "fix this bug",
                "--- a/src/App.java\n+++ b/src/App.java\n",
                List.of("src/App.java")
        );
    }
}
