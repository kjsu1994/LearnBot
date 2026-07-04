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
    private static final int MAX_CHANGED_FILES = 5;
    private static final int MAX_CHANGED_LINES = 300;
    private static final int MAX_EXPLICIT_REWRITE_CHANGED_LINES = 1_200;
    private static final int MAX_CREATED_FILE_CHANGED_LINES = 1_500;
    private static final int MAX_DIFF_CHARS = 30_000;

    private final CodePatchFileLoader fileLoader;

    public PatchValidationService(CodePatchFileLoader fileLoader) {
        this.fileLoader = fileLoader;
    }

    public PatchValidationResult validate(String diff, List<String> targetFiles) {
        return validate(diff, targetFiles, "");
    }

    public PatchValidationResult validate(String diff, List<String> targetFiles, String instruction) {
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
        if ((!clean.contains("--- a/") && !clean.contains("--- /dev/null")) || !clean.contains("+++ b/") || !clean.contains("@@")) {
            warnings.add("Patch output is not a unified diff.");
        }

        Set<String> allowed = new LinkedHashSet<>();
        for (String target : targetFiles == null ? List.<String>of() : targetFiles) {
            allowed.add(normalizePath(target));
        }
        List<PatchFileChange> changes = changedFiles(clean, warnings);
        Set<String> changedFiles = changes.stream()
                .map(PatchFileChange::effectivePath)
                .filter(path -> !path.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (changedFiles.size() > MAX_CHANGED_FILES) {
            warnings.add("Patch changes too many files.");
        }
        for (PatchFileChange change : changes) {
            String path = change.effectivePath();
            if (path.isBlank()) {
                warnings.add("Patch file header did not declare a usable path.");
                continue;
            }
            if (change.deletesFile()) {
                warnings.add("Deleting files is not allowed in Patch Agent v1: " + path);
                continue;
            }
            if (!allowed.contains(path)) {
                warnings.add("Patch modifies a file outside targetFiles: " + path);
            }
            if (fileLoader.isSensitiveOrUnsafe(path)) {
                warnings.add("Patch modifies an unsafe or sensitive path: " + path);
            }
        }
        ChangedLineBudget changedLineBudget = changedLineBudget(clean, changes, instruction);
        if (changedLineBudget.changedLines() > changedLineBudget.maxChangedLines()) {
            warnings.add("Patch changes too many lines. changedLines="
                    + changedLineBudget.changedLines()
                    + ", maxChangedLines=" + changedLineBudget.maxChangedLines()
                    + ", budgetReason=" + changedLineBudget.reason());
        }
        return new PatchValidationResult(warnings.isEmpty(), List.copyOf(warnings));
    }

    private ChangedLineBudget changedLineBudget(String diff, List<PatchFileChange> changes, String instruction) {
        long changedLines = diff.lines()
                .filter(line -> (line.startsWith("+") && !line.startsWith("+++"))
                        || (line.startsWith("-") && !line.startsWith("---")))
                .count();
        boolean onlyCreatesFiles = changes != null
                && !changes.isEmpty()
                && changes.stream().allMatch(PatchFileChange::createsFile);
        if (onlyCreatesFiles) {
            return new ChangedLineBudget(changedLines, MAX_CREATED_FILE_CHANGED_LINES, "created-files");
        }
        if (explicitRewriteRequested(instruction)) {
            return new ChangedLineBudget(changedLines, MAX_EXPLICIT_REWRITE_CHANGED_LINES, "explicit-rewrite");
        }
        return new ChangedLineBudget(changedLines, MAX_CHANGED_LINES, "existing-file-safe-default");
    }

    private boolean explicitRewriteRequested(String instruction) {
        String lower = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        return lower.contains("rewrite")
                || lower.contains("replace entire")
                || lower.contains("whole file")
                || lower.contains("from scratch")
                || lower.contains("전체 교체")
                || lower.contains("전체를 교체")
                || lower.contains("전체 재작성")
                || lower.contains("새로 작성")
                || lower.contains("처음부터");
    }

    private List<PatchFileChange> changedFiles(String diff, List<String> warnings) {
        List<PatchFileChange> changes = new ArrayList<>();
        String oldPath = null;
        for (String line : diff.split("\n")) {
            if (line.startsWith("--- ")) {
                oldPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
            } else if (line.startsWith("+++ ")) {
                String newPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
                if (oldPath == null || oldPath.isBlank() || newPath.isBlank()) {
                    warnings.add("Patch file headers are incomplete.");
                } else {
                    changes.add(new PatchFileChange(oldPath, newPath));
                }
                oldPath = null;
            }
        }
        if (changes.isEmpty()) {
            warnings.add("Patch did not declare any changed files.");
        }
        return List.copyOf(changes);
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

    private record PatchFileChange(String oldPath, String newPath) {
        String effectivePath() {
            return "/dev/null".equals(newPath) ? oldPath : newPath;
        }

        boolean createsFile() {
            return "/dev/null".equals(oldPath) && !"/dev/null".equals(newPath);
        }

        boolean deletesFile() {
            return "/dev/null".equals(newPath);
        }
    }

    private record ChangedLineBudget(long changedLines, int maxChangedLines, String reason) {
    }
}
