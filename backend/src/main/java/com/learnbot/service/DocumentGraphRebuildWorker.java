package com.learnbot.service;

import com.learnbot.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class DocumentGraphRebuildWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentGraphRebuildWorker.class);
    private static final int MAX_ATTEMPTS = 2;

    private final DocumentRepository repository;
    private final String workerId = "document-graph-" + UUID.randomUUID();

    public DocumentGraphRebuildWorker(DocumentRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        repository.recoverDocumentGraphJobs();
    }

    @Scheduled(fixedDelayString = "${learnbot.document.graph-poll-millis:10000}")
    public void processNext() {
        repository.claimDocumentGraphJob(workerId).ifPresent(this::process);
    }

    private void process(DocumentGraphJob job) {
        try {
            if (!repository.heartbeatDocumentGraphJob(job.id(), workerId)) {
                return;
            }
            DocumentProcessingDiagnostic diagnostic = repository.rebuildDocumentGraphWithDiagnostic(job.sourceId());
            repository.addDocumentProcessingDiagnostic(job.sourceId(), job.jobId(), diagnostic);
            if (!repository.heartbeatDocumentGraphJob(job.id(), workerId)) {
                return;
            }
            finish(job, "SUCCEEDED", diagnostic.message());
            log.info("document_graph_rebuild sourceId={} jobId={} status=SUCCESS nodes={} edges={} durationMs={}",
                    job.sourceId(), job.jobId(), diagnostic.nodeCount(), diagnostic.edgeCount(), diagnostic.durationMillis());
        } catch (RuntimeException ex) {
            String message = rootMessage(ex);
            repository.addDocumentProcessingDiagnostic(job.sourceId(), job.jobId(), new DocumentProcessingDiagnostic(
                    "DOCUMENT_GRAPH_REBUILD", "Document graph builder", "FAILED", "ASYNC",
                    0, 0, 1, 0, 0, 0, message, Map.of("errorType", ex.getClass().getSimpleName())
            ));
            retryOrFail(job, message);
        }
    }

    private void retryOrFail(DocumentGraphJob job, String message) {
        if (job.attempts() >= MAX_ATTEMPTS) {
            finish(job, "FAILED", message);
            return;
        }
        if (repository.retryDocumentGraphJob(job.id(), workerId, job.attempts(), message)) {
            repository.refreshSourceReadiness(job.sourceId());
        }
    }

    private void finish(DocumentGraphJob job, String status, String message) {
        if (repository.finishDocumentGraphJob(job.id(), workerId, status, message)) {
            repository.refreshSourceReadiness(job.sourceId());
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
