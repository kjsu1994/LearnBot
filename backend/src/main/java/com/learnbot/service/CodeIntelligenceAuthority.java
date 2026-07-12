package com.learnbot.service;

import java.util.Locale;

public enum CodeIntelligenceAuthority {
    COMPILER_SEMANTIC(600),
    SCIP_SEMANTIC(500),
    LSP_SEMANTIC(450),
    SYNTAX(300),
    LEXICAL(200),
    LLM_INFERRED(100),
    UNKNOWN(0);

    private final int rank;

    CodeIntelligenceAuthority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static CodeIntelligenceAuthority from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
