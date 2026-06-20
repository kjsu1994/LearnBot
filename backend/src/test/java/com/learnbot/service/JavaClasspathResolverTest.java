package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JavaClasspathResolverTest {
    @Test
    void resolvesDeclaredDependencyFromPersistentCacheWithoutExecutingBuild(@TempDir Path root) throws Exception {
        Path repository = root.resolve("repository");
        Path workspace = root.resolve("workspace");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><dependencies><dependency>
                  <groupId>org.example</groupId><artifactId>sample-api</artifactId><version>1.2.3</version>
                </dependency></dependencies></project>
                """);
        Path jar = workspace.resolve(".dependency-cache/org/example/sample-api/1.2.3/sample-api-1.2.3.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(jar))) {
        }
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().setWorkspacePath(workspace.toString());

        JavaClasspathResolution result = new JavaClasspathResolver(properties).resolve(repository);

        assertThat(result.jars()).containsExactly(jar);
        assertThat(result.diagnostic().status()).isEqualTo("SUCCESS");
    }

    @Test
    void skipsSnapshotAndKeepsAnalysisAvailable(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), "implementation 'org.example:unsafe:1.0-SNAPSHOT'");
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().setWorkspacePath(root.resolve("workspace").toString());

        JavaClasspathResolution result = new JavaClasspathResolver(properties).resolve(root);

        assertThat(result.jars()).isEmpty();
        assertThat(result.diagnostic().status()).isEqualTo("FAILED");
    }
}
