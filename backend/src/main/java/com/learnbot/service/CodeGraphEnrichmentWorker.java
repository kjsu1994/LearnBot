package com.learnbot.service;

import com.learnbot.repository.CodeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class CodeGraphEnrichmentWorker {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphEnrichmentWorker.class);
    private static final int MAX_ATTEMPTS = 3;

    private final CodeRepository repository;
    private final CodeGraphLlmEnricher enricher;
    private final String workerId = "code-graph-enrichment-" + UUID.randomUUID();

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
        repository.claimGraphEnrichmentJob(workerId).ifPresent(this::process);
    }

    private void process(CodeGraphEnrichmentJob job) {
        long started = System.nanoTime();
        try {
            if (!repository.isActiveIndex(job.repositoryId(), job.indexVersion())) {
                CodeAnalysisDiagnostic skipped = CodeAnalysisDiagnostic.skipped(
                        "LLM_ENRICHMENT", "Ollama auxiliary", "ASYNC", "Index is no longer active."
                );
                repository.addAnalysisDiagnostics(job.repositoryId(), job.indexVersion(), List.of(skipped));
                repository.updateJobEnrichment(job.indexVersion(), "SKIPPED", skipped.message());
                finish(job, "SKIPPED", skipped.message());
                return;
            }
            repository.updateJobEnrichment(job.indexVersion(), "RUNNING", "코드 LLM 관계 보강을 진행 중입니다.");
            if (!repository.heartbeatGraphEnrichmentJob(job.id(), workerId)) {
                log.warn("code_graph_enrichment repositoryId={} indexVersion={} status=LEASE_LOST beforeLlm=true",
                        job.repositoryId(), job.indexVersion());
                return;
            }
            CodeGraph original = repository.loadGraph(job.repositoryId(), job.indexVersion());
            CodeGraphAnalysisResult result = enricher.enrichWithDiagnostics(
                    original, repository.listChunksForIndex(job.repositoryId(), job.indexVersion())
            );
            if (!repository.heartbeatGraphEnrichmentJob(job.id(), workerId)) {
                log.warn("code_graph_enrichment repositoryId={} indexVersion={} status=LEASE_LOST afterLlm=true",
                        job.repositoryId(), job.indexVersion());
                return;
            }
            repository.addAnalysisDiagnostics(job.repositoryId(), job.indexVersion(), List.of(result.diagnostic()));
            if ("FAILED".equals(result.diagnostic().status())) {
                retryOrFail(job, result.diagnostic().message());
                return;
            }
            List<CodeGraphEdge> llmEdges = result.graph().edges().stream()
                    .filter(edge -> "llm_fallback".equals(String.valueOf(edge.metadata().get("source"))))
                    .toList();
            int inserted = repository.mergeGraphEdges(job.repositoryId(), job.indexVersion(), llmEdges);
            repository.updateJobEnrichment(job.indexVersion(), "SUCCEEDED", "코드 LLM 관계 보강이 완료되었습니다.");
            finish(job, "SUCCEEDED", "Inserted " + inserted + " validated LLM relationships.");
            log.info("code_graph_enrichment repositoryId={} indexVersion={} status=SUCCESS inserted={} durationMs={}",
                    job.repositoryId(), job.indexVersion(), inserted,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (RuntimeException ex) {
            retryOrFail(job, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void retryOrFail(CodeGraphEnrichmentJob job, String message) {
        boolean updated;
        if (job.attempts() >= MAX_ATTEMPTS) {
            updated = repository.finishGraphEnrichmentJob(job.id(), workerId, "FAILED", message);
            if (updated) {
                repository.updateJobEnrichment(job.indexVersion(), "FAILED", message);
            }
        } else {
            updated = repository.retryGraphEnrichmentJob(job.id(), workerId, job.attempts(), message);
            if (updated) {
                repository.updateJobEnrichment(job.indexVersion(), "RETRYING", message);
            }
        }
        if (!updated) {
            log.warn("code_graph_enrichment repositoryId={} indexVersion={} status=LEASE_LOST attempt={}",
                    job.repositoryId(), job.indexVersion(), job.attempts());
            return;
        }
        log.warn("code_graph_enrichment repositoryId={} indexVersion={} status={} attempt={} reason={}",
                job.repositoryId(), job.indexVersion(), job.attempts() >= MAX_ATTEMPTS ? "FAILED" : "RETRY",
                job.attempts(), message);
    }

    private void finish(CodeGraphEnrichmentJob job, String status, String message) {
        if (!repository.finishGraphEnrichmentJob(job.id(), workerId, status, message)) {
            log.warn("code_graph_enrichment repositoryId={} indexVersion={} status=LEASE_LOST finalStatus={}",
                    job.repositoryId(), job.indexVersion(), status);
        }
    }
}
