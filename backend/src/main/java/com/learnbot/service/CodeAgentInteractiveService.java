package com.learnbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationDetail;
import com.learnbot.dto.RagConversationSummary;
import com.learnbot.dto.RagConversationTurn;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.dto.interactive.CodeAgentInteractiveContextReadResultRequest;
import com.learnbot.dto.interactive.CodeAgentInteractiveTurnRequest;
import com.learnbot.dto.interactive.CodeAgentInteractiveTurnResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CodeAgentInteractiveService {
    private static final int INTENT_OUTPUT_TOKENS = 900;

    private final RagConversationService conversationService;
    private final CodeRagService codeRagService;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public CodeAgentInteractiveService(
            RagConversationService conversationService,
            CodeRagService codeRagService,
            OllamaClient ollamaClient,
            ObjectMapper objectMapper
    ) {
        this.conversationService = conversationService;
        this.codeRagService = codeRagService;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public CodeAgentInteractiveTurnResponse handleTurn(
            AppUser user,
            UUID selectedSpaceId,
            List<UUID> accessibleSpaceIds,
            CodeAgentInteractiveTurnRequest request
    ) {
        List<String> warnings = new ArrayList<>();
        String message = safe(request.message());
        RagConversationContext context = conversationService.prepare(
                user,
                selectedSpaceId,
                RagConversationService.CODE,
                request.repositoryId(),
                request.conversationId(),
                message,
                true
        );
        IntentDecision decision = classifyIntent(message, request.intentHint(), context, warnings);
        return switch (decision.intent()) {
            case "ANSWER_ONLY" -> answerOnly(user, selectedSpaceId, accessibleSpaceIds, request, context, warnings);
            case "ADVISE" -> adviceResponse(request, context, decision, warnings);
            case "REVIEW" -> commandResponse(request, context, decision, "review", warnings);
            case "FIX" -> commandResponse(request, context, decision, "fix", warnings);
            case "READ_CONTEXT" -> readContextResponse(request, context, decision, warnings);
            default -> clarification(request, context, decision, warnings);
        };
    }

    public Map<String, Object> saveContextReadResult(
            AppUser user,
            UUID selectedSpaceId,
            CodeAgentInteractiveContextReadResultRequest request
    ) {
        Map<String, Object> loadedContext = new LinkedHashMap<>();
        loadedContext.put("schema", "learnbot.server.code-agent.loaded-context.v1");
        loadedContext.put("agentId", request.agentId());
        loadedContext.put("workspaceId", request.workspaceId());
        loadedContext.put("files", request.files() == null ? List.of() : request.files());
        loadedContext.put("toolResults", request.toolResults() == null ? List.of() : request.toolResults());
        loadedContext.put("warnings", request.warnings() == null ? List.of() : request.warnings());
        conversationService.updateTurnMetadata(
                user,
                selectedSpaceId,
                RagConversationService.CODE,
                request.repositoryId(),
                request.conversationId(),
                request.turnId(),
                "loadedContext",
                objectMapper.valueToTree(loadedContext)
        );
        RagConversationTurn turn = conversationService.requireTurn(
                user,
                selectedSpaceId,
                RagConversationService.CODE,
                request.repositoryId(),
                request.conversationId(),
                request.turnId()
        );
        if ("agent_advice".equals(turn.mode())) {
            Map<String, Object> next = continueAdviceAfterContextRead(user, selectedSpaceId, request, turn, loadedContext);
            if (!next.isEmpty()) {
                return next;
            }
        }
        return sessionContext(user, selectedSpaceId, request.repositoryId(), request.conversationId());
    }

    public Map<String, Object> sessionContext(AppUser user, UUID selectedSpaceId, UUID repositoryId, UUID conversationId) {
        RagConversationDetail detail = conversationService.detail(user, conversationId);
        List<Map<String, Object>> loaded = detail.turns().stream()
                .filter(turn -> turn.metadata() != null && turn.metadata().has("loadedContext"))
                .map(this::loadedContextSummary)
                .toList();
        return Map.of(
                "schema", "learnbot.server.code-agent.session-context.v1",
                "conversationId", conversationId,
                "repositoryId", repositoryId,
                "spaceId", selectedSpaceId,
                "loadedContexts", loaded
        );
    }

    public List<RagConversationSummary> recentSessions(AppUser user, UUID selectedSpaceId) {
        return conversationService.list(user, selectedSpaceId, RagConversationService.CODE);
    }

    private CodeAgentInteractiveTurnResponse answerOnly(
            AppUser user,
            UUID selectedSpaceId,
            List<UUID> accessibleSpaceIds,
            CodeAgentInteractiveTurnRequest request,
            RagConversationContext context,
            List<String> warnings
    ) {
        CodeAskResponse response = codeRagService.askConversational(
                request.repositoryId(),
                selectedSpaceId,
                accessibleSpaceIds,
                request.message(),
                "balanced",
                null,
                context
        );
        CodeAskResponse saved = conversationService.saveCodeTurn(context, request.parentTurnId(), request.message(), response);
        return response(
                saved.conversationId(),
                saved.turnId(),
                "ANSWER_ONLY",
                null,
                null,
                saved.answer(),
                saved,
                false,
                false,
                false,
                List.of(),
                List.of(),
                metadata(context, null),
                warnings
        );
    }

    private CodeAgentInteractiveTurnResponse commandResponse(
            CodeAgentInteractiveTurnRequest request,
            RagConversationContext context,
            IntentDecision decision,
            String command,
            List<String> warnings
    ) {
        String goal = commandGoalEnvelope(request, context, decision, command, warnings);
        CodeAskResponse marker = new CodeAskResponse(
                "agent_intent",
                command.equals("review")
                        ? "검토 요청으로 판단했습니다. 기존 Local Agent 검토 흐름으로 이어갑니다."
                        : "수정 요청으로 판단했습니다. 기존 Local Agent 승인 기반 수정 흐름으로 이어갑니다.",
                List.of(),
                decision.confidence(),
                List.of("interactive intent=" + decision.intent(), "command=" + command)
        );
        CodeAskResponse saved = conversationService.saveCodeTurn(context, request.parentTurnId(), request.message(), marker);
        return response(
                saved.conversationId(),
                saved.turnId(),
                decision.intent(),
                command,
                goal,
                saved.answer(),
                null,
                true,
                command.equals("fix"),
                false,
                List.of(),
                List.of(),
                metadata(context, decision),
                warnings
        );
    }

    private CodeAgentInteractiveTurnResponse adviceResponse(
            CodeAgentInteractiveTurnRequest request,
            RagConversationContext context,
            IntentDecision decision,
            List<String> warnings
    ) {
        CodeAskResponse marker = new CodeAskResponse(
                "agent_advice",
                "로컬 workspace 구조를 먼저 확인한 뒤 개선 후보를 제안하겠습니다. 최신 CLI에서는 자동으로 이어집니다. 이 문장만 보이고 멈추면 learnbot restart --install로 CLI를 갱신하세요.",
                List.of(),
                decision.confidence(),
                List.of("interactive intent=" + decision.intent())
        );
        CodeAskResponse saved = conversationService.saveCodeTurn(context, request.parentTurnId(), request.message(), marker);
        return response(
                saved.conversationId(),
                saved.turnId(),
                "ADVISE",
                null,
                firstNonBlank(decision.goal(), request.message()),
                saved.answer(),
                null,
                false,
                false,
                true,
                List.of(),
                List.of(Map.of(
                        "tool", "workspace.tree",
                        "input", Map.of("path", ".", "maxEntries", 120, "maxDepth", 4)
                )),
                metadata(context, decision),
                warnings
        );
    }

    private String commandGoalEnvelope(
            CodeAgentInteractiveTurnRequest request,
            RagConversationContext context,
            IntentDecision decision,
            String command,
            List<String> warnings
    ) {
        String modelGoal = firstNonBlank(decision.goal(), request.message());
        String rewritten = safe(context.rewrittenQuestion());
        String current = safe(request.message());
        List<String> targetFiles = sanitizeTargetFiles(decision.targetFiles(), warnings);
        StringBuilder builder = new StringBuilder();
        builder.append("LearnBot interactive ").append(command).append(" request.\n")
                .append("Use the full conversation context below to resolve follow-up references. Do not drop the original user goal just because the latest message only names files or confirms intent.\n\n")
                .append("Current user message:\n")
                .append(current)
                .append("\n\nModel-interpreted goal:\n")
                .append(modelGoal)
                .append("\n\nConversation-aware goal:\n")
                .append(rewritten.isBlank() ? modelGoal : rewritten)
                .append("\n\nRecent conversation turns:\n")
                .append(recentTurns(context.recentTurns()));
        if (!targetFiles.isEmpty()) {
            builder.append("\n\nUser/model mentioned target files:\n");
            targetFiles.forEach(path -> builder.append("- ").append(path).append("\n"));
        }
        builder.append("\n\nInstruction for patch generation:\n")
                .append("- Prefer the conversation-aware goal when it contains more task detail than the current message.\n")
                .append("- Use the current message mainly to refine target files, constraints, or confirmation.\n")
                .append("- If target files are mentioned, inspect and modify those files when relevant, but preserve the original requested behavior.\n");
        return boundedText(builder.toString(), 4000);
    }

    private CodeAgentInteractiveTurnResponse readContextResponse(
            CodeAgentInteractiveTurnRequest request,
            RagConversationContext context,
            IntentDecision decision,
            List<String> warnings
    ) {
        List<String> targetFiles = sanitizeTargetFiles(decision.targetFiles(), warnings);
        List<Map<String, Object>> toolPlan = sanitizeToolPlan(decision.toolPlan(), targetFiles, warnings);
        String answer = targetFiles.isEmpty()
                ? "읽을 파일을 LLM이 명확히 지정하지 못했습니다. 파일명이나 확인할 범위를 조금 더 구체적으로 말해주세요."
                : "요청한 컨텍스트를 읽기 전용으로 확인하겠습니다: " + String.join(", ", targetFiles);
        CodeAskResponse marker = new CodeAskResponse(
                "agent_context_read",
                answer,
                List.of(),
                decision.confidence(),
                List.of("interactive intent=" + decision.intent(), "targetFiles=" + targetFiles)
        );
        CodeAskResponse saved = conversationService.saveCodeTurn(context, request.parentTurnId(), request.message(), marker);
        return response(
                saved.conversationId(),
                saved.turnId(),
                "READ_CONTEXT",
                null,
                firstNonBlank(decision.goal(), request.message()),
                answer,
                null,
                false,
                false,
                !targetFiles.isEmpty(),
                targetFiles,
                toolPlan,
                metadata(context, decision),
                warnings
        );
    }

    private CodeAgentInteractiveTurnResponse clarification(
            CodeAgentInteractiveTurnRequest request,
            RagConversationContext context,
            IntentDecision decision,
            List<String> warnings
    ) {
        String answer = firstNonBlank(
                decision.clarifyingQuestion(),
                "수정, 검토, 설명, 컨텍스트 읽기 중 어떤 작업을 원하는지 조금 더 명확히 알려주세요."
        );
        CodeAskResponse marker = new CodeAskResponse(
                "agent_clarification",
                answer,
                List.of(),
                decision.confidence(),
                List.of("interactive intent=" + decision.intent())
        );
        CodeAskResponse saved = conversationService.saveCodeTurn(context, request.parentTurnId(), request.message(), marker);
        return response(
                saved.conversationId(),
                saved.turnId(),
                "ASK_CLARIFICATION",
                null,
                null,
                answer,
                null,
                false,
                false,
                false,
                List.of(),
                List.of(),
                metadata(context, decision),
                warnings
        );
    }

    private CodeAgentInteractiveTurnResponse response(
            UUID conversationId,
            UUID turnId,
            String intent,
            String command,
            String goal,
            String answer,
            CodeAskResponse ragAnswer,
            boolean shouldRunCommand,
            boolean mutationRequiresApproval,
            boolean contextRequired,
            List<String> targetFiles,
            List<Map<String, Object>> toolPlan,
            Map<String, Object> metadata,
            List<String> warnings
    ) {
        return new CodeAgentInteractiveTurnResponse(
                "learnbot.server.code-agent.interactive-turn.v1",
                conversationId,
                turnId,
                intent,
                command,
                goal,
                answer,
                ragAnswer,
                shouldRunCommand,
                mutationRequiresApproval,
                contextRequired,
                List.copyOf(targetFiles),
                List.copyOf(toolPlan),
                metadata,
                List.copyOf(warnings)
        );
    }

    private IntentDecision classifyIntent(String message, String intentHint, RagConversationContext context, List<String> warnings) {
        String normalizedHint = safe(intentHint).toUpperCase(Locale.ROOT);
        if (normalizedHint.equals("FIX") || normalizedHint.equals("REVIEW") || normalizedHint.equals("ANSWER_ONLY")) {
            return new IntentDecision(normalizedHint, message, "", "high", List.of(), List.of());
        }
        try {
            OllamaClient.ChatResult result = ollamaClient.chatResult(
                    intentSystemPrompt(),
                    intentUserPrompt(message, context),
                    OllamaClient.ChatRole.PRIMARY,
                    INTENT_OUTPUT_TOKENS,
                    Duration.ofSeconds(30)
            );
            IntentDecision decision = parseDecision(result.content());
            if (decision != null) {
                if (result.fallbackUsed()) {
                    warnings.add("Interactive intent classification used fallback LLM settings.");
                }
                return decision;
            }
            warnings.add("Interactive intent classifier returned unparsable JSON; asking for clarification.");
        } catch (RuntimeException ex) {
            warnings.add("Interactive intent classifier failed: " + ex.getMessage());
        }
        return new IntentDecision(
                "ASK_CLARIFICATION",
                "",
                "수정, 검토, 설명, 컨텍스트 읽기 중 어떤 작업을 원하는지 조금 더 명확히 알려주세요.",
                "low",
                List.of(),
                List.of()
        );
    }

    private IntentDecision parseDecision(String content) {
        try {
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            String intent = safe(root.path("intent").asText()).toUpperCase(Locale.ROOT);
            if (!List.of("ANSWER_ONLY", "ADVISE", "REVIEW", "FIX", "READ_CONTEXT", "ASK_CLARIFICATION").contains(intent)) {
                return null;
            }
            return new IntentDecision(
                    intent,
                    safe(root.path("goal").asText()),
                    safe(root.path("clarifyingQuestion").asText()),
                    firstNonBlank(root.path("confidence").asText(), "medium"),
                    parseStringArray(root.path("targetFiles")),
                    parseToolPlan(root.path("toolPlan"))
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private String intentSystemPrompt() {
        return """
                You classify a LearnBot local coding-agent chat turn.
                Return only JSON with keys: intent, goal, targetFiles, toolPlan, clarifyingQuestion, confidence.
                intent must be one of ANSWER_ONLY, ADVISE, REVIEW, FIX, READ_CONTEXT, ASK_CLARIFICATION.
                ANSWER_ONLY: user asks an explanation, how-to guidance, or a question without asking the agent to change files.
                ADVISE: user asks the local agent to inspect the workspace and suggest possible improvements, next steps, or what could be better, without directly asking to apply a change yet.
                REVIEW: user asks to inspect risks, regressions, diff, or current code without changing files.
                FIX: user asks the agent to modify, create, delete, improve, repair, implement, or apply code.
                READ_CONTEXT: user asks you to read, load, remember, inspect, or use a specific local instruction/context file before later work.
                ASK_CLARIFICATION: the request is too ambiguous to safely choose answer/review/fix/read-context.
                For READ_CONTEXT, fill targetFiles with relative file paths chosen by the model from the user's request.
                For READ_CONTEXT, fill toolPlan with read-only tool steps. Allowed tools: workspace.tree, workspace.search, file.read, git.status, git.diff.
                Do not choose FIX just because the user asks "how should I fix"; choose ANSWER_ONLY unless they ask you to perform the change.
                """;
    }

    private String intentUserPrompt(String message, RagConversationContext context) {
        return "User message:\n" + message
                + "\n\nConversation-aware question:\n" + safe(context.rewrittenQuestion())
                + "\n\nRecent turns:\n" + recentTurns(context.recentTurns());
    }

    private String recentTurns(List<RagConversationTurnContext> turns) {
        if (turns == null || turns.isEmpty()) {
            return "(none)";
        }
        return turns.stream()
                .limit(5)
                .map(turn -> "- Q: " + compact(turn.question(), 160) + "\n  A: " + compact(turn.answer(), 220))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(none)");
    }

    private Map<String, Object> metadata(RagConversationContext context, IntentDecision decision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("conversationIntent", context.conversationIntent().name());
        metadata.put("contextual", context.contextual());
        if (decision != null) {
            metadata.put("agentIntent", decision.intent());
            metadata.put("confidence", decision.confidence());
            if (!decision.targetFiles().isEmpty()) {
                metadata.put("targetFiles", decision.targetFiles());
            }
        }
        return metadata;
    }

    private List<String> sanitizeTargetFiles(List<String> targetFiles, List<String> warnings) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String path : targetFiles == null ? List.<String>of() : targetFiles) {
            String clean = safe(path).replace('\\', '/');
            if (clean.isBlank()) {
                continue;
            }
            if (clean.startsWith("/") || clean.contains("..") || clean.contains("\u0000")) {
                warnings.add("Skipped unsafe context target path: " + clean);
                continue;
            }
            normalized.add(clean);
        }
        return normalized.stream().limit(8).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> continueAdviceAfterContextRead(
            AppUser user,
            UUID selectedSpaceId,
            CodeAgentInteractiveContextReadResultRequest request,
            RagConversationTurn turn,
            Map<String, Object> loadedContext
    ) {
        List<Map<String, Object>> files = mapList(loadedContext.get("files"));
        List<Map<String, Object>> toolResults = mapList(loadedContext.get("toolResults"));
        List<String> warnings = stringList(loadedContext.get("warnings"));
        if (files.stream().anyMatch(this::isSucceededFileRead)) {
            Map<String, Object> advice = generateAdviceFromLoadedFiles(turn, files, warnings);
            conversationService.updateTurnMetadata(
                    user,
                    selectedSpaceId,
                    RagConversationService.CODE,
                    request.repositoryId(),
                    request.conversationId(),
                    request.turnId(),
                    "adviceResult",
                    objectMapper.valueToTree(advice)
            );
            return advice;
        }
        Map<String, Object> readPlan = selectAdviceReadPlan(turn, toolResults, warnings);
        if (!readPlan.isEmpty()) {
            conversationService.updateTurnMetadata(
                    user,
                    selectedSpaceId,
                    RagConversationService.CODE,
                    request.repositoryId(),
                    request.conversationId(),
                    request.turnId(),
                    "adviceReadPlan",
                    objectMapper.valueToTree(readPlan)
            );
        }
        return readPlan;
    }

    private Map<String, Object> selectAdviceReadPlan(
            RagConversationTurn turn,
            List<Map<String, Object>> toolResults,
            List<String> warnings
    ) {
        try {
            OllamaClient.ChatResult result = ollamaClient.chatResult(
                    adviceFileSelectionSystemPrompt(),
                    adviceFileSelectionUserPrompt(turn, toolResults),
                    OllamaClient.ChatRole.PRIMARY,
                    900,
                    Duration.ofSeconds(30)
            );
            JsonNode root = objectMapper.readTree(extractJson(result.content()));
            List<String> targets = sanitizeTargetFiles(parseStringArray(root.path("targetFiles")), warnings);
            if (targets.isEmpty()) {
                return Map.of(
                        "schema", "learnbot.server.code-agent.advice-result.v1",
                        "contextRequired", false,
                        "answer", firstNonBlank(root.path("clarifyingQuestion").asText(), "읽을 로컬 파일을 특정하지 못했습니다. 화면명이나 파일명을 조금 더 알려주세요."),
                        "adviceCandidates", List.of(),
                        "warnings", warnings
                );
            }
            List<Map<String, Object>> toolPlan = targets.stream()
                    .map(path -> Map.<String, Object>of(
                            "tool", "file.read",
                            "input", Map.of("path", path, "maxBytes", 16_000)
                    ))
                    .toList();
            return Map.of(
                    "schema", "learnbot.server.code-agent.advice-read-plan.v1",
                    "contextRequired", true,
                    "answer", "개선 후보를 판단하기 위해 로컬 파일을 읽겠습니다: " + String.join(", ", targets),
                    "targetFiles", targets,
                    "toolPlan", toolPlan,
                    "warnings", warnings
            );
        } catch (Exception ex) {
            return Map.of(
                    "schema", "learnbot.server.code-agent.advice-result.v1",
                    "contextRequired", false,
                    "answer", "로컬 workspace 구조는 확인했지만 읽을 파일 선택에 실패했습니다. 파일명을 포함해 다시 요청해주세요.",
                    "adviceCandidates", List.of(),
                    "warnings", appendWarning(warnings, "Advice file selection failed: " + ex.getMessage())
            );
        }
    }

    private Map<String, Object> generateAdviceFromLoadedFiles(
            RagConversationTurn turn,
            List<Map<String, Object>> files,
            List<String> warnings
    ) {
        try {
            OllamaClient.ChatResult result = ollamaClient.chatResult(
                    adviceAnswerSystemPrompt(),
                    adviceAnswerUserPrompt(turn, files),
                    OllamaClient.ChatRole.PRIMARY,
                    1800,
                    Duration.ofSeconds(45)
            );
            JsonNode root = objectMapper.readTree(extractJson(result.content()));
            List<Map<String, Object>> candidates = parseCandidateMaps(root.path("candidates"));
            String answer = adviceAnswerText(root, candidates);
            return Map.of(
                    "schema", "learnbot.server.code-agent.advice-result.v1",
                    "contextRequired", false,
                    "answer", answer,
                    "adviceCandidates", candidates,
                    "warnings", warnings
            );
        } catch (Exception ex) {
            return Map.of(
                    "schema", "learnbot.server.code-agent.advice-result.v1",
                    "contextRequired", false,
                    "answer", "로컬 파일은 읽었지만 개선 후보 응답을 구조화하지 못했습니다. 같은 요청을 다시 보내거나 범위를 좁혀주세요.",
                    "adviceCandidates", List.of(),
                    "warnings", appendWarning(warnings, "Advice answer generation failed: " + ex.getMessage())
            );
        }
    }

    private String adviceFileSelectionSystemPrompt() {
        return """
                You are LearnBot Local Agent file-selection planner.
                The local agent has already read the workspace tree. Choose the smallest set of existing files to read before suggesting improvements.
                Return only JSON: {"targetFiles":["relative/path"],"reason":"...","clarifyingQuestion":""}
                Do not invent files. Use only paths present in the workspace tree output.
                Prefer files that define the visible UI or app entrypoint for the user's question.
                Select at most 5 files.
                """;
    }

    private String adviceFileSelectionUserPrompt(RagConversationTurn turn, List<Map<String, Object>> toolResults) {
        return "User request:\n" + safe(turn.question())
                + "\n\nConversation-aware request:\n" + safe(turn.rewrittenQuestion())
                + "\n\nLocal agent read-only tool results:\n" + boundedText(toJson(toolResults), 12_000);
    }

    private String adviceAnswerSystemPrompt() {
        return """
                You are LearnBot Local Agent, an agentic coding assistant discussing the user's local workspace.
                Use only the local files provided. Do not claim files were changed.
                Suggest concrete improvements that fit the user's request and project context.
                Return only JSON:
                {"summary":"...","candidates":[{"title":"...","reason":"...","evidenceFiles":["path"],"expectedFiles":["path"],"riskLevel":"low|medium|high","testPlan":"...","recommendedFixGoal":"..."}]}
                The recommendedFixGoal must be a natural-language request that can be passed to a later fix command.
                """;
    }

    private String adviceAnswerUserPrompt(RagConversationTurn turn, List<Map<String, Object>> files) {
        return "User request:\n" + safe(turn.question())
                + "\n\nConversation-aware request:\n" + safe(turn.rewrittenQuestion())
                + "\n\nLoaded local files:\n" + boundedText(toJson(files), 20_000);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCandidateMaps(JsonNode candidatesNode) {
        if (candidatesNode == null || !candidatesNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode item : candidatesNode) {
            if (!item.isObject()) {
                continue;
            }
            Map<String, Object> candidate = objectMapper.convertValue(item, Map.class);
            candidates.add(candidate);
            if (candidates.size() >= 5) {
                break;
            }
        }
        return candidates;
    }

    private String adviceAnswerText(JsonNode root, List<Map<String, Object>> candidates) {
        StringBuilder builder = new StringBuilder();
        String summary = safe(root.path("summary").asText());
        if (!summary.isBlank()) {
            builder.append(summary).append("\n\n");
        }
        if (candidates.isEmpty()) {
            return firstNonBlank(summary, "로컬 파일 기준으로 제안할 개선 후보를 충분히 만들지 못했습니다.");
        }
        builder.append("개선 후보:\n");
        for (int index = 0; index < candidates.size(); index++) {
            Map<String, Object> candidate = candidates.get(index);
            builder.append(index + 1).append(". ")
                    .append(firstNonBlank(String.valueOf(candidate.get("title")), "개선 후보"))
                    .append("\n");
            String reason = safe(String.valueOf(candidate.getOrDefault("reason", "")));
            if (!reason.isBlank()) {
                builder.append("   - 이유: ").append(reason).append("\n");
            }
            String expected = stringList(candidate.get("expectedFiles")).stream().collect(Collectors.joining(", "));
            if (!expected.isBlank()) {
                builder.append("   - 예상 수정 파일: ").append(expected).append("\n");
            }
            String testPlan = safe(String.valueOf(candidate.getOrDefault("testPlan", "")));
            if (!testPlan.isBlank()) {
                builder.append("   - 확인 방법: ").append(testPlan).append("\n");
            }
        }
        builder.append("\n번호를 입력하면 해당 개선안으로 수정 제안을 만들 수 있습니다.");
        return builder.toString().trim();
    }

    private List<Map<String, Object>> sanitizeToolPlan(List<Map<String, Object>> toolPlan, List<String> targetFiles, List<String> warnings) {
        Set<String> allowed = Set.of("workspace.tree", "workspace.search", "file.read", "git.status", "git.diff");
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> step : toolPlan == null ? List.<Map<String, Object>>of() : toolPlan) {
            String tool = safe(String.valueOf(step.getOrDefault("tool", "")));
            if (!allowed.contains(tool)) {
                warnings.add("Skipped unsupported read-only context tool: " + tool);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("tool", tool);
            Object input = step.get("input");
            copy.put("input", input instanceof Map<?, ?> ? input : Map.of());
            sanitized.add(copy);
        }
        if (sanitized.isEmpty()) {
            targetFiles.forEach(path -> sanitized.add(Map.of(
                    "tool", "file.read",
                    "input", Map.of("path", path)
            )));
        }
        return sanitized.stream().limit(12).collect(Collectors.toList());
    }

    private List<String> parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseToolPlan(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        node.forEach(item -> {
            if (item.isObject()) {
                values.add(objectMapper.convertValue(item, Map.class));
            }
        });
        return values;
    }

    private Map<String, Object> loadedContextSummary(RagConversationTurn turn) {
        JsonNode loaded = turn.metadata().path("loadedContext");
        return Map.of(
                "turnId", turn.id(),
                "createdAt", turn.createdAt(),
                "files", loaded.path("files"),
                "warnings", loaded.path("warnings")
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                maps.add((Map<String, Object>) map);
            }
        }
        return maps;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                strings.add(String.valueOf(item));
            }
        }
        return List.copyOf(strings);
    }

    private boolean isSucceededFileRead(Map<String, Object> file) {
        return "SUCCEEDED".equalsIgnoreCase(String.valueOf(file.getOrDefault("status", "")))
                && !safe(String.valueOf(file.getOrDefault("content", ""))).isBlank();
    }

    private List<String> appendWarning(List<String> warnings, String warning) {
        List<String> copy = new ArrayList<>(warnings == null ? List.of() : warnings);
        if (!safe(warning).isBlank()) {
            copy.add(warning);
        }
        return List.copyOf(copy);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String extractJson(String content) {
        String clean = safe(content);
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        return start >= 0 && end > start ? clean.substring(start, end + 1) : clean;
    }

    private String compact(String value, int max) {
        String clean = safe(value).replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private String boundedText(String value, int max) {
        String clean = safe(value);
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max)) + "\n...<truncated>";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record IntentDecision(
            String intent,
            String goal,
            String clarifyingQuestion,
            String confidence,
            List<String> targetFiles,
            List<Map<String, Object>> toolPlan
    ) {
    }
}
