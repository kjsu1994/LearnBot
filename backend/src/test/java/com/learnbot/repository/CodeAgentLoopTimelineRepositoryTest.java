package com.learnbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentLoopStep;
import com.learnbot.dto.CodeAgentLoopStopCondition;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentLoopTimelineRepositoryTest {

    @Test
    void createPreviewPersistsAuditOnlyLoopTimelineFields() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID loopId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        CodeAgentLoopPreviewResponse preview = new CodeAgentLoopPreviewResponse(
                loopId,
                repositoryId,
                spaceId,
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                List.of(new CodeAgentLoopStep(
                        1,
                        "PLAN",
                        "Retrieve code evidence.",
                        AgentExecutionTarget.SERVER_LOCAL,
                        null,
                        false,
                        false,
                        true,
                        "Stop on weak evidence."
                )),
                List.of(new CodeAgentLoopStopCondition("MUTATION_DISABLED", "Do not apply patches.")),
                List.of("Preview only.")
        );

        repository.createPreview(userId, "fix this bug", preview);

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(11)).update(anyString(), params.capture());
        MapSqlParameterSource values = params.getAllValues().get(0);
        assertThat(values.getValue("id")).isEqualTo(loopId);
        assertThat(values.getValue("userId")).isEqualTo(userId);
        assertThat(values.getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(values.getValue("spaceId")).isEqualTo(spaceId);
        assertThat(values.getValue("instruction")).isEqualTo("fix this bug");
        assertThat(values.getValue("status")).isEqualTo("PREVIEW_ONLY");
        assertThat(values.getValue("maxSteps")).isEqualTo(6);
        assertThat(values.getValue("timeoutSeconds")).isEqualTo(120);
        assertThat(values.getValue("cancellationEnabled")).isEqualTo(false);
        assertThat(values.getValue("timelinePersistenceEnabled")).isEqualTo(true);
        assertThat(values.getValue("mutationEnabled")).isEqualTo(false);
        assertThat((String) values.getValue("steps")).contains("PLAN").contains("mayMutate");
        assertThat((String) values.getValue("stopConditions")).contains("MUTATION_DISABLED");
        assertThat((String) values.getValue("warnings")).contains("Preview only.");
        MapSqlParameterSource createdEvent = params.getAllValues().get(1);
        assertThat(createdEvent.getValue("timelineId")).isEqualTo(loopId);
        assertThat(createdEvent.getValue("sequenceNumber")).isEqualTo(1);
        assertThat(createdEvent.getValue("eventType")).isEqualTo("LOOP_PREVIEW_CREATED");
        assertThat(createdEvent.getValue("mayMutate")).isEqualTo(false);
        MapSqlParameterSource stepEvent = params.getAllValues().get(2);
        assertThat(stepEvent.getValue("sequenceNumber")).isEqualTo(2);
        assertThat(stepEvent.getValue("eventType")).isEqualTo("MODEL_DECISION_PREVIEW");
        assertThat(stepEvent.getValue("phase")).isEqualTo("PLAN");
        assertThat(stepEvent.getValue("executionTarget")).isEqualTo("SERVER_LOCAL");
        assertThat(stepEvent.getValue("requiresApproval")).isEqualTo(false);
        assertThat(stepEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat(stepEvent.getValue("enabled")).isEqualTo(true);
        assertThat((String) stepEvent.getValue("details")).contains("Retrieve code evidence.");
        MapSqlParameterSource stopEvent = params.getAllValues().get(3);
        assertThat(stopEvent.getValue("eventType")).isEqualTo("STOP_CONDITIONS_REGISTERED");
        assertThat((String) stopEvent.getValue("details")).contains("MUTATION_DISABLED");
        MapSqlParameterSource timeoutEvent = params.getAllValues().get(4);
        assertThat(timeoutEvent.getValue("eventType")).isEqualTo("TIMEOUT_POLICY_REGISTERED");
        assertThat(timeoutEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) timeoutEvent.getValue("details")).contains("\"timeoutSeconds\":120");
        MapSqlParameterSource cancellationEvent = params.getAllValues().get(5);
        assertThat(cancellationEvent.getValue("eventType")).isEqualTo("CANCELLATION_POLICY_REGISTERED");
        assertThat(cancellationEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) cancellationEvent.getValue("details")).contains("\"cancellationEnabled\":false");
        MapSqlParameterSource finalResultEvent = params.getAllValues().get(6);
        assertThat(finalResultEvent.getValue("eventType")).isEqualTo("FINAL_RESULT_POLICY_REGISTERED");
        assertThat(finalResultEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) finalResultEvent.getValue("details")).contains("\"finalResultEnabled\":false");
        MapSqlParameterSource weakEvidenceEvent = params.getAllValues().get(7);
        assertThat(weakEvidenceEvent.getValue("eventType")).isEqualTo("STOP_OUTCOME_POLICY_REGISTERED");
        assertThat(weakEvidenceEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) weakEvidenceEvent.getValue("details")).contains("\"stopKey\":\"WEAK_EVIDENCE\"");
        MapSqlParameterSource approvalDeniedEvent = params.getAllValues().get(10);
        assertThat(approvalDeniedEvent.getValue("eventType")).isEqualTo("STOP_OUTCOME_POLICY_REGISTERED");
        assertThat(approvalDeniedEvent.getValue("mayMutate")).isEqualTo(false);
        assertThat((String) approvalDeniedEvent.getValue("details")).contains("\"stopKey\":\"APPROVAL_DENIED\"");
    }

    @Test
    void findRecentScopesReadOnlyTimelinesToUserAndRepository() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                ArgumentMatchers.any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<CodeAgentLoopTimelineSummary>>any()
        )).thenReturn(List.of());

        var result = repository.findRecent(userId, repositoryId, 5);

        assertThat(result).isEmpty();
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(anyString(), params.capture(), ArgumentMatchers.<RowMapper<CodeAgentLoopTimelineSummary>>any());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("limit")).isEqualTo(5);
    }

    @Test
    void appendObservationResultPersistsAuditOnlyEventForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                requestId,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "dryRun", true,
                        "mutationApplied", false,
                        "snapshotCreated", true
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("dry-run only")
        );

        repository.appendObservationResult(userId, repositoryId, loopId, response, Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "freshObservationOnly", true,
                "dryRunOnly", true,
                "mutationAllowed", false
        ));

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_OBSERVATION_RESULT");
        assertThat(params.getValue().getValue("phase")).isEqualTo("OBSERVE");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains(requestId.toString())
                .contains(sourceRequestId.toString())
                .contains("\"freshObservationOnly\":true")
                .contains("\"dryRun\":true")
                .contains("\"mutationApplied\":false")
                .contains("\"snapshotCreated\":true");
    }

    @Test
    void appendApprovalDecisionPersistsAuditOnlyDecisionEventForLatestTimeline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeAgentLoopTimelineRepository repository = new CodeAgentLoopTimelineRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();

        repository.appendApprovalDecision(
                userId,
                repositoryId,
                requestId,
                sessionId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                "APPROVED",
                "APPROVED_HELD",
                loopId,
                Map.of("sourceRequestId", requestId.toString())
        );

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(params.getValue().getValue("loopId")).isEqualTo(loopId);
        assertThat(params.getValue().getValue("eventType")).isEqualTo("LOCAL_AGENT_APPROVAL_DECISION");
        assertThat(params.getValue().getValue("phase")).isEqualTo("REQUEST_APPROVAL");
        assertThat(params.getValue().getValue("executionTarget")).isEqualTo("USER_LOCAL_AGENT");
        assertThat(params.getValue().getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue().getValue("requiresApproval")).isEqualTo(true);
        String details = (String) params.getValue().getValue("details");
        assertThat(details)
                .contains(requestId.toString())
                .contains(sessionId.toString())
                .contains(agentId.toString())
                .contains(workspaceId.toString())
                .contains("\"approvalState\":\"APPROVED\"")
                .contains("\"status\":\"APPROVED_HELD\"");
    }
}
