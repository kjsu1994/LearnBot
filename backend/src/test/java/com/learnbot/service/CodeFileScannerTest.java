package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodeFileScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scansMonorepoPackagesButStillSkipsVendorDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("packages/service-a/src"));
        Files.writeString(tempDir.resolve("packages/service-a/src/index.ts"), "export function run() { return true; }");
        Files.createDirectories(tempDir.resolve("third_party/lib"));
        Files.writeString(tempDir.resolve("third_party/lib/index.ts"), "export function vendored() { return true; }");
        Files.createDirectories(tempDir.resolve("external/lib"));
        Files.writeString(tempDir.resolve("external/lib/index.ts"), "export function external() { return true; }");
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("node_modules/pkg/index.js"), "module.exports = {};");

        CodeFileScanner scanner = new CodeFileScanner(new LearnBotProperties());

        assertThat(scanner.scan(tempDir))
                .extracting(CodeFileCandidate::relativePath)
                .contains("packages/service-a/src/index.ts")
                .doesNotContain(
                        "third_party/lib/index.ts",
                        "external/lib/index.ts",
                        "node_modules/pkg/index.js"
                );
    }
}
