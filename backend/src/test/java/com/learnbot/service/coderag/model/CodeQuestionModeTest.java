package com.learnbot.service.coderag.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeQuestionModeTest {
    @Test
    void uiEventInstructionIsFrameworkNeutral() {
        assertThat(CodeQuestionMode.UI_EVENT.instruction()).isEqualTo(
                "Explain UI event flow from cited evidence. Connect controls, events, handlers, bindings, and commands only when their relationship is shown.");
    }
}
