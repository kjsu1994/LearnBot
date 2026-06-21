package com.learnbot.service;

import com.learnbot.repository.DocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentGraphRebuildWorkerTest {
    @Test
    void recordsDiagnosticsAndRefreshesReadinessWhenGraphRebuildSucceeds() {
        DocumentRepository repository = mock(DocumentRepository.class);
        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        DocumentGraphJob job = new DocumentGraphJob(UUID.randomUUID(), sourceId, jobId, "RUNNING", 1, "worker");
        DocumentProcessingDiagnostic diagnostic = new DocumentProcessingDiagnostic(
                "DOCUMENT_GRAPH_REBUILD", "Document graph builder", "SUCCESS", "ASYNC",
                3, 3, 0, 7, 9, 12, "done", Map.of()
        );

        when(repository.claimDocumentGraphJob(anyString())).thenReturn(Optional.of(job));
        when(repository.heartbeatDocumentGraphJob(eq(job.id()), anyString())).thenReturn(true);
        when(repository.rebuildDocumentGraphWithDiagnostic(sourceId)).thenReturn(diagnostic);
        when(repository.finishDocumentGraphJob(eq(job.id()), anyString(), eq("SUCCEEDED"), eq("done"))).thenReturn(true);

        new DocumentGraphRebuildWorker(repository).processNext();

        verify(repository).addDocumentProcessingDiagnostic(sourceId, jobId, diagnostic);
        verify(repository).finishDocumentGraphJob(eq(job.id()), anyString(), eq("SUCCEEDED"), eq("done"));
        verify(repository).refreshSourceReadiness(sourceId);
    }

    @Test
    void retriesGraphRebuildFailureWithoutFailingSearchableSource() {
        DocumentRepository repository = mock(DocumentRepository.class);
        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        DocumentGraphJob job = new DocumentGraphJob(UUID.randomUUID(), sourceId, jobId, "RUNNING", 1, "worker");

        when(repository.claimDocumentGraphJob(anyString())).thenReturn(Optional.of(job));
        when(repository.heartbeatDocumentGraphJob(eq(job.id()), anyString())).thenReturn(true);
        when(repository.rebuildDocumentGraphWithDiagnostic(sourceId)).thenThrow(new RuntimeException("graph timeout"));
        when(repository.retryDocumentGraphJob(eq(job.id()), anyString(), eq(1), eq("graph timeout"))).thenReturn(true);

        new DocumentGraphRebuildWorker(repository).processNext();

        verify(repository).retryDocumentGraphJob(eq(job.id()), anyString(), eq(1), eq("graph timeout"));
        verify(repository).refreshSourceReadiness(sourceId);
    }
}
