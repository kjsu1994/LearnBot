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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.CodeAgentLocalPatchRequestService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodePatchFileLoader;
import com.learnbot.service.OllamaClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
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
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of(path)), anyList(), anyMap()))
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

    @Test
    void advanceSelectsOnlyClearReadmeTargetWhenSeveralFilesWereRead() {
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
        String instruction = "README file end append short poem";
        String diff = """
                --- a/readme.txt
                +++ b/readme.txt
                @@ -1 +1,2 @@
                 hello
                +poem
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("readme.txt", "hello\n"),
                        fileReadEvent("verify-readme.txt", "not the target\n"),
                        fileReadEvent("lbverify-20260704.txt", "not the target\n")
                )
        );
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
                List.of(new PatchFileDiff("readme.txt", diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of("readme.txt")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).extracting(CodePatchFileLoader.LoadedPatchFile::path)
                .containsExactly("readme.txt");
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(diff),
                eq(List.of("readme.txt")),
                anyList(),
                anyMap()
        );
    }

    @Test
    void advanceSelectsFileStemAliasWhenInstructionUsesKoreanFileSuffix() {
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
        String instruction = "test파일 끝에 한글로 아무거나 한마디를 추가해줘";
        String diff = """
                --- a/testfile.md
                +++ b/testfile.md
                @@ -0,0 +1,1 @@
                +한마디
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("cold.txt", "cold\n"),
                        fileReadEvent("readme.txt", "readme\n"),
                        fileReadEvent("testfile.md", "")
                )
        );
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
                List.of(new PatchFileDiff("testfile.md", diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of("testfile.md")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).extracting(CodePatchFileLoader.LoadedPatchFile::path)
                .containsExactly("testfile.md");
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(diff),
                eq(List.of("testfile.md")),
                anyList(),
                anyMap()
        );
    }

    @Test
    void advanceSelectsSingleExtensionMatchWhenInstructionNamesFileType() {
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
        String instruction = "html\uD30C\uC77C\uC5D0 \uAC04\uB2E8\uD55C \uC6F9\uD398\uC774\uC9C0 \uB9CC\uB4E4\uC5B4\uC918";
        String diff = """
                --- a/home.html
                +++ b/home.html
                @@ -0,0 +1,1 @@
                +<main>Hello</main>
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("cold.txt", "cold\n"),
                        fileReadEvent("home.html", ""),
                        fileReadEvent("readme.txt", "readme\n")
                )
        );
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
                List.of(new PatchFileDiff("home.html", diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of("home.html")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).extracting(CodePatchFileLoader.LoadedPatchFile::path)
                .containsExactly("home.html");
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(diff),
                eq(List.of("home.html")),
                anyList(),
                anyMap()
        );
    }

    @Test
    void advanceUsesModelTargetSelectionBeforeDeterministicFallback() {
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeAgentLoopRunnerService runnerService = mock(CodeAgentLoopRunnerService.class);
        CodeAgentService codeAgentService = mock(CodeAgentService.class);
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentLoopRunService service = new CodeAgentLoopRunService(
                loopPreviewService,
                toolSelectionService,
                runnerService,
                codeAgentService,
                localPatchRequestService,
                ollamaClient,
                new ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String instruction = "맨 마지막 파일 끝에 한글로 아무거나 한마디를 추가해줘";
        String diff = """
                --- a/testfile.md
                +++ b/testfile.md
                @@ -0,0 +1,1 @@
                +한마디
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("cold.txt", "cold\n"),
                        fileReadEvent("readme.txt", "readme\n"),
                        fileReadEvent("testfile.md", "")
                )
        );
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
        when(ollamaClient.chatResult(any(), any(), eq(500))).thenReturn(chat("""
                {"targetFiles":["testfile.md"],"reason":"The user referred to the last file from observations.","confidence":"medium","needsClarification":false}
                """));
        when(codeAgentService.patchFromLoadedFiles(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "ok",
                List.of(new PatchFileDiff("testfile.md", diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of("testfile.md")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).extracting(CodePatchFileLoader.LoadedPatchFile::path)
                .containsExactly("testfile.md");
        verify(ollamaClient).chatResult(any(), any(), eq(500));
    }

    @Test
    void advanceProvidesRecentSuccessfulPatchContextToModelTargetSelection() {
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeAgentLoopRunnerService runnerService = mock(CodeAgentLoopRunnerService.class);
        CodeAgentService codeAgentService = mock(CodeAgentService.class);
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentLoopRunService service = new CodeAgentLoopRunService(
                loopPreviewService,
                toolSelectionService,
                runnerService,
                codeAgentService,
                localPatchRequestService,
                ollamaClient,
                new ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID previousLoopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String instruction = "이 상태에서 메인페이지에 탭 3개를 추가해줘";
        String diff = """
                --- a/home.html
                +++ b/home.html
                @@ -1 +1,2 @@
                 <main></main>
                +<nav>Tab 1 Tab 2 Tab 3</nav>
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("cold.txt", "cold\n"),
                        fileReadEvent("home.html", "<main></main>\n"),
                        fileReadEvent("readme.txt", "readme\n")
                )
        );
        CodeAgentLoopTimelineSummary previousTimeline = timeline(
                repositoryId,
                spaceId,
                previousLoopId,
                "html파일에 내 웹페이지를 좀더 업그레이드 해줘",
                List.of(
                        patchApprovalEvent(requestId, workspaceId, List.of("home.html"), 1),
                        successfulPatchObservationEvent(requestId, workspaceId, 2)
                )
        );
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
        when(loopPreviewService.recentTimelines(userId, repositoryId, 20)).thenReturn(List.of(timeline, previousTimeline));
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
        when(ollamaClient.chatResult(any(), any(), eq(500))).thenReturn(chat("""
                {"targetFiles":["home.html"],"reason":"The user is continuing the previous main page edit.","confidence":"high","usedRecentContext":true,"contextSourceLoopId":"%s","needsClarification":false}
                """.formatted(previousLoopId)));
        when(codeAgentService.patchFromLoadedFiles(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "ok",
                List.of(new PatchFileDiff("home.html", diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of("home.html")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(any(), promptCaptor.capture(), eq(500));
        assertThat(promptCaptor.getValue())
                .contains("Recent successful edits")
                .contains(previousLoopId.toString())
                .contains("home.html")
                .contains("html파일에 내 웹페이지를 좀더 업그레이드 해줘");
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), anyList());
    }

    @Test
    void advanceDoesNotSelectRecentPatchContextOutsideCurrentReadCandidates() {
        CodeAgentLoopPreviewService loopPreviewService = mock(CodeAgentLoopPreviewService.class);
        CodeAgentLoopToolSelectionService toolSelectionService = mock(CodeAgentLoopToolSelectionService.class);
        CodeAgentLoopRunnerService runnerService = mock(CodeAgentLoopRunnerService.class);
        CodeAgentService codeAgentService = mock(CodeAgentService.class);
        CodeAgentLocalPatchRequestService localPatchRequestService = mock(CodeAgentLocalPatchRequestService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeAgentLoopRunService service = new CodeAgentLoopRunService(
                loopPreviewService,
                toolSelectionService,
                runnerService,
                codeAgentService,
                localPatchRequestService,
                ollamaClient,
                new ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID previousLoopId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                "이 상태에서 계속 고쳐줘",
                List.of(
                        fileReadEvent("cold.txt", "cold\n"),
                        fileReadEvent("readme.txt", "readme\n")
                )
        );
        CodeAgentLoopTimelineSummary previousTimeline = timeline(
                repositoryId,
                spaceId,
                previousLoopId,
                "home.html을 업그레이드해줘",
                List.of(
                        patchApprovalEvent(requestId, workspaceId, List.of("home.html"), 1),
                        successfulPatchObservationEvent(requestId, workspaceId, 2)
                )
        );

        when(loopPreviewService.nextAction(userId, repositoryId, loopId))
                .thenReturn(next(loopId, repositoryId, "QUEUE_READ_ONLY_OBSERVATION"))
                .thenReturn(next(loopId, repositoryId, "STOP_WITH_REASON"));
        when(loopPreviewService.recentTimelines(userId, repositoryId, 20)).thenReturn(List.of(timeline, previousTimeline));
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
        when(ollamaClient.chatResult(any(), any(), eq(500))).thenReturn(chat("""
                {"targetFiles":["home.html"],"reason":"Recent edit target.","confidence":"high","usedRecentContext":true,"contextSourceLoopId":"%s","needsClarification":false}
                """.formatted(previousLoopId)));

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("AMBIGUOUS_TARGET_FILES");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(any(), promptCaptor.capture(), eq(500));
        assertThat(promptCaptor.getValue()).contains("Recent successful edits").contains("- none");
        verify(codeAgentService, never()).patchFromLoadedFiles(any(), anyList());
        verify(localPatchRequestService, never()).prepare(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyList(), anyMap());
    }

    @Test
    void advanceBlocksExtensionSelectionWhenSeveralFilesShareRequestedType() {
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
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                "html\uD30C\uC77C\uC5D0 \uBB38\uAD6C \uCD94\uAC00\uD574\uC918",
                List.of(
                        fileReadEvent("home.html", "<main></main>\n"),
                        fileReadEvent("about.html", "<main></main>\n"),
                        fileReadEvent("readme.txt", "readme\n")
                )
        );

        when(loopPreviewService.nextAction(userId, repositoryId, loopId))
                .thenReturn(next(loopId, repositoryId, "QUEUE_READ_ONLY_OBSERVATION"))
                .thenReturn(next(loopId, repositoryId, "STOP_WITH_REASON"));
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

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("AMBIGUOUS_TARGET_FILES");
        verify(codeAgentService, never()).patchFromLoadedFiles(any(), anyList());
        verify(localPatchRequestService, never()).prepare(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyList(), anyMap());
    }

    @Test
    void advanceBlocksPatchProposalWhenSeveralReadFilesDoNotIdentifySingleTarget() {
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
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                "append a short poem",
                List.of(
                        fileReadEvent("notes.txt", "hello\n"),
                        fileReadEvent("journal.txt", "world\n")
                )
        );

        when(loopPreviewService.nextAction(userId, repositoryId, loopId))
                .thenReturn(next(loopId, repositoryId, "QUEUE_READ_ONLY_OBSERVATION"))
                .thenReturn(next(loopId, repositoryId, "STOP_WITH_REASON"));
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

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("AMBIGUOUS_TARGET_FILES");
        assertThat(response.reason()).contains("Multiple candidate files");
        verify(codeAgentService, never()).patchFromLoadedFiles(any(), anyList());
        verify(localPatchRequestService, never()).prepare(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyList(), anyMap());
        verify(loopPreviewService).appendPatchProposalBlocked(
                eq(userId),
                eq(repositoryId),
                eq(loopId),
                eq("AMBIGUOUS_TARGET_FILES"),
                any(),
                any()
        );
    }

    private CodeAgentLoopTimelineSummary timeline(
            UUID repositoryId,
            UUID spaceId,
            UUID loopId,
            String instruction,
            CodeAgentLoopTimelineEventSummary event
    ) {
        return timeline(repositoryId, spaceId, loopId, instruction, List.of(event));
    }

    private CodeAgentLoopTimelineSummary timeline(
            UUID repositoryId,
            UUID spaceId,
            UUID loopId,
            String instruction,
            List<CodeAgentLoopTimelineEventSummary> events
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
                events
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

    private CodeAgentLoopTimelineEventSummary patchApprovalEvent(UUID requestId, UUID workspaceId, List<String> targetFiles, int sequenceNumber) {
        return new CodeAgentLoopTimelineEventSummary(
                UUID.randomUUID(),
                sequenceNumber,
                "LOCAL_AGENT_APPROVAL_REQUEST_CREATED",
                "APPROVAL",
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                true,
                true,
                true,
                Map.of(
                        "requestId", requestId.toString(),
                        "workspaceId", workspaceId.toString(),
                        "targetFiles", targetFiles
                ),
                OffsetDateTime.now()
        );
    }

    private CodeAgentLoopTimelineEventSummary successfulPatchObservationEvent(UUID requestId, UUID workspaceId, int sequenceNumber) {
        return new CodeAgentLoopTimelineEventSummary(
                UUID.randomUUID(),
                sequenceNumber,
                "LOCAL_AGENT_OBSERVATION_RESULT",
                "APPLY",
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.PATCH_APPLY,
                true,
                true,
                true,
                Map.of(
                        "requestId", requestId.toString(),
                        "workspaceId", workspaceId.toString(),
                        "status", "SUCCEEDED",
                        "mutationApplied", true
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

    private OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://localhost:11434", "test", "PRIMARY", false);
    }
}
