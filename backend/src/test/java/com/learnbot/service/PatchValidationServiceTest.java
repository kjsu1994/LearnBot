package com.learnbot.service;

import com.learnbot.dto.PatchValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatchValidationServiceTest {

    private final CodePatchFileLoader fileLoader = mock(CodePatchFileLoader.class);
    private final PatchValidationService service = new PatchValidationService(fileLoader);

    @Test
    void validateAllowsExistingFilePatchWithinRaisedLineBudget() {
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        String diff = """
                --- a/index.html
                +++ b/index.html
                @@ -1 +1,401 @@
                -<main>Home</main>
                %s
                """.formatted("+<p>x</p>\n".repeat(400));

        PatchValidationResult result = service.validate(diff, List.of("index.html"), "make the homepage more flashy but keep the existing structure");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void validateStillRejectsExistingFilePatchBeyondRaisedLineBudget() {
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        String diff = """
                --- a/index.html
                +++ b/index.html
                @@ -1 +1,551 @@
                -<main>Home</main>
                %s
                """.formatted("+<p>x</p>\n".repeat(550));

        PatchValidationResult result = service.validate(diff, List.of("index.html"), "make the homepage more flashy but keep the existing structure");

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("Patch changes too many lines")
                .contains("existing-file-safe-default"));
    }

    @Test
    void validateAllowsLargerNewFileCreationBudget() {
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        String diff = """
                --- /dev/null
                +++ b/script.js
                @@ -0,0 +1,801 @@
                %s
                """.formatted("+console.log('x');\n".repeat(801));

        PatchValidationResult result = service.validate(diff, List.of("script.js"), "js 파일을 하나 추가해줘");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void validateAllowsExplicitRewriteBudgetButStillBoundsIt() {
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        String diff = """
                --- a/index.html
                +++ b/index.html
                @@ -1 +1,801 @@
                -<main>Old</main>
                %s
                """.formatted("+<p>new</p>\n".repeat(801));

        PatchValidationResult result = service.validate(diff, List.of("index.html"), "index.html 전체 작성해줘");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void validateRejectsPatchThatEmptiesExistingFile() {
        when(fileLoader.isSensitiveOrUnsafe(anyString())).thenReturn(false);
        String diff = """
                --- a/index.html
                +++ b/index.html
                @@ -1,4 +0,0 @@
                -<!DOCTYPE html>
                -<html lang="ko">
                -<body>Home</body>
                -</html>
                """;

        PatchValidationResult result = service.validate(diff, List.of("index.html"), "improve the homepage organization chart");

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("Patch would empty an existing file")
                .contains("index.html"));
    }
}
