package com.learnbot.service;

import com.learnbot.dto.DocumentSchemaProfileResponse;
import com.learnbot.dto.DocumentSchemaProfileUpdateRequest;
import com.learnbot.repository.DocumentSchemaProfileRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentSchemaProfileService {
    public static final String CORE_SCHEMA = "CORE";
    public static final String GENERAL_DOCUMENT = "GENERAL_DOCUMENT";

    private static final DocumentSchemaProfileResponse CORE_FALLBACK = new DocumentSchemaProfileResponse(
            new UUID(0L, 0L),
            CORE_SCHEMA,
            "Core document graph fallback profile.",
            List.of(GENERAL_DOCUMENT),
            List.of(),
            List.of(),
            true,
            true,
            OffsetDateTime.MIN
    );

    private final DocumentSchemaProfileRepository repository;

    public DocumentSchemaProfileService(DocumentSchemaProfileRepository repository) {
        this.repository = repository;
    }

    public List<DocumentSchemaProfileResponse> listProfiles() {
        try {
            List<DocumentSchemaProfileResponse> profiles = repository.findAll();
            return profiles.isEmpty() ? List.of(CORE_FALLBACK) : profiles;
        } catch (RuntimeException ex) {
            return List.of(CORE_FALLBACK);
        }
    }

    public DocumentSchemaProfileResponse defaultProfile() {
        try {
            return repository.findDefaultEnabled().orElse(CORE_FALLBACK);
        } catch (RuntimeException ex) {
            return CORE_FALLBACK;
        }
    }

    public DocumentSchemaProfileResponse updateProfile(String schemaName, DocumentSchemaProfileUpdateRequest request) {
        DocumentSchemaProfileResponse current = repository.findByName(normalizeName(schemaName))
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema profile: " + schemaName));
        String description = nonBlank(request.description(), current.description());
        List<String> documentTypes = normalizeTokens(request.documentTypes(), current.documentTypes());
        List<String> entityTypes = normalizeTokens(request.entityTypes(), current.entityTypes());
        List<String> relationTypes = normalizeTokens(request.relationTypes(), current.relationTypes());
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        boolean defaultProfile = request.defaultProfile() == null ? current.defaultProfile() : request.defaultProfile();
        if (documentTypes.isEmpty()) {
            throw new IllegalArgumentException("At least one document type is required.");
        }
        if (defaultProfile && !enabled) {
            throw new IllegalArgumentException("Default schema profile must be enabled.");
        }
        return repository.update(current.schemaName(), description, documentTypes, entityTypes, relationTypes, enabled, defaultProfile);
    }

    public String schemaNameFor(String documentType) {
        String safeDocumentType = normalizeName(documentType);
        for (DocumentSchemaProfileResponse profile : listProfiles()) {
            if (!profile.enabled()) {
                continue;
            }
            if (profile.documentTypes().stream().map(this::normalizeName).anyMatch(safeDocumentType::equals)) {
                return profile.schemaName();
            }
        }
        return defaultProfile().schemaName();
    }

    private List<String> normalizeTokens(List<String> requested, List<String> fallback) {
        List<String> source = requested == null ? fallback : requested;
        return source == null ? List.of() : source.stream()
                .map(this::normalizeName)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
