package com.learnbot.service.coderag.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeRagLlmCallBudgetTest {
    @Test
    void reservesTheFinalCallForAnswerGeneration() {
        try (CodeRagLlmCallBudget.Scope scope = CodeRagLlmCallBudget.open(5, 1)) {
            for (int index = 0; index < 4; index++) {
                CodeRagLlmCallBudget.acquirePlanning("planner-" + index);
            }

            assertThatThrownBy(() -> CodeRagLlmCallBudget.acquirePlanning("extra verifier"))
                    .isInstanceOf(CodeRagLlmCallBudget.BudgetExceededException.class);
            CodeRagLlmCallBudget.acquireGeneration("generator");
            assertThat(scope.used()).isEqualTo(5);
        }
    }

    @Test
    void neverAllowsMoreThanFiveCalls() {
        try (CodeRagLlmCallBudget.Scope scope = CodeRagLlmCallBudget.open(5, 1)) {
            CodeRagLlmCallBudget.acquirePlanning("planner");
            CodeRagLlmCallBudget.acquireGeneration("generator");
            CodeRagLlmCallBudget.acquireGeneration("repair");
            CodeRagLlmCallBudget.acquireGeneration("continuation");
            CodeRagLlmCallBudget.acquireGeneration("final repair");

            assertThatThrownBy(() -> CodeRagLlmCallBudget.acquireGeneration("overflow"))
                    .isInstanceOf(CodeRagLlmCallBudget.BudgetExceededException.class);
            assertThat(scope.used()).isEqualTo(5);
        }
    }
}
