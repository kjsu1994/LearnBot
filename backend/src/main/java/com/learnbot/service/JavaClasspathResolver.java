package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaClasspathResolver {
    private static final Pattern COORDINATE = Pattern.compile("([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)");
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile("(?:implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*[\"']([^\"']+:[^\"']+:[^\"']+)[\"']");
    private static final Pattern TOML_VERSION = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*=\\s*[\"']([^\"']+)[\"']$");
    private static final Pattern TOML_LIBRARY = Pattern.compile("module\\s*=\\s*[\"']([^\"']+)[\"'].*?(?:version(?:\\.ref)?\\s*=\\s*[\"']([^\"']+)[\"'])");

    private final LearnBotProperties properties;
    private final HttpClient httpClient;

    public JavaClasspathResolver(LearnBotProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JavaClasspathResolution resolve(Path repositoryRoot) {
        long started = System.nanoTime();
        if (!properties.getCode().getGraph().isDependencyResolutionEnabled()) {
            return new JavaClasspathResolution(List.of(), CodeAnalysisDiagnostic.skipped(
                    "JAVA_CLASSPATH", "Static dependency resolver", "CACHE_AND_ALLOWLIST", "Dependency resolution is disabled."
            ));
        }
        Set<String> coordinates = discoverCoordinates(repositoryRoot);
        if (coordinates.isEmpty()) {
            return new JavaClasspathResolution(List.of(), CodeAnalysisDiagnostic.skipped(
                    "JAVA_CLASSPATH", "Static dependency resolver", "CACHE_AND_ALLOWLIST", "No static Java dependency coordinates found."
            ));
        }
        int maxArtifacts = Math.max(1, properties.getCode().getGraph().getDependencyMaxArtifacts());
        long maxBytes = Math.max(1, properties.getCode().getGraph().getDependencyMaxBytes());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                Math.max(1, properties.getCode().getGraph().getDependencyTimeoutSeconds())
        );
        Path cache = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize().resolve(".dependency-cache");
        List<Path> jars = new ArrayList<>();
        int failed = 0;
        long bytes = 0;
        boolean limited = false;
        for (String coordinate : coordinates) {
            if (jars.size() >= maxArtifacts || bytes >= maxBytes || System.nanoTime() >= deadline) {
                limited = true;
                break;
            }
            try {
                Path jar = resolveCoordinate(coordinate, cache, maxBytes - bytes, deadline);
                if (jar != null) {
                    long size = Files.size(jar);
                    if (bytes + size > maxBytes) {
                        limited = true;
                        break;
                    }
                    jars.add(jar);
                    bytes += size;
                } else {
                    failed++;
                }
            } catch (Exception ignored) {
                failed++;
            }
        }
        String status = failed == 0 && !limited ? "SUCCESS" : jars.isEmpty() ? "FAILED" : "PARTIAL";
        return new JavaClasspathResolution(List.copyOf(jars), new CodeAnalysisDiagnostic(
                "JAVA_CLASSPATH", "Static dependency resolver", status, "CACHE_AND_ALLOWLIST",
                coordinates.size(), jars.size(), failed, jars.size(), Math.max(0, coordinates.size() - jars.size()),
                0, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                limited ? "Dependency resolution stopped at a configured resource limit."
                        : failed > 0 ? "Some dependencies could not be resolved; source analysis will continue."
                        : "Dependency classpath resolved.",
                Map.of("artifactBytes", bytes, "limitReached", limited)
        ));
    }

    private Set<String> discoverCoordinates(Path root) {
        Set<String> coordinates = new LinkedHashSet<>();
        if (root == null || !Files.isDirectory(root)) return coordinates;
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if ("pom.xml".equals(name)) parsePom(path, coordinates);
                if ("build.gradle".equals(name) || "build.gradle.kts".equals(name)) parseGradle(path, coordinates);
                if ("libs.versions.toml".equals(name)) parseVersionCatalog(path, coordinates);
            });
        } catch (Exception ignored) {
            // Diagnostics reports unresolved coordinates without failing indexing.
        }
        return coordinates;
    }

    private void parsePom(Path path, Set<String> coordinates) {
        try (InputStream input = Files.newInputStream(path)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(input);
            Map<String, String> values = new HashMap<>();
            var propertiesNodes = document.getElementsByTagName("properties");
            if (propertiesNodes.getLength() > 0) {
                var children = propertiesNodes.item(0).getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element element) values.put(element.getTagName(), element.getTextContent().trim());
                }
            }
            var dependencies = document.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dependency = (Element) dependencies.item(i);
                String scope = child(dependency, "scope");
                if ("test".equals(scope) || "provided".equals(scope)) continue;
                String group = child(dependency, "groupId");
                String artifact = child(dependency, "artifactId");
                String version = resolveProperty(child(dependency, "version"), values);
                addCoordinate(coordinates, group + ":" + artifact + ":" + version);
            }
        } catch (Exception ignored) {
            // Invalid descriptors are reported indirectly as missing dependencies.
        }
    }

    private void parseGradle(Path path, Set<String> coordinates) {
        try {
            Matcher matcher = GRADLE_DEPENDENCY.matcher(Files.readString(path));
            while (matcher.find()) addCoordinate(coordinates, matcher.group(1));
        } catch (Exception ignored) {
            // Static parsing is best-effort and never executes Gradle.
        }
    }

    private void parseVersionCatalog(Path path, Set<String> coordinates) {
        try {
            Map<String, String> versions = new HashMap<>();
            List<String> libraryLines = new ArrayList<>();
            String section = "";
            for (String raw : Files.readAllLines(path)) {
                String line = raw.replaceFirst("\\s+#.*$", "").trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    continue;
                }
                if ("versions".equals(section)) {
                    Matcher version = TOML_VERSION.matcher(line);
                    if (version.matches()) versions.put(version.group(1), version.group(2));
                } else if ("libraries".equals(section)) {
                    libraryLines.add(line);
                }
            }
            for (String line : libraryLines) {
                Matcher direct = Pattern.compile("[\"']([^\"']+:[^\"']+:[^\"']+)[\"']").matcher(line);
                if (direct.find()) {
                    addCoordinate(coordinates, direct.group(1));
                    continue;
                }
                Matcher library = TOML_LIBRARY.matcher(line);
                if (library.find()) {
                    String version = versions.getOrDefault(library.group(2), library.group(2));
                    addCoordinate(coordinates, library.group(1) + ":" + version);
                }
            }
        } catch (Exception ignored) {
            // Version catalogs remain optional static hints.
        }
    }

    private Path resolveCoordinate(String coordinate, Path cache, long remainingBytes, long deadline) throws Exception {
        Matcher matcher = COORDINATE.matcher(coordinate);
        if (!matcher.matches() || matcher.group(3).endsWith("-SNAPSHOT")) return null;
        String relative = matcher.group(1).replace('.', '/') + "/" + matcher.group(2) + "/" + matcher.group(3)
                + "/" + matcher.group(2) + "-" + matcher.group(3) + ".jar";
        Path target = cache.resolve(relative).normalize();
        if (!target.startsWith(cache) || (Files.isRegularFile(target) && Files.size(target) > remainingBytes)) return null;
        if (Files.isRegularFile(target)) return target;
        for (String base : properties.getCode().getGraph().getDependencyAllowedRepositories()) {
            URI repository = URI.create(base.endsWith("/") ? base : base + "/");
            if (!"https".equalsIgnoreCase(repository.getScheme()) || repository.getHost() == null) continue;
            URI uri = repository.resolve(relative);
            if (!repository.getHost().equalsIgnoreCase(uri.getHost()) || System.nanoTime() >= deadline) continue;
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.min(20, Math.max(1, properties.getCode().getGraph().getDependencyTimeoutSeconds()))))
                    .GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                continue;
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > remainingBytes) {
                response.body().close();
                return null;
            }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try (InputStream body = response.body()) {
                Files.copy(body, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(temporary) > remainingBytes) {
                Files.deleteIfExists(temporary);
                return null;
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        }
        return null;
    }

    private void addCoordinate(Set<String> values, String coordinate) {
        if (coordinate != null && COORDINATE.matcher(coordinate.trim()).matches()) values.add(coordinate.trim());
    }
    private String child(Element parent, String name) {
        var nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    private String resolveProperty(String value, Map<String, String> properties) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) return properties.getOrDefault(value.substring(2, value.length() - 1), "");
        return value == null ? "" : value;
    }
}
