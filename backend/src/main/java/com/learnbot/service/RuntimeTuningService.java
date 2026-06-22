package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminTuningPreset;
import com.learnbot.dto.AdminTuningResponse;
import com.learnbot.dto.AdminTuningSetting;
import com.learnbot.dto.AdminTuningUpdateRequest;
import com.learnbot.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RuntimeTuningService {
    private static final String PREFIX = "tuning.";
    private static final String ACTIVE_PRESET_KEY = PREFIX + "activePreset";

    public static final String LLM_CONTEXT_WINDOW = "LLM_CONTEXT_WINDOW";
    public static final String OLLAMA_CONTEXT_LENGTH = "OLLAMA_CONTEXT_LENGTH";
    public static final String PROMPT_TOKEN_BUDGET_BALANCED = "RAG_PIPELINE_PROMPT_TOKEN_BUDGET_BALANCED";
    public static final String CODE_CONTEXT_LIMIT = "RAG_PIPELINE_CODE_CONTEXT_LIMIT";
    public static final String DOCUMENT_CONTEXT_LIMIT = "RAG_PIPELINE_DOCUMENT_CONTEXT_LIMIT";
    public static final String OVERVIEW_MAX_DOCUMENTS = "LEARNBOT_RAG_OVERVIEW_MAX_DOCUMENTS";
    public static final String OVERVIEW_MAX_CODE_CATEGORIES = "LEARNBOT_RAG_OVERVIEW_MAX_CODE_CATEGORIES";
    public static final String OVERVIEW_MAX_RECURSIVE_ITERATIONS = "LEARNBOT_RAG_OVERVIEW_MAX_RECURSIVE_ITERATIONS";
    public static final String LLM_MAX_OUTPUT_TOKENS = "LLM_MAX_OUTPUT_TOKENS";
    public static final String OLLAMA_MAX_LOADED_MODELS = "OLLAMA_MAX_LOADED_MODELS";
    public static final String OLLAMA_NUM_PARALLEL = "OLLAMA_NUM_PARALLEL";

    private final AppSettingsRepository settingsRepository;
    private final LearnBotProperties properties;
    private final AdminSettingsService adminSettingsService;
    private final AuditService auditService;

    public RuntimeTuningService(
            AppSettingsRepository settingsRepository,
            LearnBotProperties properties,
            AdminSettingsService adminSettingsService,
            AuditService auditService
    ) {
        this.settingsRepository = settingsRepository;
        this.properties = properties;
        this.adminSettingsService = adminSettingsService;
        this.auditService = auditService;
    }

    public AdminTuningResponse current() {
        List<AdminTuningSetting> settings = definitions().stream().map(this::toResponse).toList();
        AdminSettingsService.LlmSettings primary = adminSettingsService.primaryLlmSettings();
        AdminSettingsService.LlmSettings auxiliary = adminSettingsService.auxiliaryLlmSettings();
        boolean usingDefaults = settings.stream().allMatch(item -> item.value() == item.defaultValue())
                && !primary.customized()
                && !auxiliary.customized();
        return new AdminTuningResponse(
                activePreset(settings),
                usingDefaults,
                primary.customized() ? primary.baseUrl() : "",
                primary.customized() ? primary.model() : "",
                auxiliary.customized() ? auxiliary.model() : "",
                primary.baseUrl(),
                primary.model(),
                auxiliary.model(),
                settings,
                presets(),
                warnings(settings)
        );
    }

    public AdminTuningResponse update(AppUser actor, AdminTuningUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No tuning settings were provided.");
        }
        if (request.ollamaBaseUrl() != null || request.primaryChatModel() != null || request.auxiliaryChatModel() != null) {
            adminSettingsService.update(actor, null, null, request.ollamaBaseUrl(), null, request.primaryChatModel(), request.auxiliaryChatModel());
        }

        Map<String, Integer> nextValues = new LinkedHashMap<>();
        if ("custom".equalsIgnoreCase(request.preset())) {
            settingsRepository.upsertValue(ACTIVE_PRESET_KEY, "custom", actor.id());
        } else if (request.preset() != null && !request.preset().isBlank()) {
            AdminTuningPreset preset = presets().stream()
                    .filter(item -> item.id().equals(request.preset()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tuning preset: " + request.preset()));
            nextValues.putAll(preset.values());
            settingsRepository.upsertValue(ACTIVE_PRESET_KEY, preset.id(), actor.id());
        }
        if (request.values() != null) {
            nextValues.putAll(request.values());
            settingsRepository.upsertValue(ACTIVE_PRESET_KEY, "custom", actor.id());
        }
        for (Map.Entry<String, Integer> entry : nextValues.entrySet()) {
            TuningDefinition definition = definition(entry.getKey());
            int value = validate(definition, entry.getValue(), nextValues);
            settingsRepository.upsertValue(settingKey(definition.key()), Integer.toString(value), actor.id());
        }
        auditService.log(
                actor,
                "ADMIN_TUNING_UPDATE",
                "TUNING",
                "global",
                null,
                "Admin tuning settings were updated.",
                Map.of("preset", request.preset() == null ? "custom" : request.preset(), "values", nextValues)
        );
        return current();
    }

    public int llmContextWindow() {
        return value(LLM_CONTEXT_WINDOW);
    }

    public int promptTokenBudgetBalanced() {
        return value(PROMPT_TOKEN_BUDGET_BALANCED);
    }

    public int codeContextLimit() {
        return value(CODE_CONTEXT_LIMIT);
    }

    public int documentContextLimit() {
        return value(DOCUMENT_CONTEXT_LIMIT);
    }

    public int overviewMaxDocuments() {
        return value(OVERVIEW_MAX_DOCUMENTS);
    }

    public int overviewMaxCodeCategories() {
        return value(OVERVIEW_MAX_CODE_CATEGORIES);
    }

    public int overviewMaxRecursiveIterations() {
        return value(OVERVIEW_MAX_RECURSIVE_ITERATIONS);
    }

    public int llmMaxOutputTokens() {
        return value(LLM_MAX_OUTPUT_TOKENS);
    }

    public int ollamaMaxLoadedModels() {
        return value(OLLAMA_MAX_LOADED_MODELS);
    }

    public int ollamaNumParallel() {
        return value(OLLAMA_NUM_PARALLEL);
    }

    private int value(String key) {
        TuningDefinition definition = definition(key);
        return settingsRepository.findValue(settingKey(key))
                .map(raw -> parseInt(raw, definition.defaultValue()))
                .map(raw -> clamp(raw, definition.min(), definition.max()))
                .orElse(definition.defaultValue());
    }

    private AdminTuningSetting toResponse(TuningDefinition definition) {
        int effective = value(definition.key());
        return new AdminTuningSetting(
                definition.key(),
                definition.label(),
                definition.description(),
                definition.category(),
                definition.control(),
                effective,
                effective,
                definition.defaultValue(),
                definition.min(),
                definition.max(),
                definition.step(),
                definition.restartRequired(),
                definition.impact(),
                definition.envKey()
        );
    }

    private List<AdminTuningPreset> presets() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        for (TuningDefinition definition : definitions()) {
            defaults.put(definition.key(), definition.defaultValue());
        }

        Map<String, Integer> slightlyHigh = new LinkedHashMap<>(defaults);
        slightlyHigh.put(LLM_CONTEXT_WINDOW, 6144);
        slightlyHigh.put(OLLAMA_CONTEXT_LENGTH, 6144);
        slightlyHigh.put(PROMPT_TOKEN_BUDGET_BALANCED, 5000);
        slightlyHigh.put(CODE_CONTEXT_LIMIT, 16);
        slightlyHigh.put(DOCUMENT_CONTEXT_LIMIT, 10);
        slightlyHigh.put(OVERVIEW_MAX_DOCUMENTS, 16);
        slightlyHigh.put(OVERVIEW_MAX_CODE_CATEGORIES, 14);
        slightlyHigh.put(OVERVIEW_MAX_RECURSIVE_ITERATIONS, 3);
        slightlyHigh.put(LLM_MAX_OUTPUT_TOKENS, 0);
        slightlyHigh.put(OLLAMA_MAX_LOADED_MODELS, 1);
        slightlyHigh.put(OLLAMA_NUM_PARALLEL, 1);

        Map<String, Integer> performance = new LinkedHashMap<>(slightlyHigh);
        performance.put(LLM_CONTEXT_WINDOW, 8192);
        performance.put(OLLAMA_CONTEXT_LENGTH, 8192);
        performance.put(PROMPT_TOKEN_BUDGET_BALANCED, 6500);
        performance.put(DOCUMENT_CONTEXT_LIMIT, 14);
        performance.put(CODE_CONTEXT_LIMIT, 20);
        performance.put(OVERVIEW_MAX_DOCUMENTS, 24);
        performance.put(OVERVIEW_MAX_CODE_CATEGORIES, 20);
        performance.put(OVERVIEW_MAX_RECURSIVE_ITERATIONS, 4);
        performance.put(LLM_MAX_OUTPUT_TOKENS, 0);

        return List.of(
                new AdminTuningPreset("default", "Default", "Current server defaults.", defaults),
                new AdminTuningPreset("slightly_high", "Slightly higher", "Slightly broader evidence and answer budget than defaults.", slightlyHigh),
                new AdminTuningPreset("performance", "Performance", "Large context and broad evidence. May be slower.", performance)
        );
    }

    private String activePreset(List<AdminTuningSetting> settings) {
        String stored = settingsRepository.findValue(ACTIVE_PRESET_KEY).orElse("default");
        if ("custom".equals(stored)) {
            return "custom";
        }
        Map<String, Integer> current = new LinkedHashMap<>();
        for (AdminTuningSetting setting : settings) {
            current.put(setting.key(), setting.value());
        }
        return presets().stream()
                .filter(preset -> preset.values().equals(current))
                .map(AdminTuningPreset::id)
                .findFirst()
                .orElse("custom");
    }

    private List<String> warnings(List<AdminTuningSetting> settings) {
        List<String> warnings = new ArrayList<>();
        int llmContext = settings.stream().filter(item -> LLM_CONTEXT_WINDOW.equals(item.key())).findFirst().map(AdminTuningSetting::value).orElse(properties.getOllama().getContextWindow());
        int ollamaContext = settings.stream().filter(item -> OLLAMA_CONTEXT_LENGTH.equals(item.key())).findFirst().map(AdminTuningSetting::value).orElse(llmContext);
        if (llmContext != ollamaContext) {
            warnings.add("LLM context window and Ollama context length differ. The smaller value may effectively limit prompts.");
        }
        if (settings.stream().anyMatch(item -> item.restartRequired() && item.value() != item.defaultValue())) {
            warnings.add("재시작 후에 해당 튜닝값이 적용됩니다.");
        }
        return warnings;
    }

    private int validate(TuningDefinition definition, Integer value, Map<String, Integer> requestedValues) {
        if (value == null) {
            throw new IllegalArgumentException(definition.label() + " is required.");
        }
        if (value < definition.min() || value > definition.max()) {
            throw new IllegalArgumentException(definition.label() + " must be between " + definition.min() + " and " + definition.max() + ".");
        }
        if (PROMPT_TOKEN_BUDGET_BALANCED.equals(definition.key())) {
            int contextWindow = Math.max(2048, requestedValues.getOrDefault(
                    LLM_CONTEXT_WINDOW,
                    valueOrDefault(LLM_CONTEXT_WINDOW, properties.getOllama().getContextWindow())
            ));
            if (value > contextWindow - 700) {
                throw new IllegalArgumentException("Prompt budget must be at least 700 tokens smaller than the LLM context window.");
            }
        }
        return value;
    }

    private int valueOrDefault(String key, int fallback) {
        return settingsRepository.findValue(settingKey(key)).map(raw -> parseInt(raw, fallback)).orElse(fallback);
    }

    private TuningDefinition definition(String key) {
        return definitions().stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported tuning key: " + key));
    }

    private List<TuningDefinition> definitions() {
        LearnBotProperties.Rag.Pipeline pipeline = properties.getRag().getPipeline();
        LearnBotProperties.Rag.Overview overview = properties.getRag().getOverview();
        int contextWindow = properties.getOllama().getContextWindow();
        return List.of(
                new TuningDefinition(LLM_CONTEXT_WINDOW, "LLM context window", "Maximum prompt context sent to the model.", "LLM", "range", contextWindow, 2048, 32768, 512, false, "Higher values allow more evidence but use more memory.", LLM_CONTEXT_WINDOW),
                new TuningDefinition(OLLAMA_CONTEXT_LENGTH, "Ollama context length", "Ollama daemon context length.", "LLM", "range", envInt(OLLAMA_CONTEXT_LENGTH, contextWindow), 2048, 32768, 512, true, "Requires container restart. Keep it aligned with LLM context window.", OLLAMA_CONTEXT_LENGTH),
                new TuningDefinition(PROMPT_TOKEN_BUDGET_BALANCED, "Document prompt token budget", "Token budget for document evidence and question.", "RAG", "range", pipeline.getPromptTokenBudgetBalanced(), 1024, 32000, 256, false, "Higher values include more evidence but may be slower.", PROMPT_TOKEN_BUDGET_BALANCED),
                new TuningDefinition(DOCUMENT_CONTEXT_LIMIT, "Document answer chunk count", "Evidence chunks used for document answers.", "RAG", "range", pipeline.getDocumentContextLimit(), 2, 16, 1, false, "Higher values add evidence and latency.", DOCUMENT_CONTEXT_LIMIT),
                new TuningDefinition(CODE_CONTEXT_LIMIT, "Code answer chunk count", "Evidence chunks used for code answers.", "RAG", "range", pipeline.getCodeContextLimit(), 4, 24, 1, false, "Higher values include more files and methods.", CODE_CONTEXT_LIMIT),
                new TuningDefinition(OVERVIEW_MAX_DOCUMENTS, "Overview document count", "Different documents included in overview-style answers.", "Overview", "range", overview.getMaxDocuments(), 4, 32, 1, false, "Higher values improve broad context and add latency.", OVERVIEW_MAX_DOCUMENTS),
                new TuningDefinition(OVERVIEW_MAX_CODE_CATEGORIES, "Code overview category count", "Different code evidence categories for architecture and flow answers.", "Overview", "range", overview.getMaxCodeCategories(), 4, 24, 1, false, "Higher values broaden code answers and may add noise.", OVERVIEW_MAX_CODE_CATEGORIES),
                new TuningDefinition(OVERVIEW_MAX_RECURSIVE_ITERATIONS, "Overview search iterations", "Additional overview search passes.", "Overview", "select", overview.getMaxRecursiveIterations(), 1, 5, 1, false, "Higher values may improve quality but increase latency.", OVERVIEW_MAX_RECURSIVE_ITERATIONS),
                new TuningDefinition(LLM_MAX_OUTPUT_TOKENS, "Max answer length", "Maximum generated answer tokens. 0 means no explicit limit.", "LLM", "number", properties.getOllama().getMaxOutputTokens(), 0, 4096, 128, false, "Too low can truncate answers; too high may be slower.", LLM_MAX_OUTPUT_TOKENS),
                new TuningDefinition(OLLAMA_MAX_LOADED_MODELS, "Loaded model count", "Number of models kept loaded by Ollama.", "Ollama", "select", envInt(OLLAMA_MAX_LOADED_MODELS, 1), 1, 4, 1, true, "Use 1 unless VRAM/RAM is sufficient.", OLLAMA_MAX_LOADED_MODELS),
                new TuningDefinition(OLLAMA_NUM_PARALLEL, "Parallel Ollama requests", "Parallel requests handled by Ollama.", "Ollama", "select", envInt(OLLAMA_NUM_PARALLEL, 1), 1, 8, 1, true, "Use 1 on small machines; increase only on larger GPU servers.", OLLAMA_NUM_PARALLEL)
        );
    }

    private int envInt(String key, int fallback) {
        return parseInt(System.getenv(key), fallback);
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String settingKey(String key) {
        return PREFIX + key.toLowerCase(Locale.ROOT);
    }

    private record TuningDefinition(
            String key,
            String label,
            String description,
            String category,
            String control,
            int defaultValue,
            int min,
            int max,
            int step,
            boolean restartRequired,
            String impact,
            String envKey
    ) {
    }
}
