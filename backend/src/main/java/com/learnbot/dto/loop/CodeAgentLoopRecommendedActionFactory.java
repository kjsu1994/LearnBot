package com.learnbot.dto.loop;

import java.util.Map;

public final class CodeAgentLoopRecommendedActionFactory {
    public static final String SCHEMA = "learnbot.code-agent.runner-recommended-action.v1";

    private CodeAgentLoopRecommendedActionFactory() {
    }

    public static Map<String, Object> create(String actionKey) {
        return createModel(actionKey).toMap();
    }

    public static CodeAgentLoopRecommendedAction createModel(String actionKey) {
        return new CodeAgentLoopRecommendedAction(
                SCHEMA,
                actionKey,
                label(actionKey),
                enabled(actionKey),
                method(actionKey),
                endpoint(actionKey),
                false,
                false,
                false,
                false,
                reason(actionKey)
        );
    }

    private static String label(String actionKey) {
        return switch (actionKey) {
            case "QUEUE_SELECTED_READ_ONLY" -> "Queue read-only step";
            case "PREVIEW_RUNNER_STEP" -> "Preview runner step";
            case "REVIEW_RELEASE_REFUSAL" -> "Review release refusal";
            case "CHECK_ENQUEUE_REFUSAL" -> "Check enqueue refusal";
            case "SELECT_LOCAL_AGENT_WORKSPACE" -> "Select Local Agent workspace";
            case "STOP_AND_REPORT" -> "Stop and report";
            default -> "Ask user";
        };
    }

    private static boolean enabled(String actionKey) {
        return "QUEUE_SELECTED_READ_ONLY".equals(actionKey)
                || "PREVIEW_RUNNER_STEP".equals(actionKey)
                || "REVIEW_RELEASE_REFUSAL".equals(actionKey)
                || "CHECK_ENQUEUE_REFUSAL".equals(actionKey);
    }

    private static String method(String actionKey) {
        return enabled(actionKey) ? "POST" : "";
    }

    private static String endpoint(String actionKey) {
        return switch (actionKey) {
            case "QUEUE_SELECTED_READ_ONLY" -> "/api/code-agent/loop/runner/enqueue-selected-read-only";
            case "PREVIEW_RUNNER_STEP" -> "/api/code-agent/loop/runner/preview";
            case "REVIEW_RELEASE_REFUSAL" -> "/api/code-agent/loop/runner/release-review";
            case "CHECK_ENQUEUE_REFUSAL" -> "/api/code-agent/loop/runner/enqueue-read-only";
            default -> "";
        };
    }

    private static String reason(String actionKey) {
        return switch (actionKey) {
            case "QUEUE_SELECTED_READ_ONLY" -> "Queue only the prepared read-only git.status observation; mutation remains disabled.";
            case "PREVIEW_RUNNER_STEP" -> "Ask the runner to prepare the next safe Local Agent candidate without creating mutation work.";
            case "REVIEW_RELEASE_REFUSAL" -> "Inspect the disabled release boundary before reporting that the held patch remains non-claimable.";
            case "CHECK_ENQUEUE_REFUSAL" -> "Confirm the runner will not enqueue mutation work from this handoff state.";
            case "SELECT_LOCAL_AGENT_WORKSPACE" -> "Choose a connected Local Agent and approved workspace before preparing a read-only candidate.";
            case "STOP_AND_REPORT" -> "Stop the loop and report the blocking state without creating Local Agent work.";
            default -> "Ask the user for the next bounded code-agent goal.";
        };
    }
}
