package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminTuningUpdateRequest;
import com.learnbot.repository.AppSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeTuningServiceTest {
    @Test
    void customPresetWithoutValuesPersistsAllCurrentNumericSettings() {
        Map<String, String> store = new HashMap<>();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setContextWindow(12288);
        properties.getRag().getPipeline().setPromptTokenBudgetBalanced(9000);
        RuntimeTuningService service = serviceWithStore(properties, store);
        AppUser actor = new AppUser(UUID.randomUUID(), "admin", "admin", "ADMIN", "ACTIVE");

        service.update(actor, new AdminTuningUpdateRequest("custom", null, null, null, null));

        assertThat(store).containsEntry("tuning.activePreset", "custom");
        assertThat(store).containsEntry("tuning.llm_context_window", "12288");
        assertThat(store).containsEntry("tuning.ollama_context_length", "12288");
        assertThat(store).containsEntry("tuning.rag_pipeline_prompt_token_budget_balanced", "9000");
        assertThat(store).containsKey("tuning.rag_pipeline_code_context_limit");
    }

    @Test
    void customValuesMergeWithCurrentSettingsAndPersistCompleteSnapshot() {
        Map<String, String> store = new HashMap<>();
        store.put("tuning.llm_context_window", "12288");
        store.put("tuning.ollama_context_length", "12288");
        LearnBotProperties properties = new LearnBotProperties();
        RuntimeTuningService service = serviceWithStore(properties, store);
        AppUser actor = new AppUser(UUID.randomUUID(), "admin", "admin", "ADMIN", "ACTIVE");

        service.update(actor, new AdminTuningUpdateRequest(
                "custom",
                null,
                null,
                null,
                Map.of(RuntimeTuningService.PROMPT_TOKEN_BUDGET_BALANCED, 9000)
        ));

        assertThat(store).containsEntry("tuning.activePreset", "custom");
        assertThat(store).containsEntry("tuning.llm_context_window", "12288");
        assertThat(store).containsEntry("tuning.ollama_context_length", "12288");
        assertThat(store).containsEntry("tuning.rag_pipeline_prompt_token_budget_balanced", "9000");
        assertThat(store).containsKey("tuning.rag_pipeline_document_context_limit");
    }

    private RuntimeTuningService serviceWithStore(LearnBotProperties properties, Map<String, String> store) {
        AppSettingsRepository repository = mock(AppSettingsRepository.class);
        when(repository.findValue(anyString())).thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(repository).upsertValue(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        AdminSettingsService adminSettingsService = mock(AdminSettingsService.class);
        when(adminSettingsService.primaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings(
                properties.getOllama().getBaseUrl(),
                properties.getOllama().getPrimaryChatModel(),
                false,
                "primary"
        ));
        when(adminSettingsService.auxiliaryLlmSettings()).thenReturn(new AdminSettingsService.LlmSettings(
                properties.getOllama().getBaseUrl(),
                properties.getOllama().getAuxiliaryChatModel(),
                false,
                "auxiliary"
        ));
        return new RuntimeTuningService(repository, properties, adminSettingsService, mock(AuditService.class));
    }
}
