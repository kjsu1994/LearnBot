package com.learnbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentMutationObservationIntakeRepositoryTest {

    @Test
    void saveAcceptedObservationPersistsLinkedReadModel() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        LocalAgentMutationObservationIntakeRepository repository =
                new LocalAgentMutationObservationIntakeRepository(jdbc, new ObjectMapper());
        UUID requestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalAgentToolResponse response = response(
                requestId,
                sessionId,
                userId,
                agentId,
                workspaceId,
                Map.of(
                        "acceptedMutationObservation", Map.ofEntries(
                                Map.entry("schema", "learnbot.local-agent.accepted-mutation-observation.v1"),
                                Map.entry("status", "ACCEPTED"),
                                Map.entry("accepted", true),
                                Map.entry("toolName", "patch.apply"),
                                Map.entry("sourceRequestId", sourceRequestId.toString()),
                                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                                Map.entry("verificationStatus", "APPLIED")
                        ),
                        "mutationResultIntakeCandidate", Map.of(
                                "schema", "learnbot.local-agent.mutation-result-intake-candidate.v1",
                                "acceptanceStatus", "ACCEPTED"
                        )
                )
        );

        repository.saveAcceptedObservation(response, Map.of());

        var captor = forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());
        MapSqlParameterSource params = (MapSqlParameterSource) captor.getValue();
        assertThat(params.getValue("requestId")).isEqualTo(requestId);
        assertThat(params.getValue("sourceRequestId")).isEqualTo(sourceRequestId);
        assertThat(params.getValue("releaseAttemptId")).isEqualTo(releaseAttemptId);
        assertThat(params.getValue("sessionId")).isEqualTo(sessionId);
        assertThat(params.getValue("userId")).isEqualTo(userId);
        assertThat(params.getValue("agentId")).isEqualTo(agentId);
        assertThat(params.getValue("workspaceId")).isEqualTo(workspaceId);
        assertThat(params.getValue("toolName")).isEqualTo("patch.apply");
        assertThat(params.getValue("status")).isEqualTo("ACCEPTED");
        assertThat(params.getValue("accepted")).isEqualTo(true);
        assertThat(params.getValue("verificationStatus")).isEqualTo("APPLIED");
        assertThat(String.valueOf(params.getValue("observation"))).contains("accepted-mutation-observation", "ACCEPTED");
        assertThat(String.valueOf(params.getValue("candidate"))).contains("mutation-result-intake-candidate", "ACCEPTED");
    }

    @Test
    void saveAcceptedObservationSkipsWhenObservationIsMissing() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        LocalAgentMutationObservationIntakeRepository repository =
                new LocalAgentMutationObservationIntakeRepository(jdbc, new ObjectMapper());

        repository.saveAcceptedObservation(
                response(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()),
                Map.of()
        );

        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    private LocalAgentToolResponse response(
            UUID requestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            Map<String, Object> output
    ) {
        return new LocalAgentToolResponse(
                sessionId,
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                output,
                null,
                null,
                OffsetDateTime.now().minusSeconds(1),
                OffsetDateTime.now(),
                List.of()
        );
    }
}
