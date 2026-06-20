package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class PresentationPreviewRenderer {
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final LearnBotProperties.Document.Preview properties;

    public PresentationPreviewRenderer(LearnBotProperties properties) {
        this.properties = properties.getDocument().getPreview();
    }

    public boolean supports(String filename, String contentType) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".ppt")
                || lowerName.endsWith(".pptx")
                || lowerType.contains("powerpoint")
                || lowerType.contains("presentation");
    }

    public Optional<RenderedPresentation> renderPdf(StoredFile file) {
        if (!properties.isOfficeRenderEnabled() || file == null || !supports(file.filename(), file.contentType())) {
            return Optional.empty();
        }
        byte[] content = file.content() == null ? new byte[0] : file.content();
        if (content.length == 0 || content.length > properties.getOfficeMaxFileBytes()) {
            return Optional.empty();
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("learnbot-office-preview-");
            Path input = workDir.resolve(safeFilename(file.filename()));
            Files.write(input, content);
            Path profileDir = workDir.resolve("profile");
            Files.createDirectories(profileDir);

            ProcessBuilder builder = new ProcessBuilder(List.of(
                    properties.getOfficeCommand(),
                    "--headless",
                    "--nologo",
                    "--nofirststartwizard",
                    "--nodefault",
                    "--norestore",
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--convert-to",
                    "pdf",
                    "--outdir",
                    workDir.toString(),
                    input.toString()
            ));
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(true);

            Process process = builder.start();
            boolean finished = process.waitFor(properties.getOfficeTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }

            Path output = findPdf(workDir, input);
            if (output == null || !Files.isRegularFile(output)) {
                return Optional.empty();
            }
            byte[] pdf = Files.readAllBytes(output);
            if (pdf.length == 0) {
                return Optional.empty();
            }
            return Optional.of(new RenderedPresentation(pdfFilename(file.filename()), PDF_CONTENT_TYPE, pdf));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        } finally {
            deleteQuietly(workDir);
        }
    }

    private Path findPdf(Path workDir, Path input) throws IOException {
        String base = stripExtension(input.getFileName().toString()).toLowerCase(Locale.ROOT);
        try (var paths = Files.list(workDir)) {
            return paths
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(path -> stripExtension(path.getFileName().toString()).toLowerCase(Locale.ROOT).equals(base))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String pdfFilename(String filename) {
        String safe = safeFilename(filename == null || filename.isBlank() ? "presentation" : filename);
        return stripExtension(safe) + ".pdf";
    }

    private String stripExtension(String filename) {
        int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "presentation.pptx" : filename;
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "presentation.pptx" : safe;
    }

    private void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup for temporary render artifacts.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary render artifacts.
        }
    }

    public record RenderedPresentation(String filename, String contentType, byte[] content) {
        public StoredFile asStoredFile() {
            return new StoredFile(filename, contentType, content);
        }
    }
}
