package com.learnbot.service.coderag.answer;

import com.learnbot.service.OllamaClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OllamaCodeAnswerGeneratorTest {
    @Test
    void mapsANonStreamingOllamaResultWithoutChangingProviderMetadata() {
        OllamaClient client = mock(OllamaClient.class);
        when(client.chatResult("system", "user", 400)).thenReturn(new OllamaClient.ChatResult(
                "grounded answer [1]", "stop", true, 21, 8,
                "http://ollama", "answer-model", "primary", false));
        OllamaCodeAnswerGenerator generator = new OllamaCodeAnswerGenerator(client);

        CodeAnswerGenerator.GenerationResult result = generator.generate(
                CodeAnswerGenerator.GenerationRequest.initial("system", "user", 400));

        assertThat(result.answer()).isEqualTo("grounded answer [1]");
        assertThat(result.doneReason()).isEqualTo("stop");
        assertThat(result.promptTokens()).isEqualTo(21);
        assertThat(result.outputTokens()).isEqualTo(8);
        assertThat(result.model()).isEqualTo("answer-model");
        assertThat(result.fallbackUsed()).isFalse();
        verify(client).chatResult("system", "user", 400);
    }

    @Test
    void returnsNullWhenTheProviderReturnsNoResultSoTheOrchestratorCanUseItsLegacyFallback() {
        OllamaClient client = mock(OllamaClient.class);
        when(client.chatResult("system", "user", 400)).thenReturn(null);
        OllamaCodeAnswerGenerator generator = new OllamaCodeAnswerGenerator(client);

        CodeAnswerGenerator.GenerationResult result = generator.generate(
                CodeAnswerGenerator.GenerationRequest.repair("system", "user", 400));

        assertThat(result).isNull();
        verify(client).chatResult("system", "user", 400);
    }

    @Test
    void streamsBufferedTextAndUsesTheTerminalDeltaForResultMetadata() {
        OllamaClient client = mock(OllamaClient.class);
        when(client.streamChat("system", "continue", 240)).thenReturn(Flux.just(
                delta("First ", false, null, 0, 0),
                delta("second.", false, null, 0, 0),
                delta("", true, "stop", 35, 12)));
        OllamaCodeAnswerGenerator generator = new OllamaCodeAnswerGenerator(client);
        List<String> emitted = new ArrayList<>();

        CodeAnswerGenerator.GenerationResult result = generator.stream(
                CodeAnswerGenerator.GenerationRequest.continuation("system", "continue", 240),
                emitted::add);

        assertThat(String.join("", emitted)).isEqualTo("First second.");
        assertThat(result.answer()).isEqualTo("First second.");
        assertThat(result.done()).isTrue();
        assertThat(result.doneReason()).isEqualTo("stop");
        assertThat(result.promptTokens()).isEqualTo(35);
        assertThat(result.outputTokens()).isEqualTo(12);
        assertThat(result.role()).isEqualTo("primary");
    }

    @Test
    void preservesLengthStopAndFallbackSignalsForContinuationDecisions() {
        CodeAnswerGenerator.GenerationResult result = new CodeAnswerGenerator.GenerationResult(
                "partial", "length", true, 10, 20, "url", "model", "primary", true);

        assertThat(result.stoppedByLength()).isTrue();
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    void rejectsAnInvalidGenerationTokenLimitAtTheBoundary() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                CodeAnswerGenerator.GenerationRequest.repair("system", "repair", -1));
    }

    private OllamaClient.ChatStreamDelta delta(
            String content,
            boolean done,
            String doneReason,
            int promptTokens,
            int outputTokens
    ) {
        return new OllamaClient.ChatStreamDelta(
                content, doneReason, done, promptTokens, outputTokens,
                "http://ollama", "answer-model", "primary", false);
    }
}
