package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminSettingsResponse;
import com.learnbot.repository.AppSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSettingsServiceTest {
    @Test
    void llmSettingsUseSystemPrimaryAndAuxiliaryDefaultsWhenNoAdminValuesExist() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setBaseUrl("http://ollama:11434");
        properties.getOllama().setChatModel("qwen3:8b-q4_K_M");
        AdminSettingsService service = serviceWithStore(properties, new HashMap<>());

        AdminSettingsResponse response = service.current();

        assertThat(response.llmUsingDefaults()).isTrue();
        assertThat(response.ollamaBaseUrl()).isBlank();
        assertThat(response.primaryChatModel()).isEqualTo(properties.getOllama().getPrimaryChatModel());
        assertThat(response.auxiliaryChatModel()).isEqualTo(properties.getOllama().getAuxiliaryChatModel());
        assertThat(response.effectiveOllamaBaseUrl()).isEqualTo("http://ollama:11434");
        assertThat(response.effectivePrimaryChatModel()).isEqualTo(properties.getOllama().getPrimaryChatModel());
        assertThat(response.effectiveAuxiliaryChatModel()).isEqualTo(properties.getOllama().getAuxiliaryChatModel());
    }

    @Test
    void llmSettingsNormalizePortOnlyInputForDockerHostFallback() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setBaseUrl("http://ollama:11434");
        properties.getOllama().setChatModel("qwen3:8b-q4_K_M");
        Map<String, String> store = new HashMap<>();
        AdminSettingsService service = serviceWithStore(properties, store);
        AppUser actor = new AppUser(UUID.randomUUID(), "admin", "admin", "ADMIN", "ACTIVE");

        AdminSettingsResponse response = service.update(actor, null, null, "11436", null, "qwen:test", "qwen-small:test");

        assertThat(response.llmUsingDefaults()).isFalse();
        assertThat(response.ollamaBaseUrl()).isEqualTo("http://host.docker.internal:11436");
        assertThat(response.primaryChatModel()).isEqualTo("qwen:test");
        assertThat(response.auxiliaryChatModel()).isEqualTo("qwen-small:test");
        assertThat(response.effectiveOllamaBaseUrl()).isEqualTo("http://host.docker.internal:11436");
        assertThat(response.effectivePrimaryChatModel()).isEqualTo("qwen:test");
        assertThat(response.effectiveAuxiliaryChatModel()).isEqualTo("qwen-small:test");
        assertThat(store).containsEntry("llm.ollamaBaseUrl", "http://host.docker.internal:11436");
        assertThat(store).containsEntry("llm.primaryChatModel", "qwen:test");
        assertThat(store).containsEntry("llm.auxiliaryChatModel", "qwen-small:test");
    }

    @Test
    void blankAdminModelsResolveToPrimaryAndAuxiliaryDefaults() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getOllama().setBaseUrl("http://ollama:11434");
        properties.getOllama().setChatModel("legacy:model");
        properties.getOllama().setPrimaryChatModel("qwen:test");
        properties.getOllama().setAuxiliaryChatModel("qwen-small:test");
        Map<String, String> store = new HashMap<>();
        AdminSettingsService service = serviceWithStore(properties, store);
        AppUser actor = new AppUser(UUID.randomUUID(), "admin", "admin", "ADMIN", "ACTIVE");

        AdminSettingsResponse response = service.update(actor, null, null, "", null, "", "");

        assertThat(response.primaryChatModel()).isBlank();
        assertThat(response.auxiliaryChatModel()).isBlank();
        assertThat(response.effectivePrimaryChatModel()).isEqualTo("qwen:test");
        assertThat(response.effectiveAuxiliaryChatModel()).isEqualTo("qwen-small:test");
        assertThat(service.primaryLlmSettings().model()).isEqualTo("qwen:test");
        assertThat(service.auxiliaryLlmSettings().model()).isEqualTo("qwen-small:test");
    }

    private AdminSettingsService serviceWithStore(LearnBotProperties properties, Map<String, String> store) {
        AppSettingsRepository repository = mock(AppSettingsRepository.class);
        when(repository.findValue(anyString())).thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(repository).upsertValue(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        return new AdminSettingsService(repository, properties, mock(AuditService.class), WebClient.builder());
    }
}
