package com.learnbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminSettingsResponse;
import com.learnbot.dto.LlmSettingsTestResponse;
import com.learnbot.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminSettingsService {
    private static final String RESPECT_ROBOTS_TXT_KEY = "crawler.respectRobotsTxt";
    private static final String ALLOWED_DOMAINS_KEY = "crawler.allowedDomains";
    private static final String OLLAMA_BASE_URL_KEY = "llm.ollamaBaseUrl";
    private static final String LEGACY_CHAT_MODEL_KEY = "llm.chatModel";
    private static final String PRIMARY_CHAT_MODEL_KEY = "llm.primaryChatModel";
    private static final String AUXILIARY_CHAT_MODEL_KEY = "llm.auxiliaryChatModel";
    private final AppSettingsRepository settingsRepository;
    private final LearnBotProperties properties;
    private final AuditService auditService;
    private final WebClient.Builder webClientBuilder;

    public AdminSettingsService(
            AppSettingsRepository settingsRepository,
            LearnBotProperties properties,
            AuditService auditService,
            WebClient.Builder webClientBuilder
    ) {
        this.settingsRepository = settingsRepository;
        this.properties = properties;
        this.auditService = auditService;
        this.webClientBuilder = webClientBuilder;
    }

    public AdminSettingsResponse current() {
        LlmSettings primary = primaryLlmSettings();
        LlmSettings auxiliary = auxiliaryLlmSettings();
        return new AdminSettingsResponse(
                isRespectRobotsTxt(),
                allowedDomains(),
                configuredOllamaBaseUrlForDisplay(),
                configuredPrimaryChatModelForDisplay(),
                configuredPrimaryChatModelForDisplay(),
                configuredAuxiliaryChatModelForDisplay(),
                primary.baseUrl(),
                primary.model(),
                primary.model(),
                auxiliary.model(),
                !hasAnyLlmOverride()
        );
    }

    public boolean isRespectRobotsTxt() {
        return settingsRepository.findValue(RESPECT_ROBOTS_TXT_KEY)
                .map(Boolean::parseBoolean)
                .orElse(properties.getCrawler().isRespectRobotsTxt());
    }

    public List<String> allowedDomains() {
        return settingsRepository.findValue(ALLOWED_DOMAINS_KEY)
                .map(this::parseAllowedDomainText)
                .filter(domains -> !domains.isEmpty())
                .orElseGet(() -> normalizeAllowedDomains(properties.getCrawler().getAllowedDomains()));
    }

    public LlmSettings primaryLlmSettings() {
        String configuredBaseUrl = configuredOllamaBaseUrlEffective();
        String configuredModel = configuredPrimaryChatModelEffective();
        boolean hasCustomBaseUrl = hasNonBlankSetting(OLLAMA_BASE_URL_KEY);
        boolean hasCustomModel = hasNonBlankSetting(PRIMARY_CHAT_MODEL_KEY) || hasNonBlankSetting(LEGACY_CHAT_MODEL_KEY);
        return new LlmSettings(
                configuredBaseUrl,
                configuredModel,
                hasCustomBaseUrl || hasCustomModel,
                "primary"
        );
    }

    public LlmSettings auxiliaryLlmSettings() {
        String configuredBaseUrl = configuredOllamaBaseUrlEffective();
        String configuredModel = configuredAuxiliaryChatModelEffective();
        boolean hasCustomBaseUrl = hasNonBlankSetting(OLLAMA_BASE_URL_KEY);
        boolean hasCustomModel = hasNonBlankSetting(AUXILIARY_CHAT_MODEL_KEY);
        return new LlmSettings(
                configuredBaseUrl,
                configuredModel,
                hasCustomBaseUrl || hasCustomModel,
                "auxiliary"
        );
    }

    public LlmSettingsTestResponse testLlmSettings(String baseUrl, String chatModel, String primaryModel, String auxiliaryModel) {
        RequestedLlmSettings settings = resolveRequestedLlmSettings(baseUrl, chatModel, primaryModel, auxiliaryModel);
        try {
            TagsResponse tags = webClientBuilder.clone()
                    .baseUrl(settings.baseUrl)
                    .build()
                    .get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(TagsResponse.class)
                    .block();
            List<String> models = tags == null || tags.models() == null
                    ? List.of()
                    : tags.models().stream()
                    .map(TagModel::name)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
            boolean primaryFound = models.contains(settings.primaryModel);
            boolean auxiliaryFound = models.contains(settings.auxiliaryModel);
            boolean modelFound = primaryFound && auxiliaryFound;
            return new LlmSettingsTestResponse(
                    modelFound,
                    modelFound ? "Ollama 연결을 확인했습니다." : "Ollama에는 연결됐지만 선택한 모델을 찾지 못했습니다.",
                    settings.baseUrl,
                    settings.primaryModel,
                    settings.primaryModel,
                    settings.auxiliaryModel,
                    settings.usingDefaults,
                    models
            );
        } catch (RuntimeException ex) {
            return new LlmSettingsTestResponse(
                    false,
                    "Ollama 연결 실패: " + safeMessage(ex),
                    settings.baseUrl,
                    settings.primaryModel,
                    settings.primaryModel,
                    settings.auxiliaryModel,
                    settings.usingDefaults,
                    List.of()
            );
        }
    }

    public AdminSettingsResponse update(
            AppUser actor,
            Boolean respectRobotsTxt,
            List<String> allowedDomains,
            String ollamaBaseUrl,
            String chatModel,
            String primaryChatModel,
            String auxiliaryChatModel
    ) {
        if (respectRobotsTxt == null && allowedDomains == null && ollamaBaseUrl == null
                && chatModel == null && primaryChatModel == null && auxiliaryChatModel == null) {
            throw new IllegalArgumentException("변경할 관리자 설정이 없습니다.");
        }
        if (respectRobotsTxt != null) {
            settingsRepository.upsertValue(RESPECT_ROBOTS_TXT_KEY, Boolean.toString(respectRobotsTxt), actor.id());
        }
        if (allowedDomains != null) {
            List<String> cleanDomains = normalizeAllowedDomains(allowedDomains);
            if (cleanDomains.isEmpty()) {
                throw new IllegalArgumentException("허용 도메인 또는 URL을 1개 이상 입력하세요.");
            }
            settingsRepository.upsertValue(ALLOWED_DOMAINS_KEY, String.join("\n", cleanDomains), actor.id());
        }
        if (ollamaBaseUrl != null) {
            settingsRepository.upsertValue(OLLAMA_BASE_URL_KEY, normalizeOllamaBaseUrl(ollamaBaseUrl), actor.id());
        }
        if (chatModel != null || primaryChatModel != null) {
            settingsRepository.upsertValue(PRIMARY_CHAT_MODEL_KEY, normalizeChatModel(primaryChatModel != null ? primaryChatModel : chatModel), actor.id());
        }
        if (auxiliaryChatModel != null) {
            settingsRepository.upsertValue(AUXILIARY_CHAT_MODEL_KEY, normalizeChatModel(auxiliaryChatModel), actor.id());
        }
        auditService.log(
                actor,
                "ADMIN_SETTINGS_UPDATE",
                "CRAWLER",
                "crawler",
                null,
                "관리자 설정을 변경했습니다.",
                Map.of(
                        "respectRobotsTxt", isRespectRobotsTxt(),
                        "allowedDomains", allowedDomains(),
                        "llm", Map.of(
                                "ollamaBaseUrl", configuredOllamaBaseUrlForDisplay(),
                                "primaryChatModel", configuredPrimaryChatModelForDisplay(),
                                "auxiliaryChatModel", configuredAuxiliaryChatModelForDisplay()
                        )
                )
        );
        return current();
    }

    private List<String> parseAllowedDomainText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return normalizeAllowedDomains(List.of(text.split("[,\\r\\n]+")));
    }

    private List<String> normalizeAllowedDomains(List<String> values) {
        Set<String> domains = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String domain = normalizeAllowedDomain(value);
            if (domain != null) {
                domains.add(domain);
            }
        }
        return new ArrayList<>(domains);
    }

    private String normalizeAllowedDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.trim().toLowerCase(Locale.ROOT);
        try {
            URI uri = clean.contains("://") ? URI.create(clean) : URI.create("https://" + clean);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                clean = host;
            }
        } catch (IllegalArgumentException ignored) {
            int slash = clean.indexOf('/');
            if (slash >= 0) {
                clean = clean.substring(0, slash);
            }
        }
        if (clean.startsWith("*.")) {
            clean = clean.substring(2);
        }
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isBlank() || clean.contains(" ")) {
            throw new IllegalArgumentException("허용 도메인 형식이 올바르지 않습니다: " + value);
        }
        return clean;
    }

    private RequestedLlmSettings resolveRequestedLlmSettings(String baseUrl, String chatModel, String primaryModel, String auxiliaryModel) {
        String cleanBaseUrl = normalizeOllamaBaseUrl(baseUrl);
        String cleanPrimaryModel = normalizeChatModel(primaryModel != null ? primaryModel : chatModel);
        String cleanAuxiliaryModel = normalizeChatModel(auxiliaryModel);
        boolean hasCustomBaseUrl = !cleanBaseUrl.isBlank();
        boolean hasPrimaryModel = !cleanPrimaryModel.isBlank();
        boolean hasAuxiliaryModel = !cleanAuxiliaryModel.isBlank();
        return new RequestedLlmSettings(
                hasCustomBaseUrl ? cleanBaseUrl : properties.getOllama().getBaseUrl(),
                hasPrimaryModel ? cleanPrimaryModel : defaultPrimaryChatModel(),
                hasAuxiliaryModel ? cleanAuxiliaryModel : defaultAuxiliaryChatModel(),
                !hasCustomBaseUrl && !hasPrimaryModel && !hasAuxiliaryModel
        );
    }

    private String configuredOllamaBaseUrlForDisplay() {
        return settingsRepository.findValue(OLLAMA_BASE_URL_KEY)
                .map(this::normalizeOllamaBaseUrl)
                .orElse("");
    }

    private String configuredOllamaBaseUrlEffective() {
        String value = configuredOllamaBaseUrlForDisplay();
        return value.isBlank() ? properties.getOllama().getBaseUrl() : value;
    }

    private String configuredPrimaryChatModelForDisplay() {
        return settingsRepository.findValue(PRIMARY_CHAT_MODEL_KEY)
                .map(this::normalizeChatModel)
                .or(() -> settingsRepository.findValue(LEGACY_CHAT_MODEL_KEY).map(this::normalizeChatModel))
                .orElse(defaultPrimaryChatModel());
    }

    private String configuredAuxiliaryChatModelForDisplay() {
        return settingsRepository.findValue(AUXILIARY_CHAT_MODEL_KEY)
                .map(this::normalizeChatModel)
                .orElse(defaultAuxiliaryChatModel());
    }

    private String configuredPrimaryChatModelEffective() {
        String value = configuredPrimaryChatModelForDisplay();
        return value.isBlank() ? defaultPrimaryChatModel() : value;
    }

    private String configuredAuxiliaryChatModelEffective() {
        String value = configuredAuxiliaryChatModelForDisplay();
        return value.isBlank() ? defaultAuxiliaryChatModel() : value;
    }

    private boolean hasAnyLlmOverride() {
        return settingsRepository.findValue(OLLAMA_BASE_URL_KEY).isPresent()
                || settingsRepository.findValue(PRIMARY_CHAT_MODEL_KEY).isPresent()
                || settingsRepository.findValue(AUXILIARY_CHAT_MODEL_KEY).isPresent()
                || settingsRepository.findValue(LEGACY_CHAT_MODEL_KEY).isPresent();
    }

    private boolean hasNonBlankSetting(String key) {
        return settingsRepository.findValue(key)
                .map(this::normalizeChatModel)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private String normalizeOllamaBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.trim();
        if (clean.matches("\\d{2,5}")) {
            clean = "http://host.docker.internal:" + clean;
        } else if (!clean.contains("://")) {
            clean = "http://" + clean;
        }
        URI uri;
        try {
            uri = URI.create(clean);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Ollama 주소 형식이 올바르지 않습니다: " + value);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Ollama 주소에는 호스트가 필요합니다.");
        }
        if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Ollama 주소는 http 또는 https만 사용할 수 있습니다.");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeChatModel(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultPrimaryChatModel() {
        String value = normalizeChatModel(properties.getOllama().getPrimaryChatModel());
        return value.isBlank() ? properties.getOllama().getChatModel() : value;
    }

    private String defaultAuxiliaryChatModel() {
        String value = normalizeChatModel(properties.getOllama().getAuxiliaryChatModel());
        return value.isBlank() ? properties.getOllama().getChatModel() : value;
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public record LlmSettings(String baseUrl, String model, boolean customized, String role) {
    }

    private record RequestedLlmSettings(String baseUrl, String primaryModel, String auxiliaryModel, boolean usingDefaults) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TagsResponse(List<TagModel> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TagModel(String name) {
    }
}
