package com.learnbot.service;

import com.learnbot.repository.CodeRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CodePatchFileLoader {
    private static final int MAX_TARGET_FILES = 3;
    private static final int MAX_FILE_CHARS = 40_000;
    private static final List<String> SENSITIVE_NAMES = List.of(
            ".env", "id_rsa", "credentials", "application-prod.yml", "application-secret.yml"
    );
    private static final List<String> SENSITIVE_SUFFIXES = List.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".truststore"
    );

    private final CodeRepository repository;
    private final CodeContentReader contentReader;

    public CodePatchFileLoader(CodeRepository repository, CodeContentReader contentReader) {
        this.repository = repository;
        this.contentReader = contentReader;
    }

    public LoadResult load(UUID repositoryId, List<String> requestedPaths) {
        CodeRepositoryRecord repo = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
        List<String> warnings = new ArrayList<>();
        List<String> paths = normalizeRequestedPaths(requestedPaths, warnings);
        if (paths.isEmpty()) {
            return new LoadResult(List.of(), warnings);
        }
        Map<String, UUID> idsByPath = repository.findActiveFileIdsByPath(repositoryId, paths);
        List<LoadedPatchFile> files = new ArrayList<>();
        for (String path : paths) {
            UUID fileId = idsByPath.get(path);
            if (fileId == null) {
                warnings.add("Target file is not indexed: " + path);
                continue;
            }
            CodeFileRecord file = repository.findActiveFile(repositoryId, fileId)
                    .orElseThrow(() -> new IllegalArgumentException("Code file was not found: " + path));
            String content = readContent(repo, file, warnings);
            if (content.length() > MAX_FILE_CHARS) {
                warnings.add("Target file is too large for patch generation and was skipped: " + path);
                continue;
            }
            files.add(new LoadedPatchFile(file.id(), file.filePath(), file.language(), content));
        }
        return new LoadResult(List.copyOf(files), List.copyOf(warnings));
    }

    public LocalTargetFile localTarget(UUID repositoryId, String requestedPath) {
        CodeRepositoryRecord repo = repository.findRepository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
        String path = normalizePath(requestedPath);
        String rejection = rejectionReason(path);
        if (rejection != null) {
            throw new IllegalArgumentException(rejection + ": " + path);
        }
        if (repo.localPath() == null || repo.localPath().isBlank() || repo.localPath().contains("://")) {
            throw new IllegalArgumentException("Patch apply requires a local repository path.");
        }
        Path root = Path.of(repo.localPath()).toAbsolutePath().normalize();
        Path target = root.resolve(path).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid file path outside repository root: " + path);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Target file does not exist locally: " + path);
        }
        Map<String, UUID> idsByPath = repository.findActiveFileIdsByPath(repositoryId, List.of(path));
        if (!idsByPath.containsKey(path)) {
            throw new IllegalArgumentException("Target file is not indexed: " + path);
        }
        return new LocalTargetFile(path, target, contentReader.read(target));
    }

    public List<String> normalizeRequestedPaths(List<String> requestedPaths, List<String> warnings) {
        if (requestedPaths == null || requestedPaths.isEmpty()) {
            return List.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String requested : requestedPaths) {
            String path = normalizePath(requested);
            if (path.isBlank()) {
                continue;
            }
            String rejection = rejectionReason(path);
            if (rejection != null) {
                warnings.add(rejection + ": " + path);
                continue;
            }
            normalized.putIfAbsent(path, path);
            if (normalized.size() >= MAX_TARGET_FILES) {
                break;
            }
        }
        if (requestedPaths.size() > MAX_TARGET_FILES) {
            warnings.add("Only the first " + MAX_TARGET_FILES + " safe target files are used.");
        }
        return List.copyOf(normalized.values());
    }

    public String rejectionReason(String path) {
        if (path == null || path.isBlank()) {
            return "Blank target file path was rejected";
        }
        String clean = normalizePath(path);
        if (clean.startsWith("/") || clean.matches("^[A-Za-z]:.*")) {
            return "Absolute target file path was rejected";
        }
        Path normalized = Path.of(clean).normalize();
        if (normalized.startsWith("..") || clean.contains("../") || clean.equals("..")) {
            return "Path traversal target file was rejected";
        }
        if (clean.equals("__learnbot__/project-context.md")) {
            return "Generated retrieval context file was rejected";
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        String fileName = Path.of(clean).getFileName() == null ? lower : Path.of(clean).getFileName().toString().toLowerCase(Locale.ROOT);
        if (SENSITIVE_NAMES.contains(fileName)
                || lower.contains("/.env")
                || lower.contains("secret")
                || lower.contains("credential")
                || SENSITIVE_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            return "Sensitive target file was rejected";
        }
        return null;
    }

    public boolean isSensitiveOrUnsafe(String path) {
        return rejectionReason(path) != null;
    }

    private String readContent(CodeRepositoryRecord repo, CodeFileRecord file, List<String> warnings) {
        if (repo.localPath() != null && !repo.localPath().isBlank() && !repo.localPath().contains("://")) {
            Path root = Path.of(repo.localPath()).toAbsolutePath().normalize();
            Path target = root.resolve(file.filePath()).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Invalid file path outside repository root: " + file.filePath());
            }
            if (Files.isRegularFile(target)) {
                return contentReader.read(target);
            }
        }
        warnings.add("Full local file was unavailable; indexed chunk content fallback was used for " + file.filePath());
        String fallback = repository.activeFileContentFromChunks(file.id());
        if (fallback == null || fallback.isBlank()) {
            throw new IllegalArgumentException("Code file content was not available: " + file.filePath());
        }
        return fallback;
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.trim().replace('\\', '/').replaceAll("^/+", "");
    }

    public record LoadedPatchFile(UUID fileId, String path, String language, String content) {
    }

    public record LocalTargetFile(String path, Path localPath, String content) {
    }

    public record LoadResult(List<LoadedPatchFile> files, List<String> warnings) {
    }
}
