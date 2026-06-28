package com.learnbot.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAgentProtocolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sideEffectfulToolsDefaultToApprovalRequired() {
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                Map.of("path", "src/App.java"),
                null,
                null,
                null
        );

        assertThat(request.approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(request.input()).containsEntry("path", "src/App.java");
    }

    @Test
    void readOnlyToolsDoNotRequireApprovalByDefault() {
        LocalAgentToolRequest request = new LocalAgentToolRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.FILE_READ,
                Map.of("path", "src/App.java"),
                null,
                null,
                null
        );

        assertThat(request.approvalState()).isEqualTo(LocalAgentApprovalState.NOT_REQUIRED);
    }

    @Test
    void userLocalAgentRequestsRequireAgentIdAndWorkspaceForWorkspaceTools() {
        assertThatThrownBy(() -> new LocalAgentToolRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.FILE_READ,
                Map.of(),
                null,
                null,
                null
        )).hasMessageContaining("agentId");

        assertThatThrownBy(() -> new LocalAgentToolRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.FILE_READ,
                Map.of(),
                null,
                null,
                null
        )).hasMessageContaining("workspaceId");
    }

    @Test
    void failedResponsesRequireFailureCode() {
        assertThatThrownBy(() -> new LocalAgentToolResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.COMMAND_RUN_ALLOWED,
                LocalAgentToolStatus.FAILED,
                Map.of(),
                null,
                "command failed",
                null,
                null,
                null
        )).hasMessageContaining("failureCode");
    }

    @Test
    void toolNamesUseStableWireNames() throws Exception {
        String json = objectMapper.writeValueAsString(LocalAgentToolName.COMMAND_RUN_ALLOWED);

        assertThat(json).isEqualTo("\"command.runAllowed\"");
        assertThat(objectMapper.readValue("\"file.read\"", LocalAgentToolName.class)).isEqualTo(LocalAgentToolName.FILE_READ);
    }
}
