package com.learnbot.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentEntityMentionExtractor {
    private static final int MAX_MENTIONS_PER_CHUNK = 12;

    private final DocumentDomainProfileService domainProfileService;

    public DocumentEntityMentionExtractor() {
        this(new DocumentDomainProfileService());
    }

    @Autowired
    public DocumentEntityMentionExtractor(DocumentDomainProfileService domainProfileService) {
        this.domainProfileService = domainProfileService;
    }

    public List<EntityMention> extract(String text, Map<String, Object> metadata) {
        String schemaName = string(metadata, "schemaName");
        String documentType = string(metadata, "documentType");
        DocumentDomainProfile profile = domainProfileService.profileFor(schemaName);
        if (profile.entityPatterns().isEmpty() || text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<EntityMention> mentions = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : profile.entityPatterns().entrySet()) {
            if (!profile.entityTypes().isEmpty() && !profile.entityTypes().contains(entry.getKey())) {
                continue;
            }
            for (String pattern : entry.getValue()) {
                Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
                while (matcher.find()) {
                    String value = matcher.group().trim();
                    String key = entry.getKey() + "::" + value.toUpperCase(java.util.Locale.ROOT);
                    if (value.length() < 2 || !seen.add(key)) {
                        continue;
                    }
                    mentions.add(new EntityMention(entry.getKey(), value, schemaName, documentType));
                    if (mentions.size() >= MAX_MENTIONS_PER_CHUNK) {
                        return mentions;
                    }
                }
            }
        }
        return mentions;
    }

    private String string(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record EntityMention(String entityType, String value, String schemaName, String documentType) {
    }
}
