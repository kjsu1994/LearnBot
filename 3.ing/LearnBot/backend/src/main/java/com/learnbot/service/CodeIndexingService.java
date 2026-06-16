package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.repository.CodeRepository;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class CodeIndexingService {
    private final CodeRepository repository;
    private final GitWorkspaceService gitWorkspaceService;
    private final CodeFileScanner fileScanner;
    private final CodeContentReader contentReader;
    private final CodeChunkParser chunkParser;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final ExecutorService executor;
    private final Map<UUID, Future<?>> runningJobs = new ConcurrentHashMap<>();
    private final Set<UUID> cancelledJobs = ConcurrentHashMap.newKeySet();

    public CodeIndexingService(
            CodeRepository repository,
            GitWorkspaceService gitWorkspaceService,
            CodeFileScanner fileScanner,
            CodeContentReader contentReader,
            CodeChunkParser chunkParser,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this.repository = repository;
        this.gitWorkspaceService = gitWorkspaceService;
        this.fileScanner = fileScanner;
        this.contentReader = contentReader;
        this.chunkParser = chunkParser;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(properties.getCode().getIndexThreads());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @PostConstruct
    void resetInterruptedJobs() {
        repository.resetInterruptedJobs();
    }

    public CodeRepositoryRecord createRepository(String gitUrl, String name, String branch, String authType) {
        String cleanUrl = requireGitUrl(gitUrl);
        String cleanBranch = branch == null || branch.isBlank() ? "HEAD" : branch.trim();
        String cleanAuthType = authType == null || authType.isBlank() ? "NONE" : authType.trim().toUpperCase(Locale.ROOT);
        if (!cleanAuthType.equals("NONE") && !cleanAuthType.equals("TOKEN")) {
            throw new IllegalArgumentException("authType은 NONE 또는 TOKEN만 지원합니다.");
        }
        String repositoryName = name == null || name.isBlank() ? inferName(cleanUrl) : name.trim();
        String localPath = Path.of(properties.getCode().getWorkspacePath(), UUID.randomUUID().toString()).toString();
        return repository.createRepository(repositoryName, cleanUrl, cleanBranch, cleanAuthType, localPath);
    }

    public List<CodeRepositorySummary> listRepositories() {
        return repository.listRepositories();
    }

    public List<IndexingJobSummary> listJobs(UUID repositoryId) {
        return repository.listJobs(repositoryId);
    }

    public IndexingJobSummary startIndex(UUID repositoryId, GitAccessToken accessToken) {
        CodeRepositoryRecord record = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("코드 저장소를 찾을 수 없습니다."));

        Optional<IndexingJobSummary> runningJob = repository.findRunningJob(repositoryId);
        if (runningJob.isPresent()) {
            return runningJob.get();
        }

        UUID jobId = repository.createJob(repositoryId, record.lastIndexedCommit() == null ? "INITIAL_INDEX" : "REINDEX");
        repository.updateRepositoryStatus(repositoryId, "INDEXING", null);
        Future<?> future = executor.submit(() -> runIndex(record, accessToken, jobId));
        runningJobs.put(jobId, future);
        return repository.findJob(jobId).orElseThrow();
    }

    public IndexingJobSummary cancelIndex(UUID repositoryId, UUID jobId) {
        IndexingJobSummary job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("인덱싱 작업을 찾을 수 없습니다."));
        if (!job.repositoryId().equals(repositoryId)) {
            throw new IllegalArgumentException("저장소와 인덱싱 작업이 일치하지 않습니다.");
        }
        if (!job.status().equals("RUNNING") && !job.status().equals("CANCELLING")) {
            return job;
        }

        cancelledJobs.add(jobId);
        repository.updateJobStatus(jobId, "CANCELLING", "사용자가 취소를 요청했습니다.");
        Future<?> future = runningJobs.get(jobId);
        if (future == null) {
            repository.finishJob(jobId, "CANCELLED", job.commitHash(), "사용자가 취소했습니다.");
            restoreRepositoryStatus(repositoryId);
        }
        return repository.findJob(jobId).orElseThrow();
    }

    private void runIndex(CodeRepositoryRecord record, GitAccessToken accessToken, UUID jobId) {
        UUID repositoryId = record.id();
        String commitHash = null;
        int totalFiles = 0;
        int processedFiles = 0;
        int totalChunks = 0;
        int failedFiles = 0;

        try {
            ensureNotCancelled(jobId);
            commitHash = gitWorkspaceService.sync(record, accessToken);
            ensureNotCancelled(jobId);

            List<CodeFileCandidate> candidates = fileScanner.scan(Path.of(record.localPath()));
            totalFiles = candidates.size();
            repository.updateJobProgress(jobId, totalFiles, 0, 0, 0);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("색인할 수 있는 코드 파일이 없습니다.");
            }

            for (CodeFileCandidate candidate : candidates) {
                ensureNotCancelled(jobId);
                try {
                    String content = contentReader.read(candidate.absolutePath());
                    List<ParsedCodeChunk> chunks = chunkParser.parse(candidate.relativePath(), candidate.language(), content);
                    if (chunks.isEmpty()) {
                        processedFiles++;
                        repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
                        continue;
                    }

                    List<List<Double>> embeddings = ollamaClient.embed(chunks.stream().map(ParsedCodeChunk::content).toList());
                    ensureNotCancelled(jobId);
                    validateEmbeddings(embeddings);

                    UUID fileId = repository.createFile(
                            repositoryId,
                            jobId,
                            candidate.relativePath(),
                            candidate.language(),
                            sha256(content)
                    );
                    repository.addChunks(repositoryId, fileId, jobId, candidate.relativePath(), chunks, embeddings);
                    totalChunks += chunks.size();
                    processedFiles++;
                } catch (CodeIndexCancelledException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    failedFiles++;
                    processedFiles++;
                }
                repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
            }

            if (totalChunks == 0) {
                throw new IllegalArgumentException("색인 가능한 코드 chunk가 생성되지 않았습니다.");
            }
            repository.activateIndex(repositoryId, jobId);
            repository.markRepositoryIndexed(repositoryId, commitHash);
            repository.finishJob(jobId, "SUCCEEDED", commitHash, null);
        } catch (CodeIndexCancelledException ex) {
            repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
            repository.finishJob(jobId, "CANCELLED", commitHash, "사용자가 취소했습니다.");
            restoreRepositoryStatus(repositoryId);
        } catch (RuntimeException ex) {
            if (cancelledJobs.contains(jobId) || Thread.currentThread().isInterrupted()) {
                repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
                repository.finishJob(jobId, "CANCELLED", commitHash, "사용자가 취소했습니다.");
                restoreRepositoryStatus(repositoryId);
            } else {
                repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
                repository.finishJob(jobId, "FAILED", commitHash, ex.getMessage());
                repository.updateRepositoryStatus(repositoryId, "FAILED", ex.getMessage());
            }
        } finally {
            runningJobs.remove(jobId);
            cancelledJobs.remove(jobId);
        }
    }

    private String requireGitUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Git URL을 입력하세요.");
        }
        String clean = gitUrl.trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            try {
                URI uri = new URI(clean);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException("Git URL은 http://host/path.git 또는 https://host/path.git 형태여야 합니다.");
                }
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("Git URL 형식이 올바르지 않습니다. 예: http://192.168.1.146/Git/project.git");
            }
            return clean;
        }
        if (clean.startsWith("ssh://") || clean.startsWith("git://") || clean.startsWith("git@")) {
            return clean;
        }
        throw new IllegalArgumentException("지원하는 Git URL 형식은 http://, https://, ssh://, git:// 또는 git@host:path.git 입니다.");
    }

    private String inferName(String gitUrl) {
        String clean = gitUrl;
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf(':'));
        if (slash >= 0) {
            clean = clean.substring(slash + 1);
        }
        if (clean.endsWith(".git")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        return clean.isBlank() ? "code-repository" : clean;
    }

    private void validateEmbeddings(List<List<Double>> embeddings) {
        for (List<Double> embedding : embeddings) {
            if (embedding.size() != properties.getEmbedding().getDimensions()) {
                throw new IllegalArgumentException("Embedding dimension mismatch. Expected "
                        + properties.getEmbedding().getDimensions() + " but got " + embedding.size()
                        + ". Recreate the vector column and reindex when changing embedding models.");
            }
        }
    }

    private void ensureNotCancelled(UUID jobId) {
        if (cancelledJobs.contains(jobId) || Thread.currentThread().isInterrupted()) {
            throw new CodeIndexCancelledException();
        }
    }

    private void restoreRepositoryStatus(UUID repositoryId) {
        CodeRepositoryRecord latest = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("코드 저장소를 찾을 수 없습니다."));
        if (latest.lastIndexedCommit() == null || latest.lastIndexedCommit().isBlank()) {
            repository.updateRepositoryStatus(repositoryId, "PENDING", null);
        } else {
            repository.markRepositoryIndexed(repositoryId, latest.lastIndexedCommit());
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private static class CodeIndexCancelledException extends RuntimeException {
    }
}
