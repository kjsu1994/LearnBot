package com.learnbot.service.agentloop;

import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class CodeAgentLoopStateMachine {
    private final CodeAgentLoopEventMapper eventMapper;
    private final Map<CodeAgentLoopState, List<CodeAgentLoopTransition>> transitions;

    public CodeAgentLoopStateMachine() {
        this(new CodeAgentLoopEventMapper());
    }

    public CodeAgentLoopStateMachine(CodeAgentLoopEventMapper eventMapper) {
        this.eventMapper = eventMapper == null ? new CodeAgentLoopEventMapper() : eventMapper;
        this.transitions = Map.ofEntries(
                Map.entry(CodeAgentLoopState.CREATED, List.of(
                        transition(CodeAgentLoopState.CREATED, CodeAgentLoopEvent.READ_ONLY_REQUEST_QUEUED, CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, false, false, "Read-only Local Agent work was queued."),
                        transition(CodeAgentLoopState.CREATED, CodeAgentLoopEvent.LOCAL_AGENT_RESULT_SUCCEEDED, CodeAgentLoopState.OBSERVATION_RECEIVED, false, false, "A Local Agent observation was received."),
                        transition(CodeAgentLoopState.CREATED, CodeAgentLoopEvent.NEXT_DECISION_RECORDED, CodeAgentLoopState.OBSERVATION_RECEIVED, false, false, "Server recorded a loop decision from prior observation context."),
                        transition(CodeAgentLoopState.CREATED, CodeAgentLoopEvent.PATCH_APPROVAL_CREATED, CodeAgentLoopState.WAITING_FOR_APPROVAL, false, true, "Patch approval was created.")
                )),
                Map.entry(CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, List.of(
                        transition(CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, CodeAgentLoopEvent.LOCAL_AGENT_RESULT_SUCCEEDED, CodeAgentLoopState.OBSERVATION_RECEIVED, false, false, "Local Agent read-only observation succeeded."),
                        transition(CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, CodeAgentLoopEvent.LOCAL_AGENT_RESULT_FAILED, CodeAgentLoopState.FAILED, false, false, "Local Agent observation failed."),
                        transition(CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, CodeAgentLoopEvent.LOCAL_AGENT_RESULT_REJECTED, CodeAgentLoopState.FAILED, false, false, "Local Agent observation was rejected."),
                        transition(CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, CodeAgentLoopEvent.PATCH_APPLIED, CodeAgentLoopState.VERIFYING_GIT_STATUS, true, false, "Approved patch was applied.")
                )),
                Map.entry(CodeAgentLoopState.OBSERVATION_RECEIVED, List.of(
                        transition(CodeAgentLoopState.OBSERVATION_RECEIVED, CodeAgentLoopEvent.READ_ONLY_REQUEST_QUEUED, CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT, false, false, "Another read-only observation was queued."),
                        transition(CodeAgentLoopState.OBSERVATION_RECEIVED, CodeAgentLoopEvent.NEXT_DECISION_RECORDED, CodeAgentLoopState.OBSERVATION_RECEIVED, false, false, "Server recorded the next loop decision."),
                        transition(CodeAgentLoopState.OBSERVATION_RECEIVED, CodeAgentLoopEvent.PATCH_APPROVAL_CREATED, CodeAgentLoopState.WAITING_FOR_APPROVAL, false, true, "Patch approval was created from observations."),
                        transition(CodeAgentLoopState.OBSERVATION_RECEIVED, CodeAgentLoopEvent.STOP_RECORDED, CodeAgentLoopState.STOPPED, false, false, "Loop stopped after observation.")
                )),
                Map.entry(CodeAgentLoopState.WAITING_FOR_APPROVAL, List.of(
                        transition(CodeAgentLoopState.WAITING_FOR_APPROVAL, CodeAgentLoopEvent.APPROVED, CodeAgentLoopState.APPROVED_HELD, false, true, "User approved the patch and it is held for release checks."),
                        transition(CodeAgentLoopState.WAITING_FOR_APPROVAL, CodeAgentLoopEvent.DENIED, CodeAgentLoopState.STOPPED, false, false, "User denied the patch approval request.")
                )),
                Map.entry(CodeAgentLoopState.APPROVED_HELD, List.of(
                        transition(CodeAgentLoopState.APPROVED_HELD, CodeAgentLoopEvent.RELEASE_FRESH_OBSERVATIONS_QUEUED, CodeAgentLoopState.WAITING_FOR_RELEASE_GATE, false, false, "Fresh release precheck observations were queued."),
                        transition(CodeAgentLoopState.APPROVED_HELD, CodeAgentLoopEvent.RELEASE_BOUNDARY_REFUSED, CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, false, false, "Release boundary refused mutation."),
                        transition(CodeAgentLoopState.APPROVED_HELD, CodeAgentLoopEvent.RELEASED, CodeAgentLoopState.RELEASED_FOR_EXECUTION, true, false, "Release handoff is ready for execution.")
                )),
                Map.entry(CodeAgentLoopState.WAITING_FOR_RELEASE_GATE, List.of(
                        transition(CodeAgentLoopState.WAITING_FOR_RELEASE_GATE, CodeAgentLoopEvent.RELEASE_FRESH_OBSERVATIONS_COMPLETE, CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, false, false, "Fresh release evidence completed."),
                        transition(CodeAgentLoopState.WAITING_FOR_RELEASE_GATE, CodeAgentLoopEvent.RELEASE_READINESS_REFRESHED, CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, false, false, "Release readiness was refreshed.")
                )),
                Map.entry(CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, List.of(
                        transition(CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, CodeAgentLoopEvent.RELEASED, CodeAgentLoopState.RELEASED_FOR_EXECUTION, true, false, "Release handoff is ready."),
                        transition(CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, CodeAgentLoopEvent.RELEASE_BOUNDARY_REFUSED, CodeAgentLoopState.RELEASE_PRECHECK_REQUIRED, false, false, "Release remains gated.")
                )),
                Map.entry(CodeAgentLoopState.RELEASED_FOR_EXECUTION, List.of(
                        transition(CodeAgentLoopState.RELEASED_FOR_EXECUTION, CodeAgentLoopEvent.PATCH_APPLIED, CodeAgentLoopState.VERIFYING_GIT_STATUS, true, false, "Patch apply succeeded."),
                        transition(CodeAgentLoopState.RELEASED_FOR_EXECUTION, CodeAgentLoopEvent.PATCH_FAILED, CodeAgentLoopState.FAILED, false, false, "Patch apply failed.")
                )),
                Map.entry(CodeAgentLoopState.VERIFYING_GIT_STATUS, List.of(
                        transition(CodeAgentLoopState.VERIFYING_GIT_STATUS, CodeAgentLoopEvent.LOCAL_AGENT_RESULT_SUCCEEDED, CodeAgentLoopState.FINAL_REPORT_READY, false, false, "Post-patch observation succeeded."),
                        transition(CodeAgentLoopState.VERIFYING_GIT_STATUS, CodeAgentLoopEvent.APPROVED_EXECUTION_FLOW_COMPLETED, CodeAgentLoopState.FINAL_REPORT_READY, false, false, "Approved execution flow completed.")
                )),
                Map.entry(CodeAgentLoopState.FINAL_REPORT_READY, List.of(
                        transition(CodeAgentLoopState.FINAL_REPORT_READY, CodeAgentLoopEvent.STOP_RECORDED, CodeAgentLoopState.COMPLETED, false, false, "Final stop outcome was recorded."),
                        transition(CodeAgentLoopState.FINAL_REPORT_READY, CodeAgentLoopEvent.APPROVED_EXECUTION_FLOW_COMPLETED, CodeAgentLoopState.FINAL_REPORT_READY, false, false, "Final report remains ready.")
                ))
        );
    }

    public CodeAgentLoopStateSnapshot snapshot(CodeAgentLoopTimelineSummary timeline) {
        if (timeline == null || timeline.events() == null || timeline.events().isEmpty()) {
            return new CodeAgentLoopStateSnapshot(
                    CodeAgentLoopState.CREATED,
                    null,
                    transitions.getOrDefault(CodeAgentLoopState.CREATED, List.of()),
                    "No loop timeline events have been recorded.",
                    null,
                    null
            );
        }

        CodeAgentLoopState state = CodeAgentLoopState.CREATED;
        CodeAgentLoopTransition lastTransition = null;
        CodeAgentLoopTimelineEventSummary lastEvent = null;
        for (CodeAgentLoopTimelineEventSummary event : timeline.events().stream()
                .sorted(Comparator.comparingInt(CodeAgentLoopTimelineEventSummary::sequenceNumber))
                .toList()) {
            CodeAgentLoopEvent mapped = eventMapper.map(event);
            CodeAgentLoopTransition transition = transitionFor(state, mapped);
            if (transition == null && isTerminalFailure(mapped)) {
                transition = transition(state, mapped, CodeAgentLoopState.FAILED, false, false, "Loop entered a failure state.");
            }
            if (transition == null && mapped == CodeAgentLoopEvent.STOP_RECORDED) {
                transition = transition(state, mapped, CodeAgentLoopState.STOPPED, false, false, "Loop stop outcome was recorded.");
            }
            if (transition != null) {
                state = transition.to();
                lastTransition = transition;
                lastEvent = event;
            }
        }

        return new CodeAgentLoopStateSnapshot(
                state,
                lastTransition,
                transitions.getOrDefault(state, List.of()),
                blockedReason(state),
                lastEvent == null ? null : lastEvent.eventType(),
                lastEvent == null ? null : lastEvent.sequenceNumber()
        );
    }

    private CodeAgentLoopTransition transitionFor(CodeAgentLoopState state, CodeAgentLoopEvent event) {
        return transitions.getOrDefault(state, List.of()).stream()
                .filter(transition -> transition.event() == event)
                .findFirst()
                .orElse(null);
    }

    private boolean isTerminalFailure(CodeAgentLoopEvent event) {
        return event == CodeAgentLoopEvent.PATCH_FAILED
                || event == CodeAgentLoopEvent.LOCAL_AGENT_RESULT_FAILED
                || event == CodeAgentLoopEvent.LOCAL_AGENT_RESULT_REJECTED
                || event == CodeAgentLoopEvent.TEST_FAILED;
    }

    private String blockedReason(CodeAgentLoopState state) {
        return switch (state) {
            case WAITING_FOR_LOCAL_AGENT -> "Waiting for the Local Agent to report the queued tool result.";
            case WAITING_FOR_APPROVAL -> "Waiting for explicit user approval before any mutation.";
            case APPROVED_HELD, WAITING_FOR_RELEASE_GATE, RELEASE_PRECHECK_REQUIRED -> "Approved work is held until release prechecks allow execution.";
            case FAILED -> "Loop failed; inspect the latest failure event before retrying.";
            case STOPPED -> "Loop stopped without an active next transition.";
            case COMPLETED -> "Loop completed.";
            default -> "";
        };
    }

    private CodeAgentLoopTransition transition(
            CodeAgentLoopState from,
            CodeAgentLoopEvent event,
            CodeAgentLoopState to,
            boolean sideEffectAllowed,
            boolean approvalRequired,
            String reason
    ) {
        return new CodeAgentLoopTransition(from, event, to, sideEffectAllowed, approvalRequired, reason);
    }
}
