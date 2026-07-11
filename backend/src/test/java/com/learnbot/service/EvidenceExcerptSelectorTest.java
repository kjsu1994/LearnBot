package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExcerptSelectorTest {

    @Test
    void returnsFullChunkWhenItFitsTheAvailableBudget() {
        String content = """
                public void parse(String value) {
                    decimal parsed = decimal.Parse(value);
                    validate(parsed);
                }
                """;

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "How does parse validate value?", result(content), content.length() + 20);

        assertThat(excerpt.kind()).isEqualTo("FULL_CHUNK");
        assertThat(excerpt.contentComplete()).isTrue();
        assertThat(excerpt.text()).contains("decimal.Parse", "validate(parsed)");
    }

    @Test
    void selectsRelevantLateWindowsInsteadOfStoppingAtEarlyMatches() {
        String prefix = IntStream.range(0, 30)
                .mapToObj(index -> "var commandParser" + index + " = arguments[" + index + "];")
                .reduce("", (left, right) -> left + right + "\n");
        String content = prefix + """
                /*
                if (inputRange.IsArgRange(float.Parse(argument)) == false) {
                    LogWarning("Argument value is out of range");
                }
                */
                string argText = arguments[i]?.ToString();
                if (decimal.TryParse(argText, out decimal argValue) == false) {
                    LogWarning("Argument value is not numeric");
                    return;
                }
                if (inputRange.IsArgRange(argValue) == false) {
                    string message = "Argument value is out of range";
                    LogWarning(message);
                    return;
                }
                """;

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Argument value is out of range IsArgRange validation", result(content), 900);

        assertThat(excerpt.kind()).isEqualTo("SCORED_WINDOWS");
        assertThat(excerpt.text())
                .contains("decimal.TryParse", "IsArgRange(argValue)", "LogWarning(message)", "return");
    }

    @Test
    void preservesDirectReadBoundariesInsteadOfApplyingQuestionWindows() {
        String content = "method-start\n" + "middle-line\n".repeat(200) + "catch-and-method-end";
        CodeSearchResult directRead = withMetadata(result(content), Map.of("llmDirectRead", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "middle line", directRead, 360);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_BOUNDED");
        assertThat(excerpt.contentComplete()).isFalse();
        assertThat(excerpt.omittedByBudget()).isTrue();
        assertThat(excerpt.text())
                .contains("method-start", "direct-read content omitted by prompt budget", "catch-and-method-end");
    }

    @Test
    void requestedMiddleRangeIsPreservedWithTruthfulExcerptBounds() {
        String content = IntStream.rangeClosed(100, 199)
                .mapToObj(line -> "source-line-" + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                100,
                199,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadOperation", "read_file_range",
                        "llmRequestedLineStart", 145,
                        "llmRequestedLineEnd", 147
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "explain the requested range", directRead, 120);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_REQUESTED_RANGE");
        assertThat(excerpt.contentComplete()).isFalse();
        assertThat(excerpt.omittedByBudget()).isFalse();
        assertThat(excerpt.lineStart()).isEqualTo(145);
        assertThat(excerpt.lineEnd()).isEqualTo(147);
        assertThat(excerpt.text())
                .contains(
                        "outside requested range omitted: lines 100-144",
                        "source-line-145",
                        "source-line-146",
                        "source-line-147",
                        "outside requested range omitted: lines 148-199"
                )
                .doesNotContain("source-line-144", "source-line-148");
    }

    private CodeSearchResult result(String content) {
        UUID repositoryId = UUID.randomUUID();
        return new CodeSearchResult(
                UUID.randomUUID(),
                repositoryId,
                UUID.randomUUID(),
                "test",
                "src/CommandParser.cs",
                "method",
                "LocalCommandParser",
                "CommandParser",
                "LocalCommandParser",
                "Example",
                null,
                null,
                0,
                1,
                Math.max(1, (int) content.lines().count()),
                content,
                0.9,
                Map.of(
                        "llmSupportedClaims", java.util.List.of("validates argument range and logs failures"),
                        "llmFollowUpQuery", "IsArgRange out of range validation"
                )
        );
    }

    private CodeSearchResult withMetadata(CodeSearchResult result, Map<String, Object> metadata) {
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score(), metadata);
    }

    private CodeSearchResult withLineRangeAndMetadata(
            CodeSearchResult result,
            int lineStart,
            int lineEnd,
            Map<String, Object> metadata
    ) {
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), lineStart, lineEnd,
                result.content(), result.score(), metadata);
    }
}
