package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSearchServiceTest {
    private final CodeSearchService service = new CodeSearchService(null, null, new LearnBotProperties());

    @Test
    void expandsKoreanLoginQuestionWithCodeAliases() {
        List<String> expanded = service.expandedQueries("로그인 어떻게 동작해?");

        assertThat(expanded)
                .contains("로그인 어떻게 동작해?", "login", "auth", "session", "token", "credential");
    }

    @Test
    void extractsCodeIdentifiersFromNaturalLanguageQuestion() {
        List<String> identifiers = service.identifiersFrom("LoginController.startIndex 메서드 설명해줘");

        assertThat(identifiers).contains("LoginController.startIndex");
    }
}
