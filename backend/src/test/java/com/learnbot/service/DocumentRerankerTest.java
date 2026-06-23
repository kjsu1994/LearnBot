package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminTuningRerankerStatus;
import com.learnbot.dto.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentRerankerTest {
    @Test
    void disabledRerankerDoesNotCallExternalService() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().getReranker().setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        DocumentReranker reranker = new DocumentReranker(properties, webClient(calls, ClientResponse.create(HttpStatus.OK).build()));

        List<SearchResult> output = reranker.rerank("security policy", List.of(result("A", 0.8), result("B", 0.7)));
        AdminTuningRerankerStatus status = reranker.status();
        AdminTuningRerankerStatus unload = reranker.unload();

        assertThat(output).hasSize(2);
        assertThat(calls).hasValue(0);
        assertThat(status.serviceStatus()).isEqualTo("disabled");
        assertThat(unload.serviceStatus()).isEqualTo("disabled");
    }

    @Test
    void startupWarmupRequiresDedicatedFlag() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().getReranker().setEnabled(true);
        properties.getRag().getPipeline().getReranker().setWarmupOnStartup(false);
        AtomicInteger calls = new AtomicInteger();
        DocumentReranker reranker = new DocumentReranker(properties, webClient(calls, ClientResponse.create(HttpStatus.OK).build()));

        reranker.warmupOnStartup();

        assertThat(calls).hasValue(0);
    }

    @Test
    void unloadFallsBackToUnavailableStatusWhenServiceFails() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().getReranker().setEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        DocumentReranker reranker = new DocumentReranker(properties, webClient(calls, ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()));

        AdminTuningRerankerStatus status = reranker.unload();

        assertThat(calls).hasValue(1);
        assertThat(status.serviceStatus()).isEqualTo("unavailable");
        assertThat(status.lastError()).contains("unload_");
    }

    @Test
    void statusMapsReadyPayload() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().getReranker().setEnabled(true);
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"status":"ready","modelLoaded":true,"modelLoading":false,"activeRequests":2,"modelName":"rerank:test","device":"cuda","cudaAllocatedBytes":123,"cudaReservedBytes":456}
                        """)
                .build();
        DocumentReranker reranker = new DocumentReranker(properties, webClient(new AtomicInteger(), response));

        AdminTuningRerankerStatus status = reranker.status();

        assertThat(status.serviceStatus()).isEqualTo("ready");
        assertThat(status.modelLoaded()).isTrue();
        assertThat(status.activeRequests()).isEqualTo(2);
        assertThat(status.cudaAllocatedBytes()).isEqualTo(123L);
    }

    @Test
    void runtimeEnabledSettingOverridesEnvironmentDefault() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().getReranker().setEnabled(false);
        RuntimeTuningService runtimeTuningService = mock(RuntimeTuningService.class);
        when(runtimeTuningService.rerankerEnabled()).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"results":[{"id":"%s","score":0.99}]}
                        """.formatted("11111111-1111-1111-1111-111111111111"))
                .build();
        DocumentReranker reranker = new DocumentReranker(properties, webClient(calls, response), runtimeTuningService);
        SearchResult candidate = new SearchResult(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.randomUUID(),
                "A",
                "file://A",
                "FILE",
                "application/pdf",
                1,
                "content long enough for reranking candidate",
                0.4
        );

        SearchResult second = new SearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "B",
                "file://B",
                "FILE",
                "application/pdf",
                1,
                "another long enough content for reranking candidate",
                0.1
        );

        List<SearchResult> output = reranker.rerank("security policy", List.of(candidate, second));

        assertThat(calls).hasValue(1);
        assertThat(output.get(0).metadata()).containsEntry("rerankerUsed", true);
    }

    private WebClient.Builder webClient(AtomicInteger calls, ClientResponse response) {
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return Mono.just(response);
        };
        return WebClient.builder().exchangeFunction(exchange);
    }

    private SearchResult result(String title, double score) {
        return new SearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                title,
                "file://" + title,
                "FILE",
                "application/pdf",
                1,
                "content for " + title,
                score
        );
    }
}
