package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipCodeArchiveServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsPathTraversalEntries() throws Exception {
        ZipCodeArchiveService service = service();
        MockMultipartFile file = zip("bad.zip", "../outside.java", "class Outside {}");

        assertThatThrownBy(() -> service.prepare(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid path");
    }

    @Test
    void collapsesSingleTopLevelDirectory() throws Exception {
        ZipCodeArchiveService service = service();
        MockMultipartFile file = zip("sample.zip", "sample-main/src/App.java", "class App {}");

        ZipCodeArchiveService.PreparedZip prepared = service.prepare(file);

        assertThat(Path.of(prepared.localPath()).getFileName().toString()).isEqualTo("sample-main");
        assertThat(Files.exists(Path.of(prepared.localPath(), "src", "App.java"))).isTrue();
    }

    @Test
    void createsStableHashForSameContent() throws Exception {
        ZipCodeArchiveService service = service();

        ZipCodeArchiveService.PreparedZip first = service.prepare(zip("first.zip", "project/src/App.java", "class App {}"));
        ZipCodeArchiveService.PreparedZip second = service.prepare(zip("second.zip", "project/src/App.java", "class App {}"));

        assertThat(first.sourceHash()).isEqualTo(second.sourceHash());
    }

    @Test
    void rejectsTooManyFiles() throws Exception {
        LearnBotProperties properties = properties();
        properties.getCode().setMaxFiles(1);
        ZipCodeArchiveService service = new ZipCodeArchiveService(properties);
        byte[] bytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("src/A.java"));
            zip.write("class A {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("src/B.java"));
            zip.write("class B {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            bytes = output.toByteArray();
        }

        assertThatThrownBy(() -> service.prepare(new MockMultipartFile("file", "many.zip", "application/zip", bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many files");
    }

    private ZipCodeArchiveService service() {
        return new ZipCodeArchiveService(properties());
    }

    private LearnBotProperties properties() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().setWorkspacePath(tempDir.toString());
        properties.getCode().setMaxArchiveBytes(10_000_000);
        properties.getCode().setMaxArchiveExtractedBytes(10_000_000);
        return properties;
    }

    private MockMultipartFile zip(String filename, String entryName, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("file", filename, "application/zip", output.toByteArray());
    }
}
