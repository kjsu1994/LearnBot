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
}
