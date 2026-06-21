package com.learnbot.service;

import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DocumentEnrichmentWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentEnrichmentWorker.class);
    private static final int MAX_ATTEMPTS = 2;

    private final DocumentRepository repository;
    private final DocumentContextBuilder contextBuilder;
    private final EmbeddingService embeddingService;
    private final String workerId = "document-enrichment-" + UUID.randomUUID();

    public DocumentEnrichmentWorker(DocumentRepository repository, DocumentContextBuilder contextBuilder, EmbeddingService embeddingService) {
        this.repository = repository;
        this.contextBuilder = contextBuilder;
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        repository.recoverDocumentEnrichmentJobs();
    }

    @Scheduled(fixedDelayString = "${learnbot.document.enrichment-poll-millis:10000}")
    public void processNext() {
        repository.claimDocumentEnrichmentJob(workerId).ifPresent(this::process);
    }

    private void process(DocumentEnrichmentJob job) {
        long started = System.nanoTime();
        try {
            repository.updateDocumentJobEnrichment(job.jobId(), "RUNNING", "LLM 품질 보강을 진행 중입니다.");
            if (!repository.heartbeatDocumentEnrichmentJob(job.id(), workerId)) {
                return;
            }
            List<DocumentRepository.StoredDocumentForEnrichment> storedDocuments = repository.listDocumentsForSource(job.sourceId());
            if (storedDocuments.isEmpty()) {
                recordDiagnostic(job, "SKIPPED", 0, 0, 0, elapsedMs(started), "보강할 문서가 없습니다.", Map.of());
                finish(job, "SKIPPED", "보강할 문서가 없습니다.");
                return;
            }
            List<DocumentContextBuilder.DocumentContextInput> inputs = new ArrayList<>();
            List<DocumentWork> work = new ArrayList<>();
            for (DocumentRepository.StoredDocumentForEnrichment stored : storedDocuments) {
                List<Chunk> originalChunks = repository.listDocumentChunks(stored.documentId()).stream()
                        .filter(chunk -> chunk.metadata() == null
                                || !"document_context".equals(String.valueOf(chunk.metadata().get("kind"))))
                        .map(this::toChunk)
                        .toList();
                if (originalChunks.isEmpty()) {
                    continue;
                }
                ExtractedDocument document = new ExtractedDocument(
                        stored.title(),
                        stored.sourceUri(),
                        stored.contentType(),
                        "",
                        stored.metadata()
                );
                inputs.add(new DocumentContextBuilder.DocumentContextInput(document, originalChunks));
                work.add(new DocumentWork(stored.documentId(), document, originalChunks));
            }
            List<Chunk> sourceContext = contextBuilder.buildSourceContext(inputs, false, true);
            for (int i = 0; i < work.size(); i++) {
                if (!repository.heartbeatDocumentEnrichmentJob(job.id(), workerId)) {
                    return;
                }
                DocumentWork item = work.get(i);
                List<Chunk> contextChunks = new ArrayList<>(contextBuilder.buildDocumentContext(item.document(), item.chunks(), false, true));
                if (i == 0) {
                    contextChunks.addAll(sourceContext);
                }
                List<List<Double>> embeddings = contextChunks.isEmpty()
                        ? List.of()
                        : embeddingService.embed(contextChunks.stream().map(Chunk::content).toList());
                repository.replaceDocumentContextChunks(item.documentId(), reindex(contextChunks), embeddings);
            }
            recordDiagnostic(job, "SUCCESS", work.size(), work.size(), 0, elapsedMs(started),
                    "LLM 품질 보강이 완료되었습니다.", Map.of("documentCount", work.size()));
            finish(job, "SUCCEEDED", "LLM 품질 보강이 완료되었습니다.");
            log.info("document_enrichment sourceId={} jobId={} status=SUCCESS durationMs={}",
                    job.sourceId(), job.jobId(), elapsedMs(started));
        } catch (RuntimeException ex) {
            String message = rootMessage(ex);
            recordDiagnostic(job, "FAILED", 0, 0, 1, elapsedMs(started), message,
                    Map.of("errorType", ex.getClass().getSimpleName()));
            retryOrFail(job, message);
        }
    }

    private void recordDiagnostic(DocumentEnrichmentJob job, String status, int attempted, int processed,
                                  int failed, long durationMs, String message, Map<String, Object> metadata) {
        repository.addDocumentProcessingDiagnostic(job.sourceId(), job.jobId(), new DocumentProcessingDiagnostic(
                "DOCUMENT_LLM_ENRICHMENT", "Document context builder", status, "ASYNC",
                attempted, processed, failed, 0, 0, durationMs, message, metadata
        ));
    }

    private Chunk toChunk(DocumentChunkDetail detail) {
        return new Chunk(detail.chunkIndex(), detail.content(), detail.metadata());
    }

    private List<Chunk> reindex(List<Chunk> chunks) {
        List<Chunk> output = new ArrayList<>();
        for (Chunk chunk : chunks) {
            output.add(new Chunk(output.size(), chunk.content(), chunk.metadata()));
        }
        return output;
    }

    private void retryOrFail(DocumentEnrichmentJob job, String message) {
        if (job.attempts() >= MAX_ATTEMPTS) {
            finish(job, "FAILED", message);
            return;
        }
        if (repository.retryDocumentEnrichmentJob(job.id(), workerId, job.attempts(), message)) {
            repository.updateDocumentJobEnrichment(job.jobId(), "RETRYING", message);
            repository.refreshSourceReadiness(job.sourceId());
        }
    }

    private void finish(DocumentEnrichmentJob job, String status, String message) {
        if (repository.finishDocumentEnrichmentJob(job.id(), workerId, status, message)) {
            repository.updateDocumentJobEnrichment(job.jobId(), status, message);
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

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private record DocumentWork(UUID documentId, ExtractedDocument document, List<Chunk> chunks) {
    }
}
