package com.learnbot.service;

import com.learnbot.dto.PatchValidationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PatchValidationService {
    private static final int MAX_CHANGED_FILES = 3;
    private static final int MAX_CHANGED_LINES = 300;
    private static final int MAX_DIFF_CHARS = 30_000;

    private final CodePatchFileLoader fileLoader;

    public PatchValidationService(CodePatchFileLoader fileLoader) {
        this.fileLoader = fileLoader;
    }

    public PatchValidationResult validate(String diff, List<String> targetFiles) {
        List<String> warnings = new ArrayList<>();
        String clean = diff == null ? "" : diff.replace("\r\n", "\n").trim();
        if (clean.isBlank()) {
            return new PatchValidationResult(false, List.of("Patch output was empty."));
        }
        if (clean.startsWith("NO_PATCH")) {
            warnings.add(clean.lines().skip(1).findFirst().orElse("The model declined to create a patch."));
            return new PatchValidationResult(false, warnings);
        }
        if (clean.length() > MAX_DIFF_CHARS) {
            warnings.add("Patch diff is too large.");
        }
        if (!clean.contains("--- a/") || !clean.contains("+++ b/") || !clean.contains("@@")) {
            warnings.add("Patch output is not a unified diff.");
        }

        Set<String> allowed = new LinkedHashSet<>();
        for (String target : targetFiles == null ? List.<String>of() : targetFiles) {
            allowed.add(normalizePath(target));
        }
        Set<String> changedFiles = changedFiles(clean, warnings);
        if (changedFiles.size() > MAX_CHANGED_FILES) {
            warnings.add("Patch changes too many files.");
        }
        for (String path : changedFiles) {
            if (path.equals("/dev/null")) {
                warnings.add("Creating or deleting files is not allowed in Patch Agent v1.");
                continue;
            }
            if (!allowed.contains(path)) {
                warnings.add("Patch modifies a file outside targetFiles: " + path);
            }
            if (fileLoader.isSensitiveOrUnsafe(path)) {
                warnings.add("Patch modifies an unsafe or sensitive path: " + path);
            }
        }
        long changedLines = clean.lines()
                .filter(line -> (line.startsWith("+") && !line.startsWith("+++"))
                        || (line.startsWith("-") && !line.startsWith("---")))
                .count();
        if (changedLines > MAX_CHANGED_LINES) {
            warnings.add("Patch changes too many lines.");
        }
        return new PatchValidationResult(warnings.isEmpty(), List.copyOf(warnings));
    }

    private Set<String> changedFiles(String diff, List<String> warnings) {
        Set<String> paths = new LinkedHashSet<>();
        diff.lines().forEach(line -> {
            if (line.startsWith("--- ") || line.startsWith("+++ ")) {
                String raw = line.substring(4).trim().split("\\s+", 2)[0];
                String path = normalizeDiffPath(raw);
                if (!path.isBlank()) {
                    paths.add(path);
                }
            }
        });
        if (paths.isEmpty()) {
            warnings.add("Patch did not declare any changed files.");
        }
        return paths;
    }

    private String normalizeDiffPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replace('\\', '/');
        if (value.equals("/dev/null")) {
            return value;
        }
        if (value.startsWith("a/") || value.startsWith("b/")) {
            value = value.substring(2);
        }
        return normalizePath(value);
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.trim().replace('\\', '/').replaceAll("^/+", "").toLowerCase(Locale.ROOT);
    }
}
