package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {
    @Test
    void usesConfiguredBatchSize() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = properties(64, 8);
        List<String> inputs = inputs(64);
        when(ollamaClient.embed(argThat(batch -> batch != null && batch.size() == 64)))
                .thenReturn(embeddings(64, properties.getEmbedding().getDimensions()));

        List<List<Double>> result = new EmbeddingService(ollamaClient, properties).embed(inputs);

        assertThat(result).hasSize(64);
        verify(ollamaClient, times(1)).embed(argThat(batch -> batch != null && batch.size() == 64));
    }

    @Test
    void splitsBatchWhenConfiguredBatchFails() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = properties(64, 8);
        List<String> inputs = inputs(64);
        when(ollamaClient.embed(argThat(batch -> batch != null && batch.size() == 64)))
                .thenThrow(new IllegalArgumentException("batch too large"));
        when(ollamaClient.embed(argThat(batch -> batch != null && batch.size() == 32)))
                .thenReturn(embeddings(32, properties.getEmbedding().getDimensions()));

        List<List<Double>> result = new EmbeddingService(ollamaClient, properties).embed(inputs);

        assertThat(result).hasSize(64);
        verify(ollamaClient, times(1)).embed(argThat(batch -> batch != null && batch.size() == 64));
        verify(ollamaClient, times(2)).embed(argThat(batch -> batch != null && batch.size() == 32));
    }

    private LearnBotProperties properties(int batchSize, int minBatchSize) {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getEmbedding().setBatchSize(batchSize);
        properties.getEmbedding().setMinBatchSize(minBatchSize);
        return properties;
    }

    private List<String> inputs(int count) {
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            inputs.add("text " + i);
        }
        return inputs;
    }

    private List<List<Double>> embeddings(int count, int dimensions) {
        List<List<Double>> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(Collections.nCopies(dimensions, 0.1));
        }
        return values;
    }
}
