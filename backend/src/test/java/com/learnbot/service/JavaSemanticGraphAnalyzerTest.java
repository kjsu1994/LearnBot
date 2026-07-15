package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void resolvesCrossFileCallsInModernJavaSource() throws Exception {
        Path sourceRoot = repositoryRoot.resolve("src/main/java/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("ItemStore.java"), """
                package example;

                interface ItemStore {
                    Item load(String id);
                }

                record Item(String id) {}
                """);
        Files.writeString(sourceRoot.resolve("ItemCoordinator.java"), """
                package example;

                class ItemCoordinator {
                    private final ItemStore store;

                    ItemCoordinator(ItemStore store) {
                        this.store = store;
                    }

                    Item execute(String id, int mode) {
                        String selected = switch (mode) {
                            case 1 -> id.trim();
                            default -> id;
                        };
                        return store.load(selected);
                    }
                }
                """);

        CodeGraphAnalysisResult result = new JavaSemanticGraphAnalyzer()
                .analyzeWithDiagnostics(repositoryRoot, List.of());

        assertThat(result.diagnostic().failedFiles()).isZero();
        assertThat(result.diagnostic().metadata())
                .containsKey("languageLevel");
        assertThat(result.graph().edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("CALLS");
            assertThat(edge.sourceKey()).contains("ItemCoordinator.execute");
            assertThat(edge.targetKey()).contains("ItemStore.load");
        });
    }

    @Test
    void reportsUnresolvedCallsAsPartialAnalysis() throws Exception {
        Path source = repositoryRoot.resolve("src/main/java/example/IncompleteWorker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                class IncompleteWorker {
                    void run() {
                        unavailableDependency();
                    }
                }
                """);

        CodeGraphAnalysisResult result = new JavaSemanticGraphAnalyzer()
                .analyzeWithDiagnostics(repositoryRoot, List.of());

        assertThat(result.diagnostic().status()).isEqualTo("PARTIAL");
        assertThat(result.diagnostic().unresolvedRelations()).isEqualTo(1);
        assertThat(result.diagnostic().metadata())
                .containsEntry("unresolvedMethodCalls", 1);
    }

    @Test
    void replacesCallTargetPlaceholderWhenDeclarationIsVisitedLater() throws Exception {
        Path source = repositoryRoot.resolve("src/main/java/example/Flow.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                class Flow {
                    private final Store store = null;

                    String execute() {
                        return store.load();
                    }
                }

                interface Store {
                    String load();
                }
                """);
        UUID repositoryId = UUID.randomUUID();
        UUID executeChunkId = UUID.randomUUID();
        UUID loadChunkId = UUID.randomUUID();
        List<CodeSearchResult> chunks = List.of(
                result(repositoryId, executeChunkId, "src/main/java/example/Flow.java", "Flow", "execute", 6, 8),
                result(repositoryId, loadChunkId, "src/main/java/example/Flow.java", "Store", "load", 11, 12)
        );

        CodeGraph graph = new JavaSemanticGraphAnalyzer().analyze(repositoryRoot, chunks);

        assertThat(graph.nodes()).filteredOn(node -> node.key().contains("example.Store.load"))
                .singleElement().satisfies(node -> {
                    assertThat(node.filePath()).isEqualTo("src/main/java/example/Flow.java");
                    assertThat(node.chunkId()).isEqualTo(loadChunkId);
                    assertThat(node.metadata()).doesNotContainEntry("external", true);
                });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("CALLS");
            assertThat(edge.sourceKey()).contains("example.Flow.execute");
            assertThat(edge.targetKey()).contains("example.Store.load");
        });
    }

    private CodeSearchResult result(
            UUID repositoryId,
            UUID chunkId,
            String filePath,
            String className,
            String methodName,
            int lineStart,
            int lineEnd
    ) {
        return new CodeSearchResult(
                chunkId,
                repositoryId,
                UUID.randomUUID(),
                "sample",
                filePath,
                "method",
                methodName,
                className,
                methodName,
                "example",
                null,
                null,
                0,
                lineStart,
                lineEnd,
                methodName,
                1.0,
                Map.of()
        );
    }
}
