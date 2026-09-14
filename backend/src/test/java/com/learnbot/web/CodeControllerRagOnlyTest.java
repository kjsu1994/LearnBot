package com.learnbot.web;

import com.learnbot.dto.CodeAskRequest;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CodeControllerRagOnlyTest {
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final AppUser user = new AppUser(UUID.randomUUID(), "reader@example.test", "Reader", "USER", "ACTIVE");
    private final CodeIndexingService indexing = mock(CodeIndexingService.class);
    private final CodeRagService rag = mock(CodeRagService.class);
    private final RagConversationService conversations = mock(RagConversationService.class);
    private final AuthService auth = mock(AuthService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final RagSseEmitterSupport sse = mock(RagSseEmitterSupport.class);
    private final CodeAskResponse answer = new CodeAskResponse("EXPLAIN", "Grounded answer [1]", List.of(), "HIGH", List.of());
    private final CodeController controller = new CodeController(indexing, mock(CodeFileBrowserService.class),
            mock(CodeSearchService.class), rag, conversations, mock(CodeReferenceService.class), auth, users, sse);

    @BeforeEach
    void setUp() {
        when(users.currentUser()).thenReturn(user);
        when(auth.accessibleSpaceIds(user)).thenReturn(List.of(spaceId));
        when(indexing.repositorySpace(user, repositoryId)).thenReturn(spaceId);
    }

    private CodeAskRequest request(boolean conversational) {
        return new CodeAskRequest(repositoryId, null, "Explain the entry flow", "EXPLAIN", 8, null, null, conversational);
    }

    @Test
    void independentQuestionPreservesRepositoryAccessAndRagContract() {
        when(rag.ask(repositoryId, spaceId, List.of(spaceId), request(false).question(), "EXPLAIN", 8)).thenReturn(answer);
        assertSame(answer, controller.ask(request(false)));
        verify(auth).requireSpace(user, spaceId);
        verifyNoInteractions(conversations);
    }

    @Test
    void conversationalQuestionPreservesPreparationAndPersistence() {
        var context = new RagConversationContext(null, request(true).question(), List.of());
        when(conversations.prepare(user, spaceId, "CODE", repositoryId, null, request(true).question(), true)).thenReturn(context);
        when(rag.askConversational(repositoryId, spaceId, List.of(spaceId), request(true).question(), "EXPLAIN", 8, context)).thenReturn(answer);
        when(conversations.saveCodeTurn(context, null, request(true).question(), answer)).thenReturn(answer);
        assertSame(answer, controller.ask(request(true)));
        verify(auth).requireSpace(user, spaceId);
        verify(conversations).saveCodeTurn(context, null, request(true).question(), answer);
    }

    @Test
    void streamingQuestionPreservesEvidenceAndDeltaSink() throws Exception {
        var events = mock(RagSseEmitterSupport.StreamEvents.class);
        var emitter = new SseEmitter();
        when(sse.stream(eq(user), anyString(), anyString(), any())).thenAnswer(invocation -> {
            var handler = invocation.getArgument(3, RagSseEmitterSupport.StreamHandler.class);
            assertSame(answer, handler.handle(events));
            return emitter;
        });
        when(rag.askStreaming(eq(repositoryId), eq(spaceId), eq(List.of(spaceId)), eq(request(false).question()), eq("EXPLAIN"), eq(8), any()))
                .thenAnswer(invocation -> {
                    var sink = invocation.getArgument(6, CodeRagService.CodeAnswerStreamSink.class);
                    sink.onStatus("answer", "Generating");
                    sink.onEvidence(List.of());
                    sink.onDelta("Grounded");
                    sink.onReplace(answer.answer(), "verified");
                    return answer;
                });
        assertSame(emitter, controller.askStream(request(false)));
        verify(auth).requireSpace(user, spaceId);
        verify(events).metadata(any());
        verify(events).evidence(any());
        verify(events).status("answer", "Generating");
        verify(events).delta("Grounded");
        verify(events).replace(answer.answer(), "verified");
    }

    @Test
    void removedChangeAssistEndpointIsNotMapped() throws Exception {
        standaloneSetup(controller).build()
                .perform(post("/api/code/conversations/{conversationId}/turns/{turnId}/change-assist", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
