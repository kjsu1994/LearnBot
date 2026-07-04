package com.learnbot.service.agentloop;

import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.LocalAgentToolName;

import java.util.Map;

public class CodeAgentLoopEventMapper {
    public CodeAgentLoopEvent map(CodeAgentLoopTimelineEventSummary event) {
        if (event == null || event.eventType() == null) {
            return CodeAgentLoopEvent.UNKNOWN_OBSERVATION;
        }
        Map<String, Object> details = event.details() == null ? Map.of() : event.details();
        return switch (event.eventType()) {
            case "LOOP_RUN_STARTED" -> CodeAgentLoopEvent.RUN_CREATED;
            case "LOCAL_AGENT_READ_ONLY_REQUEST_QUEUED" -> CodeAgentLoopEvent.READ_ONLY_REQUEST_QUEUED;
            case "LOCAL_AGENT_APPROVAL_REQUEST_CREATED" -> CodeAgentLoopEvent.PATCH_APPROVAL_CREATED;
            case "LOCAL_AGENT_APPROVAL_DECISION" -> approvalEvent(details);
            case "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED" -> CodeAgentLoopEvent.RELEASE_FRESH_OBSERVATIONS_QUEUED;
            case "LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_COMPLETE" -> CodeAgentLoopEvent.RELEASE_FRESH_OBSERVATIONS_COMPLETE;
            case "LOCAL_AGENT_RELEASE_READINESS_REFRESHED" -> CodeAgentLoopEvent.RELEASE_READINESS_REFRESHED;
            case "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED" -> releaseBoundaryEvent(details);
            case "LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED" -> CodeAgentLoopEvent.APPROVED_EXECUTION_FLOW_COMPLETED;
            case "LOOP_NEXT_DECISION_RECORDED" -> CodeAgentLoopEvent.NEXT_DECISION_RECORDED;
            case "STOP_OUTCOME_RECORDED" -> stopEvent(details);
            case "LOCAL_AGENT_OBSERVATION_RESULT" -> localAgentObservationEvent(event, details);
            default -> CodeAgentLoopEvent.UNKNOWN_OBSERVATION;
        };
    }

    private CodeAgentLoopEvent approvalEvent(Map<String, Object> details) {
        String approvalState = text(details.get("approvalState"));
        String status = text(details.get("status"));
        if ("APPROVED".equals(approvalState) && "APPROVED_HELD".equals(status)) {
            return CodeAgentLoopEvent.APPROVED;
        }
        if ("DENIED".equals(approvalState) || "REJECTED".equals(status)) {
            return CodeAgentLoopEvent.DENIED;
        }
        return CodeAgentLoopEvent.UNKNOWN_OBSERVATION;
    }

    private CodeAgentLoopEvent releaseBoundaryEvent(Map<String, Object> details) {
        return Boolean.TRUE.equals(details.get("handoffCreationDisabled"))
                ? CodeAgentLoopEvent.RELEASED
                : CodeAgentLoopEvent.RELEASE_BOUNDARY_REFUSED;
    }

    private CodeAgentLoopEvent stopEvent(Map<String, Object> details) {
        String stopKey = text(details.get("stopKey"));
        return stopKey.contains("FAILED") || stopKey.contains("PATCH_FAILED")
                ? CodeAgentLoopEvent.PATCH_FAILED
                : CodeAgentLoopEvent.STOP_RECORDED;
    }

    private CodeAgentLoopEvent localAgentObservationEvent(CodeAgentLoopTimelineEventSummary event, Map<String, Object> details) {
        String status = text(details.get("status"));
        if (event.toolName() == LocalAgentToolName.PATCH_APPLY) {
            if ("SUCCEEDED".equals(status) && mutationApplied(details)) {
                return CodeAgentLoopEvent.PATCH_APPLIED;
            }
            if ("FAILED".equals(status) || "REJECTED".equals(status)) {
                return CodeAgentLoopEvent.PATCH_FAILED;
            }
        }
        if ("SUCCEEDED".equals(status)) {
            return CodeAgentLoopEvent.LOCAL_AGENT_RESULT_SUCCEEDED;
        }
        if ("REJECTED".equals(status)) {
            return CodeAgentLoopEvent.LOCAL_AGENT_RESULT_REJECTED;
        }
        if ("FAILED".equals(status)) {
            return CodeAgentLoopEvent.LOCAL_AGENT_RESULT_FAILED;
        }
        return CodeAgentLoopEvent.UNKNOWN_OBSERVATION;
    }

    private boolean mutationApplied(Map<String, Object> details) {
        Object outputSummary = details.get("outputSummary");
        if (outputSummary instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("mutationApplied"))) {
            return true;
        }
        return Boolean.TRUE.equals(details.get("mutationApplied"));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
