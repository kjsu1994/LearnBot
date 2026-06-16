package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.repository.CodeRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CodeIndexingService {
    private final CodeRepository repository;
    private final GitWorkspaceService gitWorkspaceService;
    private final CodeFileScanner fileScanner;
    private final CodeContentReader contentReader;
    private final CodeChunkParser chunkParser;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;

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

    public IndexingJobSummary index(UUID repositoryId, GitAccessToken accessToken) {
        CodeRepositoryRecord record = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("코드 저장소를 찾을 수 없습니다."));
        UUID jobId = repository.createJob(repositoryId, record.lastIndexedCommit() == null ? "INITIAL_INDEX" : "REINDEX");
        repository.updateRepositoryStatus(repositoryId, "INDEXING", null);

        String commitHash = null;
        int totalFiles = 0;
        int processedFiles = 0;
        int totalChunks = 0;
        int failedFiles = 0;
        try {
            commitHash = gitWorkspaceService.sync(record, accessToken);
            List<CodeFileCandidate> candidates = fileScanner.scan(Path.of(record.localPath()));
            totalFiles = candidates.size();
            repository.updateJobProgress(jobId, totalFiles, 0, 0, 0);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("색인할 수 있는 코드 파일이 없습니다.");
            }

            for (CodeFileCandidate candidate : candidates) {
                try {
                    String content = contentReader.read(candidate.absolutePath());
                    List<ParsedCodeChunk> chunks = chunkParser.parse(candidate.relativePath(), candidate.language(), content);
                    if (chunks.isEmpty()) {
                        processedFiles++;
                        repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
                        continue;
                    }
                    List<List<Double>> embeddings = ollamaClient.embed(chunks.stream().map(ParsedCodeChunk::content).toList());
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
            return repository.listJobs(repositoryId).stream()
                    .filter(job -> job.id().equals(jobId))
                    .findFirst()
                    .orElseThrow();
        } catch (RuntimeException ex) {
            repository.updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles);
            repository.finishJob(jobId, "FAILED", commitHash, ex.getMessage());
            repository.updateRepositoryStatus(repositoryId, "FAILED", ex.getMessage());
            throw ex;
        }
    }

    private String requireGitUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Git URL을 입력하세요.");
        }
        String clean = gitUrl.trim();
        if (!(clean.startsWith("http://")
                || clean.startsWith("https://")
                || clean.startsWith("ssh://")
                || clean.startsWith("git://")
                || clean.startsWith("git@"))) {
            throw new IllegalArgumentException("지원하는 Git URL 형식은 http, https, ssh, git 프로토콜 또는 git@ 형식입니다.");
        }
        return clean;
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

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
