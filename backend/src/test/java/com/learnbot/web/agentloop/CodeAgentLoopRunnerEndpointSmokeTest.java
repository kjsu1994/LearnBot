package com.learnbot.web.agentloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.dto.PatchValidationResult;
import com.learnbot.repository.CodeAgentLoopTimelineRepository;
import com.learnbot.repository.CodeRepository;
import com.learnbot.repository.LocalAgentMutationObservationIntakeRepository;
import com.learnbot.repository.LocalAgentPatchReleaseAttemptRepository;
import com.learnbot.repository.LocalAgentToolExecutionRepository;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AppUser;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeAgentApplyService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodeIndexingService;
import com.learnbot.service.CodePatchFileLoader;
import com.learnbot.service.CodeRepositoryRecord;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;
import com.learnbot.service.LocalAgentToken;
import com.learnbot.service.LocalAgentToolExecution;
import com.learnbot.service.LocalAgentToolGatewayService;
import com.learnbot.service.LocalAgentToolPusher;
import com.learnbot.service.OllamaClient;
import com.learnbot.service.PatchValidationService;
import com.learnbot.service.agentloop.CodeAgentLoopRunnerService;
import com.learnbot.service.agentloop.CodeAgentLoopToolSelectionService;
import com.learnbot.web.CodeAgentController;
import com.learnbot.web.LocalAgentController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodeAgentLoopRunnerEndpointSmokeTest {
    @Test
    void enqueueReadOnlyEndpointCanQueueClaimCompleteAndRecordTimeline() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");

        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                previewService,
                runnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                14,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(invocation.getArgument(0), invocation.getArgument(1), LocalAgentToolStatus.PENDING));

        mockMvc.perform(post("/api/code-agent/loop/runner/enqueue-read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s"
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerDecision").value("ENQUEUED_READ_ONLY_OBSERVATION"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(true))
                .andExpect(jsonPath("$.enqueueEnabled").value(true))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.queuedRequest.request.toolName").value("git.status"));

        ArgumentCaptor<LocalAgentQueuedToolRequest> queuedCaptor = ArgumentCaptor.forClass(LocalAgentQueuedToolRequest.class);
        verify(authService).requireSpace(user, repositorySpaceId);
        verify(toolPusher).sendToolRequest(queuedCaptor.capture());

        LocalAgentQueuedToolRequest queued = queuedCaptor.getValue();
        LocalAgentToolRequest request = queued.request();
        LocalAgentToolExecution running = execution(queued.requestId(), request, LocalAgentToolStatus.RUNNING);
        when(repository.expireTimedOutLeases()).thenReturn(List.of());
        when(repository.claimNext(userId, agentId)).thenReturn(Optional.of(running));
        when(repository.find(queued.requestId())).thenReturn(Optional.of(running));

        var claimed = realGateway.claimNext(userId, agentId).orElseThrow();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                loopId,
                queued.requestId(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "clean", true,
                        "branch", "main",
                        "repositoryVerification", Map.of("status", "MATCH")
                ),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );

        realGateway.complete(response);
        when(repository.find(queued.requestId())).thenReturn(Optional.of(completedExecution(
                queued.requestId(),
                request,
                response.output()
        )));
        LocalAgentController localAgentController = new LocalAgentController(
                gatewayService,
                mock(LocalAgentAuthService.class),
                realGateway,
                currentUserProvider
        );
        var localAgentMvc = MockMvcBuilders.standaloneSetup(localAgentController).build();
        localAgentMvc.perform(get("/api/local-agents/tools/{requestId}", queued.requestId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(queued.requestId().toString()))
                .andExpect(jsonPath("$.executionTarget").value("USER_LOCAL_AGENT"))
                .andExpect(jsonPath("$.toolName").value("git.status"))
                .andExpect(jsonPath("$.approvalState").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.input.repositoryId").value(repositoryId.toString()))
                .andExpect(jsonPath("$.input.loopId").value(loopId.toString()))
                .andExpect(jsonPath("$.input.freshObservationOnly").value(true))
                .andExpect(jsonPath("$.input.mutationAllowed").value(false))
                .andExpect(jsonPath("$.output.repositoryVerification.status").value("MATCH"));

        when(previewService.recentTimelines(userId, repositoryId, 5)).thenReturn(List.of(new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                repositorySpaceId,
                "selected read-only runner",
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                List.of(
                        new CodeAgentLoopTimelineEventSummary(
                                UUID.randomUUID(),
                                15,
                                "LOCAL_AGENT_OBSERVATION_RESULT",
                                "OBSERVE",
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.GIT_STATUS,
                                false,
                                false,
                                true,
                                Map.of(
                                        "status", "SUCCEEDED",
                                        "freshObservationOnly", true,
                                        "mutationApplied", false,
                                        "requestId", queued.requestId().toString()
                                ),
                                OffsetDateTime.now()
                        ),
                        new CodeAgentLoopTimelineEventSummary(
                                UUID.randomUUID(),
                                16,
                                "LOOP_NEXT_DECISION_RECORDED",
                                "COMPLETE_OR_PAUSE",
                                AgentExecutionTarget.SERVER_LOCAL,
                                null,
                                false,
                                false,
                                true,
                                Map.of(
                                        "decisionKey", "OBSERVATION_ACCEPTED",
                                        "requestCreationEnabled", false,
                                        "pushEnabled", false,
                                        "claimEnabled", false,
                                        "mutationEnabled", false
                                ),
                                OffsetDateTime.now()
                        )
                )
        )));
        mockMvc.perform(get("/api/code-agent/loop/timelines")
                        .param("repositoryId", repositoryId.toString())
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(loopId.toString()))
                .andExpect(jsonPath("$[0].events[0].eventType").value("LOCAL_AGENT_OBSERVATION_RESULT"))
                .andExpect(jsonPath("$[0].events[0].toolName").value("git.status"))
                .andExpect(jsonPath("$[0].events[0].mayMutate").value(false))
                .andExpect(jsonPath("$[0].events[0].details.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].events[0].details.freshObservationOnly").value(true))
                .andExpect(jsonPath("$[0].events[0].details.mutationApplied").value(false))
                .andExpect(jsonPath("$[0].events[1].eventType").value("LOOP_NEXT_DECISION_RECORDED"))
                .andExpect(jsonPath("$[0].events[1].executionTarget").value("SERVER_LOCAL"))
                .andExpect(jsonPath("$[0].events[1].details.decisionKey").value("OBSERVATION_ACCEPTED"))
                .andExpect(jsonPath("$[0].events[1].details.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$[0].events[1].details.pushEnabled").value(false))
                .andExpect(jsonPath("$[0].events[1].details.claimEnabled").value(false))
                .andExpect(jsonPath("$[0].events[1].details.mutationEnabled").value(false));

        assertThat(claimed.requestId()).isEqualTo(queued.requestId());
        assertThat(claimed.request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        verify(repository).claimNext(userId, agentId);
        verify(repository).complete(response);
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(loopTimelineRepository).appendNextDecision(userId, repositoryId, loopId, response, request.input());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void enqueueSelectedReadOnlyEndpointCanQueueClaimCompleteAndRecordTimeline() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");

        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);

        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentLoopToolSelectionService selectionService = new CodeAgentLoopToolSelectionService(
                runnerService,
                previewService,
                realGateway,
                mock(CodeAgentLocalPatchRequestService.class),
                ollamaClient,
                new ObjectMapper()
        );
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                previewService,
                runnerService,
                selectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                14,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        OllamaClient.ChatResult gitStatusSelection = new OllamaClient.ChatResult(
                """
                        {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.status","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Check current workspace state."}
                        """,
                "stop",
                true,
                0,
                0,
                "http://localhost:11434",
                "test",
                "PRIMARY",
                false
        );
        OllamaClient.ChatResult gitDiffSelection = new OllamaClient.ChatResult(
                """
                        {"actionKey":"QUEUE_READ_ONLY_OBSERVATION","toolName":"git.diff","readOnly":true,"requiresApproval":false,"mutationAllowed":false,"reason":"Inspect bounded workspace diff after status."}
                        """,
                "stop",
                true,
                0,
                0,
                "http://localhost:11434",
                "test",
                "PRIMARY",
                false
        );
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400)))
                .thenReturn(gitStatusSelection, gitStatusSelection, gitDiffSelection);
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(invocation.getArgument(0), invocation.getArgument(1), LocalAgentToolStatus.PENDING));

        mockMvc.perform(post("/api/code-agent/loop/runner/select-tool-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s"
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionDecision").value("MODEL_SELECTED_READ_ONLY_CANDIDATE"))
                .andExpect(jsonPath("$.modelToolSelectionAttempted").value(true))
                .andExpect(jsonPath("$.modelToolSelectionAccepted").value(true))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.candidate.toolName").value("git.status"));

        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(toolPusher, never()).sendToolRequest(any());

        mockMvc.perform(post("/api/code-agent/loop/runner/enqueue-selected-read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s"
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerDecision").value("ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION"))
                .andExpect(jsonPath("$.modelToolSelectionAttempted").value(true))
                .andExpect(jsonPath("$.modelToolSelectionAccepted").value(true))
                .andExpect(jsonPath("$.requestCreationEnabled").value(true))
                .andExpect(jsonPath("$.enqueueEnabled").value(true))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.queuedRequest.request.toolName").value("git.status"));

        ArgumentCaptor<LocalAgentQueuedToolRequest> queuedCaptor = ArgumentCaptor.forClass(LocalAgentQueuedToolRequest.class);
        verify(authService, times(2)).requireSpace(user, repositorySpaceId);
        verify(toolPusher).sendToolRequest(queuedCaptor.capture());

        LocalAgentQueuedToolRequest queued = queuedCaptor.getValue();
        LocalAgentToolRequest request = queued.request();
        LocalAgentToolExecution running = execution(queued.requestId(), request, LocalAgentToolStatus.RUNNING);
        when(repository.expireTimedOutLeases()).thenReturn(List.of());
        when(repository.claimNext(userId, agentId)).thenReturn(Optional.of(running));
        when(repository.find(queued.requestId())).thenReturn(Optional.of(running));

        var claimed = realGateway.claimNext(userId, agentId).orElseThrow();
        LocalAgentToolResponse response = new LocalAgentToolResponse(
                loopId,
                queued.requestId(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("clean", true, "branch", "main"),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );

        realGateway.complete(response);
        when(repository.find(queued.requestId())).thenReturn(Optional.of(execution(
                queued.requestId(),
                request,
                LocalAgentToolStatus.SUCCEEDED
        )));
        when(previewService.recentTimelines(userId, repositoryId, 10)).thenReturn(List.of(new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                repositorySpaceId,
                "Observe repository state.",
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                List.of(new CodeAgentLoopTimelineEventSummary(
                        UUID.randomUUID(),
                        1,
                        "LOCAL_AGENT_OBSERVATION_RESULT",
                        "OBSERVE",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.GIT_STATUS,
                        false,
                        false,
                        true,
                        Map.of("status", "SUCCEEDED"),
                        OffsetDateTime.now()
                ))
        )));

        mockMvc.perform(post("/api/code-agent/loop/runner/continue-after-observation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s",
                                  "requestId": "%s"
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId, queued.requestId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continuationDecision").value("NEXT_MODEL_TOOL_PREVIEW_READY"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.iterationCount").value(1))
                .andExpect(jsonPath("$.maxIterations").value(6))
                .andExpect(jsonPath("$.remainingIterations").value(5))
                .andExpect(jsonPath("$.iterationLimitReached").value(false))
                .andExpect(jsonPath("$.observation.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runnerPreview.runnerDecision").value("PREPARED_READ_ONLY_CANDIDATE"))
                .andExpect(jsonPath("$.runnerPreview.candidate.toolName").value("git.diff"))
                .andExpect(jsonPath("$.toolSelectionPreview.selectionDecision").value("MODEL_SELECTED_READ_ONLY_CANDIDATE"))
                .andExpect(jsonPath("$.toolSelectionPreview.candidate.toolName").value("git.diff"));

        when(previewService.recentTimelines(userId, repositoryId, 10)).thenReturn(List.of(new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                repositorySpaceId,
                "Observe repository state.",
                "PREVIEW_ONLY",
                2,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                List.of(
                        new CodeAgentLoopTimelineEventSummary(
                                UUID.randomUUID(),
                                1,
                                "LOCAL_AGENT_OBSERVATION_RESULT",
                                "OBSERVE",
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.GIT_STATUS,
                                false,
                                false,
                                true,
                                Map.of("status", "SUCCEEDED"),
                                OffsetDateTime.now()
                        ),
                        new CodeAgentLoopTimelineEventSummary(
                                UUID.randomUUID(),
                                2,
                                "LOCAL_AGENT_OBSERVATION_RESULT",
                                "OBSERVE",
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.GIT_STATUS,
                                false,
                                false,
                                true,
                                Map.of("status", "SUCCEEDED"),
                                OffsetDateTime.now()
                        )
                )
        )));

        mockMvc.perform(post("/api/code-agent/loop/runner/continue-after-observation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s",
                                  "requestId": "%s"
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId, queued.requestId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continuationDecision").value("ITERATION_LIMIT_REACHED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.iterationCount").value(2))
                .andExpect(jsonPath("$.maxIterations").value(2))
                .andExpect(jsonPath("$.remainingIterations").value(0))
                .andExpect(jsonPath("$.iterationLimitReached").value(true))
                .andExpect(jsonPath("$.observation.status").value("SUCCEEDED"));

        assertThat(claimed.requestId()).isEqualTo(queued.requestId());
        assertThat(claimed.request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
        assertThat(request.input()).containsEntry("mutationAllowed", false);
        verify(authService, times(4)).requireSpace(user, repositorySpaceId);
        verify(ollamaClient, times(3)).chatResult(anyString(), anyString(), eq(400));
        verify(repository).claimNext(userId, agentId);
        verify(repository).complete(response);
        verify(loopTimelineRepository).appendObservationResult(userId, repositoryId, loopId, response, request.input());
        verify(loopTimelineRepository).appendNextDecision(userId, repositoryId, loopId, response, request.input());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void runnerEndpointsExposeCreationDisabledHandoffSummaryWithoutQueueing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");

        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                previewService,
                runnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        Map<String, Object> handoffSummary = creationDisabledHandoffSummary();
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "READY_HANDOFF_CREATION_DISABLED",
                "Mutation handoff is ready, but Local Agent mutation request creation is disabled.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                sourceEventId,
                21,
                "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                handoffSummary,
                Map.of("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED")
        ));

        String body = """
                {
                  "repositoryId": "%s",
                  "loopId": "%s",
                  "agentId": "%s",
                  "workspaceId": "%s"
                }
                """.formatted(repositoryId, loopId, agentId, workspaceId);

        mockMvc.perform(post("/api/code-agent/loop/runner/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.actionKey").value("READY_HANDOFF_CREATION_DISABLED"))
                .andExpect(jsonPath("$.runnerDecision").value("WAIT_CREATION_GATE_DISABLED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.finalResultEnabled").value(false))
                .andExpect(jsonPath("$.publicationEnabled").value(false))
                .andExpect(jsonPath("$.acknowledgementEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.schema").value("learnbot.code-agent.creation-disabled-handoff-summary.v1"))
                .andExpect(jsonPath("$.handoffSummary.status").value("READY_HANDOFF_CREATION_DISABLED"))
                .andExpect(jsonPath("$.handoffSummary.expectedRequestCount").value(4))
                .andExpect(jsonPath("$.handoffSummary.durableMutationExecutionRowCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.persistedRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.pushedRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.claimableRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.pushEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.claimEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.mutationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.runnerDecision").value("WAIT_CREATION_GATE_DISABLED"))
                .andExpect(jsonPath("$.nextAction.handoffSummary.schema").value("learnbot.code-agent.creation-disabled-handoff-summary.v1"));

        mockMvc.perform(post("/api/code-agent/loop/runner/enqueue-read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.actionKey").value("READY_HANDOFF_CREATION_DISABLED"))
                .andExpect(jsonPath("$.runnerDecision").value("NOT_ENQUEUED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.finalResultEnabled").value(false))
                .andExpect(jsonPath("$.publicationEnabled").value(false))
                .andExpect(jsonPath("$.acknowledgementEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.schema").value("learnbot.code-agent.creation-disabled-handoff-summary.v1"))
                .andExpect(jsonPath("$.handoffSummary.status").value("READY_HANDOFF_CREATION_DISABLED"))
                .andExpect(jsonPath("$.handoffSummary.expectedRequestCount").value(4))
                .andExpect(jsonPath("$.handoffSummary.durableMutationExecutionRowCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.persistedRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.pushedRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.claimableRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.pushEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.claimEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.mutationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.runnerDecision").value("WAIT_CREATION_GATE_DISABLED"))
                .andExpect(jsonPath("$.preview.runnerDecision").value("WAIT_CREATION_GATE_DISABLED"))
                .andExpect(jsonPath("$.preview.handoffSummary.schema").value("learnbot.code-agent.creation-disabled-handoff-summary.v1"))
                .andExpect(jsonPath("$.preview.handoffSummary.status").value("READY_HANDOFF_CREATION_DISABLED"));

        verify(authService, org.mockito.Mockito.times(2)).requireSpace(user, repositorySpaceId);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void runnerEndpointsReloadGenericReleaseRefusalAsTerminalStopWithoutQueueing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID releaseBoundaryEventId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");

        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

        CodeAgentLoopPreviewService previewService = new CodeAgentLoopPreviewService(loopTimelineRepository);
        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentController controller = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                previewService,
                runnerService,
                mock(CodeAgentLoopToolSelectionService.class),
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        Map<String, Object> refusalDetails = Map.ofEntries(
                Map.entry("status", "RECORDED"),
                Map.entry("decisionKey", "RELEASE_BOUNDARY_REFUSED"),
                Map.entry("nextAction", "Report that release was refused and mutation remains disabled."),
                Map.entry("message", "Release review refused the boundary; report the disabled release state without creating claimable mutation work."),
                Map.entry("requestId", sourceRequestId.toString()),
                Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                Map.entry("boundaryStatus", "RELEASE_REFUSED_GATE_DISABLED"),
                Map.entry("actionMode", "REFUSAL_ONLY"),
                Map.entry("blockingReasons", List.of("release gate is disabled", "held patch request remains non-claimable")),
                Map.entry("releaseGateEnabled", false),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("claimable", false),
                Map.entry("mutationEnabled", false),
                Map.entry("rollbackRestoreEnabled", false),
                Map.entry("ragFreshnessUpdateEnabled", false),
                Map.entry("finalResultEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementEnabled", false)
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(loopTimelineRepository.findRecent(userId, repositoryId, 20)).thenReturn(List.of(new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                repositorySpaceId,
                "fix",
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                List.of(new CodeAgentLoopTimelineEventSummary(
                        releaseBoundaryEventId,
                        24,
                        "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                        "COMPLETE_OR_PAUSE",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.PATCH_APPLY,
                        true,
                        false,
                        true,
                        refusalDetails,
                        OffsetDateTime.now()
                ))
        )));

        String body = """
                {
                  "repositoryId": "%s",
                  "loopId": "%s",
                  "agentId": "%s",
                  "workspaceId": "%s"
                }
                """.formatted(repositoryId, loopId, agentId, workspaceId);

        mockMvc.perform(get("/api/code-agent/loop/next-action")
                        .param("repositoryId", repositoryId.toString())
                        .param("loopId", loopId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.actionKey").value("STOP_WITH_REASON"))
                .andExpect(jsonPath("$.reason").value("Report that release was refused and mutation remains disabled."))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.finalResultEnabled").value(false))
                .andExpect(jsonPath("$.publicationEnabled").value(false))
                .andExpect(jsonPath("$.acknowledgementEnabled").value(false))
                .andExpect(jsonPath("$.sourceEventId").value(releaseBoundaryEventId.toString()))
                .andExpect(jsonPath("$.sourceEventType").value("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED"))
                .andExpect(jsonPath("$.handoffSummary.schema").value("learnbot.code-agent.release-boundary-refusal-summary.v1"))
                .andExpect(jsonPath("$.handoffSummary.status").value("RELEASE_REVIEW_REFUSED_GATE_DISABLED"))
                .andExpect(jsonPath("$.handoffSummary.sourceRequestId").value(sourceRequestId.toString()))
                .andExpect(jsonPath("$.handoffSummary.releaseAttemptId").value(releaseAttemptId.toString()))
                .andExpect(jsonPath("$.handoffSummary.boundaryStatus").value("RELEASE_REFUSED_GATE_DISABLED"))
                .andExpect(jsonPath("$.handoffSummary.actionMode").value("REFUSAL_ONLY"))
                .andExpect(jsonPath("$.handoffSummary.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.pushEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.claimEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.claimable").value(false))
                .andExpect(jsonPath("$.handoffSummary.verificationCommandExecutionEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.rollbackRestoreEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.ragFreshnessUpdateEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.finalResultEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.publicationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.finalAnswerGenerationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.deliveryEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.acknowledgementEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.mutationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.runnerDecision").value("NO_REQUEST_PREPARED"));

        mockMvc.perform(post("/api/code-agent/loop/runner/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.actionKey").value("STOP_WITH_REASON"))
                .andExpect(jsonPath("$.runnerDecision").value("NO_REQUEST_PREPARED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.finalResultEnabled").value(false))
                .andExpect(jsonPath("$.publicationEnabled").value(false))
                .andExpect(jsonPath("$.acknowledgementEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.schema").value("learnbot.code-agent.release-boundary-refusal-summary.v1"))
                .andExpect(jsonPath("$.handoffSummary.status").value("RELEASE_REVIEW_REFUSED_GATE_DISABLED"))
                .andExpect(jsonPath("$.handoffSummary.runnerDecision").value("NO_REQUEST_PREPARED"))
                .andExpect(jsonPath("$.candidate").doesNotExist())
                .andExpect(jsonPath("$.nextAction.actionKey").value("STOP_WITH_REASON"))
                .andExpect(jsonPath("$.nextAction.handoffSummary.schema").value("learnbot.code-agent.release-boundary-refusal-summary.v1"));

        mockMvc.perform(post("/api/code-agent/loop/runner/enqueue-read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.actionKey").value("STOP_WITH_REASON"))
                .andExpect(jsonPath("$.runnerDecision").value("NOT_ENQUEUED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.schema").value("learnbot.code-agent.release-boundary-refusal-summary.v1"))
                .andExpect(jsonPath("$.preview.runnerDecision").value("NO_REQUEST_PREPARED"))
                .andExpect(jsonPath("$.preview.handoffSummary.schema").value("learnbot.code-agent.release-boundary-refusal-summary.v1"))
                .andExpect(jsonPath("$.queuedRequest").doesNotExist());

        mockMvc.perform(post("/api/code-agent/loop/runner/release-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerDecision").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.enqueueEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.preview.runnerDecision").value("NO_REQUEST_PREPARED"))
                .andExpect(jsonPath("$.preview.handoffSummary.schema").value("learnbot.code-agent.release-boundary-refusal-summary.v1"));

        verify(authService, org.mockito.Mockito.times(4)).requireSpace(user, repositorySpaceId);
        verify(repository, never()).create(any(UUID.class), any(LocalAgentToolRequest.class));
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void patchApprovalRequestEndpointCanBeApprovedHeldWithoutPushClaimOrMutation() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");

        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);

        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentLoopToolSelectionService selectionService = new CodeAgentLoopToolSelectionService(
                runnerService,
                previewService,
                realGateway,
                mock(CodeAgentLocalPatchRequestService.class),
                ollamaClient,
                new ObjectMapper()
        );
        CodeAgentController codeAgentController = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                mock(CodeAgentLocalPatchRequestService.class),
                previewService,
                runnerService,
                selectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        LocalAgentController localAgentController = new LocalAgentController(
                gatewayService,
                mock(LocalAgentAuthService.class),
                realGateway,
                currentUserProvider
        );
        var codeAgentMvc = MockMvcBuilders.standaloneSetup(codeAgentController).build();
        var localAgentMvc = MockMvcBuilders.standaloneSetup(localAgentController).build();

        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                14,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(new OllamaClient.ChatResult(
                """
                        {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                        """,
                "stop",
                true,
                0,
                0,
                "http://localhost:11434",
                "test",
                "PRIMARY",
                false
        ));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        LocalAgentApprovalState.REQUIRED,
                        LocalAgentToolStatus.APPROVAL_REQUIRED
                ));

        codeAgentMvc.perform(post("/api/code-agent/loop/runner/patch-approval-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s"
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalDecision").value("CREATED_PATCH_APPROVAL_REQUEST"))
                .andExpect(jsonPath("$.approvalRequestCreated").value(true))
                .andExpect(jsonPath("$.approvalRequest.toolName").value("patch.apply"))
                .andExpect(jsonPath("$.approvalRequest.approvalState").value("REQUIRED"))
                .andExpect(jsonPath("$.approvalRequest.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(true))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false));

        ArgumentCaptor<UUID> requestIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<LocalAgentToolRequest> requestCaptor = ArgumentCaptor.forClass(LocalAgentToolRequest.class);
        verify(repository).create(requestIdCaptor.capture(), requestCaptor.capture());
        UUID requestId = requestIdCaptor.getValue();
        LocalAgentToolRequest approvalRequest = requestCaptor.getValue();
        assertThat(approvalRequest.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
        assertThat(approvalRequest.approvalState()).isEqualTo(LocalAgentApprovalState.REQUIRED);
        assertThat(approvalRequest.input()).containsEntry("repositoryId", repositoryId.toString())
                .containsEntry("loopId", loopId.toString())
                .containsEntry("mutationAllowed", false);
        verify(loopTimelineRepository).appendApprovalRequestCreated(
                userId,
                repositoryId,
                requestId,
                approvalRequest.sessionId(),
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED.name(),
                LocalAgentToolStatus.APPROVAL_REQUIRED.name(),
                loopId,
                approvalRequest.input()
        );

        LocalAgentToolExecution awaitingApproval = execution(
                requestId,
                approvalRequest,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED
        );
        LocalAgentToolExecution approvedHeld = execution(
                requestId,
                approvalRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        when(repository.find(requestId)).thenReturn(Optional.of(awaitingApproval));
        when(repository.updateApprovalDecision(
                requestId,
                userId,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD,
                "Approved by user. Execution remains held until Local Agent patch execution is enabled."
        )).thenReturn(Optional.of(approvedHeld));

        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/approval", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value("patch.apply"))
                .andExpect(jsonPath("$.approvalState").value("APPROVED"))
                .andExpect(jsonPath("$.status").value("APPROVED_HELD"));

        when(repository.find(requestId)).thenReturn(Optional.of(approvedHeld));
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.CONNECTED,
                agentId,
                "0.1.0",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(
                        LocalAgentToolName.PATCH_APPLY.wireName(),
                        LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                        LocalAgentToolName.GIT_STATUS.wireName(),
                        LocalAgentToolName.ROLLBACK_RESTORE.wireName()
                ),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);

        localAgentMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/local-agents/tools/{requestId}/readiness", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToRelease").value(false))
                .andExpect(jsonPath("$.message").value("Held patch request is not ready for Local Agent execution."))
                .andExpect(jsonPath("$.patchReleaseReadiness.status").value("BLOCKED"))
                .andExpect(jsonPath("$.patchReleaseReadiness.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.patchReleaseReadiness.mutationEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.claimEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.mutationEnabled").value(false))
                .andExpect(jsonPath("$.checks[?(@.key=='approvedHeld')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='diffPresent')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='targetFilesPresent')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='expectedFilesPresent')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='snapshotRequired')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='snapshotManifestPreview')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='workspaceRepositoryVerified')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='releaseGateEnabled')].passed").value(org.hamcrest.Matchers.contains(false)));

        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopTimelineRepository).appendApprovalDecision(
                userId,
                repositoryId,
                requestId,
                approvalRequest.sessionId(),
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.APPROVED.name(),
                LocalAgentToolStatus.APPROVED_HELD.name(),
                loopId,
                approvalRequest.input()
        );
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void validatedPatchApprovalEndpointBuildsPayloadAndReadinessStillBlocksReleaseWithoutFreshEvidence() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID requestedSpaceId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        String path = "src/App.java";
        String content = "class App {}\n";
        String diff = """
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App {}
                +class App { /* ok */ }
                """;

        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = mock(PatchValidationService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);

        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLocalPatchRequestService localPatchRequestService = new CodeAgentLocalPatchRequestService(
                fileLoader,
                validationService,
                realGateway,
                codeRepository,
                gatewayService
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentLoopToolSelectionService selectionService = new CodeAgentLoopToolSelectionService(
                runnerService,
                previewService,
                realGateway,
                localPatchRequestService,
                ollamaClient,
                new ObjectMapper()
        );
        CodeAgentController codeAgentController = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                previewService,
                runnerService,
                selectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        LocalAgentController localAgentController = new LocalAgentController(
                gatewayService,
                mock(LocalAgentAuthService.class),
                realGateway,
                currentUserProvider
        );
        var codeAgentMvc = MockMvcBuilders.standaloneSetup(codeAgentController).build();
        var localAgentMvc = MockMvcBuilders.standaloneSetup(localAgentController).build();

        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                14,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(new OllamaClient.ChatResult(
                """
                        {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                        """,
                "stop",
                true,
                0,
                0,
                "http://localhost:11434",
                "test",
                "PRIMARY",
                false
        ));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(gatewayService.approvedWorkspace(userId, workspaceId)).thenReturn(Optional.of(
                new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)
        ));
        when(fileLoader.normalizeRequestedPaths(eq(List.of(path)), any())).thenReturn(List.of(path));
        when(validationService.validate(diff, List.of(path))).thenReturn(new PatchValidationResult(true, List.of("validated")));
        when(fileLoader.load(repositoryId, List.of(path))).thenReturn(new CodePatchFileLoader.LoadResult(
                List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), path, "java", content)),
                List.of("loaded")
        ));
        when(codeRepository.findRepository(repositoryId)).thenReturn(Optional.of(new CodeRepositoryRecord(
                repositoryId,
                repositorySpaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation ->
                execution(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        LocalAgentApprovalState.REQUIRED,
                        LocalAgentToolStatus.APPROVAL_REQUIRED
                ));

        codeAgentMvc.perform(post("/api/code-agent/loop/runner/validated-patch-approval-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "spaceId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s",
                                  "instruction": "fix",
                                  "diff": %s,
                                  "targetFiles": ["%s"]
                                }
                                """.formatted(repositoryId, requestedSpaceId, loopId, agentId, workspaceId, new ObjectMapper().writeValueAsString(diff), path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalDecision").value("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST"))
                .andExpect(jsonPath("$.approvalRequestCreated").value(true))
                .andExpect(jsonPath("$.approvalRequest.toolName").value("patch.apply"))
                .andExpect(jsonPath("$.approvalRequest.approvalState").value("REQUIRED"))
                .andExpect(jsonPath("$.approvalRequest.status").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.approvalRequest.input.diff").value(diff))
                .andExpect(jsonPath("$.approvalRequest.input.targetFiles[0]").value(path))
                .andExpect(jsonPath("$.approvalRequest.input.expectedFiles[0].path").value(path))
                .andExpect(jsonPath("$.approvalRequest.input.requiresSnapshot").value(true))
                .andExpect(jsonPath("$.requestCreationEnabled").value(true))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false));

        ArgumentCaptor<UUID> requestIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<LocalAgentToolRequest> requestCaptor = ArgumentCaptor.forClass(LocalAgentToolRequest.class);
        verify(repository).create(requestIdCaptor.capture(), requestCaptor.capture());
        UUID requestId = requestIdCaptor.getValue();
        LocalAgentToolRequest approvalRequest = requestCaptor.getValue();
        assertThat(approvalRequest.input()).containsEntry("repositoryId", repositoryId.toString())
                .containsEntry("spaceId", repositorySpaceId.toString())
                .containsEntry("loopId", loopId.toString())
                .containsEntry("diff", diff)
                .containsEntry("requiresSnapshot", true)
                .containsEntry("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH");
        assertThat(approvalRequest.input().get("expectedFiles").toString()).contains(path);
        assertThat(approvalRequest.input().get("snapshotPolicy").toString()).contains("TARGET_FILES", "LOCAL_AGENT_MANAGED");
        assertThat(approvalRequest.input().get("rollbackPolicy").toString()).contains("rollback.restore", "requiresUserApproval=true");
        verify(loopTimelineRepository).appendApprovalRequestCreated(
                userId,
                repositoryId,
                requestId,
                approvalRequest.sessionId(),
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED.name(),
                LocalAgentToolStatus.APPROVAL_REQUIRED.name(),
                loopId,
                approvalRequest.input()
        );

        LocalAgentToolExecution awaitingApproval = execution(
                requestId,
                approvalRequest,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED
        );
        LocalAgentToolExecution approvedHeld = execution(
                requestId,
                approvalRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        when(repository.find(requestId)).thenReturn(Optional.of(awaitingApproval));
        when(repository.updateApprovalDecision(
                requestId,
                userId,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD,
                "Approved by user. Execution remains held until Local Agent patch execution is enabled."
        )).thenReturn(Optional.of(approvedHeld));

        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/approval", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value("patch.apply"))
                .andExpect(jsonPath("$.approvalState").value("APPROVED"))
                .andExpect(jsonPath("$.status").value("APPROVED_HELD"));

        when(repository.find(requestId)).thenReturn(Optional.of(approvedHeld));
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.CONNECTED,
                agentId,
                "0.1.0",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(
                        LocalAgentToolName.PATCH_APPLY.wireName(),
                        LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                        LocalAgentToolName.GIT_STATUS.wireName(),
                        LocalAgentToolName.ROLLBACK_RESTORE.wireName()
                ),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);

        localAgentMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/local-agents/tools/{requestId}/readiness", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToRelease").value(false))
                .andExpect(jsonPath("$.message").value("Held patch request is not ready for Local Agent execution."))
                .andExpect(jsonPath("$.patchReleaseReadiness.status").value("BLOCKED"))
                .andExpect(jsonPath("$.patchReleaseReadiness.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.patchReleaseReadiness.mutationEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.claimEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.mutationEnabled").value(false))
                .andExpect(jsonPath("$.checks[?(@.key=='approvedHeld')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='diffPresent')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='targetFilesPresent')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='expectedFilesPresent')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='snapshotRequired')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='snapshotPolicy')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='rollbackPolicy')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='staleIndexPolicy')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='snapshotManifestPreview')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='rollbackRestorePreconditions')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='workspaceRepositoryVerified')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.checks[?(@.key=='releaseGateEnabled')].passed").value(org.hamcrest.Matchers.contains(false)));

        verify(authService).requireSpace(user, repositorySpaceId);
        verify(loopTimelineRepository).appendApprovalDecision(
                userId,
                repositoryId,
                requestId,
                approvalRequest.sessionId(),
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.APPROVED.name(),
                LocalAgentToolStatus.APPROVED_HELD.name(),
                loopId,
                approvalRequest.input()
        );
        verify(toolPusher, never()).sendToolRequest(any());
        verify(repository, never()).claimNext(any(), any());
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
    }

    @Test
    void validatedPatchApprovalEndpointCanQueueFreshObservationsWithoutReleasingSourcePatch() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID repositorySpaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        String path = "src/App.java";
        String content = "class App {}\n";
        String diff = """
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App {}
                +class App { /* ok */ }
                """;

        CodeAgentLoopPreviewService previewService = mock(CodeAgentLoopPreviewService.class);
        LocalAgentToolExecutionRepository repository = mock(LocalAgentToolExecutionRepository.class);
        LocalAgentMutationObservationIntakeRepository mutationObservationIntakeRepository = mock(LocalAgentMutationObservationIntakeRepository.class);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = mock(LocalAgentPatchReleaseAttemptRepository.class);
        CodeAgentLoopTimelineRepository loopTimelineRepository = mock(CodeAgentLoopTimelineRepository.class);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        CodeIndexingService indexingService = mock(CodeIndexingService.class);
        AuthService authService = mock(AuthService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentAuthService localAgentAuthService = mock(LocalAgentAuthService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
        PatchValidationService validationService = mock(PatchValidationService.class);
        CodeRepository codeRepository = mock(CodeRepository.class);

        LocalAgentToolGatewayService realGateway = new LocalAgentToolGatewayService(
                repository,
                mutationObservationIntakeRepository,
                releaseAttemptRepository,
                loopTimelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLocalPatchRequestService localPatchRequestService = new CodeAgentLocalPatchRequestService(
                fileLoader,
                validationService,
                realGateway,
                codeRepository,
                gatewayService
        );
        CodeAgentLoopRunnerService runnerService = new CodeAgentLoopRunnerService(previewService, realGateway);
        CodeAgentLoopToolSelectionService selectionService = new CodeAgentLoopToolSelectionService(
                runnerService,
                previewService,
                realGateway,
                localPatchRequestService,
                ollamaClient,
                new ObjectMapper()
        );
        CodeAgentController codeAgentController = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                previewService,
                runnerService,
                selectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        LocalAgentController localAgentController = new LocalAgentController(
                gatewayService,
                localAgentAuthService,
                realGateway,
                currentUserProvider
        );
        var codeAgentMvc = MockMvcBuilders.standaloneSetup(codeAgentController).build();
        var localAgentMvc = MockMvcBuilders.standaloneSetup(localAgentController).build();

        when(currentUserProvider.currentUser()).thenReturn(user);
        when(indexingService.repositorySpace(user, repositoryId)).thenReturn(repositorySpaceId);
        when(previewService.nextAction(userId, repositoryId, loopId)).thenReturn(new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "Evaluate the observation.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                14,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of("decisionKey", "OBSERVATION_ACCEPTED")
        ));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(400))).thenReturn(new OllamaClient.ChatResult(
                """
                        {"actionKey":"REQUIRES_APPROVAL_RELEASE","toolName":"patch.apply","readOnly":false,"requiresApproval":true,"mutationAllowed":false,"reason":"A patch is needed."}
                        """,
                "stop",
                true,
                0,
                0,
                "http://localhost:11434",
                "test",
                "PRIMARY",
                false
        ));
        when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
        when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);
        when(gatewayService.approvedWorkspace(userId, workspaceId)).thenReturn(Optional.of(
                new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)
        ));
        when(fileLoader.normalizeRequestedPaths(eq(List.of(path)), any())).thenReturn(List.of(path));
        when(validationService.validate(diff, List.of(path))).thenReturn(new PatchValidationResult(true, List.of("validated")));
        when(fileLoader.load(repositoryId, List.of(path))).thenReturn(new CodePatchFileLoader.LoadResult(
                List.of(new CodePatchFileLoader.LoadedPatchFile(UUID.randomUUID(), path, "java", content)),
                List.of("loaded")
        ));
        when(codeRepository.findRepository(repositoryId)).thenReturn(Optional.of(new CodeRepositoryRecord(
                repositoryId,
                repositorySpaceId,
                "learnbot",
                "GIT",
                "https://example.com/acme/learnbot.git",
                null,
                "https://example.com/acme/learnbot.git",
                "main",
                "NONE",
                "/server/repos/learnbot",
                "INDEXED",
                "abc123"
        )));
        when(repository.create(any(UUID.class), any(LocalAgentToolRequest.class))).thenAnswer(invocation -> {
            LocalAgentToolRequest request = invocation.getArgument(1);
            LocalAgentToolStatus status = switch (request.approvalState()) {
                case APPROVED -> LocalAgentToolStatus.APPROVED;
                case REQUIRED -> LocalAgentToolStatus.APPROVAL_REQUIRED;
                case NOT_REQUIRED -> LocalAgentToolStatus.PENDING;
                case DENIED -> LocalAgentToolStatus.REJECTED;
                case EXPIRED -> LocalAgentToolStatus.CANCELLED;
            };
            return execution(invocation.getArgument(0), request, request.approvalState(), status);
        });

        codeAgentMvc.perform(post("/api/code-agent/loop/runner/validated-patch-approval-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryId": "%s",
                                  "loopId": "%s",
                                  "agentId": "%s",
                                  "workspaceId": "%s",
                                  "instruction": "fix",
                                  "diff": %s,
                                  "targetFiles": ["%s"]
                                }
                                """.formatted(repositoryId, loopId, agentId, workspaceId, new ObjectMapper().writeValueAsString(diff), path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalDecision").value("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST"))
                .andExpect(jsonPath("$.approvalRequest.status").value("APPROVAL_REQUIRED"));

        ArgumentCaptor<UUID> requestIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<LocalAgentToolRequest> requestCaptor = ArgumentCaptor.forClass(LocalAgentToolRequest.class);
        verify(repository).create(requestIdCaptor.capture(), requestCaptor.capture());
        UUID sourceRequestId = requestIdCaptor.getValue();
        LocalAgentToolRequest sourceRequest = requestCaptor.getValue();
        LocalAgentToolExecution awaitingApproval = execution(
                sourceRequestId,
                sourceRequest,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED
        );
        LocalAgentToolExecution approvedHeld = execution(
                sourceRequestId,
                sourceRequest,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD
        );
        when(repository.find(sourceRequestId)).thenReturn(Optional.of(awaitingApproval));
        when(repository.updateApprovalDecision(
                sourceRequestId,
                userId,
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED_HELD,
                "Approved by user. Execution remains held until Local Agent patch execution is enabled."
        )).thenReturn(Optional.of(approvedHeld));

        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/approval", sourceRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_HELD"));

        when(repository.find(sourceRequestId)).thenReturn(Optional.of(approvedHeld));
        when(releaseAttemptRepository.findLatestForSourceRequest(userId, sourceRequestId)).thenReturn(Optional.of(new LocalAgentPatchReleaseAttempt(
                attemptId,
                sourceRequestId,
                sourceRequest.sessionId(),
                userId,
                agentId,
                workspaceId,
                LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS,
                false,
                120,
                Map.of(),
                List.of(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null
        )));

        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/fresh-observations", sourceRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].request.toolName").value("git.status"))
                .andExpect(jsonPath("$[0].request.approvalState").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$[0].request.input.sourceRequestId").value(sourceRequestId.toString()))
                .andExpect(jsonPath("$[0].request.input.releaseAttemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$[0].request.input.freshObservationOnly").value(true))
                .andExpect(jsonPath("$[1].request.toolName").value("patch.apply"))
                .andExpect(jsonPath("$[1].request.approvalState").value("APPROVED"))
                .andExpect(jsonPath("$[1].request.input.sourceRequestId").value(sourceRequestId.toString()))
                .andExpect(jsonPath("$[1].request.input.releaseAttemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$[1].request.input.dryRunOnly").value(true))
                .andExpect(jsonPath("$[1].request.input.mutationAllowed").value(false))
                .andExpect(jsonPath("$[1].request.input.freshObservationOnly").value(true));

        ArgumentCaptor<LocalAgentQueuedToolRequest> pushedCaptor = ArgumentCaptor.forClass(LocalAgentQueuedToolRequest.class);
        verify(toolPusher, org.mockito.Mockito.times(2)).sendToolRequest(pushedCaptor.capture());
        List<LocalAgentQueuedToolRequest> pushed = pushedCaptor.getAllValues();
        assertThat(pushed).extracting(LocalAgentQueuedToolRequest::requestId).doesNotContain(sourceRequestId);
        assertThat(pushed).extracting(item -> item.request().toolName())
                .containsExactly(LocalAgentToolName.GIT_STATUS, LocalAgentToolName.PATCH_APPLY);
        assertThat(pushed.get(1).request().input()).containsEntry("dryRunOnly", true)
                .containsEntry("mutationAllowed", false)
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", attemptId.toString())
                .containsEntry("freshObservationOnly", true);

        LocalAgentQueuedToolRequest repositoryObservation = pushed.get(0);
        LocalAgentQueuedToolRequest patchDryRun = pushed.get(1);
        String agentToken = "agent-token";
        when(localAgentAuthService.authenticate(agentToken)).thenReturn(new LocalAgentToken(
                UUID.randomUUID(),
                userId,
                agentId,
                "agent",
                OffsetDateTime.now().plusHours(1),
                null,
                null,
                OffsetDateTime.now()
        ));
        when(repository.find(repositoryObservation.requestId())).thenReturn(Optional.of(execution(
                repositoryObservation.requestId(),
                repositoryObservation.request(),
                LocalAgentToolStatus.PENDING
        )));
        when(repository.find(patchDryRun.requestId())).thenReturn(Optional.of(execution(
                patchDryRun.requestId(),
                patchDryRun.request(),
                LocalAgentApprovalState.APPROVED,
                LocalAgentToolStatus.APPROVED
        )));

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/response", repositoryObservation.requestId())
                        .header("X-Local-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LocalAgentToolResponse(
                                sourceRequest.sessionId(),
                                repositoryObservation.requestId(),
                                userId,
                                agentId,
                                workspaceId,
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.GIT_STATUS,
                                LocalAgentToolStatus.SUCCEEDED,
                                Map.of(
                                        "clean", true,
                                        "repositoryIdentity", Map.of(
                                                "branch", "main",
                                                "headCommit", "abc123",
                                                "remoteUrl", "https://example.com/acme/learnbot.git"
                                        )
                                ),
                                null,
                                null,
                                OffsetDateTime.now(),
                                OffsetDateTime.now(),
                                List.of()
                        ))))
                .andExpect(status().isNoContent());
        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/response", patchDryRun.requestId())
                        .header("X-Local-Agent-Token", agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LocalAgentToolResponse(
                                sourceRequest.sessionId(),
                                patchDryRun.requestId(),
                                userId,
                                agentId,
                                workspaceId,
                                AgentExecutionTarget.USER_LOCAL_AGENT,
                                LocalAgentToolName.PATCH_APPLY,
                                LocalAgentToolStatus.SUCCEEDED,
                                patchDryRunOutput(path),
                                null,
                                null,
                                OffsetDateTime.now(),
                                OffsetDateTime.now(),
                                List.of("dry-run completed without mutation")
                        ))))
                .andExpect(status().isNoContent());

        ArgumentCaptor<LocalAgentToolResponse> completedCaptor = ArgumentCaptor.forClass(LocalAgentToolResponse.class);
        verify(repository, org.mockito.Mockito.times(2)).complete(completedCaptor.capture());
        List<LocalAgentToolResponse> completedResponses = completedCaptor.getAllValues();
        Map<String, Object> repositoryVerification = completedResponses.stream()
                .filter(response -> response.toolName() == LocalAgentToolName.GIT_STATUS)
                .findFirst()
                .orElseThrow()
                .output();
        Map<String, Object> dryRunOutput = completedResponses.stream()
                .filter(response -> response.toolName() == LocalAgentToolName.PATCH_APPLY)
                .findFirst()
                .orElseThrow()
                .output();
        assertThat(repositoryVerification.get("repositoryVerification")).isInstanceOf(Map.class);
        assertThat(dryRunOutput)
                .containsEntry("dryRun", true)
                .containsEntry("mutationApplied", false)
                .containsEntry("snapshotCreated", true);
        when(repository.findLatestRepositoryVerificationForReleaseAttempt(userId, sourceRequestId, attemptId))
                .thenReturn(Optional.of((Map<String, Object>) repositoryVerification.get("repositoryVerification")));
        when(repository.findLatestPatchDryRunOutputForReleaseAttempt(userId, sourceRequestId, attemptId))
                .thenReturn(Optional.of(dryRunOutput));
        when(gatewayService.status(userId)).thenReturn(new LocalAgentStatusResponse(
                LocalAgentConnectionState.CONNECTED,
                agentId,
                "0.1.0",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(
                        LocalAgentToolName.PATCH_APPLY.wireName(),
                        LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName(),
                        LocalAgentToolName.GIT_STATUS.wireName(),
                        LocalAgentToolName.ROLLBACK_RESTORE.wireName()
                ),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true)),
                "polling",
                "polling",
                0,
                null,
                "Local Agent is connected."
        ));

        localAgentMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/local-agents/tools/{requestId}/readiness", sourceRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToRelease").value(false))
                .andExpect(jsonPath("$.checks[?(@.key=='approvedHeld')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='diffPresent')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='snapshotManifestPreview')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='rollbackRestorePreconditions')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='workspaceRepositoryVerified')].passed").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.checks[?(@.key=='releaseGateEnabled')].passed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.repositoryVerification.observationLinkage.status").value("RELEASE_ATTEMPT_LINKED"))
                .andExpect(jsonPath("$.repositoryVerification.observationLinkage.releaseAttemptLinked").value(true))
                .andExpect(jsonPath("$.snapshotReadiness.status").value("CREATED"))
                .andExpect(jsonPath("$.snapshotReadiness.observationLinkage.status").value("RELEASE_ATTEMPT_LINKED"))
                .andExpect(jsonPath("$.rollbackReadiness.status").value("RESTORE_VALIDATED"))
                .andExpect(jsonPath("$.patchExecutionGate.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.claimEnabled").value(false))
                .andExpect(jsonPath("$.patchExecutionGate.mutationEnabled").value(false));

        localAgentMvc.perform(post("/api/local-agents/tools/{requestId}/release", sourceRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_REFUSED_GATE_DISABLED"))
                .andExpect(jsonPath("$.actionMode").value("REFUSAL_ONLY"))
                .andExpect(jsonPath("$.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimable").value(false))
                .andExpect(jsonPath("$.mutationAllowed").value(false))
                .andExpect(jsonPath("$.applyEnabled").value(false))
                .andExpect(jsonPath("$.testEnabled").value(false))
                .andExpect(jsonPath("$.rollbackRestoreEnabled").value(false))
                .andExpect(jsonPath("$.ragFreshnessUpdateEnabled").value(false))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchEnvelopeContract.status").value("READY_DISPATCH_DISABLED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchEnvelopeContract.prerequisitesPassed").value(true))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchEnvelopeContract.orderedToolSequence.length()").value(4))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchPreflightBoundary.status").value("READY_PREFLIGHT_DISABLED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchPreflightBoundary.prerequisitesPassed").value(true))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchPreflightBoundary.missingCapabilities.length()").value(0))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchDecisionModel.status").value("REFUSED_DISPATCH_DISABLED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationDispatchDecisionModel.decision").value("REFUSE_DISPATCH"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.status").value("REFUSED_REQUEST_CREATION_DISABLED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.prerequisitesPassed").value(true))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests.length()").value(4))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.schema").value("learnbot.local-agent.expected-mutation-execution-row.v1"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.toolName").value("patch.apply"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.initialStatus").value("APPROVED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.persisted").value(false))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.pushed").value(false))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.claimable").value(false))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[0].expectedExecutionRow.inputContract.mutationAllowedWhenGateOpens").value(true))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[2].expectedExecutionRow.toolName").value("git.status"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[2].expectedExecutionRow.initialStatus").value("PENDING"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.orderedToolRequests[2].expectedExecutionRow.inputContract.mutationAllowedWhenGateOpens").value(false))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.status").value("REFUSED_CREATION_DISABLED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.expectedRequestCount").value(4))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.durableMutationExecutionRowCount").value(0))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.persistedRequestCount").value(0))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.pushedRequestCount").value(0))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.claimableRequestCount").value(0))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationRequestClaimGate.status").value("REFUSED_CLAIM_DISABLED"))
                .andExpect(jsonPath("$.releaseAttemptModel.latestAttempt.mutationExecutionGate.status").value("REFUSED_EXECUTION_DISABLED"));

        ArgumentCaptor<LocalAgentPatchReleaseBoundaryResponse> boundaryCaptor =
                ArgumentCaptor.forClass(LocalAgentPatchReleaseBoundaryResponse.class);
        verify(loopTimelineRepository).appendReleaseBoundaryRefusal(
                eq(userId),
                eq(repositoryId),
                eq(loopId),
                eq(sourceRequest.sessionId()),
                eq(agentId),
                eq(workspaceId),
                eq(AgentExecutionTarget.USER_LOCAL_AGENT),
                eq(LocalAgentToolName.PATCH_APPLY),
                boundaryCaptor.capture(),
                eq(sourceRequest.input())
        );
        LocalAgentPatchReleaseBoundaryResponse boundary = boundaryCaptor.getValue();
        assertThat(boundary.status()).isEqualTo("RELEASE_REFUSED_GATE_DISABLED");
        assertThat(boundary.claimable()).isFalse();
        assertThat(boundary.mutationAllowed()).isFalse();
        assertThat(boundary.releaseAttemptModel().latestAttempt().get("mutationRequestBlueprint")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> mutationRequestBlueprint = (Map<String, Object>) boundary.releaseAttemptModel()
                .latestAttempt()
                .get("mutationRequestBlueprint");
        assertThat(mutationRequestBlueprint)
                .containsEntry("status", "REFUSED_REQUEST_CREATION_DISABLED")
                .containsEntry("prerequisitesPassed", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("claimable", false)
                .containsEntry("mutationAllowed", false);

        CodeAgentLoopPreviewService realNextActionService = new CodeAgentLoopPreviewService(loopTimelineRepository);
        CodeAgentController nextActionController = new CodeAgentController(
                mock(CodeAgentService.class),
                mock(CodeAgentApplyService.class),
                localPatchRequestService,
                realNextActionService,
                runnerService,
                selectionService,
                indexingService,
                authService,
                currentUserProvider,
                new LearnBotProperties()
        );
        var nextActionMvc = MockMvcBuilders.standaloneSetup(nextActionController).build();
        UUID releaseBoundaryEventId = UUID.randomUUID();
        when(loopTimelineRepository.findRecent(userId, repositoryId, 20)).thenReturn(List.of(new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                repositorySpaceId,
                "fix",
                "PREVIEW_ONLY",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                List.of(new CodeAgentLoopTimelineEventSummary(
                        releaseBoundaryEventId,
                        20,
                        "LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED",
                        "COMPLETE_OR_PAUSE",
                        AgentExecutionTarget.USER_LOCAL_AGENT,
                        LocalAgentToolName.PATCH_APPLY,
                        true,
                        false,
                        true,
                        Map.of(
                                "status", "RECORDED",
                                "decisionKey", "RELEASE_BOUNDARY_REFUSED",
                                "nextAction", "Wait for release gate enablement or report that mutation remains disabled.",
                                "boundaryStatus", boundary.status(),
                                "releaseAttemptModel", Map.of(
                                        "latestAttempt", boundary.releaseAttemptModel().latestAttempt()
                                ),
                                "releaseGateEnabled", false,
                                "requestCreationEnabled", false,
                                "pushEnabled", false,
                                "claimEnabled", false,
                                "mutationEnabled", false
                        ),
                        OffsetDateTime.now()
                ))
        )));

        nextActionMvc.perform(get("/api/code-agent/loop/timelines")
                        .param("repositoryId", repositoryId.toString())
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(loopId.toString()))
                .andExpect(jsonPath("$[0].events[0].eventType").value("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED"))
                .andExpect(jsonPath("$[0].events[0].toolName").value("patch.apply"))
                .andExpect(jsonPath("$[0].events[0].requiresApproval").value(true))
                .andExpect(jsonPath("$[0].events[0].mayMutate").value(false))
                .andExpect(jsonPath("$[0].events[0].details.boundaryStatus").value("RELEASE_REFUSED_GATE_DISABLED"))
                .andExpect(jsonPath("$[0].events[0].details.releaseGateEnabled").value(false))
                .andExpect(jsonPath("$[0].events[0].details.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$[0].events[0].details.pushEnabled").value(false))
                .andExpect(jsonPath("$[0].events[0].details.claimEnabled").value(false))
                .andExpect(jsonPath("$[0].events[0].details.mutationEnabled").value(false))
                .andExpect(jsonPath("$[0].events[0].details.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.status").value("REFUSED_REQUEST_CREATION_DISABLED"))
                .andExpect(jsonPath("$[0].events[0].details.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.status").value("REFUSED_CREATION_DISABLED"))
                .andExpect(jsonPath("$[0].events[0].details.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.durableMutationExecutionRowCount").value(0))
                .andExpect(jsonPath("$[0].events[0].details.releaseAttemptModel.latestAttempt.mutationRequestCreationGate.claimableRequestCount").value(0));

        nextActionMvc.perform(get("/api/code-agent/loop/next-action")
                        .param("repositoryId", repositoryId.toString())
                        .param("loopId", loopId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.actionKey").value("READY_HANDOFF_CREATION_DISABLED"))
                .andExpect(jsonPath("$.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.claimEnabled").value(false))
                .andExpect(jsonPath("$.mutationEnabled").value(false))
                .andExpect(jsonPath("$.finalResultEnabled").value(false))
                .andExpect(jsonPath("$.publicationEnabled").value(false))
                .andExpect(jsonPath("$.acknowledgementEnabled").value(false))
                .andExpect(jsonPath("$.sourceEventId").value(releaseBoundaryEventId.toString()))
                .andExpect(jsonPath("$.sourceEventType").value("LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED"))
                .andExpect(jsonPath("$.handoffSummary.schema").value("learnbot.code-agent.creation-disabled-handoff-summary.v1"))
                .andExpect(jsonPath("$.handoffSummary.status").value("READY_HANDOFF_CREATION_DISABLED"))
                .andExpect(jsonPath("$.handoffSummary.expectedRequestCount").value(4))
                .andExpect(jsonPath("$.handoffSummary.durableMutationExecutionRowCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.persistedRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.pushedRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.claimableRequestCount").value(0))
                .andExpect(jsonPath("$.handoffSummary.requestCreationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.pushEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.claimEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.mutationEnabled").value(false))
                .andExpect(jsonPath("$.handoffSummary.runnerDecision").value("WAIT_CREATION_GATE_DISABLED"))
                .andExpect(jsonPath("$.sourceDetails.boundaryStatus").value("RELEASE_REFUSED_GATE_DISABLED"))
                .andExpect(jsonPath("$.sourceDetails.releaseAttemptModel.latestAttempt.mutationRequestBlueprint.status").value("REFUSED_REQUEST_CREATION_DISABLED"));
        verify(repository, never()).releaseApprovedHeldPatch(any(), any(), any());
        verify(repository, never()).releaseApprovedHeldPatchWithMutationInput(any(), any(), any(), any());
        verify(repository, never()).claimNext(any(), any());
    }

    private Map<String, Object> creationDisabledHandoffSummary() {
        return Map.ofEntries(
                Map.entry("schema", "learnbot.code-agent.creation-disabled-handoff-summary.v1"),
                Map.entry("status", "READY_HANDOFF_CREATION_DISABLED"),
                Map.entry("sourceBoundaryStatus", "RELEASE_REFUSED_GATE_DISABLED"),
                Map.entry("expectedRequestCount", 4),
                Map.entry("durableMutationExecutionRowCount", 0),
                Map.entry("persistedRequestCount", 0),
                Map.entry("pushedRequestCount", 0),
                Map.entry("claimableRequestCount", 0),
                Map.entry("requestCreationEnabled", false),
                Map.entry("pushEnabled", false),
                Map.entry("claimEnabled", false),
                Map.entry("mutationEnabled", false),
                Map.entry("finalResultEnabled", false),
                Map.entry("publicationEnabled", false),
                Map.entry("acknowledgementEnabled", false),
                Map.entry("runnerDecision", "WAIT_CREATION_GATE_DISABLED"),
                Map.entry("message", "Mutation handoff is ready, but Local Agent mutation request creation is disabled.")
        );
    }

    private LocalAgentToolExecution execution(
            UUID requestId,
            LocalAgentToolRequest request,
            LocalAgentToolStatus status
    ) {
        return execution(requestId, request, request.approvalState(), status);
    }

    private LocalAgentToolExecution execution(
            UUID requestId,
            LocalAgentToolRequest request,
            LocalAgentApprovalState approvalState,
            LocalAgentToolStatus status
    ) {
        return new LocalAgentToolExecution(
                requestId,
                request.sessionId(),
                request.userId(),
                request.agentId(),
                request.workspaceId(),
                request.executionTarget(),
                request.toolName(),
                approvalState,
                status,
                request.input(),
                Map.of(),
                null,
                null,
                request.warnings(),
                List.of(),
                request.createdAt(),
                status == LocalAgentToolStatus.RUNNING ? OffsetDateTime.now() : null,
                null
        );
    }

    private LocalAgentToolExecution completedExecution(
            UUID requestId,
            LocalAgentToolRequest request,
            Map<String, Object> output
    ) {
        return new LocalAgentToolExecution(
                requestId,
                request.sessionId(),
                request.userId(),
                request.agentId(),
                request.workspaceId(),
                request.executionTarget(),
                request.toolName(),
                request.approvalState(),
                LocalAgentToolStatus.SUCCEEDED,
                request.input(),
                output,
                null,
                null,
                request.warnings(),
                List.of(),
                request.createdAt(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private Map<String, Object> patchDryRunOutput(String path) {
        return Map.of(
                "dryRun", true,
                "preflightPassed", true,
                "mutationApplied", false,
                "snapshotCreated", true,
                "snapshotObservation", Map.of(
                        "manifestPreview", Map.of(
                                "id", "snap-1234",
                                "version", 1,
                                "schema", "learnbot.local-agent.snapshot-manifest.v1",
                                "relativeManifestPath", "snap-1234/manifest.json",
                                "contentStrategy", "COPY_TARGET_FILES_BEFORE_MUTATION",
                                "created", true,
                                "writesPlanned", true,
                                "writesCompleted", true,
                                "files", List.of(Map.of(
                                        "path", path,
                                        "snapshotRelativePath", "files/" + path,
                                        "actualSha256", "abc123"
                                ))
                        )
                ),
                "rollbackObservation", Map.of(
                        "restored", false,
                        "restorePreconditions", List.of(
                                Map.of(
                                        "key", "snapshotManifestExists",
                                        "required", true,
                                        "previewOnly", true
                                ),
                                Map.of(
                                        "key", "userApprovalRequired",
                                        "required", true,
                                        "previewOnly", true
                                )
                        )
                )
        );
    }
}
