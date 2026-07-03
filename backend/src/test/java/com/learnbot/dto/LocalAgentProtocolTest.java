package com.learnbot.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    void failedResponsesIgnoreNullOutputAndWarningEntries() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("dryRun", true);
        output.put("snapshotManifestId", null);
        output.put("mutationApplied", false);
        output.put(null, "ignored");
        List<String> warnings = new ArrayList<>();
        warnings.add("kept");
        warnings.add(null);

        LocalAgentToolResponse response = new LocalAgentToolResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.REJECTED,
                output,
                LocalAgentFailureCode.UNSAFE_TOOL,
                "dry-run snapshot created; mutation disabled",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                warnings
        );

        assertThat(response.output())
                .containsEntry("dryRun", true)
                .containsEntry("mutationApplied", false)
                .doesNotContainKey("snapshotManifestId")
                .doesNotContainKey(null);
        assertThat(response.warnings()).containsExactly("kept");
    }

    @Test
    void rejectedPatchApplyResponseCanBeReadFromJsonWithNullOutputEntries() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String json = """
                {
                  "sessionId": "%s",
                  "requestId": "%s",
                  "userId": "%s",
                  "agentId": "%s",
                  "workspaceId": "%s",
                  "executionTarget": "USER_LOCAL_AGENT",
                  "toolName": "patch.apply",
                  "status": "REJECTED",
                  "output": {
                    "dryRun": true,
                    "snapshotManifestId": null,
                    "mutationApplied": false
                  },
                  "failureCode": "UNSAFE_TOOL",
                  "error": "dry-run snapshot created; mutation disabled",
                  "warnings": ["kept", null]
                }
                """.formatted(sessionId, requestId, userId, agentId, workspaceId);

        LocalAgentToolResponse response = objectMapper.readValue(json, LocalAgentToolResponse.class);

        assertThat(response.status()).isEqualTo(LocalAgentToolStatus.REJECTED);
        assertThat(response.output())
                .containsEntry("dryRun", true)
                .containsEntry("mutationApplied", false)
                .doesNotContainKey("snapshotManifestId");
        assertThat(response.warnings()).containsExactly("kept");
    }

    @Test
    void toolNamesUseStableWireNames() throws Exception {
        String json = objectMapper.writeValueAsString(LocalAgentToolName.COMMAND_RUN_ALLOWED);

        assertThat(json).isEqualTo("\"command.runAllowed\"");
        assertThat(objectMapper.readValue("\"file.read\"", LocalAgentToolName.class)).isEqualTo(LocalAgentToolName.FILE_READ);
    }
}
