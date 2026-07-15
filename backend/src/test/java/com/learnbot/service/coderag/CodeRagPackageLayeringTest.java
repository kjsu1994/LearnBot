package com.learnbot.service.coderag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRagPackageLayeringTest {
    private static final Path CODE_RAG_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "learnbot", "service", "coderag");
    private static final Set<String> SUBSYSTEMS = Set.of(
            "answer", "diagnostics", "evidence", "model", "orchestration", "retrieval");

    @Test
    void plannedSubsystemEntryPointsExistInTheirRolePackages() {
        Map<String, List<String>> entryPoints = new LinkedHashMap<>();
        entryPoints.put("orchestration", List.of("CodeRagOrchestrator", "CodeQuestionRouter"));
        entryPoints.put("retrieval", List.of("CodeRetrievalCoordinator", "CodeRetrievalLoop"));
        entryPoints.put("evidence", List.of("CodeEvidenceAccumulator", "CodeEvidenceAdjudicator"));
        entryPoints.put("answer", List.of("CodeContextAssembler", "CodeAnswerGenerator", "CodeAnswerVerifier"));
        entryPoints.put("diagnostics", List.of("CodeRagDiagnosticsBuilder"));
        entryPoints.put("model", List.of(
                "CodeEvidenceIr", "CodeEvidenceItem", "CodeEvidenceFact", "CodeEvidenceConstraint",
                "CodeEvidenceSignal", "CodeNavigationHandle"));

        entryPoints.forEach((subsystem, simpleNames) -> simpleNames.forEach(simpleName -> {
            String expectedName = "com.learnbot.service.coderag." + subsystem + "." + simpleName;
            assertThat(load(expectedName).getName()).isEqualTo(expectedName);
        }));
    }

    @Test
    void evidenceExtractorsLiveBehindTheExtractorSpiPackage() {
        for (String simpleName : List.of(
                "EvidenceExtractor", "EvidenceExtractorRegistry", "EndpointEvidenceExtractor",
                "AssignmentEvidenceExtractor", "TransactionEvidenceExtractor",
                "NavigationEvidenceExtractor", "PersistenceEvidenceExtractor")) {
            String expectedName = "com.learnbot.service.coderag.evidence.extractor." + simpleName;
            assertThat(load(expectedName).getName()).isEqualTo(expectedName);
        }
    }

    @Test
    void codeRagSourcesLiveInRoleBasedSubpackagesWithMatchingPackageDeclarations() throws IOException {
        assertThat(CODE_RAG_SOURCE_ROOT).isDirectory();

        List<Path> sourceFiles = javaSources(CODE_RAG_SOURCE_ROOT);
        assertThat(sourceFiles).isNotEmpty();
        assertThat(sourceFiles)
                .allSatisfy(source -> {
                    Path relative = CODE_RAG_SOURCE_ROOT.relativize(source);
                    assertThat(relative.getNameCount())
                            .as("Code RAG source must live below a role package: %s", relative)
                            .isGreaterThan(1);
                    assertThat(SUBSYSTEMS)
                            .as("recognized Code RAG role for %s", relative)
                            .contains(relative.getName(0).toString());

                    String expectedPackage = "com.learnbot.service.coderag."
                            + relative.getParent().toString().replace('\\', '.').replace('/', '.');
                    assertThat(read(source))
                            .as("package declaration for %s", relative)
                            .contains("package " + expectedPackage + ";");
                });
    }

    @Test
    void modelPackageDoesNotDependOnOtherCodeRagSubsystems() throws IOException {
        for (Path source : javaSources(CODE_RAG_SOURCE_ROOT.resolve("model"))) {
            assertThat(codeRagImports(source))
                    .as("model imports for %s", source.getFileName())
                    .allMatch(imported -> imported.startsWith("com.learnbot.service.coderag.model."));
        }
    }

    @Test
    void retrievalAndAnswerPackagesDoNotCallEachOtherDirectly() throws IOException {
        for (Path source : javaSources(CODE_RAG_SOURCE_ROOT.resolve("retrieval"))) {
            assertThat(codeRagImports(source))
                    .as("retrieval imports for %s", source.getFileName())
                    .noneMatch(imported -> imported.startsWith("com.learnbot.service.coderag.answer."));
        }
        for (Path source : javaSources(CODE_RAG_SOURCE_ROOT.resolve("answer"))) {
            assertThat(codeRagImports(source))
                    .as("answer imports for %s", source.getFileName())
                    .noneMatch(imported -> imported.startsWith("com.learnbot.service.coderag.retrieval."));
        }
    }

    @Test
    void lowerLevelSubsystemsDoNotDependOnOrchestration() throws IOException {
        for (String subsystem : Set.of("answer", "diagnostics", "evidence", "model", "retrieval")) {
            for (Path source : javaSources(CODE_RAG_SOURCE_ROOT.resolve(subsystem))) {
                assertThat(codeRagImports(source))
                        .as("%s imports for %s", subsystem, source.getFileName())
                        .noneMatch(imported -> imported.startsWith("com.learnbot.service.coderag.orchestration."));
            }
        }
    }

    @Test
    void lowerLevelSubsystemsDoNotDependOnCodeRagFacade() throws IOException {
        for (String subsystem : Set.of("answer", "diagnostics", "evidence", "model", "retrieval")) {
            for (Path source : javaSources(CODE_RAG_SOURCE_ROOT.resolve(subsystem))) {
                assertThat(read(source))
                        .as("%s source must not depend on the CodeRagService facade: %s",
                                subsystem, source.getFileName())
                        .doesNotContain("com.learnbot.service.CodeRagService");
            }
        }
    }

    private static List<Path> javaSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> codeRagImports(Path source) throws IOException {
        return Files.readAllLines(source).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("import com.learnbot.service.coderag."))
                .map(line -> line.substring("import ".length(), line.length() - 1))
                .toList();
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + source, exception);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Missing planned Code RAG component: " + className, exception);
        }
    }
}
