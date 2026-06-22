package com.learnbot.service;

import com.learnbot.dto.AdminTuningMetricSample;
import com.learnbot.dto.AdminTuningMetricsResponse;
import com.learnbot.dto.AdminTuningMetricsSummary;
import com.learnbot.dto.AdminTuningOllamaStatus;
import com.learnbot.dto.AdminTuningRecommendationChange;
import com.learnbot.dto.AdminTuningRecommendationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class RagMetricsService {
    private static final Logger log = LoggerFactory.getLogger(RagMetricsService.class);
    private static final int WINDOW_SIZE = 160;

    private final ArrayDeque<AdminTuningMetricSample> samples = new ArrayDeque<>();
    private final RuntimeTuningService runtimeTuningService;
    private final AdminSettingsService adminSettingsService;
    private final OllamaClient ollamaClient;

    public RagMetricsService(
            RuntimeTuningService runtimeTuningService,
            AdminSettingsService adminSettingsService,
            OllamaClient ollamaClient
    ) {
        this.runtimeTuningService = runtimeTuningService;
        this.adminSettingsService = adminSettingsService;
        this.ollamaClient = ollamaClient;
    }

    public void record(AdminTuningMetricSample sample) {
        try {
            if (sample == null) {
                return;
            }
            synchronized (samples) {
                samples.addFirst(sample);
                while (samples.size() > WINDOW_SIZE) {
                    samples.removeLast();
                }
            }
        } catch (RuntimeException ex) {
            log.debug("RAG metrics record skipped: {}", ex.getMessage());
        }
    }

    public AdminTuningMetricsResponse current() {
        List<AdminTuningMetricSample> snapshot = snapshot();
        return new AdminTuningMetricsResponse(
                Instant.now(),
                WINDOW_SIZE,
                summary(snapshot),
                ollamaStatus(),
                snapshot
        );
    }

    public void reset() {
        synchronized (samples) {
            samples.clear();
        }
    }

    public AdminTuningRecommendationResponse recommendations() {
        List<AdminTuningMetricSample> snapshot = snapshot();
        if (snapshot.size() < 3) {
            return new AdminTuningRecommendationResponse(
                    Instant.now(),
                    "LOW",
                    "최근 요청 데이터가 부족합니다. 문서/코드 질문을 몇 번 실행한 뒤 다시 확인하세요.",
                    List.of(),
                    List.of("추천은 최근 메모리 계측값만 사용하며 자동 적용되지 않습니다.")
            );
        }

        AdminTuningMetricsSummary summary = summary(snapshot);
        List<AdminTuningRecommendationChange> changes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int contextWindow = runtimeTuningService.llmContextWindow();
        int promptBudget = runtimeTuningService.promptTokenBudgetBalanced();
        int documentLimit = runtimeTuningService.documentContextLimit();
        int codeLimit = runtimeTuningService.codeContextLimit();
        int parallel = runtimeTuningService.ollamaNumParallel();

        if (promptBudget > 0 && summary.avgPromptTokens() >= Math.round(promptBudget * 0.90)) {
            int recommendedBudget = clamp(roundUp(Math.min(contextWindow - 700, Math.max(promptBudget + 512, promptBudget * 4 / 3)), 256), 1024, 32000);
            if (recommendedBudget > promptBudget) {
                changes.add(new AdminTuningRecommendationChange(
                        RuntimeTuningService.PROMPT_TOKEN_BUDGET_BALANCED,
                        promptBudget,
                        recommendedBudget,
                        "최근 프롬프트 토큰 사용량이 예산의 90% 이상입니다.",
                        "근거는 더 들어가지만 응답 시간이 늘 수 있습니다.",
                        false
                ));
            } else if (contextWindow < 8192) {
                changes.add(new AdminTuningRecommendationChange(
                        RuntimeTuningService.LLM_CONTEXT_WINDOW,
                        contextWindow,
                        clamp(contextWindow + 2048, 2048, 32768),
                        "프롬프트 예산이 문맥 길이에 막혀 더 늘리기 어렵습니다.",
                        "메모리 사용량이 증가합니다. Ollama 컨텍스트 길이도 맞춰야 합니다.",
                        false
                ));
            }
        }

        long documentCeilingHits = snapshot.stream()
                .filter(sample -> "document".equalsIgnoreCase(sample.domain()))
                .filter(sample -> sample.contextChunkCount() >= documentLimit)
                .count();
        if (documentCeilingHits >= 2 && documentLimit < 16) {
            changes.add(new AdminTuningRecommendationChange(
                    RuntimeTuningService.DOCUMENT_CONTEXT_LIMIT,
                    documentLimit,
                    Math.min(16, documentLimit + 2),
                    "문서 답변이 설정된 청크 수 상한에 자주 도달했습니다.",
                    "근거는 늘지만 검색 결과가 많을수록 답변이 느려질 수 있습니다.",
                    false
            ));
        }

        long codeCeilingHits = snapshot.stream()
                .filter(sample -> "code".equalsIgnoreCase(sample.domain()))
                .filter(sample -> sample.contextChunkCount() >= codeLimit)
                .count();
        if (codeCeilingHits >= 2 && codeLimit < 24) {
            changes.add(new AdminTuningRecommendationChange(
                    RuntimeTuningService.CODE_CONTEXT_LIMIT,
                    codeLimit,
                    Math.min(24, codeLimit + 4),
                    "코드 답변이 설정된 청크 수 상한에 자주 도달했습니다.",
                    "더 많은 파일/메서드를 보지만 잡음과 지연이 늘 수 있습니다.",
                    false
            ));
        }

        if (ollamaClient.primaryRequestCount() > parallel && parallel < 8) {
            changes.add(new AdminTuningRecommendationChange(
                    RuntimeTuningService.OLLAMA_NUM_PARALLEL,
                    parallel,
                    parallel + 1,
                    "현재 Ollama 처리 대기 추정값이 있습니다.",
                    "GPU/RAM 여유가 없으면 오히려 전체 응답이 느려질 수 있으며 컨테이너 재시작이 필요합니다.",
                    true
            ));
        }

        if (summary.avgEmbeddingMs() > 800) {
            notes.add("임베딩 시간이 높습니다. 반복 질문이 많다면 임베딩 캐시 설정과 모델 상태를 확인하세요.");
        }
        if (summary.avgRerankMs() > 600) {
            notes.add("rerank 시간이 높습니다. 빠른 응답이 우선이면 문서 질문 속도 프로필 FAST 사용을 검토하세요.");
        }
        if (summary.fallbackCount() > 0) {
            notes.add("최근 LLM fallback이 발생했습니다. 모델명, Ollama 주소, 컨테이너 상태를 우선 확인하세요.");
        }
        if (summary.avgLlmSharePercent() >= 70) {
            notes.add("총 시간 중 LLM 생성 비중이 높습니다. 이 경우 검색값을 올리기보다 모델 성능/병렬 처리/출력 길이를 먼저 봐야 합니다.");
        }
        if (changes.isEmpty() && notes.isEmpty()) {
            notes.add("최근 계측 기준으로 즉시 바꿀 튜닝값은 없습니다.");
        }

        return new AdminTuningRecommendationResponse(
                Instant.now(),
                changes.isEmpty() ? "LOW" : "MEDIUM",
                changes.isEmpty() ? "최근 계측 기준으로 보수적 변경만 권장됩니다." : "최근 병목을 기준으로 사용자 지정 튜닝 후보를 만들었습니다.",
                changes,
                notes
        );
    }

    private List<AdminTuningMetricSample> snapshot() {
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    private AdminTuningMetricsSummary summary(List<AdminTuningMetricSample> snapshot) {
        if (snapshot.isEmpty()) {
            return new AdminTuningMetricsSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, runtimeTuningService.promptTokenBudgetBalanced(), 0);
        }
        int count = snapshot.size();
        long total = snapshot.stream().mapToLong(AdminTuningMetricSample::totalMs).sum();
        long llm = snapshot.stream().mapToLong(AdminTuningMetricSample::llmMs).sum();
        List<Long> totals = snapshot.stream().map(AdminTuningMetricSample::totalMs).sorted().toList();
        int p95Index = Math.min(totals.size() - 1, (int) Math.ceil(totals.size() * 0.95) - 1);
        return new AdminTuningMetricsSummary(
                count,
                total / count,
                totals.get(Math.max(0, p95Index)),
                total <= 0 ? 0 : (int) Math.round((double) llm * 100.0 / (double) total),
                snapshot.stream().mapToLong(AdminTuningMetricSample::searchMs).sum() / count,
                snapshot.stream().mapToLong(AdminTuningMetricSample::embeddingMs).sum() / count,
                snapshot.stream().mapToLong(AdminTuningMetricSample::rerankMs).sum() / count,
                (int) Math.round(snapshot.stream().mapToInt(AdminTuningMetricSample::contextChunkCount).average().orElse(0)),
                (int) Math.round(snapshot.stream().mapToInt(AdminTuningMetricSample::promptEvalTokens).average().orElse(0)),
                runtimeTuningService.promptTokenBudgetBalanced(),
                (int) snapshot.stream().filter(sample -> sample.fallbackUsed() || sample.llmUnavailable()).count()
        );
    }

    private AdminTuningOllamaStatus ollamaStatus() {
        AdminSettingsService.LlmSettings primary = adminSettingsService.primaryLlmSettings();
        AdminSettingsService.LlmSettings auxiliary = adminSettingsService.auxiliaryLlmSettings();
        int parallel = runtimeTuningService.ollamaNumParallel();
        int primaryInFlight = ollamaClient.primaryRequestCount();
        return new AdminTuningOllamaStatus(
                primary.baseUrl(),
                primary.model(),
                auxiliary.model(),
                primaryInFlight,
                ollamaClient.embeddingRequestCount(),
                parallel,
                Math.max(0, primaryInFlight - parallel),
                runtimeTuningService.ollamaMaxLoadedModels(),
                runtimeTuningService.llmContextWindow(),
                gpuMode(),
                List.of()
        );
    }

    private String gpuMode() {
        String visibleDevices = System.getenv("NVIDIA_VISIBLE_DEVICES");
        if (visibleDevices == null || visibleDevices.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = visibleDevices.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(normalized) || "void".equals(normalized)) {
            return "NOT_CONFIGURED";
        }
        return "ENABLED";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int roundUp(int value, int step) {
        return ((value + step - 1) / step) * step;
    }
}
