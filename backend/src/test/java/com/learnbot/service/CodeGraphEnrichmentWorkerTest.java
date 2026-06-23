package com.learnbot.service;

import com.learnbot.repository.CodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeGraphEnrichmentWorkerTest {
    @Test
    void mergesValidatedEdgesWithoutReplacingDeterministicGraph() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeGraphLlmEnricher enricher = mock(CodeGraphLlmEnricher.class);
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        CodeGraphEnrichmentJob job = new CodeGraphEnrichmentJob(UUID.randomUUID(), repositoryId, indexVersion, "RUNNING", 1, "worker");
        CodeGraph original = new CodeGraph(List.of(), List.of());
        CodeGraphEdge llmEdge = new CodeGraphEdge("method:a", "method:b", "CALLS", 0.52, null,
                Map.of("source", "llm_fallback"));
        CodeAnalysisDiagnostic diagnostic = new CodeAnalysisDiagnostic(
                "LLM_ENRICHMENT", "Ollama auxiliary", "SUCCESS", "ASYNC",
                1, 1, 0, 1, 0, 0, 1, 10, "done", Map.of()
        );
        when(repository.claimGraphEnrichmentJob(anyString())).thenReturn(Optional.of(job));
        when(repository.isActiveIndex(repositoryId, indexVersion)).thenReturn(true);
        when(repository.heartbeatGraphEnrichmentJob(eq(job.id()), anyString())).thenReturn(true);
        when(repository.loadGraph(repositoryId, indexVersion)).thenReturn(original);
        when(repository.listChunksForIndex(repositoryId, indexVersion)).thenReturn(List.of());
        when(enricher.enrichWithDiagnostics(eq(original), anyList()))
                .thenReturn(new CodeGraphAnalysisResult(new CodeGraph(List.of(), List.of(llmEdge)), diagnostic));
        when(repository.mergeGraphEdges(repositoryId, indexVersion, List.of(llmEdge))).thenReturn(1);
        when(repository.finishGraphEnrichmentJob(eq(job.id()), anyString(), eq("SUCCEEDED"), eq("Inserted 1 validated LLM relationships.")))
                .thenReturn(true);

        new CodeGraphEnrichmentWorker(repository, enricher).processNext();

        verify(repository).mergeGraphEdges(repositoryId, indexVersion, List.of(llmEdge));
        verify(repository).finishGraphEnrichmentJob(eq(job.id()), anyString(), eq("SUCCEEDED"), eq("Inserted 1 validated LLM relationships."));
    }

    @Test
    void skipsSupersededIndexWithoutCallingLlm() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeGraphLlmEnricher enricher = mock(CodeGraphLlmEnricher.class);
        CodeGraphEnrichmentJob job = new CodeGraphEnrichmentJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RUNNING", 1, "worker");
        when(repository.claimGraphEnrichmentJob(anyString())).thenReturn(Optional.of(job));
        when(repository.isActiveIndex(job.repositoryId(), job.indexVersion())).thenReturn(false);
        when(repository.finishGraphEnrichmentJob(eq(job.id()), anyString(), eq("SKIPPED"), eq("Index is no longer active.")))
                .thenReturn(true);

        new CodeGraphEnrichmentWorker(repository, enricher).processNext();

        verify(repository).finishGraphEnrichmentJob(eq(job.id()), anyString(), eq("SKIPPED"), eq("Index is no longer active."));
    }

    @Test
    void defersWhenUserRagRequestIsActive() {
        CodeRepository repository = mock(CodeRepository.class);
        CodeGraphLlmEnricher enricher = mock(CodeGraphLlmEnricher.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        CodeGraphEnrichmentJob job = new CodeGraphEnrichmentJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RUNNING", 1, "worker");
        String message = "User RAG request is active; code graph LLM enrichment was deferred.";
        when(repository.claimGraphEnrichmentJob(anyString())).thenReturn(Optional.of(job));
        when(repository.isActiveIndex(job.repositoryId(), job.indexVersion())).thenReturn(true);
        when(ollamaClient.hasPrimaryRequestInFlight()).thenReturn(true);
        when(repository.deferGraphEnrichmentJob(eq(job.id()), anyString(), eq(30), eq(message))).thenReturn(true);

        new CodeGraphEnrichmentWorker(repository, enricher, ollamaClient).processNext();

        verify(repository).deferGraphEnrichmentJob(eq(job.id()), anyString(), eq(30), eq(message));
        verify(repository).updateJobEnrichment(job.indexVersion(), "PENDING", message);
        verify(enricher, never()).enrichWithDiagnostics(any(), anyList());
    }
}
