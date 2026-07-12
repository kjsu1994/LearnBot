package com.learnbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeLanguageCatalogTest {
    @Test
    void recognizesLanguagesWithoutRepositorySpecificRules() {
        assertThat(CodeLanguageCatalog.languageForPath("src/App.java")).isEqualTo("java");
        assertThat(CodeLanguageCatalog.languageForPath("src/App.cs")).isEqualTo("csharp");
        assertThat(CodeLanguageCatalog.languageForPath("src/app.py")).isEqualTo("python");
        assertThat(CodeLanguageCatalog.languageForPath("src/lib.rs")).isEqualTo("rust");
        assertThat(CodeLanguageCatalog.languageForPath("src/main.cpp")).isEqualTo("cpp");
        assertThat(CodeLanguageCatalog.languageForPath("unknown.custom")).isEqualTo("other");
    }
}
