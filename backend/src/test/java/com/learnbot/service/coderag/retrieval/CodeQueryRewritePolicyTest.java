package com.learnbot.service.coderag.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeQueryRewritePolicyTest {
    private final CodeQueryRewritePolicy policy = new CodeQueryRewritePolicy();

    @Test
    void nonLatinQuestionsUseTheSourceVocabularyBridge() {
        assertThat(policy.needsSourceVocabularyBridge("심볼 참조 흐름을 추적해줘")).isTrue();
        assertThat(policy.needsSourceVocabularyBridge("Сопоставь вызовы методов")).isTrue();
        assertThat(policy.needsSourceVocabularyBridge("呼び出しフローを追跡して")).isTrue();
    }

    @Test
    void latinSourceVocabularyDoesNotSpendARewriteCall() {
        assertThat(policy.needsSourceVocabularyBridge(
                "Trace symbol reference calls from Controller to Repository")).isFalse();
        assertThat(policy.needsSourceVocabularyBridge("CodeController.ask 호출 flow")).isTrue();
        assertThat(policy.needsSourceVocabularyBridge("  ")).isFalse();
    }
}
