package com.learnbot.service.coderag.answer;

import com.learnbot.service.OllamaClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ollama adapter for {@link CodeAnswerGenerator}.
 */
@Component
public final class OllamaCodeAnswerGenerator implements CodeAnswerGenerator {
    private static final int STREAM_BATCH_SIZE = 256;
    private static final Duration STREAM_BATCH_WINDOW = Duration.ofMillis(35);

    private final OllamaClient ollamaClient;

    public OllamaCodeAnswerGenerator(OllamaClient ollamaClient) {
        this.ollamaClient = Objects.requireNonNull(ollamaClient, "ollamaClient");
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        GenerationRequest safeRequest = Objects.requireNonNull(request, "request");
        return from(ollamaClient.chatResult(
                safeRequest.systemPrompt(),
                safeRequest.userPrompt(),
                safeRequest.maxOutputTokens()));
    }

    @Override
    public GenerationResult stream(GenerationRequest request, DeltaSink deltaSink) {
        GenerationRequest safeRequest = Objects.requireNonNull(request, "request");
        DeltaSink safeSink = Objects.requireNonNull(deltaSink, "deltaSink");
        StringBuilder answer = new StringBuilder();
        AtomicReference<OllamaClient.ChatStreamDelta> finalDelta = new AtomicReference<>();

        ollamaClient.streamChat(
                        safeRequest.systemPrompt(),
                        safeRequest.userPrompt(),
                        safeRequest.maxOutputTokens())
                .bufferTimeout(STREAM_BATCH_SIZE, STREAM_BATCH_WINDOW)
                .filter(batch -> !batch.isEmpty())
                .doOnNext(batch -> {
                    StringBuilder emitted = new StringBuilder();
                    for (OllamaClient.ChatStreamDelta delta : batch) {
                        if (delta == null) {
                            continue;
                        }
                        if (delta.done()) {
                            finalDelta.set(delta);
                        }
                        if (delta.content() != null && !delta.content().isEmpty()) {
                            answer.append(delta.content());
                            emitted.append(delta.content());
                        }
                    }
                    if (!emitted.isEmpty()) {
                        safeSink.onDelta(emitted.toString());
                    }
                })
                .blockLast();

        OllamaClient.ChatStreamDelta done = finalDelta.get();
        return new GenerationResult(
                answer.toString().trim(),
                done == null ? null : done.doneReason(),
                done == null || done.done(),
                done == null ? 0 : done.promptEvalCount(),
                done == null ? 0 : done.evalCount(),
                done == null ? "" : done.baseUrl(),
                done == null ? "" : done.model(),
                done == null ? "primary" : done.role(),
                done != null && done.fallbackUsed());
    }

    private GenerationResult from(OllamaClient.ChatResult result) {
        if (result == null) {
            return null;
        }
        return new GenerationResult(
                result.content(),
                result.doneReason(),
                result.done(),
                result.promptEvalCount(),
                result.evalCount(),
                result.baseUrl(),
                result.model(),
                result.role(),
                result.fallbackUsed());
    }
}
