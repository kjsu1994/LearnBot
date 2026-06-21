package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.repository.DocumentRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentEnrichmentWorkerTest {
    @Test
    void replacesOnlyContextChunksWhenLlmEnrichmentSucceeds() {
        DocumentRepository repository = mock(DocumentRepository.class);
        DocumentContextBuilder contextBuilder = mock(DocumentContextBuilder.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        DocumentEnrichmentJob job = new DocumentEnrichmentJob(UUID.randomUUID(), sourceId, jobId, "RUNNING", 1, "worker");
        Chunk contextChunk = new Chunk(0, "LLM summary", Map.of("kind", "document_context", "contextType", "document_summary"));

        when(repository.claimDocumentEnrichmentJob(anyString())).thenReturn(Optional.of(job));
        when(repository.heartbeatDocumentEnrichmentJob(eq(job.id()), anyString())).thenReturn(true);
        when(repository.listDocumentsForSource(sourceId)).thenReturn(List.of(
                new DocumentRepository.StoredDocumentForEnrichment(documentId, "guide.md", "https://example.test/guide.md", "text/markdown", Map.of())
        ));
        when(repository.listDocumentChunks(documentId)).thenReturn(List.of(
                new DocumentChunkDetail(UUID.randomUUID(), 0, "Original content", Map.of("kind", "content"), OffsetDateTime.now()),
                new DocumentChunkDetail(UUID.randomUUID(), 1, "Old context", Map.of("kind", "document_context"), OffsetDateTime.now())
        ));
        when(contextBuilder.buildSourceContext(anyList(), eq(false), eq(true))).thenReturn(List.of());
        when(contextBuilder.buildDocumentContext(any(), anyList(), eq(false), eq(true))).thenReturn(List.of(contextChunk));
        when(embeddingService.embed(List.of("LLM summary"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        when(repository.finishDocumentEnrichmentJob(eq(job.id()), anyString(), eq("SUCCEEDED"), eq("LLM 품질 보강이 완료되었습니다."))).thenReturn(true);

        newWorker(repository, contextBuilder, embeddingService).processNext();

        verify(repository).updateDocumentJobEnrichment(jobId, "RUNNING", "LLM 품질 보강을 진행 중입니다.");
        verify(repository).replaceDocumentContextChunks(documentId, List.of(contextChunk), List.of(List.of(0.1, 0.2, 0.3)));
        verify(repository).updateDocumentJobEnrichment(jobId, "SUCCEEDED", "LLM 품질 보강이 완료되었습니다.");
    }

    @Test
    void retriesAndKeepsSearchableIndexWhenEnrichmentFails() {
        DocumentRepository repository = mock(DocumentRepository.class);
        DocumentContextBuilder contextBuilder = mock(DocumentContextBuilder.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        DocumentEnrichmentJob job = new DocumentEnrichmentJob(UUID.randomUUID(), sourceId, jobId, "RUNNING", 1, "worker");

        when(repository.claimDocumentEnrichmentJob(anyString())).thenReturn(Optional.of(job));
        when(repository.heartbeatDocumentEnrichmentJob(eq(job.id()), anyString())).thenReturn(true);
        when(repository.listDocumentsForSource(sourceId)).thenReturn(List.of(
                new DocumentRepository.StoredDocumentForEnrichment(documentId, "guide.md", "https://example.test/guide.md", "text/markdown", Map.of())
        ));
        when(repository.listDocumentChunks(documentId)).thenReturn(List.of(
                new DocumentChunkDetail(UUID.randomUUID(), 0, "Original content", Map.of("kind", "content"), OffsetDateTime.now())
        ));
        when(contextBuilder.buildSourceContext(anyList(), eq(false), eq(true))).thenThrow(new RuntimeException("model timeout"));
        when(repository.retryDocumentEnrichmentJob(eq(job.id()), anyString(), eq(1), eq("model timeout"))).thenReturn(true);

        newWorker(repository, contextBuilder, embeddingService).processNext();

        verify(repository).updateDocumentJobEnrichment(jobId, "RUNNING", "LLM 품질 보강을 진행 중입니다.");
        verify(repository).retryDocumentEnrichmentJob(eq(job.id()), anyString(), eq(1), eq("model timeout"));
        verify(repository).updateDocumentJobEnrichment(jobId, "RETRYING", "model timeout");
    }

    private DocumentEnrichmentWorker newWorker(
            DocumentRepository repository,
            DocumentContextBuilder contextBuilder,
            EmbeddingService embeddingService
    ) {
        return new DocumentEnrichmentWorker(
                repository,
                contextBuilder,
                embeddingService,
                mock(OllamaClient.class),
                new LearnBotProperties()
        );
    }
}
