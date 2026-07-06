package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.ConversationIntent;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurn;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.dto.interactive.CodeAgentInteractiveContextReadResultRequest;
import com.learnbot.dto.interactive.CodeAgentInteractiveTurnRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAgentInteractiveServiceTest {
    private final RagConversationService conversationService = mock(RagConversationService.class);
    private final CodeRagService codeRagService = mock(CodeRagService.class);
    private final OllamaClient ollamaClient = mock(OllamaClient.class);
    private final CodeAgentInteractiveService service = new CodeAgentInteractiveService(
            conversationService,
            codeRagService,
            ollamaClient,
            new ObjectMapper()
    );

    @Test
    void llmFixIntentReturnsCommandWithoutApplyingPatchServerSide() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AppUser user = user();
        RagConversationContext context = context(conversationId);
        when(conversationService.prepare(eq(user), eq(spaceId), eq(RagConversationService.CODE), eq(repositoryId), eq(null), anyString(), eq(true)))
                .thenReturn(context);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY), anyInt(), any()))
                .thenReturn(new OllamaClient.ChatResult(
                        "{\"intent\":\"FIX\",\"goal\":\"Update homepage styling\",\"confidence\":\"high\"}",
                        "stop",
                        true,
                        10,
                        20,
                        "http://ollama",
                        "model",
                        "PRIMARY",
                        false
                ));
        when(conversationService.saveCodeTurn(eq(context), eq(null), anyString(), any()))
                .thenReturn(new CodeAskResponse("agent_intent", "saved", List.of(), "high", List.of())
                        .withConversation(conversationId, turnId, null));

        var response = service.handleTurn(user, spaceId, List.of(spaceId), new CodeAgentInteractiveTurnRequest(
                repositoryId,
                spaceId,
                null,
                null,
                "홈페이지를 더 세련되게 수정해줘",
                null,
                6,
                UUID.randomUUID(),
                UUID.randomUUID()
        ));

        assertThat(response.intent()).isEqualTo("FIX");
        assertThat(response.command()).isEqualTo("fix");
        assertThat(response.goal()).contains("Model-interpreted goal:\nUpdate homepage styling");
        assertThat(response.goal()).contains("Conversation-aware goal:");
        assertThat(response.shouldRunCommand()).isTrue();
        assertThat(response.mutationRequiresApproval()).isTrue();
        assertThat(response.contextRequired()).isFalse();
        verify(codeRagService, never()).askConversational(any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void followUpFixGoalPreservesPreviousTaskContextInsteadOfOnlyLatestFileMessage() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AppUser user = user();
        RagConversationContext context = new RagConversationContext(
                conversationId,
                "Add one tab to the homepage and put a virtual organization chart there. Modify home.html and js/css if needed.",
                List.of(
                        new RagConversationTurnContext("홈페이지에 탭을 하나 추가하고 거기에 가상의 조직도넣어줘", "수정, 검토, 설명, 컨텍스트 읽기 중 어떤 작업을 원하는지 조금 더 명확히 알려주세요.", null),
                        new RagConversationTurnContext("수정을 원해", "수정을 원하시는데, 구체적으로 어떤 파일을 수정하고 싶으신지 알려주세요.", null)
                ),
                List.of(),
                List.of(),
                true
        );
        when(conversationService.prepare(eq(user), eq(spaceId), eq(RagConversationService.CODE), eq(repositoryId), eq(conversationId), anyString(), eq(true)))
                .thenReturn(context);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY), anyInt(), any()))
                .thenReturn(new OllamaClient.ChatResult(
                        "{\"intent\":\"FIX\",\"goal\":\"Modify home.html and potentially js/css files as needed\",\"targetFiles\":[\"home.html\",\"script.js\",\"style.css\"],\"confidence\":\"high\"}",
                        "stop",
                        true,
                        10,
                        20,
                        "http://ollama",
                        "model",
                        "PRIMARY",
                        false
                ));
        when(conversationService.saveCodeTurn(eq(context), eq(turnId), anyString(), any()))
                .thenReturn(new CodeAskResponse("agent_intent", "saved", List.of(), "high", List.of())
                        .withConversation(conversationId, UUID.randomUUID(), null));

        var response = service.handleTurn(user, spaceId, List.of(spaceId), new CodeAgentInteractiveTurnRequest(
                repositoryId,
                spaceId,
                conversationId,
                turnId,
                "home.html 그리고 필요시 js와 css를 수정해줘",
                null,
                6,
                UUID.randomUUID(),
                UUID.randomUUID()
        ));

        assertThat(response.intent()).isEqualTo("FIX");
        assertThat(response.goal()).contains("Conversation-aware goal:\nAdd one tab to the homepage and put a virtual organization chart there");
        assertThat(response.goal()).contains("Current user message:\nhome.html 그리고 필요시 js와 css를 수정해줘");
        assertThat(response.goal()).contains("- home.html");
        assertThat(response.goal()).contains("- script.js");
        assertThat(response.goal()).contains("- style.css");
        assertThat(response.shouldRunCommand()).isTrue();
    }

    @Test
    void llmReadContextIntentReturnsReadOnlyTargetFilesWithoutRunningFix() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AppUser user = user();
        RagConversationContext context = context(conversationId);
        when(conversationService.prepare(eq(user), eq(spaceId), eq(RagConversationService.CODE), eq(repositoryId), eq(null), anyString(), eq(true)))
                .thenReturn(context);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY), anyInt(), any()))
                .thenReturn(new OllamaClient.ChatResult(
                        """
                        {"intent":"READ_CONTEXT","goal":"Load project instructions","targetFiles":["agent.md"],"toolPlan":[{"tool":"file.read","input":{"path":"agent.md"}}],"confidence":"high"}
                        """,
                        "stop",
                        true,
                        10,
                        20,
                        "http://ollama",
                        "model",
                        "PRIMARY",
                        false
                ));
        when(conversationService.saveCodeTurn(eq(context), eq(null), anyString(), any()))
                .thenReturn(new CodeAskResponse("agent_context_read", "saved", List.of(), "high", List.of())
                        .withConversation(conversationId, turnId, null));

        var response = service.handleTurn(user, spaceId, List.of(spaceId), new CodeAgentInteractiveTurnRequest(
                repositoryId,
                spaceId,
                null,
                null,
                "agent.md 읽고와",
                null,
                6,
                UUID.randomUUID(),
                UUID.randomUUID()
        ));

        assertThat(response.intent()).isEqualTo("READ_CONTEXT");
        assertThat(response.shouldRunCommand()).isFalse();
        assertThat(response.mutationRequiresApproval()).isFalse();
        assertThat(response.contextRequired()).isTrue();
        assertThat(response.targetFiles()).containsExactly("agent.md");
        assertThat(response.toolPlan()).hasSize(1);
        verify(codeRagService, never()).askConversational(any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void llmAdviseIntentStartsReadOnlyWorkspaceObservationWithoutRunningFixOrRag() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AppUser user = user();
        RagConversationContext context = context(conversationId);
        when(conversationService.prepare(eq(user), eq(spaceId), eq(RagConversationService.CODE), eq(repositoryId), eq(null), anyString(), eq(true)))
                .thenReturn(context);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY), anyInt(), any()))
                .thenReturn(new OllamaClient.ChatResult(
                        "{\"intent\":\"ADVISE\",\"goal\":\"Suggest homepage improvements\",\"confidence\":\"high\"}",
                        "stop",
                        true,
                        10,
                        20,
                        "http://ollama",
                        "model",
                        "PRIMARY",
                        false
                ));
        when(conversationService.saveCodeTurn(eq(context), eq(null), anyString(), any()))
                .thenReturn(new CodeAskResponse("agent_advice", "saved", List.of(), "high", List.of())
                        .withConversation(conversationId, turnId, null));

        var response = service.handleTurn(user, spaceId, List.of(spaceId), new CodeAgentInteractiveTurnRequest(
                repositoryId,
                spaceId,
                null,
                null,
                "내 홈페이지에서 추가적으로 뭘 더 개선하면 좋을까?",
                null,
                6,
                UUID.randomUUID(),
                UUID.randomUUID()
        ));

        assertThat(response.intent()).isEqualTo("ADVISE");
        assertThat(response.shouldRunCommand()).isFalse();
        assertThat(response.mutationRequiresApproval()).isFalse();
        assertThat(response.contextRequired()).isTrue();
        assertThat(response.toolPlan()).singleElement().satisfies(step -> assertThat(step.get("tool")).isEqualTo("workspace.tree"));
        ArgumentCaptor<CodeAskResponse> markerCaptor = ArgumentCaptor.forClass(CodeAskResponse.class);
        verify(conversationService).saveCodeTurn(eq(context), eq(null), anyString(), markerCaptor.capture());
        assertThat(markerCaptor.getValue().answer()).contains("최신 CLI에서는 자동으로 이어집니다");
        verify(codeRagService, never()).askConversational(any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void adviceContextReadRepairsNonJsonAdviceAnswerInsteadOfDroppingIt() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AppUser user = user();
        when(conversationService.requireTurn(eq(user), eq(spaceId), eq(RagConversationService.CODE), eq(repositoryId), eq(conversationId), eq(turnId)))
                .thenReturn(new RagConversationTurn(
                        turnId,
                        conversationId,
                        null,
                        "What else should I improve on my homepage?",
                        "Suggest additional homepage improvements.",
                        "agent_advice",
                        "saved",
                        "high",
                        null,
                        null,
                        null,
                        null,
                        OffsetDateTime.now()
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY), anyInt(), any()))
                .thenReturn(new OllamaClient.ChatResult(
                        "You could improve the tab navigation and add a clearer organization section.",
                        "stop",
                        true,
                        100,
                        40,
                        "http://ollama",
                        "model",
                        "PRIMARY",
                        false
                ))
                .thenReturn(new OllamaClient.ChatResult(
                        """
                        {"summary":"Homepage improvements are available.","candidates":[{"title":"Improve tab navigation","reason":"The homepage has tabs but the visual state can be clearer.","evidenceFiles":["index.html","script.js"],"expectedFiles":["index.html","script.js","style.css"],"riskLevel":"low","testPlan":"Open the homepage and switch tabs.","recommendedFixGoal":"Improve homepage tab navigation and active state styling."}]}
                        """,
                        "stop",
                        true,
                        100,
                        120,
                        "http://ollama",
                        "model",
                        "PRIMARY",
                        false
                ));

        Map<String, Object> response = service.saveContextReadResult(user, spaceId, new CodeAgentInteractiveContextReadResultRequest(
                repositoryId,
                conversationId,
                turnId,
                spaceId,
                agentId,
                workspaceId,
                List.of(Map.of(
                        "path", "index.html",
                        "status", "SUCCEEDED",
                        "content", "<main><nav><button>Home</button></nav></main>"
                )),
                List.of(),
                List.of()
        ));

        assertThat(response.get("contextRequired")).isEqualTo(false);
        assertThat(String.valueOf(response.get("answer"))).contains("개선 후보").contains("Improve tab navigation");
        assertThat(response.get("adviceCandidates")).asList().hasSize(1);
        assertThat(response.get("warnings").toString()).contains("Advice answer JSON parsing failed");
    }

    @Test
    void llmFailureAsksClarificationInsteadOfDefaultingToFix() {
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AppUser user = user();
        RagConversationContext context = context(conversationId);
        when(conversationService.prepare(eq(user), eq(spaceId), eq(RagConversationService.CODE), eq(repositoryId), eq(null), anyString(), eq(true)))
                .thenReturn(context);
        when(ollamaClient.chatResult(anyString(), anyString(), eq(OllamaClient.ChatRole.PRIMARY), anyInt(), any()))
                .thenThrow(new IllegalArgumentException("model unavailable"));
        when(conversationService.saveCodeTurn(eq(context), eq(null), anyString(), any()))
                .thenReturn(new CodeAskResponse("agent_clarification", "saved", List.of(), "low", List.of())
                        .withConversation(conversationId, turnId, null));

        var response = service.handleTurn(user, spaceId, List.of(spaceId), new CodeAgentInteractiveTurnRequest(
                repositoryId,
                spaceId,
                null,
                null,
                "음",
                null,
                6,
                null,
                null
        ));

        assertThat(response.intent()).isEqualTo("ASK_CLARIFICATION");
        assertThat(response.shouldRunCommand()).isFalse();
        assertThat(response.command()).isNull();
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("Interactive intent classifier failed"));
        verify(codeRagService, never()).askConversational(any(), any(), any(), anyString(), anyString(), any(), any());
    }

    private AppUser user() {
        return new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");
    }

    private RagConversationContext context(UUID conversationId) {
        return new RagConversationContext(
                conversationId,
                "rewritten",
                List.of(),
                List.of(),
                List.of(),
                false,
                ConversationIntent.NONE,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
