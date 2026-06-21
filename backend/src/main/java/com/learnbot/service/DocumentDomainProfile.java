package com.learnbot.service;

import java.util.List;
import java.util.Map;

public record DocumentDomainProfile(
        String schemaName,
        List<String> documentTypes,
        List<String> entityTypes,
        List<String> relationTypes,
        Map<String, List<String>> documentTypeSignals,
        Map<String, List<String>> queryAliases,
        Map<String, List<String>> entityPatterns
) {
}
