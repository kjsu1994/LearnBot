package com.learnbot.web;

import com.learnbot.dto.AskRequest;
import com.learnbot.dto.AskResponse;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.RagConversationService;
import com.learnbot.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagService ragService;
    private final RagConversationService conversationService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;
    private final RagSseEmitterSupport sseSupport;

    public RagController(
            RagService ragService,
            RagConversationService conversationService,
            AuthService authService,
            CurrentUserProvider currentUserProvider,
            RagSseEmitterSupport sseSupport
    ) {
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
        this.sseSupport = sseSupport;
    }

    @PostMapping("/ask")
    AskResponse ask(@Valid @RequestBody AskRequest request) {
        var user = currentUserProvider.currentUser();
        var selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        var accessibleSpaceIds = authService.accessibleSpaceIds(user);
        if (selectedSpaceId == null && !accessibleSpaceIds.isEmpty()) {
            selectedSpaceId = accessibleSpaceIds.get(0);
        }
        boolean conversational = Boolean.TRUE.equals(request.conversational()) || request.conversationId() != null;
        if (!conversational) {
            return ragService.ask(request.question(), request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selectedSpaceId);
        }
        var context = conversationService.prepare(
                user,
                selectedSpaceId,
                RagConversationService.DOCUMENT,
                null,
                request.conversationId(),
                request.question(),
                true
        );
        AskResponse response = ragService.askConversational(request.question(), context, request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selectedSpaceId);
        return conversationService.saveDocumentTurn(context, request.parentTurnId(), request.question(), response);
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter askStream(@Valid @RequestBody AskRequest request) {
        var user = currentUserProvider.currentUser();
        var selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        var accessibleSpaceIds = authService.accessibleSpaceIds(user);
        if (selectedSpaceId == null && !accessibleSpaceIds.isEmpty()) {
            selectedSpaceId = accessibleSpaceIds.get(0);
        }
        UUIDHolder selected = new UUIDHolder(selectedSpaceId);
        return sseSupport.stream(
                user,
                "\uB3D9\uC2DC \uB2F5\uBCC0 \uC0DD\uC131 \uC694\uCCAD\uC774 \uB9CE\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD558\uC138\uC694.",
                "Document RAG stream failed.",
                events -> {
                    boolean conversational = Boolean.TRUE.equals(request.conversational()) || request.conversationId() != null;
                    events.metadata(java.util.Map.of(
                            "conversational", conversational,
                            "domain", RagConversationService.DOCUMENT,
                            "mode", request.mode() == null ? "qa" : request.mode()
                    ));
                    RagService.AnswerStreamSink sink = new RagService.AnswerStreamSink() {
                        @Override
                        public void onEvidence(java.util.List<com.learnbot.dto.SearchResult> citations, java.util.List<com.learnbot.dto.AnswerEvidence> evidence) {
                            events.evidence(java.util.Map.of("citations", citations, "evidence", evidence));
                        }

                        @Override
                        public void onDelta(String text) {
                            events.delta(text);
                        }

                        @Override
                        public void onReplace(String answer, String reason) {
                            events.replace(answer, reason);
                        }
                    };
                    if (!conversational) {
                        return ragService.askStreaming(request.question(), request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selected.value(), sink);
                    }
                    var context = conversationService.prepare(
                            user,
                            selected.value(),
                            RagConversationService.DOCUMENT,
                            null,
                            request.conversationId(),
                            request.question(),
                            true
                    );
                    AskResponse response = ragService.askConversationalStreaming(request.question(), context, request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selected.value(), sink);
                    return conversationService.saveDocumentTurn(context, request.parentTurnId(), request.question(), response);
                }
        );
    }

    private record UUIDHolder(java.util.UUID value) {
    }
}
