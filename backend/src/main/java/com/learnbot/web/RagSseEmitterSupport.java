package com.learnbot.web;

import com.learnbot.service.AppUser;
import com.learnbot.service.RagStreamLimiter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Component
public class RagSseEmitterSupport {
    private final RagStreamLimiter streamLimiter;

    public RagSseEmitterSupport(RagStreamLimiter streamLimiter) {
        this.streamLimiter = streamLimiter;
    }

    public SseEmitter stream(AppUser user, String limitMessage, String fallbackErrorMessage, StreamHandler handler) {
        SseEmitter emitter = new SseEmitter(0L);
        var permit = streamLimiter.tryAcquire(user);
        if (permit == null) {
            try {
                sendEvent(emitter, "error", Map.of(
                        "code", "STREAM_LIMIT_EXCEEDED",
                        "message", limitMessage
                ));
            } catch (Exception ignored) {
                // Ignore SSE error reporting failures.
            }
            emitter.complete();
            return emitter;
        }
        StreamEvents events = new StreamEvents(emitter);
        Mono.fromRunnable(() -> {
            try {
                Object response = handler.handle(events);
                events.done(response);
                emitter.complete();
            } catch (Exception ex) {
                try {
                    events.error(ex.getMessage() == null ? fallbackErrorMessage : ex.getMessage());
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

    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException("SSE client disconnected.", ex);
        }
    }

    @FunctionalInterface
    public interface StreamHandler {
        Object handle(StreamEvents events) throws Exception;
    }

    public record StreamEvents(SseEmitter emitter) {
        public void metadata(Object data) {
            event("metadata", data);
        }

        public void evidence(Object data) {
            event("evidence", data);
        }

        public void delta(String text) {
            event("delta", Map.of("text", text == null ? "" : text));
        }

        public void replace(String answer, String reason) {
            event("replace", Map.of("answer", answer == null ? "" : answer, "reason", reason == null ? "" : reason));
        }

        public void done(Object data) {
            event("done", data);
        }

        public void error(String message) {
            event("error", Map.of("message", message));
        }

        public void event(String name, Object data) {
            sendEvent(emitter, name, data);
        }
    }
}
