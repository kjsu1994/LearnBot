package com.learnbot.web;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeAgentApplyService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodeIndexingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CodeAgentControllerTest {
    @Test
    void mutationPolicyDefaultsToUserLocalAgentBoundaryWithoutEnablingMutationTools() {
        LearnBotProperties properties = new LearnBotProperties();
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
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
}
