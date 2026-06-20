package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeGraphLlmEnricherTest {
    @Test
    void addsOnlyValidatedCandidateRelations() {
        LearnBotProperties properties = new LearnBotProperties();
        OllamaClient ollama = mock(OllamaClient.class);
        UUID chunkId = UUID.randomUUID();
        CodeGraph graph = graph(chunkId);
        when(ollama.chat(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY))).thenReturn("""
                {"relations":[
                  {"sourceKey":"method:a","targetKey":"method:b","type":"CALLS"},
                  {"sourceKey":"method:a","targetKey":"method:unknown","type":"CALLS"},
                  {"sourceKey":"method:a","targetKey":"method:b","type":"DELETES"}
                ]}
                """);
        CodeGraphLlmEnricher enricher = new CodeGraphLlmEnricher(properties, ollama, new ObjectMapper());

        CodeGraph enriched = enricher.enrich(graph, List.of(chunk(chunkId)));

        assertThat(enriched.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("CALLS");
            assertThat(edge.confidence()).isEqualTo(0.52);
            assertThat(edge.metadata()).containsEntry("source", "llm_fallback");
        });
        assertThat(enriched.edges()).noneMatch(edge -> "DELETES".equals(edge.type()) || edge.targetKey().contains("unknown"));
    }

    @Test
    void preservesDeterministicGraphWhenLlmFails() {
        LearnBotProperties properties = new LearnBotProperties();
        OllamaClient ollama = mock(OllamaClient.class);
        UUID chunkId = UUID.randomUUID();
        CodeGraph graph = graph(chunkId);
        when(ollama.chat(anyString(), anyString(), eq(OllamaClient.ChatRole.AUXILIARY)))
                .thenThrow(new IllegalStateException("offline"));
        CodeGraphLlmEnricher enricher = new CodeGraphLlmEnricher(properties, ollama, new ObjectMapper());

        CodeGraph enriched = enricher.enrich(graph, List.of(chunk(chunkId)));

        assertThat(enriched).isSameAs(graph);
    }

    private CodeGraph graph(UUID chunkId) {
        return new CodeGraph(
                List.of(
                        new CodeGraphNode("method:a", "method", "a", "A.a()", "A.java", chunkId, Map.of()),
                        new CodeGraphNode("method:b", "method", "b", "B.b()", "B.java", UUID.randomUUID(), Map.of())
                ),
                List.of(new CodeGraphEdge(
                        "method:a", "method:b", "REFERENCES", 0.55, chunkId,
                        Map.of("source", "deterministic_text_reference")
                ))
        );
    }

    private CodeSearchResult chunk(UUID chunkId) {
        return new CodeSearchResult(
                chunkId, UUID.randomUUID(), UUID.randomUUID(), "repo", "A.java", "method", "a", "A", "a",
                "sample", null, null, 0, 1, 3, "void a() { b(); }", 0, Map.of("language", "java")
        );
    }
}
