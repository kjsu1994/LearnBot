package com.learnbot.service.agentloop;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAgentLoopStateMachineTest {
    private final CodeAgentLoopStateMachine stateMachine = new CodeAgentLoopStateMachine();

    @Test
    void snapshotStartsAtCreatedWhenTimelineHasNoEvents() {
        var snapshot = stateMachine.snapshot(timeline(List.of()));

        assertThat(snapshot.state()).isEqualTo(CodeAgentLoopState.CREATED);
        assertThat(snapshot.availableTransitions())
                .extracting(CodeAgentLoopTransition::event)
                .contains(CodeAgentLoopEvent.READ_ONLY_REQUEST_QUEUED, CodeAgentLoopEvent.PATCH_APPROVAL_CREATED);
    }

    @Test
    void readOnlyQueueMovesToWaitingForLocalAgent() {
        var snapshot = stateMachine.snapshot(timeline(List.of(
                event(1, "LOCAL_AGENT_READ_ONLY_REQUEST_QUEUED", null, Map.of("status", "QUEUED"))
        )));

        assertThat(snapshot.state()).isEqualTo(CodeAgentLoopState.WAITING_FOR_LOCAL_AGENT);
        assertThat(snapshot.lastTransition().event()).isEqualTo(CodeAgentLoopEvent.READ_ONLY_REQUEST_QUEUED);
        assertThat(snapshot.blockedReason()).contains("Waiting for the Local Agent");
    }

    @Test
    void readOnlyObservationThenApprovalRequestWaitsForApproval() {
        var snapshot = stateMachine.snapshot(timeline(List.of(
                event(1, "LOCAL_AGENT_READ_ONLY_REQUEST_QUEUED", null, Map.of("status", "QUEUED")),
                event(2, "LOCAL_AGENT_OBSERVATION_RESULT", LocalAgentToolName.FILE_READ, Map.of("status", "SUCCEEDED")),
                event(3, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED", LocalAgentToolName.PATCH_APPLY, Map.of("status", "APPROVAL_REQUIRED"))
        )));

        assertThat(snapshot.state()).isEqualTo(CodeAgentLoopState.WAITING_FOR_APPROVAL);
        assertThat(snapshot.availableTransitions())
                .extracting(CodeAgentLoopTransition::event)
                .containsExactly(CodeAgentLoopEvent.APPROVED, CodeAgentLoopEvent.DENIED);
    }

    @Test
    void approvedHeldMovesToReleaseGate() {
        var snapshot = stateMachine.snapshot(timeline(List.of(
                event(1, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED", LocalAgentToolName.PATCH_APPLY, Map.of("status", "APPROVAL_REQUIRED")),
                event(2, "LOCAL_AGENT_APPROVAL_DECISION", LocalAgentToolName.PATCH_APPLY, Map.of(
                        "status", "APPROVED_HELD",
                        "approvalState", "APPROVED"
                ))
        )));

        assertThat(snapshot.state()).isEqualTo(CodeAgentLoopState.APPROVED_HELD);
        assertThat(snapshot.blockedReason()).contains("release prechecks");
    }

    @Test
    void patchApplyFailureMovesToFailedFromAnyActiveState() {
        var snapshot = stateMachine.snapshot(timeline(List.of(
                event(1, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED", LocalAgentToolName.PATCH_APPLY, Map.of("status", "APPROVAL_REQUIRED")),
                event(2, "LOCAL_AGENT_APPROVAL_DECISION", LocalAgentToolName.PATCH_APPLY, Map.of(
                        "status", "APPROVED_HELD",
                        "approvalState", "APPROVED"
                )),
                event(3, "LOCAL_AGENT_OBSERVATION_RESULT", LocalAgentToolName.PATCH_APPLY, Map.of(
                        "status", "REJECTED",
                        "failureCode", "CONTEXT_MISMATCH"
                ))
        )));

        assertThat(snapshot.state()).isEqualTo(CodeAgentLoopState.FAILED);
        assertThat(snapshot.lastTransition().event()).isEqualTo(CodeAgentLoopEvent.PATCH_FAILED);
        assertThat(snapshot.blockedReason()).contains("failed");
    }

    @Test
    void deniedApprovalStopsLoop() {
        var snapshot = stateMachine.snapshot(timeline(List.of(
                event(1, "LOCAL_AGENT_APPROVAL_REQUEST_CREATED", LocalAgentToolName.PATCH_APPLY, Map.of("status", "APPROVAL_REQUIRED")),
                event(2, "LOCAL_AGENT_APPROVAL_DECISION", LocalAgentToolName.PATCH_APPLY, Map.of(
                        "status", "REJECTED",
                        "approvalState", "DENIED"
                ))
        )));

        assertThat(snapshot.state()).isEqualTo(CodeAgentLoopState.STOPPED);
        assertThat(snapshot.lastTransition().event()).isEqualTo(CodeAgentLoopEvent.DENIED);
    }

    private CodeAgentLoopTimelineSummary timeline(List<CodeAgentLoopTimelineEventSummary> events) {
        return new CodeAgentLoopTimelineSummary(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "fix issue",
                "RUNNING",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                events
        );
    }

    private CodeAgentLoopTimelineEventSummary event(
            int sequenceNumber,
            String eventType,
            LocalAgentToolName toolName,
            Map<String, Object> details
    ) {
        return new CodeAgentLoopTimelineEventSummary(
                UUID.randomUUID(),
                sequenceNumber,
                eventType,
                "COMPLETE_OR_PAUSE",
                AgentExecutionTarget.SERVER_LOCAL,
                toolName,
                false,
                toolName != null && toolName.isSideEffectful(),
                true,
                details,
                OffsetDateTime.now()
        );
    }
}
