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
import com.learnbot.dto.loop.CodeAgentLoopRunnerEnqueueResponse;
import com.learnbot.dto.loop.CodeAgentLoopRunnerPreviewResponse;
import com.learnbot.dto.loop.CodeAgentLoopToolCandidate;
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
    void advanceRepairsPatchWhenApprovalPreflightRequiresExplicitNewJsFile() {
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
        String instruction = "js파일을 하나 추가해서 홈페이지 소개부분을 직접 수정하고 로컬스토리지에 저장해줘";
        String htmlPath = "index.html";
        String jsPath = "script.js";
        String initialDiff = """
                --- a/index.html
                +++ b/index.html
                @@ -1,3 +1,4 @@
                 <html>
                 <body><section id="intro">Hello</section></body>
                +<script src="script.js"></script>
                 </html>
                """;
        String repairedHtmlDiff = """
                --- a/index.html
                +++ b/index.html
                @@ -1,3 +1,4 @@
                 <html>
                 <body><section id="intro">Hello</section></body>
                +<script src="script.js"></script>
                 </html>
                """;
        String repairedJsDiff = """
                --- /dev/null
                +++ b/script.js
                @@ -0,0 +1,2 @@
                +const intro = document.getElementById('intro');
                +localStorage.setItem('introText', intro ? intro.textContent : '');
                """;
        String repairedDiff = repairedHtmlDiff + "\n" + repairedJsDiff;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                fileReadEvent(htmlPath, "<html>\n<body><section id=\"intro\">Hello</section></body>\n</html>\n")
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
                Map.of("diff", repairedDiff, "targetFiles", List.of(htmlPath, jsPath), "createdFiles", List.of(jsPath)),
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
                "initial",
                List.of(new PatchFileDiff(htmlPath, initialDiff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(initialDiff), eq(List.of(htmlPath)), anyList(), anyMap()))
                .thenThrow(new IllegalArgumentException("Patch did not satisfy requested file creation/reference integrity: Instruction explicitly requested a new .js file, but the patch did not create one."));
        when(codeAgentService.repairPatchFromLoadedFiles(eq(instruction), anyList(), eq(initialDiff), anyList())).thenReturn(new CodeAgentPatchResponse(
                "repaired",
                List.of(new PatchFileDiff(htmlPath, repairedHtmlDiff), new PatchFileDiff(jsPath, repairedJsDiff)),
                "medium",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(repairedDiff), eq(List.of(htmlPath, jsPath)), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        verify(codeAgentService).repairPatchFromLoadedFiles(eq(instruction), anyList(), eq(initialDiff), anyList());
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(repairedDiff),
                eq(List.of(htmlPath, jsPath)),
                anyList(),
                anyMap()
        );
    }

    @Test
    void advanceCanCreatePatchApprovalWithoutExistingFileReadTargets() {
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
        String instruction = "홈페이지를 만들어줘";
        String path = "index.html";
        String diff = """
                --- /dev/null
                +++ b/index.html
                @@ -0,0 +1,2 @@
                +<!doctype html>
                +<main>Hello</main>
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(repositoryId, spaceId, loopId, instruction, List.of());
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
        when(codeAgentService.patchFromLoadedFiles(eq(instruction), eq(List.of()))).thenReturn(new CodeAgentPatchResponse(
                "ok",
                List.of(new PatchFileDiff(path, diff)),
                "low",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of(path)), eq(List.of()), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), eq(List.of()));
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(diff),
                eq(List.of(path)),
                eq(List.of()),
                anyMap()
        );
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
    void advanceUsesModelTargetSelectionWhenInstructionNamesFileType() {
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
        when(ollamaClient.chatResult(any(), any(), eq(500))).thenReturn(chat("""
                {"targetFiles":["home.html"],"reason":"The instruction asks to edit the HTML file and home.html is the observed HTML candidate.","confidence":"high","usedRecentContext":false,"contextSourceLoopId":null,"needsClarification":false}
                """));
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
    void advanceRetriesCompactTargetSelectionWhenInitialSelectionStopsByLength() {
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
        String instruction = "방금 만든 홈페이지에 소개, 프로젝트, 연락처 탭을 추가하고 각 탭이 버튼 클릭으로 전환되게 해줘";
        String diff = """
                --- a/index.html
                +++ b/index.html
                @@ -1 +1,2 @@
                 <main></main>
                +<nav class="tab-nav"></nav>
                --- a/style.css
                +++ b/style.css
                @@ -1 +1,2 @@
                 body {}
                +.tab-nav { display: flex; }
                """;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("index.html", "<!doctype html>\n<main></main>\n"),
                        fileReadEvent("style.css", "body {}\n"),
                        fileReadEvent("새 텍스트 문서.txt", "learnbot fix \"개인 포트폴리오 홈페이지를 만들어줘\"\n")
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
        when(ollamaClient.chatResult(any(), any(), eq(500))).thenReturn(chat("thinking without final json", "length"));
        when(ollamaClient.chatResult(any(), any(), eq(300))).thenReturn(chat("""
                {"targetFiles":["index.html","style.css"],"reason":"The request modifies homepage markup and styling; the txt file is only notes.","confidence":"high","usedRecentContext":false,"contextSourceLoopId":null,"needsClarification":false}
                """));
        when(codeAgentService.patchFromLoadedFiles(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "ok",
                List.of(new PatchFileDiff("index.html", diff), new PatchFileDiff("style.css", diff)),
                "medium",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(diff), eq(List.of("index.html", "style.css")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<String> initialPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> compactPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(any(), initialPromptCaptor.capture(), eq(500));
        verify(ollamaClient).chatResult(any(), compactPromptCaptor.capture(), eq(300));
        assertThat(initialPromptCaptor.getValue()).contains("CONTENT_FOR_TARGET_DECISION");
        assertThat(compactPromptCaptor.getValue())
                .contains("Candidate file map")
                .contains("initial-target-selection-stopped-by-length")
                .contains("index.html")
                .contains("style.css")
                .contains("새 텍스트 문서.txt")
                .contains("roleHint: markup/main-page-candidate")
                .doesNotContain("CONTENT_FOR_TARGET_DECISION");
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).extracting(CodePatchFileLoader.LoadedPatchFile::path)
                .containsExactly("index.html", "style.css");
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(diff),
                eq(List.of("index.html", "style.css")),
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

    @Test
    void advanceAllowsModelToSelectMultipleCandidateFilesForPatch() {
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
        String instruction = "Update the HTML page and its JavaScript tabs together";
        String htmlDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -1 +1,2 @@
                 <main></main>
                +<script src="tabs.js"></script>
                """;
        String jsDiff = """
                --- a/tabs.js
                +++ b/tabs.js
                @@ -1 +1,2 @@
                 export function initTabs() {}
                +export function enhanceTabs() {}
                """;
        String combinedDiff = htmlDiff + "\n" + jsDiff;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("home.html", "<main></main>\n"),
                        fileReadEvent("tabs.js", "export function initTabs() {}\n"),
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
                Map.of("diff", combinedDiff),
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
                {"targetFiles":["home.html","tabs.js"],"reason":"The requested UI change spans the page and its tab script.","confidence":"high","needsClarification":false}
                """));
        when(codeAgentService.patchFromLoadedFiles(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "ok",
                List.of(new PatchFileDiff("home.html", htmlDiff), new PatchFileDiff("tabs.js", jsDiff)),
                "medium",
                List.of(),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(combinedDiff), eq(List.of("home.html", "tabs.js")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        ArgumentCaptor<List<CodePatchFileLoader.LoadedPatchFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeAgentService).patchFromLoadedFiles(eq(instruction), filesCaptor.capture());
        assertThat(filesCaptor.getValue()).extracting(CodePatchFileLoader.LoadedPatchFile::path)
                .containsExactly("home.html", "tabs.js");
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(combinedDiff),
                eq(List.of("home.html", "tabs.js")),
                anyList(),
                anyMap()
        );
    }

    @Test
    void advanceUsesValidatedBatchPatchBeforeOneShotPatchForMultipleObservedFiles() {
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
        String instruction = "Add a homepage tab and a sample organization chart";
        String htmlDiff = """
                --- a/home.html
                +++ b/home.html
                @@ -1 +1,2 @@
                 <main></main>
                +<section id="org-chart"></section>
                """;
        String cssDiff = """
                --- a/style.css
                +++ b/style.css
                @@ -1 +1,2 @@
                 body { margin: 0; }
                +.org-card { border: 1px solid #ddd; }
                """;
        String groupedDiff = htmlDiff + "\n" + cssDiff;
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("home.html", "<main></main>\n"),
                        fileReadEvent("style.css", "body { margin: 0; }\n")
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
                Map.of("diff", groupedDiff),
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
                {"targetFiles":["home.html","style.css"],"reason":"The UI change spans markup and styles.","confidence":"high","needsClarification":false}
                """));
        when(codeAgentService.patchFromLoadedFilesInBatches(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "batched",
                List.of(new PatchFileDiff("home.html", groupedDiff), new PatchFileDiff("style.css", groupedDiff)),
                "medium",
                List.of("Patch batches were composed into one approval proposal."),
                List.of(),
                true
        ));
        when(localPatchRequestService.prepare(eq(repositoryId), eq(spaceId), eq(userId), eq(agentId), eq(workspaceId), eq(loopId), eq(instruction), eq(groupedDiff), eq(List.of("home.html", "style.css")), anyList(), anyMap()))
                .thenReturn(approval);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("CREATED_VALIDATED_PATCH_APPROVAL_REQUEST");
        verify(codeAgentService).patchFromLoadedFilesInBatches(eq(instruction), anyList());
        verify(codeAgentService, never()).patchFromLoadedFiles(eq(instruction), anyList());
        verify(localPatchRequestService).prepare(
                eq(repositoryId),
                eq(spaceId),
                eq(userId),
                eq(agentId),
                eq(workspaceId),
                eq(loopId),
                eq(instruction),
                eq(groupedDiff),
                eq(List.of("home.html", "style.css")),
                anyList(),
                anyMap()
        );
    }

    @Test
    void advanceBlocksWithBatchWarningsInsteadOfFallingBackToOneShotWhenBatchPatchFails() {
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
        String instruction = "Add a homepage tab and a sample organization chart";
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(
                        fileReadEvent("index.html", "<main></main>\n"),
                        fileReadEvent("script.js", "const ready = true;\n"),
                        fileReadEvent("style.css", "body { margin: 0; }\n")
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
        when(ollamaClient.chatResult(any(), any(), eq(500))).thenReturn(chat("""
                {"targetFiles":["index.html","script.js","style.css"],"reason":"The requested UI change spans markup, script, and style.","confidence":"high","needsClarification":false}
                """));
        when(codeAgentService.patchFromLoadedFilesInBatches(eq(instruction), anyList())).thenReturn(new CodeAgentPatchResponse(
                "Patch batch style did not produce a valid unified diff.",
                List.of(),
                "high",
                List.of("Patch batch style targeted [style.css] and valid=false.", "reason: malformed JSON patch proposal"),
                List.of(),
                false
        ));

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("PATCH_PROPOSAL_BLOCKED");
        verify(codeAgentService).patchFromLoadedFilesInBatches(eq(instruction), anyList());
        verify(codeAgentService, never()).patchFromLoadedFiles(eq(instruction), anyList());
        verify(localPatchRequestService, never()).prepare(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyList(), anyMap());
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(loopPreviewService).appendPatchProposalBlocked(
                eq(userId),
                eq(repositoryId),
                eq(loopId),
                eq("PATCH_PROPOSAL_BLOCKED"),
                eq("Patch proposal did not produce a valid unified diff."),
                detailsCaptor.capture()
        );
        assertThat(detailsCaptor.getValue()).containsEntry("patchSource", "local-agent-file-read-batched");
        assertThat(detailsCaptor.getValue().get("warnings").toString()).contains("malformed JSON patch proposal");
    }

    @Test
    void advanceContinuesReadOnlyDiscoveryWhenWorkspaceSearchCandidateRemainsAfterOneFileRead() {
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
        String instruction = "내 홈페이지에 간단한 조직도 탭을 만들어줘";
        CodeAgentLoopTimelineSummary timeline = timeline(
                repositoryId,
                spaceId,
                loopId,
                instruction,
                List.of(fileReadEvent("cold.txt", "Added by LearnBot.\n"))
        );
        CodeAgentLoopToolCandidate searchCandidate = new CodeAgentLoopToolCandidate(
                loopId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                LocalAgentToolName.WORKSPACE_SEARCH,
                LocalAgentApprovalState.NOT_REQUIRED,
                false,
                false,
                false,
                false,
                Map.of("path", ".", "query", instruction, "mutationAllowed", false),
                List.of()
        );
        CodeAgentLoopRunnerPreviewResponse searchPreview = new CodeAgentLoopRunnerPreviewResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "PREPARED_READ_ONLY_CANDIDATE",
                "Search is still needed before patch target selection.",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                searchCandidate,
                Map.of()
        );
        CodeAgentLoopRunnerEnqueueResponse enqueueResponse = new CodeAgentLoopRunnerEnqueueResponse(
                loopId,
                repositoryId,
                "RECORDED",
                "QUEUE_READ_ONLY_OBSERVATION",
                "ENQUEUED_READ_ONLY_OBSERVATION",
                "Queued workspace.search.",
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                searchPreview,
                null
        );

        when(loopPreviewService.nextAction(userId, repositoryId, loopId))
                .thenReturn(next(loopId, repositoryId, "QUEUE_READ_ONLY_OBSERVATION"))
                .thenReturn(next(loopId, repositoryId, "WAIT_FOR_LOCAL_AGENT_OBSERVATION"));
        when(loopPreviewService.recentTimelines(userId, repositoryId, 20)).thenReturn(List.of(timeline));
        when(runnerService.previewNextStep(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(searchPreview);
        when(runnerService.enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId)).thenReturn(enqueueResponse);

        CodeAgentLoopRunStatusResponse response = service.advance(userId, repositoryId, loopId, agentId, workspaceId);

        assertThat(response.runnerDecision()).isEqualTo("ENQUEUED_READ_ONLY_OBSERVATION");
        verify(codeAgentService, never()).patchFromLoadedFiles(any(), anyList());
        verify(localPatchRequestService, never()).prepare(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyList(), anyMap());
        verify(runnerService).enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);
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

    private OllamaClient.ChatResult chat(String content, String doneReason) {
        return new OllamaClient.ChatResult(content, doneReason, true, 0, 0, "http://localhost:11434", "test", "PRIMARY", false);
    }
}
