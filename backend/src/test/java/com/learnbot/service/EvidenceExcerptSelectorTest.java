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
}
