package com.learnbot.service.coderag.model;

/** Stable question modes shared by routing, retrieval, ranking, and answer generation. */
public enum CodeQuestionMode {
    OVERVIEW("overview", "Synthesize search, definitions, references, and nearby chunks. Answer natural-language architecture questions with sections: summary, related files/methods, flow, evidence, and limitations."),
    REASONING("reasoning", "Explain why the implementation appears to be structured this way. Separate direct code evidence from inferred design intent, tradeoffs, and uncertainty."),
    LOCATE("locate", "Find where the requested feature or behavior is implemented. Prioritize files, classes, methods, and line ranges."),
    EXPLAIN_METHOD("method", "Explain the selected or named method. Cover inputs, side effects, called logic, and return/result behavior."),
    CALL_FLOW("flow", "Explain the call flow step by step using only cited code. Keep the sequence compact."),
    UI_EVENT("ui_event", "Explain WPF/WinForms UI event flow. Connect XAML controls/events to code-behind handlers when evidence exists."),
    IMPACT("impact", "Analyze likely impact areas. Separate confirmed evidence from uncertain areas and cite every claim.");

    private final String value;
    private final String instruction;

    CodeQuestionMode(String value, String instruction) {
        this.value = value;
        this.instruction = instruction;
    }

    public static CodeQuestionMode from(String value) {
        if (value == null || value.isBlank()) {
            return LOCATE;
        }
        for (CodeQuestionMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return LOCATE;
    }

    public String value() {
        return value;
    }

    public String instruction() {
        return instruction;
    }
}
