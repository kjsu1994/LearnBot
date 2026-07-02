package com.learnbot.dto.loop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAgentLoopRecommendedActionFactoryTest {

    @Test
    void createsEnabledReadOnlyRunnerActionsWithoutOpeningMutationControls() {
        assertAction(
                "QUEUE_SELECTED_READ_ONLY",
                "Queue read-only step",
                true,
                "POST",
                "/api/code-agent/loop/runner/enqueue-selected-read-only",
                "Queue only the prepared read-only git.status observation; mutation remains disabled."
        );
        assertAction(
                "PREVIEW_RUNNER_STEP",
                "Preview runner step",
                true,
                "POST",
                "/api/code-agent/loop/runner/preview",
                "Ask the runner to prepare the next safe Local Agent candidate without creating mutation work."
        );
        assertAction(
                "CHECK_ENQUEUE_REFUSAL",
                "Check enqueue refusal",
                true,
                "POST",
                "/api/code-agent/loop/runner/enqueue-read-only",
                "Confirm the runner will not enqueue mutation work from this handoff state."
        );
        assertAction(
                "REVIEW_RELEASE_REFUSAL",
                "Review release refusal",
                true,
                "POST",
                "/api/code-agent/loop/runner/release-review",
                "Inspect the disabled release boundary before reporting that the held patch remains non-claimable."
        );
    }

    @Test
    void createsDisabledNonEndpointActionsWithoutOpeningMutationControls() {
        assertAction(
                "SELECT_LOCAL_AGENT_WORKSPACE",
                "Select Local Agent workspace",
                false,
                "",
                "",
                "Choose a connected Local Agent and approved workspace before preparing a read-only candidate."
        );
        assertAction(
                "STOP_AND_REPORT",
                "Stop and report",
                false,
                "",
                "",
                "Stop the loop and report the blocking state without creating Local Agent work."
        );
        assertAction(
                "ASK_USER",
                "Ask user",
                false,
                "",
                "",
                "Ask the user for the next bounded code-agent goal."
        );
    }

    private void assertAction(
            String actionKey,
            String label,
            boolean enabled,
            String method,
            String endpoint,
            String reason
    ) {
        CodeAgentLoopRecommendedAction model = CodeAgentLoopRecommendedActionFactory.createModel(actionKey);
        assertThat(model.schema()).isEqualTo(CodeAgentLoopRecommendedActionFactory.SCHEMA);
        assertThat(model.actionKey()).isEqualTo(actionKey);
        assertThat(model.label()).isEqualTo(label);
        assertThat(model.enabled()).isEqualTo(enabled);
        assertThat(model.method()).isEqualTo(method);
        assertThat(model.endpoint()).isEqualTo(endpoint);
        assertThat(model.requestCreationEnabled()).isFalse();
        assertThat(model.pushEnabled()).isFalse();
        assertThat(model.claimEnabled()).isFalse();
        assertThat(model.mutationEnabled()).isFalse();
        assertThat(model.reason()).isEqualTo(reason);

        Map<String, Object> action = CodeAgentLoopRecommendedActionFactory.create(actionKey);
        assertThat(action).isEqualTo(model.toMap());
        assertThat(action)
                .containsEntry("schema", CodeAgentLoopRecommendedActionFactory.SCHEMA)
                .containsEntry("actionKey", actionKey)
                .containsEntry("label", label)
                .containsEntry("enabled", enabled)
                .containsEntry("method", method)
                .containsEntry("endpoint", endpoint)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("mutationEnabled", false)
                .containsEntry("reason", reason);
    }
}
