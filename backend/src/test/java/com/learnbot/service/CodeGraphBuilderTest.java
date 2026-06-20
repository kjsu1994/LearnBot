package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphBuilderTest {
    @Test
    void conservativeChunkFallbackDoesNotClaimResolvedCalls() {
        CodeGraphBuilder builder = new CodeGraphBuilder(new LearnBotProperties());
        UUID repositoryId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        CodeSearchResult controller = result(repositoryId, fileId, "backend/AuthController.java", "method", "AuthController", "login", null, null,
                "public void login() { authService.authenticate(); }");
        CodeSearchResult service = result(repositoryId, fileId, "backend/AuthService.java", "method", "AuthService", "authenticate", null, null,
                "public void authenticate() {}");

        CodeGraph graph = builder.build(java.util.List.of(controller, service));

        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.type()).isEqualTo("method");
            assertThat(node.name()).isEqualTo("login");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("REFERENCES");
            assertThat(edge.targetKey()).contains("authenticate");
        });
    }

    @Test
    void javaSemanticAnalyzerUsesQualifiedSignaturesAndResolvedRelations(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("AuthService.java"), """
                package sample;
                interface Service { void authenticate(); }
                public class AuthService implements Service {
                    @Override public void authenticate() {}
                }
                """);
        Files.writeString(sourceRoot.resolve("AuthController.java"), """
                package sample;
                public class AuthController {
                    private final AuthService service;
                    public AuthController(AuthService service) { this.service = service; }
                    public void login() { service.authenticate(); }
                }
                """);
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult controller = result(repositoryId, UUID.randomUUID(), "src/main/java/sample/AuthController.java",
                "method", "AuthController", "login", null, null, "public void login() { service.authenticate(); }");
        CodeSearchResult service = result(repositoryId, UUID.randomUUID(), "src/main/java/sample/AuthService.java",
                "method", "AuthService", "authenticate", null, null, "public void authenticate() {}");

        CodeGraph graph = new JavaSemanticGraphAnalyzer().analyze(root, java.util.List.of(controller, service));

        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.key()).startsWith("method:java:sample.AuthController.login(");
            assertThat(node.qualifiedName()).contains("sample.AuthController.login(");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("CALLS");
            assertThat(edge.sourceKey()).contains("sample.AuthController.login(");
            assertThat(edge.targetKey()).contains("sample.AuthService.authenticate(");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("IMPLEMENTS");
            assertThat(edge.sourceKey()).contains("sample.AuthService");
            assertThat(edge.targetKey()).contains("sample.Service");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("INJECTS");
            assertThat(edge.sourceKey()).contains("sample.AuthController");
            assertThat(edge.targetKey()).contains("sample.AuthService");
        });
    }

    @Test
    void onlyUsesCallsWhenMethodAppearsAsCallExpression() {
        CodeGraphBuilder builder = new CodeGraphBuilder(new LearnBotProperties());
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult source = result(repositoryId, UUID.randomUUID(), "backend/AuthController.java", "method", "AuthController", "login", null, null,
                """
                        public void login() {
                            String methodName = "authenticate";
                            // authenticate should not be treated as a call here
                            audit("authenticate");
                        }
                        """);
        CodeSearchResult target = result(repositoryId, UUID.randomUUID(), "backend/AuthService.java", "method", "AuthService", "authenticate", null, null,
                "public void authenticate() {}");

        CodeGraph graph = builder.build(java.util.List.of(source, target));

        assertThat(graph.edges()).noneSatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("CALLS");
            assertThat(edge.targetKey()).contains("authenticate");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("REFERENCES");
            assertThat(edge.targetKey()).contains("authenticate");
        });
    }

    @Test
    void buildsXamlEventHandlerEdge() {
        CodeGraphBuilder builder = new CodeGraphBuilder(new LearnBotProperties());
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult view = result(repositoryId, UUID.randomUUID(), "MainWindow.xaml", "xaml_event", "MainWindow", null, "SaveButton", "SaveButton_Click",
                "<Button x:Name=\"SaveButton\" Click=\"SaveButton_Click\" />");
        CodeSearchResult handler = result(repositoryId, UUID.randomUUID(), "MainWindow.xaml.cs", "event_handler", "MainWindow", "SaveButton_Click", null, "SaveButton_Click",
                "private void SaveButton_Click(object sender, RoutedEventArgs e) {}");

        CodeGraph graph = builder.build(java.util.List.of(view, handler));

        assertThat(graph.edges()).anySatisfy(edge -> assertThat(edge.type()).isEqualTo("HANDLES_EVENT"));
    }

    @Test
    void returnsEmptyGraphWhenDisabled() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().getGraph().setEnabled(false);
        CodeGraphBuilder builder = new CodeGraphBuilder(properties);

        CodeGraph graph = builder.build(java.util.List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), "A.java", "class", "A", null, null, null, "class A {}")
        ));

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
    }

    private CodeSearchResult result(
            UUID repositoryId,
            UUID fileId,
            String filePath,
            String chunkType,
            String className,
            String methodName,
            String controlName,
            String eventName,
            String content
    ) {
        return new CodeSearchResult(
                UUID.randomUUID(),
                repositoryId,
                fileId,
                "repo",
                filePath,
                chunkType,
                methodName == null ? className : methodName,
                className,
                methodName,
                null,
                controlName,
                eventName,
                0,
                1,
                10,
                content,
                0,
                Map.of()
        );
    }
}
