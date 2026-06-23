package com.learnbot.web;

import com.learnbot.dto.AskRequest;
import com.learnbot.dto.AskResponse;
import com.learnbot.service.RagConversationService;
import com.learnbot.service.RagStreamLimiter;
import com.learnbot.service.RagService;
import com.learnbot.service.AuthService;
import com.learnbot.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagService ragService;
    private final RagConversationService conversationService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;
    private final RagStreamLimiter streamLimiter;

    public RagController(RagService ragService, RagConversationService conversationService, AuthService authService, CurrentUserProvider currentUserProvider, RagStreamLimiter streamLimiter) {
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
        this.streamLimiter = streamLimiter;
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
        SseEmitter emitter = new SseEmitter(0L);
        var user = currentUserProvider.currentUser();
        var permit = streamLimiter.tryAcquire(user);
        if (permit == null) {
            try {
                sendEvent(emitter, "error", java.util.Map.of(
                        "code", "STREAM_LIMIT_EXCEEDED",
                        "message", "동시 답변 생성 요청이 많습니다. 잠시 후 다시 시도하세요."
                ));
            } catch (Exception ignored) {
                // Ignore SSE error reporting failures.
            }
            emitter.complete();
            return emitter;
        }
        var selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        var accessibleSpaceIds = authService.accessibleSpaceIds(user);
        if (selectedSpaceId == null && !accessibleSpaceIds.isEmpty()) {
            selectedSpaceId = accessibleSpaceIds.get(0);
        }
        UUIDHolder selected = new UUIDHolder(selectedSpaceId);
        Mono.fromRunnable(() -> {
            try {
                boolean conversational = Boolean.TRUE.equals(request.conversational()) || request.conversationId() != null;
                sendEvent(emitter, "metadata", java.util.Map.of(
                        "conversational", conversational,
                        "domain", RagConversationService.DOCUMENT,
                        "mode", request.mode() == null ? "qa" : request.mode()
                ));
                AskResponse response;
                RagService.AnswerStreamSink sink = new RagService.AnswerStreamSink() {
                    @Override
                    public void onEvidence(java.util.List<com.learnbot.dto.SearchResult> citations, java.util.List<com.learnbot.dto.AnswerEvidence> evidence) {
                        sendEvent(emitter, "evidence", java.util.Map.of("citations", citations, "evidence", evidence));
                    }

                    @Override
                    public void onDelta(String text) {
                        sendEvent(emitter, "delta", java.util.Map.of("text", text));
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                        sendEvent(emitter, "replace", java.util.Map.of("answer", answer, "reason", reason));
                    }
                };
                if (!conversational) {
                    response = ragService.askStreaming(request.question(), request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selected.value(), sink);
                } else {
                    var context = conversationService.prepare(
                            user,
                            selected.value(),
                            RagConversationService.DOCUMENT,
                            null,
                            request.conversationId(),
                            request.question(),
                            true
                    );
                    response = ragService.askConversationalStreaming(request.question(), context, request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selected.value(), sink);
                    response = conversationService.saveDocumentTurn(context, request.parentTurnId(), request.question(), response);
                }
                sendEvent(emitter, "done", response);
                emitter.complete();
            } catch (Exception ex) {
                try {
                    sendEvent(emitter, "error", java.util.Map.of("message", ex.getMessage() == null ? "Document RAG stream failed." : ex.getMessage()));
                } catch (Exception ignored) {
                    // Ignore SSE error reporting failures.
                }
                emitter.completeWithError(ex);
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> permit.close())
                .subscribe();
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException("SSE client disconnected.", ex);
        }
    }

    private record UUIDHolder(java.util.UUID value) {
    }
}
