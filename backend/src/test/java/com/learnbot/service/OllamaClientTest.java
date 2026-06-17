package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaClientTest {
    @Test
    void primaryChatFallsBackToAuxiliaryBeforeComposeDefault() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setBaseUrl("http://compose-ollama:11434");
        properties.getOllama().setChatModel("compose:model");
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://primary:11434", "qwen:test", true, "primary"));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));
        when(adminSettingsService.defaultLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://compose-ollama:11434", "compose:model", false, "compose-default"));

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
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));
        when(adminSettingsService.defaultLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://compose-ollama:11434", "compose:model", false, "compose-default"));

        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
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
    }

    @Test
    void primaryChatFallsBackToComposeDefaultAfterAuxiliaryFails() {
        LearnBotProperties properties = new LearnBotProperties();
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://primary:11434", "qwen:test", true, "primary"));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://auxiliary:11434", "qwen-small:test", true, "auxiliary"));
        when(adminSettingsService.defaultLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings("http://compose-ollama:11434", "compose:model", false, "compose-default"));

        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int call = calls.incrementAndGet();
            if (call < 3) {
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("failed").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"message":{"content":"compose fallback"},"done_reason":"stop","done":true}
                            """)
                    .build());
        };
        OllamaClient client = new OllamaClient(WebClient.builder().exchangeFunction(exchange), properties, adminSettingsService);

        OllamaClient.ChatResult result = client.chatResult("system", "user");

        assertThat(result.content()).isEqualTo("compose fallback");
        assertThat(result.model()).isEqualTo("compose:model");
        assertThat(result.role()).isEqualTo("compose-default");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(calls).hasValue(3);
    }
}
