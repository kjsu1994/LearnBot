package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphBuilderTest {
    @Test
    void buildsClassMethodAndCallEdges() {
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
            assertThat(edge.type()).isEqualTo("CALLS");
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
