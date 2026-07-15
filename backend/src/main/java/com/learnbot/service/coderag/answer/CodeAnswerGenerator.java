package com.learnbot.service.coderag.answer;

/**
 * Provider-neutral boundary for initial, repair, and continuation answer calls.
 *
 * <p>Prompt construction, retry decisions, continuation overlap removal, and fallback
 * selection remain orchestrator responsibilities.</p>
 */
public interface CodeAnswerGenerator {
    GenerationResult generate(GenerationRequest request);

    GenerationResult stream(GenerationRequest request, DeltaSink deltaSink);

    enum Phase {
        INITIAL,
        REPAIR,
        CONTINUATION
    }

    record GenerationRequest(
            Phase phase,
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens
    ) {
        public GenerationRequest {
            phase = phase == null ? Phase.INITIAL : phase;
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            userPrompt = userPrompt == null ? "" : userPrompt;
            if (maxOutputTokens < 0) {
                throw new IllegalArgumentException("maxOutputTokens must not be negative");
            }
        }

        public static GenerationRequest initial(String systemPrompt, String userPrompt, int maxOutputTokens) {
            return new GenerationRequest(Phase.INITIAL, systemPrompt, userPrompt, maxOutputTokens);
        }

        public static GenerationRequest repair(String systemPrompt, String userPrompt, int maxOutputTokens) {
            return new GenerationRequest(Phase.REPAIR, systemPrompt, userPrompt, maxOutputTokens);
        }

        public static GenerationRequest continuation(String systemPrompt, String userPrompt, int maxOutputTokens) {
            return new GenerationRequest(Phase.CONTINUATION, systemPrompt, userPrompt, maxOutputTokens);
        }
    }

    record GenerationResult(
            String answer,
            String doneReason,
            boolean done,
            int promptTokens,
            int outputTokens,
            String baseUrl,
            String model,
            String role,
            boolean fallbackUsed
    ) {
        public GenerationResult {
            answer = answer == null ? "" : answer;
            promptTokens = Math.max(0, promptTokens);
            outputTokens = Math.max(0, outputTokens);
            baseUrl = baseUrl == null ? "" : baseUrl;
            model = model == null ? "" : model;
            role = role == null || role.isBlank() ? "primary" : role;
        }

        public boolean stoppedByLength() {
            return "length".equalsIgnoreCase(doneReason);
        }
    }

    @FunctionalInterface
    interface DeltaSink {
        void onDelta(String text);
    }
}
