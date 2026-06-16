package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class CodeFileScanner {
    private static final Set<String> SUPPORTED_EXTENSIONLESS_FILES = Set.of(
            "readme", "license", "notice", "dockerfile", "makefile", "procfile"
    );
    private static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock",
            "composer.lock", "poetry.lock", "go.sum", "cargo.lock"
    );
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "bin", "obj", ".vs", ".idea", ".gradle", "node_modules",
            "dist", "build", "target", "out", "vendor", "packages"
    );
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".pdf", ".zip", ".7z",
            ".rar", ".tar", ".gz", ".dll", ".exe", ".pdb", ".cache", ".lock", ".suo", ".user"
    );

    private final LearnBotProperties properties;

    public CodeFileScanner(LearnBotProperties properties) {
        this.properties = properties;
    }

    public List<CodeFileCandidate> scan(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isInsideAllowedDirectory(root, path))
                    .filter(path -> isSupported(path))
                    .filter(path -> size(path) <= properties.getCode().getMaxFileBytes())
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .limit(properties.getCode().getMaxFiles())
                    .map(path -> new CodeFileCandidate(
                            path,
                            normalize(root.relativize(path).toString()),
                            language(path),
                            size(path)
                    ))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("코드 파일 목록을 읽을 수 없습니다.", ex);
        }
    }

    private boolean isInsideAllowedDirectory(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (EXCLUDED_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (EXCLUDED_FILENAMES.contains(name) || name.endsWith(".min.js") || name.endsWith(".min.css")) {
            return false;
        }
        if (EXCLUDED_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return false;
        }
        return SUPPORTED_EXTENSIONLESS_FILES.contains(name)
                || name.endsWith(".cs")
                || name.endsWith(".xaml")
                || name.endsWith(".csproj")
                || name.endsWith(".sln")
                || name.endsWith(".js")
                || name.endsWith(".jsx")
                || name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".java")
                || name.endsWith(".py")
                || name.endsWith(".go")
                || name.endsWith(".rs")
                || name.endsWith(".c")
                || name.endsWith(".cc")
                || name.endsWith(".cpp")
                || name.endsWith(".h")
                || name.endsWith(".hpp")
                || name.endsWith(".kt")
                || name.endsWith(".kts")
                || name.endsWith(".php")
                || name.endsWith(".rb")
                || name.endsWith(".swift")
                || name.endsWith(".html")
                || name.endsWith(".css")
                || name.endsWith(".scss")
                || name.endsWith(".json")
                || name.endsWith(".config")
                || name.endsWith(".xml")
                || name.endsWith(".md")
                || name.endsWith(".sql")
                || name.endsWith(".yml")
                || name.endsWith(".yaml");
    }

    private String language(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".cs")) return "csharp";
        if (name.endsWith(".xaml")) return "xaml";
        if (name.endsWith(".js") || name.endsWith(".jsx")) return "javascript";
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return "typescript";
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".rs")) return "rust";
        if (name.endsWith(".c") || name.endsWith(".cc") || name.endsWith(".cpp")
                || name.endsWith(".h") || name.endsWith(".hpp")) return "cpp";
        if (name.endsWith(".kt") || name.endsWith(".kts")) return "kotlin";
        if (name.endsWith(".php")) return "php";
        if (name.endsWith(".rb")) return "ruby";
        if (name.endsWith(".swift")) return "swift";
        if (name.endsWith(".html")) return "html";
        if (name.endsWith(".css") || name.endsWith(".scss")) return "css";
        if (name.endsWith(".json")) return "json";
        if (name.endsWith(".csproj") || name.endsWith(".config") || name.endsWith(".xml")) return "xml";
        if (name.endsWith(".sln")) return "solution";
        if (name.endsWith(".md")) return "markdown";
        if (SUPPORTED_EXTENSIONLESS_FILES.contains(name)) return "markdown";
        if (name.endsWith(".sql")) return "sql";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yaml";
        return "text";
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return Long.MAX_VALUE;
        }
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
