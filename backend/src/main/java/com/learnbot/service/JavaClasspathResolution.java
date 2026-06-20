package com.learnbot.service;

import java.nio.file.Path;
import java.util.List;

public record JavaClasspathResolution(
        List<Path> jars,
        CodeAnalysisDiagnostic diagnostic
) {
}
