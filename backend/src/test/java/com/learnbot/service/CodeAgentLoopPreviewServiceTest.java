package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CodeAgentLoopPreviewServiceTest {
    private final CodeAgentLoopTimelineRepository timelineRepository = mock(CodeAgentLoopTimelineRepository.class);
    private final CodeAgentLoopPreviewService service = new CodeAgentLoopPreviewService(timelineRepository);

    @Test
    void previewIsBoundedReadOnlyAndStopsBeforeMutation() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();

        var preview = service.preview(userId, repositoryId, spaceId, "fix the failing parser test", 99);

        assertThat(preview.repositoryId()).isEqualTo(repositoryId);
        assertThat(preview.spaceId()).isEqualTo(spaceId);
        assertThat(preview.status()).isEqualTo("PREVIEW_ONLY");
        assertThat(preview.maxSteps()).isEqualTo(8);
        assertThat(preview.timeoutSeconds()).isEqualTo(120);
        assertThat(preview.cancellationEnabled()).isFalse();
        assertThat(preview.timelinePersistenceEnabled()).isTrue();
        assertThat(preview.mutationEnabled()).isFalse();
        assertThat(preview.steps()).hasSize(5);
        assertThat(preview.steps()).allSatisfy(step -> {
            assertThat(step.mayMutate()).isFalse();
            assertThat(step.enabled()).isTrue();
        });
        assertThat(preview.steps())
                .extracting("phase")
                .containsExactly("PLAN", "SELECT_TOOL", "REQUEST_APPROVAL", "OBSERVE", "COMPLETE_OR_PAUSE");
        assertThat(preview.steps().get(2).executionTarget()).isEqualTo(AgentExecutionTarget.USER_LOCAL_AGENT);
        assertThat(preview.steps().get(2).toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(preview.steps().get(2).requiresApproval()).isTrue();
        assertThat(preview.stopConditions())
                .extracting("key")
                .contains("MAX_STEPS", "TIMEOUT", "WEAK_EVIDENCE", "APPROVAL_REQUIRED", "AGENT_UNAVAILABLE", "TOOL_FAILED", "MUTATION_DISABLED");
        assertThat(preview.warnings()).allSatisfy(warning ->
                assertThat(warning).doesNotContain("enabled")
        );
        verify(timelineRepository).createPreview(userId, "fix the failing parser test", preview);
    }

    @Test
    void previewKeepsMinimumStepBudgetForDecisionAndObservation() {
        var preview = service.preview(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "inspect only", 1);

        assertThat(preview.maxSteps()).isEqualTo(4);
        assertThat(preview.steps())
                .extracting("phase")
                .contains("PLAN", "OBSERVE", "COMPLETE_OR_PAUSE");
    }

    @Test
    void recentTimelinesClampReadOnlyHistoryLimit() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        service.recentTimelines(userId, repositoryId, 99);

        verify(timelineRepository).findRecent(userId, repositoryId, 20);
    }
}
