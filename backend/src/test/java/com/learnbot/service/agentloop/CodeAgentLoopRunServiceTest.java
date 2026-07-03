package com.learnbot.service.agentloop;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineEventSummary;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolExecutionResponse;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.PatchFileDiff;
import com.learnbot.dto.loop.CodeAgentLoopRunStatusResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodePatchFileLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentLoopRunServiceTest {
    @Test
    void advanceCreatesPatchApprovalFromLocalAgentFileReadContentWithoutIndexedLoader() {
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeAgentLoopRunnerService runnerService = mock(CodeAgentLoopRunnerService.class);
        CodeAgentService codeAgentService = mock(CodeAgentService.class);
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        CodeAgentLoopRunService service = new CodeAgentLoopRunService(
                loopPreviewService,
                toolSelectionService,
                runnerService,
                codeAgentService,
                localPatchRequestService
        );
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String instruction = "notes.txt 끝에 짧은 시를 추가해줘";
        String path = "notes.txt";
        String diff = """
                --- a/notes.txt
                +++ b/notes.txt
                @@ -0,0 +1,1 @@
                +hello
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(repositoryId, spaceId, loopId, instruction, fileReadEvent(path, ""));
        LocalAgentToolExecutionResponse approval = new LocalAgentToolExecutionResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentApprovalState.REQUIRED,
                LocalAgentToolStatus.APPROVAL_REQUIRED,
                Map.of("diff", diff),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                OffsetDateTime.now(),
                null,
                null
        );

        when(loopPreviewService.nextAction(userId, repositoryId, loopId))
                .thenReturn(next(loopId, repositoryId, "QUEUE_READ_ONLY_OBSERVATION"))
                .thenReturn(next(loopId, repositoryId, "WAIT_FOR_APPROVAL"));
        when(loopPreviewService.recentTimelines(userId, repositoryId, 20)).thenReturn(List.of(timeline));
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(new CodeAgentLoopRunnerPreviewResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "NO_MORE_READS",
                "No further read-only step is needed.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                Map.of()
        ));
        when(codeAgentService.patchFromLoadedFiles(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "ok",
                List.of(new PatchFileDiff(path, diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of(path)), anyList()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        assertThat(response.advance().handoffSummary())
                .containsEntry("approvalRequestCreated", true)
                .containsEntry("approvalRequestId", approval.requestId().toString());
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).hasSize(1);
        assertThat(filesCaptor.getValue().get(0).path()).isEqualTo(path);
        assertThat(filesCaptor.getValue().get(0).content()).isEqualTo("");
        verify(codeAgentService, never()).patch(any(), any(), anyList(), any(), anyList());
    }

    private CodeAgentLoopTimelineSummary timeline(
            UUID repositoryId,
            UUID spaceId,
            UUID loopId,
            String instruction,
            CodeAgentLoopTimelineEventSummary event
    ) {
        return new CodeAgentLoopTimelineSummary(
                loopId,
                repositoryId,
                spaceId,
                instruction,
                "RECORDED",
                6,
                120,
                false,
                true,
                false,
                OffsetDateTime.now(),
                List.of(event)
        );
    }

    private CodeAgentLoopTimelineEventSummary fileReadEvent(String path, String content) {
        return new CodeAgentLoopTimelineEventSummary(
                UUID.randomUUID(),
                1,
                "LOCAL_AGENT_OBSERVATION_RESULT",
                "OBSERVE",
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.FILE_READ,
                false,
                false,
                true,
                Map.of(
                        "status", "SUCCEEDED",
                        "outputSummary", Map.of(
                                "relativePath", path,
                                "truncated", false,
                                "contentForPatchAvailable", true,
                                "contentForPatch", content
                        )
                ),
                OffsetDateTime.now()
        );
    }

    private CodeAgentLoopNextActionResponse next(UUID loopId, UUID repositoryId, String actionKey) {
        return new CodeAgentLoopNextActionResponse(
                loopId,
                repositoryId,
                "RECORDED",
                actionKey,
                "test",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                UUID.randomUUID(),
                1,
                "LOOP_NEXT_DECISION_RECORDED",
                Map.of()
        );
    }
}
