package com.learnbot.service;

import com.learnbot.repository.CodeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodeGraphEnrichmentWorker {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphEnrichmentWorker.class);
    private static final int MAX_ATTEMPTS = 3;

    private final CodeRepository repository;
    private final CodeGraphLlmEnricher enricher;

    public CodeGraphEnrichmentWorker(CodeRepository repository, CodeGraphLlmEnricher enricher) {
        this.repository = repository;
        this.enricher = enricher;
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        repository.recoverGraphEnrichmentJobs();
    }

    @Scheduled(fixedDelayString = "${learnbot.code.graph.enrichment-poll-millis:10000}")
    public void processNext() {
        repository.claimGraphEnrichmentJob().ifPresent(this::process);
    }

    private void process(CodeGraphEnrichmentJob job) {
        long started = System.nanoTime();
        try {
            if (!repository.isActiveIndex(job.repositoryId(), job.indexVersion())) {
                CodeAnalysisDiagnostic skipped = CodeAnalysisDiagnostic.skipped(
                        "LLM_ENRICHMENT", "Ollama auxiliary", "ASYNC", "Index is no longer active."
                );
                repository.addAnalysisDiagnostics(job.repositoryId(), job.indexVersion(), List.of(skipped));
                repository.finishGraphEnrichmentJob(job.id(), "SKIPPED", skipped.message());
                return;
            }
            CodeGraph original = repository.loadGraph(job.repositoryId(), job.indexVersion());
            CodeGraphAnalysisResult result = enricher.enrichWithDiagnostics(
                    original, repository.listChunksForIndex(job.repositoryId(), job.indexVersion())
            );
            repository.addAnalysisDiagnostics(job.repositoryId(), job.indexVersion(), List.of(result.diagnostic()));
            if ("FAILED".equals(result.diagnostic().status())) {
                retryOrFail(job, result.diagnostic().message());
                return;
            }
            List<CodeGraphEdge> llmEdges = result.graph().edges().stream()
                    .filter(edge -> "llm_fallback".equals(String.valueOf(edge.metadata().get("source"))))
                    .toList();
            int inserted = repository.mergeGraphEdges(job.repositoryId(), job.indexVersion(), llmEdges);
            repository.finishGraphEnrichmentJob(job.id(), "SUCCEEDED", "Inserted " + inserted + " validated LLM relationships.");
            log.info("code_graph_enrichment repositoryId={} indexVersion={} status=SUCCESS inserted={} durationMs={}",
                    job.repositoryId(), job.indexVersion(), inserted,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (RuntimeException ex) {
            retryOrFail(job, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void retryOrFail(CodeGraphEnrichmentJob job, String message) {
        if (job.attempts() >= MAX_ATTEMPTS) {
            repository.finishGraphEnrichmentJob(job.id(), "FAILED", message);
        } else {
            repository.retryGraphEnrichmentJob(job.id(), job.attempts(), message);
        }
        log.warn("code_graph_enrichment repositoryId={} indexVersion={} status={} attempt={} reason={}",
                job.repositoryId(), job.indexVersion(), job.attempts() >= MAX_ATTEMPTS ? "FAILED" : "RETRY",
                job.attempts(), message);
    }
}
