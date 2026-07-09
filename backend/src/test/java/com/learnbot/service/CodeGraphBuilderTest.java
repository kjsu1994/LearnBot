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
    void javaFieldAccessUsesActualDeclaringType(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Base.java"), """
                package sample;
                public class Base { protected int value; }
                """);
        Files.writeString(sourceRoot.resolve("Child.java"), """
                package sample;
                public class Child extends Base {
                    public void update() { value = 1; }
                }
                """);
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult base = result(repositoryId, UUID.randomUUID(), "src/main/java/sample/Base.java",
                "class", "Base", null, null, null, "public class Base { protected int value; }");
        CodeSearchResult child = result(repositoryId, UUID.randomUUID(), "src/main/java/sample/Child.java",
                "method", "Child", "update", null, null, "public void update() { value = 1; }");

        CodeGraph graph = new JavaSemanticGraphAnalyzer().analyze(root, java.util.List.of(base, child));

        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("WRITES_FIELD");
            assertThat(edge.sourceKey()).contains("sample.Child.update(");
            assertThat(edge.targetKey()).isEqualTo("field:java:sample.Base#value");
        });
        assertThat(graph.edges()).noneMatch(edge -> edge.targetKey().equals("field:java:sample.Child#value"));
    }

    @Test
    void javaSpringAnalyzerAddsEndpointBeanAndTransactionEdges(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("OrderController.java"), """
                package sample;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.stereotype.Service;
                import org.springframework.context.annotation.*;
                import org.springframework.transaction.annotation.Transactional;

                @RestController
                @RequestMapping(path = "/api/orders")
                class OrderController {
                    private final OrderService service;
                    OrderController(OrderService service) { this.service = service; }
                    @PostMapping(path = "/{id}")
                    String submit() { return service.submit(); }
                }

                @Service
                class OrderService {
                    @Transactional
                    String submit() { return "ok"; }
                }

                @Configuration
                class OrderConfig {
                    @Bean(value = "primaryOrderService")
                    OrderService orderService() { return new OrderService(); }
                }
                """);
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult source = result(repositoryId, UUID.randomUUID(), "src/main/java/sample/OrderController.java",
                "method", "OrderController", "submit", null, null, "String submit() { return service.submit(); }");

        CodeGraph graph = new JavaSemanticGraphAnalyzer(new LearnBotProperties()).analyze(root, java.util.List.of(source));

        assertThat(graph.edges()).anySatisfy(edge -> assertThat(edge.type()).isEqualTo("EXPOSES_ENDPOINT"));
        assertThat(graph.edges()).anySatisfy(edge -> assertThat(edge.type()).isEqualTo("TRANSACTION_BOUNDARY"));
        assertThat(graph.edges()).anySatisfy(edge -> assertThat(edge.type()).isEqualTo("DECLARES_BEAN"));
        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.type()).isEqualTo("endpoint");
            assertThat(node.metadata()).containsEntry("route", "/api/orders/{id}");
        });
        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.type()).isEqualTo("bean");
            assertThat(node.metadata()).containsEntry("beanName", "primaryOrderService");
        });
        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.type()).isEqualTo("type");
            assertThat(node.metadata()).containsEntry("springRole", "controller");
        });
    }

    @Test
    void javaSpringAnalyzerAddsRepositoryInjectionAndTransactionMetadata(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("OrderService.java"), """
                package sample;
                import jakarta.persistence.Entity;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Entity
                class Order {}

                interface OrderRepository extends JpaRepository<Order, Long> {}

                @Service
                class OrderService {
                    private final OrderRepository repository;
                    OrderService(@Qualifier("primaryOrderRepository") OrderRepository repository) {
                        this.repository = repository;
                    }
                    @Transactional(readOnly = true, propagation = Propagation.REQUIRED, rollbackFor = IllegalStateException.class)
                    Order submit() { return repository.getReferenceById(1L); }
                }
                """);
        UUID repositoryId = UUID.randomUUID();
        CodeSearchResult source = result(repositoryId, UUID.randomUUID(), "src/main/java/sample/OrderService.java",
                "method", "OrderService", "submit", null, null, "Order submit() { return repository.getReferenceById(1L); }");

        CodeGraph graph = new JavaSemanticGraphAnalyzer(new LearnBotProperties()).analyze(root, java.util.List.of(source));

        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("REPOSITORY_FOR");
            assertThat(edge.sourceKey()).contains("OrderRepository");
            assertThat(edge.targetKey()).contains("Order");
            assertThat(edge.metadata()).containsEntry("repositoryBase", "JpaRepository");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("QUERIES_ENTITY");
            assertThat(edge.confidence()).isLessThan(0.8);
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("INJECTS");
            assertThat(edge.metadata()).containsEntry("qualifier", "primaryOrderRepository");
        });
        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.type()).isEqualTo("transaction_boundary");
            assertThat(node.metadata()).containsEntry("readOnly", "true");
            assertThat(node.metadata()).containsEntry("propagation", "Propagation.REQUIRED");
            assertThat(node.metadata()).containsEntry("rollbackFor", "IllegalStateException.class");
        });
        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.type()).isEqualTo("type");
            assertThat(node.name()).isEqualTo("OrderRepository");
            assertThat(node.metadata()).containsEntry("springRole", "repository");
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
    void sourceClassifierKeepsWinFormsDesignerAsMainEvidence() {
        CodeSourceClassifier.SourceProfile profile = CodeSourceClassifier.classify("src/OrdersForm.Designer.cs", "method", "regex");

        assertThat(profile.sourceRole()).isEqualTo(CodeSourceClassifier.SOURCE_MAIN);
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

    @Test
    void javaSpringFrameworkFlagDisablesSpringSpecificEdges(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("OrderController.java"), """
                package sample;
                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/orders")
                class OrderController {
                    @GetMapping("/{id}")
                    String get() { return "ok"; }
                }
                """);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().getGraph().setJavaSpringEnabled(false);

        CodeGraph graph = new JavaSemanticGraphAnalyzer(properties).analyze(root, java.util.List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), "src/main/java/sample/OrderController.java",
                        "method", "OrderController", "get", null, null, "String get() { return \"ok\"; }")
        ));

        assertThat(graph.edges()).noneMatch(edge -> edge.type().equals("EXPOSES_ENDPOINT"));
        assertThat(graph.nodes()).noneMatch(node -> "endpoint".equals(node.type()));
    }

    @Test
    void buildKeepsBaseGraphWhenJavaSemanticAnalyzerFails(@TempDir Path root) {
        LearnBotProperties properties = new LearnBotProperties();
        JavaSemanticGraphAnalyzer failingAnalyzer = new JavaSemanticGraphAnalyzer(properties) {
            @Override
            public CodeGraphAnalysisResult analyzeWithDiagnostics(Path repositoryRoot, java.util.List<CodeSearchResult> chunks,
                                                                  java.util.List<Path> dependencyJars) {
                throw new IllegalStateException("boom");
            }
        };
        CodeGraphBuilder builder = new CodeGraphBuilder(properties, failingAnalyzer, null, null);

        CodeGraphBuildResult result = builder.buildWithDiagnostics(root, java.util.List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), "src/main/java/sample/A.java",
                        "class", "A", null, null, null, "class A {}")
        ));

        assertThat(result.graph().nodes()).anySatisfy(node -> assertThat(node.type()).isEqualTo("file"));
        assertThat(result.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.stage()).isEqualTo("JAVA_SEMANTIC");
            assertThat(diagnostic.status()).isEqualTo("FAILED");
        });
    }

    @Test
    void javaSpringAnalyzerAddsRepositoryQueryMethodPropertyEdges(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("OrderRepository.java"), """
                package sample;
                import java.time.Instant;
                import jakarta.persistence.Entity;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Modifying;
                import org.springframework.data.jpa.repository.Query;

                @Entity
                class Order {
                    String status;
                    Instant createdAt;
                }

                interface OrderRepository extends JpaRepository<Order, Long> {
                    java.util.List<Order> findByStatusAndCreatedAtAfter(String status, Instant createdAt);

                    @Modifying
                    @Query("delete from Order o where o.status = ?1")
                    int deleteByStatus(String status);
                }
                """);
        CodeGraph graph = new JavaSemanticGraphAnalyzer(new LearnBotProperties()).analyze(root, java.util.List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), "src/main/java/sample/OrderRepository.java",
                        "method", "OrderRepository", "findByStatusAndCreatedAtAfter", null, null,
                        "java.util.List<Order> findByStatusAndCreatedAtAfter(String status, Instant createdAt);")
        ));

        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("FILTERS_BY_PROPERTY");
            assertThat(edge.targetKey()).contains("#status");
            assertThat(edge.metadata()).containsEntry("queryMethodKind", "find");
            assertThat(edge.metadata()).containsEntry("evidenceKind", "candidate");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("FILTERS_BY_PROPERTY");
            assertThat(edge.targetKey()).contains("#createdAt");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("QUERIES_ENTITY");
            assertThat(edge.sourceKey()).contains("findByStatusAndCreatedAtAfter");
            assertThat(edge.targetKey()).contains("Order");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.sourceKey()).contains("deleteByStatus");
            assertThat(edge.metadata()).containsEntry("modifying", true);
            assertThat(edge.metadata()).containsKey("declaredQuery");
        });
    }

    @Test
    void javaSpringAnalyzerMarksInheritedTransactionMetadata(@TempDir Path root) throws Exception {
        Path sourceRoot = root.resolve("src/main/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("OrderService.java"), """
                package sample;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                @Transactional(readOnly = true)
                class OrderService {
                    String list() { return "ok"; }

                    @Transactional(readOnly = false)
                    String update() { return "updated"; }
                }
                """);

        CodeGraph graph = new JavaSemanticGraphAnalyzer(new LearnBotProperties()).analyze(root, java.util.List.of(
                result(UUID.randomUUID(), UUID.randomUUID(), "src/main/java/sample/OrderService.java",
                        "method", "OrderService", "list", null, null, "String list() { return \"ok\"; }")
        ));

        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("TRANSACTION_BOUNDARY");
            assertThat(edge.sourceKey()).contains("list");
            assertThat(edge.confidence()).isLessThan(0.90);
            assertThat(edge.metadata()).containsEntry("transactionInherited", true);
        });
        assertThat(graph.nodes()).anySatisfy(node -> {
            assertThat(node.key()).contains("list");
            assertThat(node.type()).isEqualTo("transaction_boundary");
            assertThat(node.metadata()).containsEntry("transactionInherited", true);
            assertThat(node.metadata()).containsEntry("readOnly", "true");
        });
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo("TRANSACTION_BOUNDARY");
            assertThat(edge.sourceKey()).contains("update");
            assertThat(edge.confidence()).isGreaterThan(0.95);
            assertThat(edge.metadata()).doesNotContainKey("transactionInherited");
        });
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
