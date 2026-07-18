package com.learnbot.service.coderag.retrieval;

/** General multilingual retrieval policy; it does not classify frameworks, repositories, or question types. */
public final class CodeQueryRewritePolicy {

    public boolean needsSourceVocabularyBridge(String question) {
        if (question == null || question.isBlank()) return false;
        return question.codePoints().anyMatch(this::isNonLatinLetter);
    }

    private boolean isNonLatinLetter(int codePoint) {
        if (!Character.isLetter(codePoint)) return false;
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script != Character.UnicodeScript.LATIN
                && script != Character.UnicodeScript.COMMON
                && script != Character.UnicodeScript.INHERITED;
    }
}
