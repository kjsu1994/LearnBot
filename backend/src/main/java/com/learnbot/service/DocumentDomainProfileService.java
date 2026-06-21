package com.learnbot.service;

import com.learnbot.dto.DocumentSchemaProfileResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DocumentDomainProfileService {
    private static final DocumentDomainProfile CORE_PROFILE = new DocumentDomainProfile(
            DocumentSchemaProfileService.CORE_SCHEMA,
            List.of(DocumentSchemaProfileService.GENERAL_DOCUMENT),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of()
    );

    private static final DocumentDomainProfile SATELLITE_GSE_PROFILE = new DocumentDomainProfile(
            "SATELLITE_GSE",
            List.of(
                    "REQUIREMENT_SPEC",
                    "DESIGN_SPEC",
                    "ICD",
                    "TEST_PROCEDURE",
                    "TEST_RESULT",
                    "OPERATION_MANUAL",
                    "TROUBLESHOOTING_GUIDE"
            ),
            List.of("REQUIREMENT", "FUNCTION", "SOFTWARE_MODULE", "GROUND_EQUIPMENT", "INTERFACE", "COMMAND",
                    "TELEMETRY", "PARAMETER", "TEST_CASE", "ERROR_CODE", "FAULT", "RESOLUTION"),
            List.of("REQUIREMENT_IMPLEMENTED_BY_FUNCTION", "REQUIREMENT_VERIFIED_BY_TEST_CASE", "FUNCTION_USES_INTERFACE",
                    "FUNCTION_SENDS_COMMAND", "FUNCTION_RECEIVES_TELEMETRY", "COMMAND_HAS_PARAMETER",
                    "TELEMETRY_HAS_PARAMETER", "ERROR_CODE_INDICATES_FAULT", "FAULT_RESOLVED_BY_PROCEDURE"),
            Map.of(
                    "REQUIREMENT_SPEC", List.of("requirement", "requirements", "shall", "specification", "요구사항", "요건"),
                    "DESIGN_SPEC", List.of("design", "architecture", "module", "설계", "구조", "아키텍처"),
                    "ICD", List.of("icd", "interface control", "interface", "command", "telemetry", "인터페이스", "명령", "텔레메트리"),
                    "TEST_PROCEDURE", List.of("test procedure", "procedure", "시험 절차", "검증 절차"),
                    "TEST_RESULT", List.of("test result", "test results", "시험 결과", "pass", "fail"),
                    "OPERATION_MANUAL", List.of("operation manual", "manual", "operation", "운용", "사용자", "operator"),
                    "TROUBLESHOOTING_GUIDE", List.of("troubleshooting", "fault", "error code", "장애", "오류", "조치")
            ),
            Map.of(
                    "REQUIREMENT_SPEC", List.of("requirement shall verification 요구사항 요건 검증"),
                    "DESIGN_SPEC", List.of("design architecture module 설계 구조 모듈"),
                    "ICD", List.of("ICD interface command telemetry parameter 인터페이스 명령 텔레메트리 파라미터"),
                    "TEST_PROCEDURE", List.of("test procedure validation 시험 절차 검증 절차"),
                    "TEST_RESULT", List.of("test result pass fail 시험 결과"),
                    "OPERATION_MANUAL", List.of("operation manual operator 운용 매뉴얼 사용자"),
                    "TROUBLESHOOTING_GUIDE", List.of("troubleshooting fault error code resolution 장애 오류 조치")
            ),
            Map.of(
                    "REQUIREMENT", List.of("REQ[-_ ]?\\d+(?:\\.\\d+)*", "요구사항\\s*\\d+(?:\\.\\d+)*"),
                    "COMMAND", List.of("CMD[-_ ][A-Z0-9_]+", "\\b[A-Z][A-Z0-9_]{2,}_CMD\\b"),
                    "TELEMETRY", List.of("TM[-_ ][A-Z0-9_]+", "TLM[-_ ][A-Z0-9_]+", "\\b[A-Z][A-Z0-9_]{2,}_TM\\b"),
                    "PARAMETER", List.of("PARAM[-_ ][A-Z0-9_]+", "parameter\\s+[A-Za-z0-9_]+", "파라미터\\s*[:：]?\\s*[A-Za-z0-9_]+"),
                    "TEST_CASE", List.of("TC[-_ ]?\\d+(?:\\.\\d+)*", "test case\\s*\\d+(?:\\.\\d+)*"),
                    "ERROR_CODE", List.of("ERR[-_ ][A-Z0-9_]+", "E\\d{3,5}", "error code\\s+[A-Za-z0-9_]+"),
                    "FAULT", List.of("fault\\s+[A-Za-z0-9_]+", "장애\\s*[:：]?\\s*[^\\s,.;]{2,40}")
            )
    );

    private final DocumentSchemaProfileService schemaProfileService;

    public DocumentDomainProfileService() {
        this(null);
    }

    @Autowired
    public DocumentDomainProfileService(DocumentSchemaProfileService schemaProfileService) {
        this.schemaProfileService = schemaProfileService;
    }

    public List<DocumentDomainProfile> profiles() {
        Map<String, DocumentDomainProfile> merged = new LinkedHashMap<>();
        merged.put(CORE_PROFILE.schemaName(), CORE_PROFILE);
        merged.put(SATELLITE_GSE_PROFILE.schemaName(), SATELLITE_GSE_PROFILE);
        if (schemaProfileService == null) {
            return List.copyOf(merged.values());
        }
        try {
            for (DocumentSchemaProfileResponse profile : schemaProfileService.listProfiles()) {
                if (!profile.enabled()) {
                    continue;
                }
                DocumentDomainProfile fallback = merged.get(profile.schemaName());
                merged.put(profile.schemaName(), merge(profile, fallback));
            }
        } catch (RuntimeException ignored) {
            return List.copyOf(merged.values());
        }
        return List.copyOf(merged.values());
    }

    public Map<String, List<String>> documentTypeSignals() {
        Map<String, List<String>> signals = new LinkedHashMap<>();
        for (DocumentDomainProfile profile : profiles()) {
            signals.putAll(profile.documentTypeSignals());
        }
        return signals;
    }

    public Set<String> expectedDocumentTypes(String query) {
        String normalized = normalize(query);
        return documentTypeSignals().entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(signal -> contains(normalized, signal)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public List<String> expandedQueries(String query) {
        List<String> expansions = new ArrayList<>();
        Set<String> documentTypes = expectedDocumentTypes(query);
        for (DocumentDomainProfile profile : profiles()) {
            for (String documentType : documentTypes) {
                expansions.addAll(profile.queryAliases().getOrDefault(documentType, List.of()));
            }
        }
        return expansions.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public DocumentDomainProfile profileFor(String schemaName) {
        String safeSchemaName = normalizeName(schemaName);
        return profiles().stream()
                .filter(profile -> normalizeName(profile.schemaName()).equals(safeSchemaName))
                .findFirst()
                .orElse(CORE_PROFILE);
    }

    private DocumentDomainProfile merge(DocumentSchemaProfileResponse profile, DocumentDomainProfile fallback) {
        return new DocumentDomainProfile(
                profile.schemaName(),
                safeList(profile.documentTypes(), fallback == null ? List.of() : fallback.documentTypes()),
                safeList(profile.entityTypes(), fallback == null ? List.of() : fallback.entityTypes()),
                safeList(profile.relationTypes(), fallback == null ? List.of() : fallback.relationTypes()),
                fallback == null ? Map.of() : fallback.documentTypeSignals(),
                fallback == null ? Map.of() : fallback.queryAliases(),
                fallback == null ? Map.of() : fallback.entityPatterns()
        );
    }

    private List<String> safeList(List<String> primary, List<String> fallback) {
        return primary == null || primary.isEmpty() ? fallback : primary;
    }

    private boolean contains(String normalizedText, String signal) {
        String normalizedSignal = normalize(signal);
        return !normalizedSignal.isBlank() && normalizedText.contains(normalizedSignal);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}_-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
