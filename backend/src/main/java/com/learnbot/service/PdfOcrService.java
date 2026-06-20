package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class PdfOcrService {
    private final LearnBotProperties.Document.Ocr properties;

    public PdfOcrService(LearnBotProperties properties) {
        this.properties = properties.getDocument().getOcr();
    }

    public boolean shouldAttempt(int pageCount, int currentTextChars) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (pageCount > properties.getMaxPages()) {
            return false;
        }
        return pageCount <= 0 || currentTextChars < pageCount * properties.getMinTextCharsPerPage();
    }

    public Optional<OcrResult> extractText(byte[] pdfBytes, String filename, int pageCount, int currentTextChars) {
        if (pdfBytes == null || pdfBytes.length == 0 || !shouldAttempt(pageCount, currentTextChars)) {
            return Optional.empty();
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("learnbot-pdf-ocr-");
            Path input = workDir.resolve(safeFilename(filename));
            Path output = workDir.resolve("ocr-output.pdf");
            Files.write(input, pdfBytes);
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    properties.getCommand(),
                    "--skip-text",
                    "--sidecar",
                    workDir.resolve("ocr-sidecar.txt").toString(),
                    "-l",
                    properties.getLanguages(),
                    input.toString(),
                    output.toString()
            ));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(Math.max(1, properties.getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                return Optional.empty();
            }
            String text = readPdfText(Files.readAllBytes(output));
            if (text.isBlank()) {
                Path sidecar = workDir.resolve("ocr-sidecar.txt");
                text = Files.isRegularFile(sidecar) ? Files.readString(sidecar) : "";
            }
            return text.isBlank() ? Optional.empty() : Optional.of(new OcrResult(text));
        } catch (Exception ex) {
            return Optional.empty();
        } finally {
            deleteQuietly(workDir);
        }
    }

    private String readPdfText(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "input.pdf" : filename;
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.toLowerCase().endsWith(".pdf") ? safe : safe + ".pdf";
    }

    private void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup for OCR temporary files.
                }
            });
        } catch (Exception ignored) {
            // Best-effort cleanup for OCR temporary files.
        }
    }

    public record OcrResult(String text) {
    }
}
