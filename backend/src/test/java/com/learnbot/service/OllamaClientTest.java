package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaClientTest {
    @Test
    void primaryChatFallsBackToAuxiliaryOnly() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setBaseUrl("http://compose-ollama:11434");
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://primary:11434", "qwen:test", true, "primary"));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));

        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("primary failed").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"message":{"content":"auxiliary answer [1]"},"done_reason":"stop","done":true,"prompt_eval_count":3,"eval_count":4}
                            """)
                    .build());
        };
        OllamaClient client = new OllamaClient(WebClient.builder().exchangeFunction(exchange), properties, adminSettingsService);

        OllamaClient.ChatResult result = client.chatResult("system", "user");

        assertThat(result.content()).isEqualTo("auxiliary answer [1]");
        assertThat(result.model()).isEqualTo("qwen-small:test");
        assertThat(result.role()).isEqualTo("auxiliary");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void auxiliaryChatSkipsPrimaryModel() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setAuxiliaryKeepAlive("0s");
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            MockClientHttpRequest capture = new MockClientHttpRequest(request.method(), URI.create("http://auxiliary:11434/api/chat"));
            request.body().insert(capture, bodyInserterContext()).block();
            body.set(capture.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"message":{"content":"{\\"queries\\":[\\"rewritten\\"]}"},"done_reason":"stop","done":true}
                            """)
                    .build());
        };
        OllamaClient client = new OllamaClient(WebClient.builder().exchangeFunction(exchange), properties, adminSettingsService);

        OllamaClient.ChatResult result = client.chatResult("system", "user", OllamaClient.ChatRole.AUXILIARY);

        assertThat(result.model()).isEqualTo("qwen-small:test");
        assertThat(result.role()).isEqualTo("auxiliary");
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(body.get()).contains("\"keep_alive\":\"0s\"");
    }

    @Test
    void primaryChatStopsAfterAuxiliaryFails() {
        LearnBotProperties properties = new LearnBotProperties();
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://primary:11434", "qwen:test", true, "primary"));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));

        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("failed").build());
        };
        OllamaClient client = new OllamaClient(WebClient.builder().exchangeFunction(exchange), properties, adminSettingsService);

        assertThatThrownBy(() -> client.chatResult("system", "user"))
                .isInstanceOf(RuntimeException.class);

        assertThat(calls).hasValue(2);
    }

    @Test
    void streamFallsBackWhenPrimaryFailsBeforeFirstDelta() {
        LearnBotProperties properties = new LearnBotProperties();
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://primary:11434", "qwen:test", true, "primary"));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));

        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("primary failed").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_NDJSON_VALUE)
                    .body("""
                            {"message":{"content":"auxiliary "},"done":false}
                            {"message":{"content":"answer"},"done_reason":"stop","done":true,"prompt_eval_count":1,"eval_count":2}
                            """)
                    .build());
        };
        OllamaClient client = new OllamaClient(WebClient.builder().exchangeFunction(exchange), properties, adminSettingsService);

        List<OllamaClient.ChatStreamDelta> deltas = client.streamChat("system", "user", null)
                .collectList()
                .block();

        assertThat(deltas).hasSize(2);
        assertThat(deltas.get(0).content()).isEqualTo("auxiliary ");
        assertThat(deltas.get(0).role()).isEqualTo("auxiliary");
        assertThat(deltas.get(0).fallbackUsed()).isTrue();
        assertThat(deltas.get(1).content()).isEqualTo("answer");
        assertThat(deltas.get(1).done()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void streamDoesNotFallBackAfterFirstDeltaFailure() {
        LearnBotProperties properties = new LearnBotProperties();
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://primary:11434", "qwen:test", true, "primary"));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));

        AtomicInteger calls = new AtomicInteger();
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_NDJSON_VALUE)
                    .body(Flux.concat(
                            Flux.just(bufferFactory.wrap("{\"message\":{\"content\":\"partial\"},\"done\":false}\n".getBytes())),
                            Flux.error(new IllegalStateException("stream interrupted"))
                    ))
                    .build());
        };
        OllamaClient client = new OllamaClient(WebClient.builder().exchangeFunction(exchange), properties, adminSettingsService);

        AtomicReference<OllamaClient.ChatStreamDelta> first = new AtomicReference<>();
        assertThatThrownBy(() -> client.streamChat("system", "user", null)
                .doOnNext(first::set)
                .blockLast())
                .isInstanceOf(WebClientResponseException.class)
                .hasMessageContaining("stream interrupted");
        assertThat(first.get().content()).isEqualTo("partial");
        assertThat(calls).hasValue(1);
    }

    private BodyInserter.Context bodyInserterContext() {
        return new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        };
    }
}
