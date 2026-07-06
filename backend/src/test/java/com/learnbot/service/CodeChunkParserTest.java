package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeChunkParserTest {
    private final CodeChunkParser parser = new CodeChunkParser();

    @Test
    void parsesJavaClassesAndMethodsWithSymbols() {
        List<ParsedCodeChunk> chunks = parser.parse(
                "src/main/java/com/example/LoginService.java",
                "java",
                """
                        package com.example;

                        public class LoginService {
                            public String login(String user) {
                                return user;
                            }
                        }
                        """
        );

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("class");
            assertThat(chunk.className()).isEqualTo("LoginService");
            assertThat(chunk.metadata()).containsEntry("parser", "javaparser");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("method");
            assertThat(chunk.methodName()).isEqualTo("login");
            assertThat(chunk.namespaceName()).isEqualTo("com.example");
        });
    }

    @Test
    void parsesJava17SyntaxWithoutLineWindowFallback() {
        List<ParsedCodeChunk> chunks = parser.parse(
                "src/main/java/com/example/RagFlow.java",
                "java",
                """
                        package com.example;

                        public class RagFlow {
                            record Step(String name) {}

                            public String prompt(String mode) {
                                String template = \"""
                                        answer with citations
                                        \""";
                                return switch (mode) {
                                    case "rag" -> template;
                                    default -> "fallback";
                                };
                            }
                        }
                        """
        );

        assertThat(chunks).noneSatisfy(chunk -> assertThat(chunk.metadata()).containsEntry("parser", "line_window"));
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("record");
            assertThat(chunk.className()).isEqualTo("Step");
            assertThat(chunk.metadata()).containsEntry("parser", "javaparser");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("method");
            assertThat(chunk.methodName()).isEqualTo("prompt");
        });
    }

    @Test
    void parsesDartFlutterSymbols() {
        List<ParsedCodeChunk> chunks = parser.parse(
                "lib/main.dart",
                "dart",
                """
                        class LoginPage extends StatelessWidget {
                          Widget build(BuildContext context) {
                            return Text('login');
                          }
                        }
                        """
        );

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.symbolName()).isEqualTo("LoginPage");
            assertThat(chunk.chunkType()).isEqualTo("class");
        });
    }

    @Test
    void parsesCommonNonCSharpLanguageSymbols() {
        assertSymbol("cmd/server/main.go", "go", "func (s *Server) ServeHTTP() {}", "ServeHTTP", "function");
        assertSymbol("src/lib.rs", "rust", "pub async fn retrieve_context() {}", "retrieve_context", "function");
        assertSymbol("src/App.kt", "kotlin", "suspend fun loadContext() {}", "loadContext", "function");
        assertSymbol("src/Controller.php", "php", "public function answer() {}", "answer", "function");
        assertSymbol("app/service.rb", "ruby", "def answer_question\nend", "answer_question", "function");
        assertSymbol("Sources/App.swift", "swift", "public func buildContext() {}", "buildContext", "function");
    }

    @Test
    void parsesCommonFrontendFrameworkSymbols() {
        assertSymbol("src/pages/Checkout.tsx", "typescript", "export const CheckoutPage = () => <main />;", "CheckoutPage", "component");
        assertSymbol("src/components/UserCard.vue", "vue", "<script setup>\nfunction loadUser() {}\n</script>", "loadUser", "function");
        assertSymbol("src/routes/+page.svelte", "svelte", "<script>\nconst loadOrders = async () => [];\n</script>", "loadOrders", "function");
        assertSymbol("src/pages/index.astro", "astro", "---\nfunction getStaticPaths() {}\n---", "getStaticPaths", "function");
        assertSymbol("ui/Main.qml", "qml", "ApplicationWindow {\n}", "ApplicationWindow", "component");
    }

    @Test
    void fallsBackToLineWindowsForUnknownCodeShape() {
        List<ParsedCodeChunk> chunks = parser.parse(
                "tools/generated.weird",
                "text",
                "alpha\nbeta\ngamma"
        );

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkType()).isEqualTo("file_section");
        assertThat(chunks.get(0).metadata()).containsEntry("parser", "line_window");
    }

    private void assertSymbol(String path, String language, String content, String symbol, String chunkType) {
        List<ParsedCodeChunk> chunks = parser.parse(path, language, content);
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.symbolName()).isEqualTo(symbol);
            assertThat(chunk.chunkType()).isEqualTo(chunkType);
            assertThat(chunk.metadata()).containsEntry("parser", "regex_symbol");
        });
    }
}
