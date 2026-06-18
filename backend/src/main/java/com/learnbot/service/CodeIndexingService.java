package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.IndexingJobFailureSummary;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.repository.CodeRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
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
    private final CredentialEncryptionService credentialEncryptionService;
    private final LearnBotProperties properties;
    private final AuthService authService;
    private final AuditService auditService;
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
            CredentialEncryptionService credentialEncryptionService,
            LearnBotProperties properties,
            AuthService authService,
            AuditService auditService
    ) {
        this.repository = repository;
        this.gitWorkspaceService = gitWorkspaceService;
        this.fileScanner = fileScanner;
        this.contentReader = contentReader;
        this.chunkParser = chunkParser;
        this.ollamaClient = ollamaClient;
        this.credentialEncryptionService = credentialEncryptionService;
        this.properties = properties;
        this.authService = authService;
        this.auditService = auditService;
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

    public CodeRepositoryRecord createRepository(
            AppUser user,
            UUID spaceId,
            String gitUrl,
            String name,
            String branch,
            String authType,
            String username,
            String token,
            Boolean storeToken
    ) {
        String cleanUrl = requireGitUrl(gitUrl);
        String cleanBranch = branch == null || branch.isBlank() ? "HEAD" : branch.trim();
        String cleanAuthType = authType == null || authType.isBlank() ? "NONE" : authType.trim().toUpperCase(Locale.ROOT);
        if (!cleanAuthType.equals("NONE") && !cleanAuthType.equals("TOKEN")) {
            throw new IllegalArgumentException("authType must be NONE or TOKEN.");
        }
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String repositoryName = name == null || name.isBlank() ? inferName(cleanUrl) : name.trim();
        String localPath = Path.of(properties.getCode().getWorkspacePath(), UUID.randomUUID().toString()).toString();
        CodeRepositoryRecord record = repository.createRepository(
                repositoryName,
                cleanUrl,
                cleanBranch,
                cleanAuthType,
                localPath,
                resolvedSpaceId,
                user.id()
        );
        if (Boolean.TRUE.equals(storeToken) && token != null && !token.isBlank()) {
            repository.storeCredential(record.id(), credentialEncryptionService.encrypt(username, token));
            auditService.log(user, "CODE_CREDENTIAL_STORED", "CODE_REPOSITORY", record.id(), resolvedSpaceId, "Git token was stored for reuse.");
        }
        auditService.log(user, "CODE_REPOSITORY_CREATED", "CODE_REPOSITORY", record.id(), resolvedSpaceId, "Code repository was registered.");
        return record;
    }

    public List<CodeRepositorySummary> listRepositories(AppUser user, UUID spaceId) {
        UUID selectedSpaceId = spaceId == null ? null : authService.resolveSpace(user, spaceId);
        return repository.listRepositories(authService.accessibleSpaceIds(user), selectedSpaceId);
    }

    public List<IndexingJobSummary> listJobs(AppUser user, UUID repositoryId) {
        requireRepositoryAccess(user, repositoryId);
        return repository.listJobs(repositoryId);
    }

    public List<IndexingJobFailureSummary> listJobFailures(AppUser user, UUID repositoryId, UUID jobId) {
        requireRepositoryAccess(user, repositoryId);
        IndexingJobSummary job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Indexing job was not found."));
        if (!job.repositoryId().equals(repositoryId)) {
            throw new IllegalArgumentException("Repository and indexing job do not match.");
        }
        return repository.listJobFailures(repositoryId, jobId);
    }

    public IndexingJobSummary startIndex(AppUser user, UUID repositoryId, GitAccessToken accessToken, Boolean storeToken) {
        CodeRepositoryRecord record = requireRepositoryAccess(user, repositoryId);
        Optional<IndexingJobSummary> runningJob = repository.findRunningJob(repositoryId);
        if (runningJob.isPresent()) {
            return runningJob.get();
        }

        GitAccessToken resolvedAccessToken = resolveAccessToken(repositoryId, accessToken, storeToken);
        if ("TOKEN".equals(record.authType()) && !resolvedAccessToken.hasToken()) {
            throw new IllegalArgumentException("This repository requires a Git token. Enter a token before indexing.");
        }
        UUID jobId = repository.createJob(repositoryId, record.lastIndexedCommit() == null ? "INITIAL_INDEX" : "REINDEX");
        repository.updateRepositoryStatus(repositoryId, "INDEXING", null);
        auditService.log(user, "CODE_INDEX_STARTED", "INDEXING_JOB", jobId, record.spaceId(), "Code repository indexing started.");
        Future<?> future = executor.submit(() -> runIndex(record, resolvedAccessToken, jobId));
        runningJobs.put(jobId, future);
        return repository.findJob(jobId).orElseThrow();
    }

    public void deleteRepository(AppUser user, UUID repositoryId) {
        CodeRepositoryRecord record = requireRepositoryAccess(user, repositoryId);
        Optional<IndexingJobSummary> runningJob = repository.findRunningJob(repositoryId);
        if (runningJob.isPresent()) {
            throw new IllegalArgumentException("Cancel the running index job before deleting this repository.");
        }
        repository.deleteRepository(repositoryId);
        auditService.log(user, "CODE_REPOSITORY_DELETED", "CODE_REPOSITORY", repositoryId, record.spaceId(), "Code repository was soft deleted.");
        deleteLocalRepository(record.localPath());
    }

    public int clearFailedJobHistory(AppUser user, UUID repositoryId) {
        CodeRepositoryRecord record = requireRepositoryAccess(user, repositoryId);
        int deleted = repository.deleteFailedJobHistory(repositoryId);
        auditService.log(user, "CODE_INDEX_FAILURES_CLEARED", "CODE_REPOSITORY", repositoryId, record.spaceId(), "Failed indexing history was cleared.");
        return deleted;
    }

    public IndexingJobSummary cancelIndex(AppUser user, UUID repositoryId, UUID jobId) {
        CodeRepositoryRecord record = requireRepositoryAccess(user, repositoryId);
        IndexingJobSummary job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Indexing job was not found."));
        if (!job.repositoryId().equals(repositoryId)) {
            throw new IllegalArgumentException("Repository and indexing job do not match.");
        }
        if (!job.status().equals("RUNNING") && !job.status().equals("CANCELLING")) {
            return job;
        }

        cancelledJobs.add(jobId);
        repository.updateJobStatus(jobId, "CANCELLING", "User requested cancellation.");
        auditService.log(user, "CODE_INDEX_CANCELLED", "INDEXING_JOB", jobId, record.spaceId(), "Code repository indexing was cancelled.");
        Future<?> future = runningJobs.get(jobId);
        if (future == null) {
            repository.finishJob(jobId, "CANCELLED", job.commitHash(), "User cancelled indexing.");
            restoreRepositoryStatus(repositoryId);
        }
        return repository.findJob(jobId).orElseThrow();
    }

    private GitAccessToken resolveAccessToken(UUID repositoryId, GitAccessToken requestToken, Boolean storeToken) {
        if (requestToken != null && requestToken.hasToken()) {
            if (Boolean.TRUE.equals(storeToken)) {
                repository.storeCredential(
                        repositoryId,
                        credentialEncryptionService.encrypt(requestToken.username(), requestToken.token())
                );
            }
            return requestToken;
        }
        return repository.findCredential(repositoryId)
                .map(credentialEncryptionService::decrypt)
                .orElse(new GitAccessToken(null, null));
    }

    private void runIndex(CodeRepositoryRecord record, GitAccessToken accessToken, UUID jobId) {
        UUID repositoryId = record.id();
        String commitHash = null;
        int totalFiles = 0;
        int processedFiles = 0;
        int totalChunks = 0;
        int failedFiles = 0;
        int addedFiles = 0;
        int modifiedFiles = 0;
        int unchangedFiles = 0;
        int deletedFiles = 0;

        try {
            ensureNotCancelled(jobId);
            commitHash = gitWorkspaceService.sync(record, accessToken);
            ensureNotCancelled(jobId);
            ensureEmbeddingAvailable();

            List<CodeFileCandidate> candidates = fileScanner.scan(Path.of(record.localPath()));
            totalFiles = candidates.size();
            repository.updateJobProgress(jobId, totalFiles, 0, 0, 0);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("No indexable code files were found.");
            }

            Map<String, ActiveCodeFileSnapshot> previousFiles = repository.listActiveFileSnapshots(repositoryId);
            Set<String> currentFilePaths = new HashSet<>();
            for (CodeFileCandidate candidate : candidates) {
                currentFilePaths.add(candidate.relativePath());
            }
            deletedFiles = (int) previousFiles.keySet().stream()
                    .filter(path -> !currentFilePaths.contains(path))
                    .count();
            updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
            for (CodeFileCandidate candidate : candidates) {
                ensureNotCancelled(jobId);
                ActiveCodeFileSnapshot previousFile = previousFiles.get(candidate.relativePath());
                try {
                    String content = contentReader.read(candidate.absolutePath());
                    String contentHash = sha256(content);
                    if (previousFile != null
                            && previousFile.contentHash().equals(contentHash)
                            && previousFile.chunkCount() > 0) {
                        repository.copyActiveFileToIndex(repositoryId, previousFile.fileId(), UUID.randomUUID(), jobId);
                        totalChunks += previousFile.chunkCount();
                        unchangedFiles++;
                        processedFiles++;
                        updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
                        continue;
                    }

                    List<ParsedCodeChunk> chunks = chunkParser.parse(candidate.relativePath(), candidate.language(), content);
                    if (chunks.isEmpty()) {
                        if (previousFile == null) {
                            addedFiles++;
                        } else {
                            modifiedFiles++;
                        }
                        processedFiles++;
                        updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
                        continue;
                    }

                    List<List<Double>> embeddings = embedInBatches(chunks.stream().map(ParsedCodeChunk::content).toList());
                    ensureNotCancelled(jobId);
                    validateEmbeddings(embeddings);

                    UUID fileId = repository.createFile(
                            repositoryId,
                            jobId,
                            candidate.relativePath(),
                            candidate.language(),
                            contentHash
                    );
                    repository.addChunks(repositoryId, fileId, jobId, candidate.relativePath(), chunks, embeddings);
                    totalChunks += chunks.size();
                    if (previousFile == null) {
                        addedFiles++;
                    } else {
                        modifiedFiles++;
                    }
                    processedFiles++;
                } catch (CodeIndexCancelledException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    failedFiles++;
                    processedFiles++;
                    repository.addJobFailure(repositoryId, jobId, candidate.relativePath(), "FILE", rootMessage(ex));
                    if (previousFile != null && previousFile.chunkCount() > 0) {
                        repository.copyActiveFileToIndex(repositoryId, previousFile.fileId(), UUID.randomUUID(), jobId);
                        totalChunks += previousFile.chunkCount();
                        unchangedFiles++;
                    }
                }
                updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
            }

            if (totalChunks == 0) {
                throw new IllegalArgumentException("No code chunks were created.");
            }
            repository.completeSuccessfulIndex(repositoryId, jobId, commitHash);
        } catch (CodeIndexCancelledException ex) {
            updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
            repository.finishJob(jobId, "CANCELLED", commitHash, "User cancelled indexing.");
            restoreRepositoryStatus(repositoryId);
        } catch (RuntimeException ex) {
            if (cancelledJobs.contains(jobId) || Thread.currentThread().isInterrupted()) {
                updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
                repository.finishJob(jobId, "CANCELLED", commitHash, "User cancelled indexing.");
                restoreRepositoryStatus(repositoryId);
            } else {
                String message = rootMessage(ex);
                updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
                repository.addJobFailure(repositoryId, jobId, null, "REPOSITORY", message);
                repository.finishJob(jobId, "FAILED", commitHash, message);
                repository.updateRepositoryStatus(repositoryId, "FAILED", message);
            }
        } finally {
            runningJobs.remove(jobId);
            cancelledJobs.remove(jobId);
        }
    }

    private void updateProgress(
            UUID jobId,
            int totalFiles,
            int processedFiles,
            int totalChunks,
            int failedFiles,
            int addedFiles,
            int modifiedFiles,
            int unchangedFiles,
            int deletedFiles
    ) {
        repository.updateJobProgress(
                jobId,
                totalFiles,
                processedFiles,
                totalChunks,
                failedFiles,
                addedFiles,
                modifiedFiles,
                unchangedFiles,
                deletedFiles
        );
    }

    private String requireGitUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Git URL is required.");
        }
        String clean = gitUrl.trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            try {
                URI uri = new URI(clean);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException("Git URL must include a host.");
                }
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("Git URL format is invalid.");
            }
            return clean;
        }
        if (clean.startsWith("ssh://") || clean.startsWith("git://") || clean.startsWith("git@")) {
            return clean;
        }
        throw new IllegalArgumentException("Git URL must start with http://, https://, ssh://, git://, or git@.");
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

    private List<List<Double>> embedInBatches(List<String> texts) {
        List<List<Double>> embeddings = new java.util.ArrayList<>();
        int batchSize = 32;
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            embeddings.addAll(ollamaClient.embed(texts.subList(start, end)));
        }
        return embeddings;
    }

    private void ensureEmbeddingAvailable() {
        List<List<Double>> embeddings = ollamaClient.embed(List.of("learnbot embedding healthcheck"));
        validateEmbeddings(embeddings);
    }

    private void ensureNotCancelled(UUID jobId) {
        if (cancelledJobs.contains(jobId) || Thread.currentThread().isInterrupted()) {
            throw new CodeIndexCancelledException();
        }
    }

    private void restoreRepositoryStatus(UUID repositoryId) {
        CodeRepositoryRecord latest = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
        if (latest.lastIndexedCommit() == null || latest.lastIndexedCommit().isBlank()) {
            repository.updateRepositoryStatus(repositoryId, "PENDING", null);
        } else {
            repository.markRepositoryIndexed(repositoryId, latest.lastIndexedCommit());
        }
    }

    private CodeRepositoryRecord requireRepositoryAccess(AppUser user, UUID repositoryId) {
        CodeRepositoryRecord record = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
        authService.requireSpace(user, record.spaceId());
        return record;
    }

    private void deleteLocalRepository(String localPath) {
        Path workspaceRoot = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize();
        Path target = Path.of(localPath).toAbsolutePath().normalize();
        if (!target.startsWith(workspaceRoot) || target.equals(workspaceRoot)) {
            throw new IllegalArgumentException("Local repository path is outside the configured workspace.");
        }
        try {
            FileSystemUtils.deleteRecursively(target);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to delete local Git workspace: " + target, ex);
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

    private String rootMessage(Throwable throwable) {
        String topMessage = throwable.getMessage();
        if (throwable instanceof IllegalArgumentException && topMessage != null && !topMessage.isBlank()) {
            return topMessage;
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = topMessage;
        }
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static class CodeIndexCancelledException extends RuntimeException {
    }
}
