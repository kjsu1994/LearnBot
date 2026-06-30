package com.learnbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentPatchExecutionReadinessResponse;
import com.learnbot.dto.LocalAgentPatchReleaseAttemptModel;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AppUser;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToken;
import com.learnbot.service.LocalAgentToolGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalAgentControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void releasePatchExecutionReturnsRefusalOnlyBoundaryWithoutClaiming() {
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
        LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentController controller = new LocalAgentController(
                gatewayService,
                authService,
                toolGatewayService,
                currentUserProvider
        );
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        LocalAgentPatchReleaseBoundaryResponse expected = new LocalAgentPatchReleaseBoundaryResponse(
                requestId,
                "RELEASE_REFUSED_GATE_DISABLED",
                "REFUSAL_ONLY",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of("release gate is disabled"),
                "Release action is modeled, but disabled.",
                Map.of("releaseGateEnabled", false),
                Map.of("releaseGateEnabled", false),
                new LocalAgentPatchReleaseAttemptModel(
                        "learnbot.local-agent.patch-release-attempt.v1",
                        "DISABLED",
                        true,
                        false,
                        120,
                        List.of(),
                        Map.of(),
                        "disabled"
                )
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(toolGatewayService.inspectPatchReleaseBoundary(userId, requestId)).thenReturn(expected);

        var actual = controller.releasePatchExecution(requestId);

        assertThat(actual).isSameAs(expected);
        verify(toolGatewayService).inspectPatchReleaseBoundary(userId, requestId);
    }

    @Test
    void enqueueReleaseAttemptFreshObservationsRouteReturnsOnlyObservationRequests() throws Exception {
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
        LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentController controller = new LocalAgentController(
                gatewayService,
                authService,
                toolGatewayService,
                currentUserProvider
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID repositoryObservationId = UUID.randomUUID();
        UUID patchDryRunId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        List<LocalAgentQueuedToolRequest> expected = List.of(
                new LocalAgentQueuedToolRequest(
                        repositoryObservationId,
                        new LocalAgentToolRequest(
                                sessionId,
                                userId,
                                agentId,
                                workspaceId,
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.GIT_STATUS,
                                Map.of(
                                        "sourceRequestId", requestId.toString(),
                                        "releaseAttemptId", attemptId.toString(),
                                        "freshObservationOnly", true
                                ),
                                LocalAgentApprovalState.NOT_REQUIRED,
                                null,
                                List.of("Fresh release-attempt git.status observation. Read-only; the source patch request stays held.")
                        )
                ),
                new LocalAgentQueuedToolRequest(
                        patchDryRunId,
                        new LocalAgentToolRequest(
                                sessionId,
                                userId,
                                agentId,
                                workspaceId,
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.PATCH_APPLY,
                                Map.of(
                                        "sourceRequestId", requestId.toString(),
                                        "releaseAttemptId", attemptId.toString(),
                                        "freshObservationOnly", true,
                                        "dryRunOnly", true,
                                        "mutationAllowed", false
                                ),
                                LocalAgentApprovalState.APPROVED,
                                null,
                                List.of("Fresh release-attempt patch dry-run observation. dryRunOnly=true, mutationAllowed=false, and the source request stays held.")
                        )
                )
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(toolGatewayService.enqueueReleaseAttemptFreshObservations(userId, requestId)).thenReturn(expected);

        mockMvc.perform(post("/api/local-agents/tools/{requestId}/fresh-observations", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestId").value(repositoryObservationId.toString()))
                .andExpect(jsonPath("$[1].requestId").value(patchDryRunId.toString()))
                .andExpect(jsonPath("$[0].requestId").value(org.hamcrest.Matchers.not(requestId.toString())))
                .andExpect(jsonPath("$[1].requestId").value(org.hamcrest.Matchers.not(requestId.toString())))
                .andExpect(jsonPath("$[0].request.toolName").value(LocalAgentToolName.GIT_STATUS.wireName()))
                .andExpect(jsonPath("$[1].request.toolName").value(LocalAgentToolName.PATCH_APPLY.wireName()))
                .andExpect(jsonPath("$[0].request.input.sourceRequestId").value(requestId.toString()))
                .andExpect(jsonPath("$[1].request.input.sourceRequestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].request.input.freshObservationOnly").value(true))
                .andExpect(jsonPath("$[1].request.input.freshObservationOnly").value(true))
                .andExpect(jsonPath("$[1].request.input.dryRunOnly").value(true))
                .andExpect(jsonPath("$[1].request.input.mutationAllowed").value(false))
                .andExpect(jsonPath("$[0].request.input.mutationAllowed").doesNotExist());

        verify(toolGatewayService).enqueueReleaseAttemptFreshObservations(userId, requestId);
    }

    @Test
    void pollingAgentCanClaimAndCompletePatchDryRunObservationWithoutOpeningMutation() throws Exception {
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
        LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentController controller = new LocalAgentController(
                gatewayService,
                authService,
                toolGatewayService,
                currentUserProvider
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        UUID tokenId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID dryRunRequestId = UUID.randomUUID();
        String agentToken = "agent-token";
        LocalAgentToken token = new LocalAgentToken(
                tokenId,
                userId,
                agentId,
                "laptop",
                OffsetDateTime.now().plusDays(1),
                null,
                null,
                OffsetDateTime.now()
        );
        LocalAgentQueuedToolRequest queued = new LocalAgentQueuedToolRequest(
                dryRunRequestId,
                new LocalAgentToolRequest(
                        sessionId,
                        userId,
                        agentId,
                        workspaceId,
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.PATCH_APPLY,
                        Map.of(
                                "sourceRequestId", sourceRequestId.toString(),
                                "dryRunOnly", true,
                                "mutationAllowed", false
                        ),
                        LocalAgentApprovalState.APPROVED,
                        null,
                        List.of("Dry-run clone of approved-held patch request. Mutation remains disabled and the source request stays held.")
                )
        );
        when(authService.authenticate(agentToken)).thenReturn(token);
        when(toolGatewayService.claimNext(userId, agentId)).thenReturn(Optional.of(queued));

        mockMvc.perform(get("/api/local-agents/tools/next")
                        .header("X-Local-Agent-Token", agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(dryRunRequestId.toString()))
                .andExpect(jsonPath("$.request.toolName").value(LocalAgentToolName.PATCH_APPLY.wireName()))
                .andExpect(jsonPath("$.request.input.sourceRequestId").value(sourceRequestId.toString()))
                .andExpect(jsonPath("$.request.input.dryRunOnly").value(true))
                .andExpect(jsonPath("$.request.input.mutationAllowed").value(false));

        String responseJson = objectMapper.writeValueAsString(new LocalAgentToolResponse(
                sessionId,
                dryRunRequestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "dryRun", true,
                        "mutationApplied", false,
                        "sourceRequestId", sourceRequestId.toString()
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of("dry-run completed without mutation")
        ));
        mockMvc.perform(post("/api/local-agents/tools/{requestId}/response", dryRunRequestId)
                        .header("X-Local-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(responseJson))
                .andExpect(status().isNoContent());

        verify(authService, org.mockito.Mockito.times(2)).authenticate(agentToken);
        verify(toolGatewayService).claimNext(userId, agentId);
        var responseCaptor = org.mockito.ArgumentCaptor.forClass(LocalAgentToolResponse.class);
        verify(toolGatewayService).complete(responseCaptor.capture());
        assertThat(responseCaptor.getValue().requestId()).isEqualTo(dryRunRequestId);
        assertThat(responseCaptor.getValue().userId()).isEqualTo(userId);
        assertThat(responseCaptor.getValue().agentId()).isEqualTo(agentId);
        assertThat(responseCaptor.getValue().toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(responseCaptor.getValue().output())
                .containsEntry("dryRun", true)
                .containsEntry("mutationApplied", false)
                .containsEntry("sourceRequestId", sourceRequestId.toString());
    }

    @Test
    void toolReadinessSerializesReleaseAttemptDisplaySummaryWithoutEnablingMutation() throws Exception {
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
        LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentController controller = new LocalAgentController(
                gatewayService,
                authService,
                toolGatewayService,
                currentUserProvider
        );
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        Map<String, Object> disabledFlags = Map.ofEntries(
                Map.entry("releaseGateEnabled", false),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("writeHelperEnabled", false),
                Map.entry("applyEnabled", false),
                Map.entry("testEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("finalAnswerGenerationEnabled", false),
                Map.entry("mutationAllowed", false)
        );
        Map<String, Object> displaySummary = Map.ofEntries(
                Map.entry("status", "READY_BUT_DISABLED_DISPLAY"),
                Map.entry("show", true),
                Map.entry("releaseAttemptId", attemptId),
                Map.entry("sourceRequestId", requestId),
                Map.entry("linkedEvidenceComplete", true),
                Map.entry("releaseReadyButDisabled", true),
                Map.entry("evidenceStatus", "ALL_LINKED_RELEASE_DISABLED"),
                Map.entry("releaseReadinessStatus", "READY_RELEASE_DISABLED"),
                Map.entry("patchPreconditionsPassed", true),
                Map.entry("evidenceComplete", true),
                Map.entry("linkedCount", 2),
                Map.entry("missingCount", 0),
                Map.entry("sourceOnlyFallbackCount", 0),
                Map.entry("blockingCount", 0),
                Map.entry("disabledFlags", disabledFlags),
                Map.entry("blockingReasons", List.of("release gate is disabled", "held patch request remains non-claimable")),
                Map.entry("message", "Linked release evidence is complete and all release controls remain disabled.")
        );
        LocalAgentPatchExecutionReadinessResponse expected = new LocalAgentPatchExecutionReadinessResponse(
                requestId,
                false,
                List.of(),
                List.of(),
                "Release remains disabled.",
                Map.of("status", "PRECONDITIONS_READY_RELEASE_DISABLED", "preconditionsPassed", true),
                Map.of("claimEnabled", false, "mutationEnabled", false, "releaseGateEnabled", false),
                new LocalAgentPatchReleaseAttemptModel(
                        "learnbot.local-agent.patch-release-attempt.v1",
                        "DISABLED",
                        true,
                        false,
                        120,
                        List.of(),
                        Map.of("releaseAttemptDisplaySummary", displaySummary),
                        "disabled"
                ),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(toolGatewayService.inspectPatchExecutionReadiness(userId, requestId)).thenReturn(expected);

        var actual = controller.toolReadiness(requestId);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(actual));
        JsonNode summary = json.at("/releaseAttemptModel/latestAttempt/releaseAttemptDisplaySummary");

        assertThat(actual).isSameAs(expected);
        verify(toolGatewayService).inspectPatchExecutionReadiness(userId, requestId);
        assertThat(summary.path("status").asText()).isEqualTo("READY_BUT_DISABLED_DISPLAY");
        assertThat(summary.path("show").asBoolean()).isTrue();
        assertThat(summary.path("linkedEvidenceComplete").asBoolean()).isTrue();
        assertThat(summary.path("releaseReadyButDisabled").asBoolean()).isTrue();
        assertThat(summary.path("evidenceStatus").asText()).isEqualTo("ALL_LINKED_RELEASE_DISABLED");
        assertThat(summary.path("releaseReadinessStatus").asText()).isEqualTo("READY_RELEASE_DISABLED");
        assertThat(summary.path("disabledFlags").path("releaseGateEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("requestCreationEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("pushEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("claimEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("writeHelperEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("applyEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("testEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("rollbackRestoreEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("ragFreshnessUpdateEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("finalAnswerGenerationEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledFlags").path("mutationAllowed").asBoolean()).isFalse();
    }

    @Test
    void toolReadinessSerializesMutationHandoffSummaryWithoutEnablingMutation() throws Exception {
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
        LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentController controller = new LocalAgentController(
                gatewayService,
                authService,
                toolGatewayService,
                currentUserProvider
        );
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        Map<String, Object> disabledControls = Map.ofEntries(
                Map.entry("releaseGateEnabled", false),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("writeHelperEnabled", false),
                Map.entry("applyEnabled", false),
                Map.entry("testEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("finalAnswerGenerationEnabled", false),
                Map.entry("finalAnswerCompletionEnabled", false),
                Map.entry("finalAnswerDeliveryEnabled", false),
                Map.entry("finalResponseHandoffEnabled", false),
                Map.entry("deliveryReceiptEnabled", false),
                Map.entry("claimable", false),
                Map.entry("mutationAllowed", false)
        );
        Map<String, Object> mutationHandoffSummary = Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.mutation-handoff-summary.v1"),
                Map.entry("status", "READY_HANDOFF_DISABLED"),
                Map.entry("prerequisitesPassed", true),
                Map.entry("blocking", true),
                Map.entry("releaseAttemptId", attemptId),
                Map.entry("sourceRequestId", requestId),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("sourceCompletionSummaryStatus", "READY_COMPLETION_DISABLED"),
                Map.entry("disabledControls", disabledControls),
                Map.entry("blockingKeys", List.of("releaseGateEnabled", "requestCreationEnabled", "pushEnabled", "claimEnabled", "mutationAllowed")),
                Map.entry("handoffStages", List.of(
                        Map.of("key", "dispatchDecision", "status", "MODELED_DISABLED", "passed", true, "requestCreationEnabled", false, "mutationAllowed", false),
                        Map.of("key", "requestCreation", "status", "MODELED_DISABLED", "passed", true, "requestCreationEnabled", false, "mutationAllowed", false),
                        Map.of("key", "transportPush", "status", "MODELED_DISABLED", "passed", true, "pushEnabled", false, "mutationAllowed", false),
                        Map.of("key", "agentClaim", "status", "MODELED_DISABLED", "passed", true, "claimEnabled", false, "claimable", false),
                        Map.of("key", "toolExecution", "status", "MODELED_DISABLED", "passed", true, "executionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "resultIntake", "status", "MODELED_DISABLED", "passed", true, "resultIntakeEnabled", false, "mutationAllowed", false),
                        Map.of("key", "finalResponse", "status", "MODELED_DISABLED", "passed", true, "finalResponseHandoffEnabled", false, "mutationAllowed", false),
                        Map.of("key", "deliveryReceipt", "status", "MODELED_DISABLED", "passed", true, "deliveryReceiptEnabled", false, "mutationAllowed", false)
                )),
                Map.entry("message", "Local Agent mutation handoff prerequisites are modeled, but all handoff controls remain disabled.")
        );
        Map<String, Object> mutationExecutionReadinessBoundary = Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.mutation-execution-readiness-boundary.v1"),
                Map.entry("status", "REFUSED_EXECUTION_READINESS_DISABLED"),
                Map.entry("prerequisitesPassed", true),
                Map.entry("blocking", true),
                Map.entry("releaseAttemptId", attemptId),
                Map.entry("sourceRequestId", requestId),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("sourceHandoffSummaryStatus", "READY_HANDOFF_DISABLED"),
                Map.entry("sourceExecutionGateStatus", "REFUSED_EXECUTION_DISABLED"),
                Map.entry("sourceWriteHelperSafetyGateStatus", "REFUSED_WRITE_HELPER_DISABLED"),
                Map.entry("expectedRequestCount", 4),
                Map.entry("completedRequestCount", 0),
                Map.entry("readinessChecks", List.of(
                        Map.of("key", "mutationHandoffSummary", "status", "READY_HANDOFF_DISABLED", "passed", true, "executionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "mutationExecutionGate", "status", "REFUSED_EXECUTION_DISABLED", "passed", true, "executionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "mutationWriteHelperSafetyGate", "status", "REFUSED_WRITE_HELPER_DISABLED", "passed", true, "writeHelperEnabled", false, "mutationAllowed", false),
                        Map.of("key", "runtimeExecutionSwitch", "status", "DISABLED", "passed", false, "executionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "sideEffectTransport", "status", "DISABLED", "passed", false, "requestCreationEnabled", false, "mutationAllowed", false)
                )),
                Map.entry("releaseGateEnabled", false),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("executionEnabled", false),
                Map.entry("toolRunnerEnabled", false),
                Map.entry("writeHelperEnabled", false),
                Map.entry("applyEnabled", false),
                Map.entry("testEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("resultIntakeEnabled", false),
                Map.entry("mutationAllowed", false),
                Map.entry("blockingKeys", List.of("runtimeExecutionSwitch", "sideEffectTransport", "releaseGateEnabled", "requestCreationEnabled", "pushEnabled", "claimEnabled", "executionEnabled", "writeHelperEnabled", "applyEnabled", "testEnabled", "rollbackRestoreEnabled", "resultIntakeEnabled", "mutationAllowed"))
        );
        Map<String, Object> mutationToolRunnerBoundary = Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.mutation-tool-runner-boundary.v1"),
                Map.entry("status", "REFUSED_TOOL_RUNNER_DISABLED"),
                Map.entry("prerequisitesPassed", true),
                Map.entry("blocking", true),
                Map.entry("releaseAttemptId", attemptId),
                Map.entry("sourceRequestId", requestId),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("sourceExecutionReadinessBoundaryStatus", "REFUSED_EXECUTION_READINESS_DISABLED"),
                Map.entry("sourceExecutionGateStatus", "REFUSED_EXECUTION_DISABLED"),
                Map.entry("toolRunnerPolicy", "DISABLED_AUDIT_ONLY"),
                Map.entry("expectedRequestCount", 4),
                Map.entry("runningRequestCount", 0),
                Map.entry("completedRequestCount", 0),
                Map.entry("runnerChecks", List.of(
                        Map.of("key", "mutationExecutionReadinessBoundary", "status", "REFUSED_EXECUTION_READINESS_DISABLED", "passed", true, "toolRunnerEnabled", false, "mutationAllowed", false),
                        Map.of("key", "mutationExecutionGate", "status", "REFUSED_EXECUTION_DISABLED", "passed", true, "toolRunnerEnabled", false, "mutationAllowed", false),
                        Map.of("key", "toolRunnerPolicy", "status", "DISABLED", "passed", false, "toolRunnerEnabled", false, "mutationAllowed", false),
                        Map.of("key", "requestRunningTransition", "status", "DISABLED", "passed", false, "runningTransitionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "resultCompletionTransition", "status", "DISABLED", "passed", false, "resultIntakeEnabled", false, "mutationAllowed", false)
                )),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("runningTransitionEnabled", false),
                Map.entry("executionEnabled", false),
                Map.entry("toolRunnerEnabled", false),
                Map.entry("writeHelperEnabled", false),
                Map.entry("applyEnabled", false),
                Map.entry("testEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("resultIntakeEnabled", false),
                Map.entry("mutationAllowed", false),
                Map.entry("blockingKeys", List.of("toolRunnerPolicy", "requestRunningTransition", "resultCompletionTransition", "requestCreationEnabled", "pushEnabled", "claimEnabled", "runningTransitionEnabled", "toolRunnerEnabled", "writeHelperEnabled", "applyEnabled", "testEnabled", "rollbackRestoreEnabled", "resultIntakeEnabled", "mutationAllowed"))
        );
        Map<String, Object> mutationResultCompletionBoundary = Map.ofEntries(
                Map.entry("schema", "learnbot.local-agent.mutation-result-completion-boundary.v1"),
                Map.entry("status", "REFUSED_RESULT_COMPLETION_DISABLED"),
                Map.entry("prerequisitesPassed", true),
                Map.entry("blocking", true),
                Map.entry("releaseAttemptId", attemptId),
                Map.entry("sourceRequestId", requestId),
                Map.entry("executionTarget", "USER_LOCAL_AGENT"),
                Map.entry("sourceToolRunnerBoundaryStatus", "REFUSED_TOOL_RUNNER_DISABLED"),
                Map.entry("sourcePostExecutionObservationGateStatus", "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED"),
                Map.entry("completionPolicy", "DISABLED_AUDIT_ONLY"),
                Map.entry("expectedResultCount", 4),
                Map.entry("completedResultCount", 0),
                Map.entry("acceptedResultCount", 0),
                Map.entry("rejectedResultCount", 0),
                Map.entry("resultChecks", List.of(
                        Map.of("key", "mutationToolRunnerBoundary", "status", "REFUSED_TOOL_RUNNER_DISABLED", "passed", true, "completedResultTransitionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "mutationPostExecutionObservationGate", "status", "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED", "passed", true, "postExecutionObservationEnabled", false, "mutationAllowed", false),
                        Map.of("key", "completedResultTransition", "status", "DISABLED", "passed", false, "completedResultTransitionEnabled", false, "mutationAllowed", false),
                        Map.of("key", "resultEnvelopePersistence", "status", "DISABLED", "passed", false, "completedResultPersistenceEnabled", false, "mutationAllowed", false),
                        Map.of("key", "observationCapture", "status", "DISABLED", "passed", false, "postExecutionObservationEnabled", false, "mutationAllowed", false)
                )),
                Map.entry("toolRunnerEnabled", false),
                Map.entry("completedResultTransitionEnabled", false),
                Map.entry("completedResultPersistenceEnabled", false),
                Map.entry("postExecutionObservationEnabled", false),
                Map.entry("resultIntakeEnabled", false),
                Map.entry("mutationAllowed", false),
                Map.entry("blockingKeys", List.of("completedResultTransition", "resultEnvelopePersistence", "observationCapture", "toolRunnerEnabled", "completedResultTransitionEnabled", "completedResultPersistenceEnabled", "postExecutionObservationEnabled", "resultIntakeEnabled", "mutationAllowed"))
        );
        LocalAgentPatchExecutionReadinessResponse expected = new LocalAgentPatchExecutionReadinessResponse(
                requestId,
                false,
                List.of(),
                List.of(),
                "Mutation handoff remains disabled.",
                Map.of(),
                Map.of("claimEnabled", false, "mutationEnabled", false, "releaseGateEnabled", false),
                new LocalAgentPatchReleaseAttemptModel(
                        "learnbot.local-agent.patch-release-attempt.v1",
                        "DISABLED",
                        true,
                        false,
                        120,
                        List.of(),
                        Map.of(
                                "mutationHandoffSummary", mutationHandoffSummary,
                                "mutationExecutionReadinessBoundary", mutationExecutionReadinessBoundary,
                                "mutationToolRunnerBoundary", mutationToolRunnerBoundary,
                                "mutationResultCompletionBoundary", mutationResultCompletionBoundary
                        ),
                        "disabled"
                ),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(toolGatewayService.inspectPatchExecutionReadiness(userId, requestId)).thenReturn(expected);

        var actual = controller.toolReadiness(requestId);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(actual));
        JsonNode summary = json.at("/releaseAttemptModel/latestAttempt/mutationHandoffSummary");

        assertThat(actual).isSameAs(expected);
        verify(toolGatewayService).inspectPatchExecutionReadiness(userId, requestId);
        assertThat(summary.path("schema").asText()).isEqualTo("learnbot.local-agent.mutation-handoff-summary.v1");
        assertThat(summary.path("status").asText()).isEqualTo("READY_HANDOFF_DISABLED");
        assertThat(summary.path("prerequisitesPassed").asBoolean()).isTrue();
        assertThat(summary.path("sourceCompletionSummaryStatus").asText()).isEqualTo("READY_COMPLETION_DISABLED");
        assertThat(summary.path("disabledControls").path("releaseGateEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("requestCreationEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("pushEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("claimEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("writeHelperEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("applyEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("testEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("rollbackRestoreEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("ragFreshnessUpdateEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("finalAnswerGenerationEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("finalResponseHandoffEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("deliveryReceiptEnabled").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("claimable").asBoolean()).isFalse();
        assertThat(summary.path("disabledControls").path("mutationAllowed").asBoolean()).isFalse();
        assertThat(summary.path("blockingKeys")).hasSize(5);
        assertThat(summary.path("handoffStages")).hasSize(8);
        assertThat(summary.path("handoffStages").get(0).path("key").asText()).isEqualTo("dispatchDecision");
        assertThat(summary.path("handoffStages").get(7).path("key").asText()).isEqualTo("deliveryReceipt");
        JsonNode executionBoundary = json.at("/releaseAttemptModel/latestAttempt/mutationExecutionReadinessBoundary");
        assertThat(executionBoundary.path("schema").asText()).isEqualTo("learnbot.local-agent.mutation-execution-readiness-boundary.v1");
        assertThat(executionBoundary.path("status").asText()).isEqualTo("REFUSED_EXECUTION_READINESS_DISABLED");
        assertThat(executionBoundary.path("prerequisitesPassed").asBoolean()).isTrue();
        assertThat(executionBoundary.path("sourceHandoffSummaryStatus").asText()).isEqualTo("READY_HANDOFF_DISABLED");
        assertThat(executionBoundary.path("sourceExecutionGateStatus").asText()).isEqualTo("REFUSED_EXECUTION_DISABLED");
        assertThat(executionBoundary.path("sourceWriteHelperSafetyGateStatus").asText()).isEqualTo("REFUSED_WRITE_HELPER_DISABLED");
        assertThat(executionBoundary.path("expectedRequestCount").asInt()).isEqualTo(4);
        assertThat(executionBoundary.path("completedRequestCount").asInt()).isZero();
        assertThat(executionBoundary.path("readinessChecks")).hasSize(5);
        assertThat(executionBoundary.path("releaseGateEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("requestCreationEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("pushEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("claimEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("executionEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("toolRunnerEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("writeHelperEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("applyEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("testEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("rollbackRestoreEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("resultIntakeEnabled").asBoolean()).isFalse();
        assertThat(executionBoundary.path("mutationAllowed").asBoolean()).isFalse();
        assertThat(executionBoundary.path("blockingKeys")).hasSize(13);
        JsonNode toolRunnerBoundary = json.at("/releaseAttemptModel/latestAttempt/mutationToolRunnerBoundary");
        assertThat(toolRunnerBoundary.path("schema").asText()).isEqualTo("learnbot.local-agent.mutation-tool-runner-boundary.v1");
        assertThat(toolRunnerBoundary.path("status").asText()).isEqualTo("REFUSED_TOOL_RUNNER_DISABLED");
        assertThat(toolRunnerBoundary.path("prerequisitesPassed").asBoolean()).isTrue();
        assertThat(toolRunnerBoundary.path("sourceExecutionReadinessBoundaryStatus").asText()).isEqualTo("REFUSED_EXECUTION_READINESS_DISABLED");
        assertThat(toolRunnerBoundary.path("sourceExecutionGateStatus").asText()).isEqualTo("REFUSED_EXECUTION_DISABLED");
        assertThat(toolRunnerBoundary.path("toolRunnerPolicy").asText()).isEqualTo("DISABLED_AUDIT_ONLY");
        assertThat(toolRunnerBoundary.path("expectedRequestCount").asInt()).isEqualTo(4);
        assertThat(toolRunnerBoundary.path("runningRequestCount").asInt()).isZero();
        assertThat(toolRunnerBoundary.path("completedRequestCount").asInt()).isZero();
        assertThat(toolRunnerBoundary.path("runnerChecks")).hasSize(5);
        assertThat(toolRunnerBoundary.path("requestCreationEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("pushEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("claimEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("runningTransitionEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("executionEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("toolRunnerEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("writeHelperEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("applyEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("testEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("rollbackRestoreEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("resultIntakeEnabled").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("mutationAllowed").asBoolean()).isFalse();
        assertThat(toolRunnerBoundary.path("blockingKeys")).hasSize(14);
        JsonNode resultCompletionBoundary = json.at("/releaseAttemptModel/latestAttempt/mutationResultCompletionBoundary");
        assertThat(resultCompletionBoundary.path("schema").asText()).isEqualTo("learnbot.local-agent.mutation-result-completion-boundary.v1");
        assertThat(resultCompletionBoundary.path("status").asText()).isEqualTo("REFUSED_RESULT_COMPLETION_DISABLED");
        assertThat(resultCompletionBoundary.path("prerequisitesPassed").asBoolean()).isTrue();
        assertThat(resultCompletionBoundary.path("sourceToolRunnerBoundaryStatus").asText()).isEqualTo("REFUSED_TOOL_RUNNER_DISABLED");
        assertThat(resultCompletionBoundary.path("sourcePostExecutionObservationGateStatus").asText()).isEqualTo("REFUSED_POST_EXECUTION_OBSERVATION_DISABLED");
        assertThat(resultCompletionBoundary.path("completionPolicy").asText()).isEqualTo("DISABLED_AUDIT_ONLY");
        assertThat(resultCompletionBoundary.path("expectedResultCount").asInt()).isEqualTo(4);
        assertThat(resultCompletionBoundary.path("completedResultCount").asInt()).isZero();
        assertThat(resultCompletionBoundary.path("acceptedResultCount").asInt()).isZero();
        assertThat(resultCompletionBoundary.path("rejectedResultCount").asInt()).isZero();
        assertThat(resultCompletionBoundary.path("resultChecks")).hasSize(5);
        assertThat(resultCompletionBoundary.path("toolRunnerEnabled").asBoolean()).isFalse();
        assertThat(resultCompletionBoundary.path("completedResultTransitionEnabled").asBoolean()).isFalse();
        assertThat(resultCompletionBoundary.path("completedResultPersistenceEnabled").asBoolean()).isFalse();
        assertThat(resultCompletionBoundary.path("postExecutionObservationEnabled").asBoolean()).isFalse();
        assertThat(resultCompletionBoundary.path("resultIntakeEnabled").asBoolean()).isFalse();
        assertThat(resultCompletionBoundary.path("mutationAllowed").asBoolean()).isFalse();
        assertThat(resultCompletionBoundary.path("blockingKeys")).hasSize(9);
    }
}
