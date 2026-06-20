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
import org.springframework.web.multipart.MultipartFile;

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
    private final ZipCodeArchiveService zipCodeArchiveService;
    private final CodeFileScanner fileScanner;
    private final CodeContentReader contentReader;
    private final CodeChunkParser chunkParser;
    private final CodeProjectContextBuilder projectContextBuilder;
    private final CodeGraphBuilder codeGraphBuilder;
    private final EmbeddingService embeddingService;
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
            ZipCodeArchiveService zipCodeArchiveService,
            CodeFileScanner fileScanner,
            CodeContentReader contentReader,
            CodeChunkParser chunkParser,
            CodeProjectContextBuilder projectContextBuilder,
            CodeGraphBuilder codeGraphBuilder,
            EmbeddingService embeddingService,
            CredentialEncryptionService credentialEncryptionService,
            LearnBotProperties properties,
            AuthService authService,
            AuditService auditService
    ) {
        this.repository = repository;
        this.gitWorkspaceService = gitWorkspaceService;
        this.zipCodeArchiveService = zipCodeArchiveService;
        this.fileScanner = fileScanner;
        this.contentReader = contentReader;
        this.chunkParser = chunkParser;
        this.projectContextBuilder = projectContextBuilder;
        this.codeGraphBuilder = codeGraphBuilder;
        this.embeddingService = embeddingService;
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

    public ZipRepositoryIndexResult createZipRepository(AppUser user, UUID spaceId, MultipartFile file, String name) {
        ZipCodeArchiveService.PreparedZip zip = zipCodeArchiveService.prepare(file);
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String repositoryName = name == null || name.isBlank() ? inferName(zip.sourceLabel()) : name.trim();
        CodeRepositoryRecord record;
        try {
            record = repository.createZipRepository(
                    repositoryName,
                    zip.sourceLabel(),
                    zip.sourceHash(),
                    zip.localPath(),
                    resolvedSpaceId,
                    user.id()
            );
        } catch (RuntimeException ex) {
            zipCodeArchiveService.deleteWorkspace(zip.localPath());
            throw ex;
        }
        auditService.log(user, "CODE_ZIP_REPOSITORY_CREATED", "CODE_REPOSITORY", record.id(), resolvedSpaceId, "ZIP code snapshot was uploaded.");
        IndexingJobSummary job = startIndex(user, record.id(), new GitAccessToken(null, null), false);
        return new ZipRepositoryIndexResult(record, job);
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

        GitAccessToken resolvedAccessToken = "ZIP".equals(record.sourceType())
                ? new GitAccessToken(null, null)
                : resolveAccessToken(repositoryId, accessToken, storeToken);
        if ("GIT".equals(record.sourceType()) && "TOKEN".equals(record.authType()) && !resolvedAccessToken.hasToken()) {
            throw new IllegalArgumentException("This repository requires a Git token. Enter a token before indexing.");
        }
        UUID jobId = repository.createJob(repositoryId, record.lastIndexedCommit() == null ? "INITIAL_INDEX" : "REINDEX");
        repository.updateRepositoryStatus(repositoryId, "INDEXING", null);
        auditService.log(user, "CODE_INDEX_STARTED", "INDEXING_JOB", jobId, record.spaceId(), "Code repository indexing started.");
        Future<?> future = executor.submit(() -> runIndex(record, resolvedAccessToken, jobId, null));
        runningJobs.put(jobId, future);
        return repository.findJob(jobId).orElseThrow();
    }

    public IndexingJobSummary replaceZipSnapshot(AppUser user, UUID repositoryId, MultipartFile file) {
        CodeRepositoryRecord current = requireRepositoryAccess(user, repositoryId);
        if (!"ZIP".equals(current.sourceType())) {
            throw new IllegalArgumentException("Only ZIP code snapshots can be replaced with a ZIP upload.");
        }
        Optional<IndexingJobSummary> runningJob = repository.findRunningJob(repositoryId);
        if (runningJob.isPresent()) {
            return runningJob.get();
        }
        ZipCodeArchiveService.PreparedZip zip = zipCodeArchiveService.prepare(file);
        PendingZipSnapshot pending = new PendingZipSnapshot(zip.sourceLabel(), zip.sourceHash(), zip.localPath(), current.localPath());
        CodeRepositoryRecord pendingRecord = withZipSnapshot(current, pending);
        try {
            UUID jobId = repository.createJob(repositoryId, "REINDEX");
            repository.updateRepositoryStatus(repositoryId, "INDEXING", null);
            auditService.log(user, "CODE_ZIP_REINDEX_STARTED", "INDEXING_JOB", jobId, current.spaceId(), "ZIP code snapshot reindexing started.");
            Future<?> future = executor.submit(() -> runIndex(pendingRecord, new GitAccessToken(null, null), jobId, pending));
            runningJobs.put(jobId, future);
            return repository.findJob(jobId).orElseThrow();
        } catch (RuntimeException ex) {
            zipCodeArchiveService.deleteWorkspace(zip.localPath());
            throw ex;
        }
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

    private void runIndex(CodeRepositoryRecord record, GitAccessToken accessToken, UUID jobId, PendingZipSnapshot pendingZipSnapshot) {
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
            commitHash = "ZIP".equals(record.sourceType())
                    ? record.sourceHash()
                    : gitWorkspaceService.sync(record, accessToken);
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
            if (projectContextBuilder.enabled()) {
                currentFilePaths.add(CodeProjectContextBuilder.CONTEXT_FILE_PATH);
            }
            deletedFiles = (int) previousFiles.keySet().stream()
                    .filter(path -> !currentFilePaths.contains(path))
                    .count();
            updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
            List<CodeProjectContextBuilder.IndexedFileContext> projectContexts = new java.util.ArrayList<>();
            for (CodeFileCandidate candidate : candidates) {
                ensureNotCancelled(jobId);
                ActiveCodeFileSnapshot previousFile = previousFiles.get(candidate.relativePath());
                try {
                    String content = contentReader.read(candidate.absolutePath());
                    String contentHash = sha256(content);
                    List<ParsedCodeChunk> chunks = chunkParser.parse(candidate.relativePath(), candidate.language(), content);
                    if (!chunks.isEmpty()) {
                        projectContexts.add(new CodeProjectContextBuilder.IndexedFileContext(
                                candidate.relativePath(),
                                candidate.language(),
                                content,
                                chunks
                        ));
                    }
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

                    List<List<Double>> embeddings = embeddingService.embed(chunks.stream().map(ParsedCodeChunk::content).toList());
                    ensureNotCancelled(jobId);

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

            totalChunks += addProjectContextChunks(record, jobId, projectContexts);
            updateProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, addedFiles, modifiedFiles, unchangedFiles, deletedFiles);
            buildCodeGraph(record, jobId);
            if (totalChunks == 0) {
                throw new IllegalArgumentException("No code chunks were created.");
            }
            if ("ZIP".equals(record.sourceType())) {
                repository.completeSuccessfulZipIndex(repositoryId, jobId, record.sourceLabel(), record.sourceHash(), record.localPath());
                if (pendingZipSnapshot != null && !record.localPath().equals(pendingZipSnapshot.previousLocalPath())) {
                    zipCodeArchiveService.deleteWorkspace(pendingZipSnapshot.previousLocalPath());
                }
            } else {
                repository.completeSuccessfulIndex(repositoryId, jobId, commitHash);
            }
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
            if (pendingZipSnapshot != null && !"SUCCEEDED".equals(repository.findJob(jobId).map(IndexingJobSummary::status).orElse(null))) {
                zipCodeArchiveService.deleteWorkspace(pendingZipSnapshot.localPath());
            }
            runningJobs.remove(jobId);
            cancelledJobs.remove(jobId);
        }
    }

    private int addProjectContextChunks(
            CodeRepositoryRecord record,
            UUID jobId,
            List<CodeProjectContextBuilder.IndexedFileContext> projectContexts
    ) {
        if (!projectContextBuilder.enabled() || projectContexts == null || projectContexts.isEmpty()) {
            return 0;
        }
        try {
            ensureNotCancelled(jobId);
            List<ParsedCodeChunk> chunks = projectContextBuilder.build(record, projectContexts);
            if (chunks.isEmpty()) {
                return 0;
            }
            List<List<Double>> embeddings = embeddingService.embed(chunks.stream().map(ParsedCodeChunk::content).toList());
            ensureNotCancelled(jobId);
            String combinedContent = chunks.stream()
                    .map(ParsedCodeChunk::content)
                    .reduce("", (left, right) -> left + "\n\n" + right);
            UUID fileId = repository.createFile(
                    record.id(),
                    jobId,
                    CodeProjectContextBuilder.CONTEXT_FILE_PATH,
                    "markdown",
                    sha256(combinedContent)
            );
            repository.addChunks(record.id(), fileId, jobId, CodeProjectContextBuilder.CONTEXT_FILE_PATH, chunks, embeddings);
            return chunks.size();
        } catch (CodeIndexCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            repository.addJobFailure(record.id(), jobId, CodeProjectContextBuilder.CONTEXT_FILE_PATH, "PROJECT_CONTEXT", rootMessage(ex));
            return 0;
        }
    }

    private void buildCodeGraph(CodeRepositoryRecord record, UUID jobId) {
        if (!codeGraphBuilder.enabled()) {
            return;
        }
        try {
            ensureNotCancelled(jobId);
            CodeGraph graph = codeGraphBuilder.build(
                    Path.of(record.localPath()),
                    repository.listChunksForIndex(record.id(), jobId)
            );
            if (!graph.nodes().isEmpty()) {
                repository.replaceGraph(record.id(), jobId, graph);
            }
        } catch (CodeIndexCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            repository.addJobFailure(record.id(), jobId, null, "CODE_GRAPH", rootMessage(ex));
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

    private CodeRepositoryRecord withZipSnapshot(CodeRepositoryRecord record, PendingZipSnapshot pending) {
        return new CodeRepositoryRecord(
                record.id(),
                record.spaceId(),
                record.name(),
                record.sourceType(),
                pending.sourceLabel(),
                pending.sourceHash(),
                record.gitUrl(),
                record.branch(),
                record.authType(),
                pending.localPath(),
                record.status(),
                record.lastIndexedCommit()
        );
    }

    private void ensureEmbeddingAvailable() {
        embeddingService.embed(List.of("learnbot embedding healthcheck"));
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

    public record ZipRepositoryIndexResult(CodeRepositoryRecord repository, IndexingJobSummary job) {
    }

    private record PendingZipSnapshot(String sourceLabel, String sourceHash, String localPath, String previousLocalPath) {
    }
}
