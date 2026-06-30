package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopStopCondition;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class CodeAgentLoopPreviewService {
    private static final int DEFAULT_MAX_STEPS = 6;
    private static final int MIN_MAX_STEPS = 4;
    private static final int HARD_MAX_STEPS = 8;
    private static final int TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_RECENT_TIMELINES = 5;
    private static final int HARD_MAX_RECENT_TIMELINES = 20;

    private final CodeAgentLoopTimelineRepository timelineRepository;

    public CodeAgentLoopPreviewService(CodeAgentLoopTimelineRepository timelineRepository) {
        this.timelineRepository = timelineRepository;
    }

    public CodeAgentLoopPreviewResponse preview(UUID userId, UUID repositoryId, UUID spaceId, String instruction, Integer requestedMaxSteps) {
        int maxSteps = boundedMaxSteps(requestedMaxSteps);
        CodeAgentLoopPreviewResponse preview = new CodeAgentLoopPreviewResponse(
                UUID.randomUUID(),
                repositoryId,
                spaceId,
                "PREVIEW_ONLY",
                maxSteps,
                TIMEOUT_SECONDS,
                false,
                true,
                false,
                steps(),
                stopConditions(),
                warnings(instruction)
        );
        timelineRepository.createPreview(userId, instruction, preview);
        return preview;
    }

    public List<CodeAgentLoopTimelineSummary> recentTimelines(UUID userId, UUID repositoryId, Integer requestedLimit) {
        int limit = requestedLimit == null
                ? DEFAULT_RECENT_TIMELINES
                : Math.max(1, Math.min(HARD_MAX_RECENT_TIMELINES, requestedLimit));
        return timelineRepository.findRecent(userId, repositoryId, limit);
    }

    private int boundedMaxSteps(Integer requestedMaxSteps) {
        if (requestedMaxSteps == null) {
            return DEFAULT_MAX_STEPS;
        }
        return Math.max(MIN_MAX_STEPS, Math.min(HARD_MAX_STEPS, requestedMaxSteps));
    }

    private List<CodeAgentLoopStep> steps() {
        return List.of(
                new CodeAgentLoopStep(
                        1,
                        "PLAN",
                        "Retrieve code evidence and form a bounded repair plan.",
                        AgentExecutionTarget.SERVER_LOCAL,
                        null,
                        false,
                        false,
                        true,
                        "Stop and ask for clarification when evidence is weak or the target is ambiguous."
                ),
                new CodeAgentLoopStep(
                        2,
                        "SELECT_TOOL",
                        "Select the next typed tool from the approved Local Agent protocol.",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        null,
                        false,
                        false,
                        true,
                        "Stop when the requested tool is unavailable, unsafe, or outside the approved workspace."
                ),
                new CodeAgentLoopStep(
                        3,
                        "REQUEST_APPROVAL",
                        "Require explicit user approval before any side-effectful tool can run.",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.PATCH_APPLY,
                        true,
                        false,
                        true,
                        "Stop on approval denial, missing agent, disconnected agent, or unapproved workspace."
                ),
                new CodeAgentLoopStep(
                        4,
                        "OBSERVE",
                        "Consume non-mutating Local Agent observations such as repository status and patch dry-run output.",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        null,
                        false,
                        false,
                        true,
                        "Stop when observations report context mismatch, failed preflight, or stale evidence."
                ),
                new CodeAgentLoopStep(
                        5,
                        "COMPLETE_OR_PAUSE",
                        "Produce the next user-visible decision: ask, wait for approval, or report why mutation remains disabled.",
                        AgentExecutionTarget.SERVER_LOCAL,
                        null,
                        false,
                        false,
                        true,
                        "Stop before real patch apply, test execution, rollback restore, or final mutation publication."
                )
        );
    }

    private List<CodeAgentLoopStopCondition> stopConditions() {
        return List.of(
                new CodeAgentLoopStopCondition("MAX_STEPS", "Stop when the bounded step count is reached."),
                new CodeAgentLoopStopCondition("TIMEOUT", "Stop when the loop timeout is reached."),
                new CodeAgentLoopStopCondition("WEAK_EVIDENCE", "Ask for clarification instead of making risky changes."),
                new CodeAgentLoopStopCondition("APPROVAL_REQUIRED", "Pause before side-effectful Local Agent tools."),
                new CodeAgentLoopStopCondition("AGENT_UNAVAILABLE", "Stop when the selected Local Agent is disconnected or missing."),
                new CodeAgentLoopStopCondition("TOOL_FAILED", "Stop when a tool observation reports failure or unsafe state."),
                new CodeAgentLoopStopCondition("MUTATION_DISABLED", "Do not apply patches, run tests, restore rollback, update RAG freshness, or publish a mutation result in this preview slice.")
        );
    }

    private List<String> warnings(String instruction) {
        String normalizedInstruction = instruction == null ? "" : instruction.trim();
        return List.of(
                "Agent loop preview is read-only and does not create, push, claim, release, or execute Local Agent mutation requests.",
                normalizedInstruction.isBlank()
                        ? "No instruction text was available for this preview."
                        : "Instruction is used only to scope the preview; no model call or tool execution is started."
        );
    }
}
