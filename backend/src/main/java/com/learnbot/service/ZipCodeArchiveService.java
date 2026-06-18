package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ZipCodeArchiveService {
    private static final int BUFFER_SIZE = 8192;

    private final LearnBotProperties properties;

    public ZipCodeArchiveService(LearnBotProperties properties) {
        this.properties = properties;
    }

    public PreparedZip prepare(MultipartFile file) {
        String filename = cleanFilename(file);
        validateZipFile(file, filename);
        Path workspaceRoot = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize();
        Path target = workspaceRoot.resolve(UUID.randomUUID().toString()).toAbsolutePath().normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("ZIP workspace path is invalid.");
        }
        try {
            Files.createDirectories(target);
            extract(file, target);
            Path normalizedRoot = collapseSingleTopLevelDirectory(target);
            Snapshot snapshot = snapshot(normalizedRoot);
            if (snapshot.files().isEmpty()) {
                throw new IllegalArgumentException("ZIP archive does not contain any files.");
            }
            return new PreparedZip(filename, snapshot.hash(), normalizedRoot.toString());
        } catch (RuntimeException ex) {
            deleteQuietly(target);
            throw ex;
        } catch (IOException ex) {
            deleteQuietly(target);
            throw new IllegalArgumentException("Could not extract ZIP archive.", ex);
        }
    }

    public void deleteWorkspace(String localPath) {
        if (localPath == null || localPath.isBlank() || localPath.contains("://")) {
            return;
        }
        Path workspaceRoot = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize();
        Path target = Path.of(localPath).toAbsolutePath().normalize();
        if (!target.startsWith(workspaceRoot) || target.equals(workspaceRoot)) {
            return;
        }
        deleteQuietly(target);
    }

    private void validateZipFile(MultipartFile file, String filename) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("ZIP file is required.");
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("Only .zip files can be uploaded as code snapshots.");
        }
        if (file.getSize() > properties.getCode().getMaxArchiveBytes()) {
            throw new IllegalArgumentException("ZIP file is too large.");
        }
    }

    private void extract(MultipartFile file, Path target) throws IOException {
        int entries = 0;
        long extractedBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                Path relative = safeRelativePath(name);
                if (relative == null) {
                    continue;
                }
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) {
                    throw new IllegalArgumentException("ZIP archive contains an invalid path: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                entries++;
                if (entries > properties.getCode().getMaxFiles()) {
                    throw new IllegalArgumentException("ZIP archive contains too many files.");
                }
                Files.createDirectories(destination.getParent());
                long written = copyLimited(zip, destination, properties.getCode().getMaxArchiveExtractedBytes() - extractedBytes);
                extractedBytes += written;
                if (extractedBytes > properties.getCode().getMaxArchiveExtractedBytes()) {
                    throw new IllegalArgumentException("ZIP archive expands to too much data.");
                }
            }
        }
    }

    private Path safeRelativePath(String entryName) {
        String clean = entryName.replace('\\', '/');
        if (clean.startsWith("/") || clean.contains("\0") || clean.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("ZIP archive contains an invalid path: " + entryName);
        }
        Path relative = Path.of(clean).normalize();
        for (Path part : relative) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("ZIP archive contains an invalid path: " + entryName);
            }
        }
        return relative.getNameCount() == 0 ? null : relative;
    }

    private long copyLimited(InputStream input, Path destination, long remainingBytes) throws IOException {
        if (remainingBytes <= 0) {
            throw new IllegalArgumentException("ZIP archive expands to too much data.");
        }
        long written = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (var output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                written += read;
                if (written > remainingBytes) {
                    throw new IllegalArgumentException("ZIP archive expands to too much data.");
                }
                output.write(buffer, 0, read);
            }
        }
        return written;
    }

    private Path collapseSingleTopLevelDirectory(Path root) throws IOException {
        List<Path> children;
        try (var stream = Files.list(root)) {
            children = stream.toList();
        }
        if (children.size() != 1 || !Files.isDirectory(children.get(0))) {
            return root;
        }
        return children.get(0).toAbsolutePath().normalize();
    }

    private Snapshot snapshot(Path root) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path).toString())))
                    .toList();
        }
        MessageDigest digest = sha256();
        List<String> relativePaths = new ArrayList<>();
        for (Path file : files) {
            String relativePath = normalize(root.relativize(file).toString());
            relativePaths.add(relativePath);
            digest.update(relativePath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStreamSink.INSTANCE);
            }
            digest.update((byte) 0);
        }
        return new Snapshot(HexFormat.of().formatHex(digest.digest()), relativePaths);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private String cleanFilename(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "code-snapshot.zip";
        }
        String clean = name.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private void deleteQuietly(Path path) {
        try {
            FileSystemUtils.deleteRecursively(path);
        } catch (IOException ignored) {
        }
    }

    public record PreparedZip(String sourceLabel, String sourceHash, String localPath) {
    }

    private record Snapshot(String hash, List<String> files) {
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
