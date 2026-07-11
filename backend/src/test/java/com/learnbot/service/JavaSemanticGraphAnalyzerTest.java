package com.learnbot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSemanticGraphAnalyzerTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void extractsLanguageNeutralIdeRelationsFromSource() throws Exception {
        Path source = repositoryRoot.resolve("src/main/java/example/Worker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                interface Job {
                    void run();
                }

                class Worker implements Job {
                    private int count;

                    @Override
                    public void run() {
                        helper();
                        count = 1;
                    }

                    int helper() {
                        return count;
                    }
                }
                """);

        CodeGraphAnalysisResult result = new JavaSemanticGraphAnalyzer()
                .analyzeWithDiagnostics(repositoryRoot, List.of());

        assertThat(result.diagnostic().status()).isEqualTo("SUCCESS");
        assertThat(result.graph().edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("IMPLEMENTS");
            assertThat(edge.sourceKey()).contains("example.Worker");
            assertThat(edge.targetKey()).contains("example.Job");
        });
        assertThat(result.graph().edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("CALLS");
            assertThat(edge.sourceKey()).contains("Worker.run");
            assertThat(edge.targetKey()).contains("Worker.helper");
        });
        assertThat(result.graph().edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("OVERRIDES");
            assertThat(edge.sourceKey()).contains("Worker.run");
            assertThat(edge.targetKey()).contains("Job.run");
        });
        assertThat(result.graph().edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("WRITES_FIELD");
            assertThat(edge.sourceKey()).contains("Worker.run");
            assertThat(edge.targetKey()).contains("Worker#count");
        });
        assertThat(result.graph().edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("READS_FIELD");
            assertThat(edge.sourceKey()).contains("Worker.helper");
            assertThat(edge.targetKey()).contains("Worker#count");
        });
    }
}
