package com.learnbot.service;

import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.PatchFileDiff;
import com.learnbot.dto.PatchTargetFile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CodeChangeAssistViewBuilder {
    public Map<String, Object> build(
            CodeAgentPlanResponse plan,
            CodeAgentPatchResponse patch,
            CodeAgentLoopPreviewResponse loopPreview
    ) {
        Map<String, ChangeCard> byPath = new LinkedHashMap<>();
        List<PatchTargetFile> targetFiles = plan == null || plan.targetFiles() == null ? List.of() : plan.targetFiles();
        List<CodeEvidence> evidence = plan == null || plan.evidence() == null ? List.of() : plan.evidence();
        List<PatchFileDiff> patchFiles = patch == null || patch.files() == null ? List.of() : patch.files();

        for (PatchTargetFile file : targetFiles) {
            if (file == null || blank(file.path())) {
                continue;
            }
            byPath.put(file.path(), new ChangeCard(file.path(), file.reason(), "", new ArrayList<>()));
        }
        for (PatchFileDiff file : patchFiles) {
            if (file == null || blank(file.path())) {
                continue;
            }
            ChangeCard current = byPath.computeIfAbsent(file.path(), path -> new ChangeCard(path, "", "", new ArrayList<>()));
            current.diff = file.diff() == null ? "" : file.diff();
        }
        for (CodeEvidence item : evidence) {
            if (item == null || blank(item.filePath())) {
                continue;
            }
            ChangeCard current = byPath.computeIfAbsent(item.filePath(), path -> new ChangeCard(path, "", "", new ArrayList<>()));
            current.evidence.add(item);
        }

        List<Map<String, Object>> cards = byPath.values().stream().map(ChangeCard::toMap).toList();
        long diffReady = cards.stream().filter(card -> "DIFF_READY".equals(card.get("status"))).count();
        long candidatesOnly = cards.stream().filter(card -> "CANDIDATE_ONLY".equals(card.get("status"))).count();
        long needsMoreContext = (plan != null && plan.needsMoreContext()) || (plan != null && cards.isEmpty()) ? 1 : 0;
        String overallStatus = overallStatus(patch, diffReady, candidatesOnly, needsMoreContext, plan != null);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("diffReady", diffReady);
        counts.put("candidatesOnly", candidatesOnly);
        counts.put("needsMoreContext", needsMoreContext);

        List<String> warnings = new ArrayList<>();
        if (plan != null && plan.warnings() != null) warnings.addAll(plan.warnings());
        if (patch != null && patch.warnings() != null) warnings.addAll(patch.warnings());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.code.change-assist.v1");
        result.put("createdAt", OffsetDateTime.now().toString());
        result.put("overallStatus", overallStatus);
        result.put("overallStatusLabel", overallStatusLabel(overallStatus));
        result.put("counts", counts);
        result.put("cards", cards);
        result.put("warnings", warnings);
        result.put("planSummary", plan == null ? "" : safe(plan.summary()));
        result.put("planIntent", plan == null ? "" : safe(plan.intent()));
        result.put("patchValid", patch != null && patch.valid());
        result.put("testSuggestions", patch == null || patch.testSuggestions() == null ? List.of() : patch.testSuggestions());
        result.put("loopPreview", loopPreviewSummary(loopPreview));
        result.put("appliedFileCount", 0);
        return result;
    }

    private String overallStatus(CodeAgentPatchResponse patch, long diffReady, long candidatesOnly, long needsMoreContext, boolean hasPlan) {
        if (patch != null && !patch.valid()) return "FAILED";
        if (diffReady > 0 && candidatesOnly == 0 && needsMoreContext == 0) return "DIFF_READY";
        if (diffReady > 0 || candidatesOnly > 0) return "PARTIAL";
        if (hasPlan) return "NEEDS_MORE_CONTEXT";
        return "IDLE";
    }

    private String overallStatusLabel(String status) {
        return switch (status) {
            case "DIFF_READY" -> "수정 예시 생성됨";
            case "PARTIAL" -> "일부 수정 예시 생성됨";
            case "NEEDS_MORE_CONTEXT" -> "추가 정보 필요";
            case "FAILED" -> "diff 검증 필요";
            default -> "대기 중";
        };
    }

    private Map<String, Object> loopPreviewSummary(CodeAgentLoopPreviewResponse loopPreview) {
        if (loopPreview == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("loopId", loopPreview.loopId());
        summary.put("status", loopPreview.status());
        summary.put("maxSteps", loopPreview.maxSteps());
        summary.put("mutationEnabled", loopPreview.mutationEnabled());
        summary.put("warnings", loopPreview.warnings() == null ? List.of() : loopPreview.warnings());
        return summary;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class ChangeCard {
        private final String path;
        private final String reason;
        private String diff;
        private final List<CodeEvidence> evidence;

        private ChangeCard(String path, String reason, String diff, List<CodeEvidence> evidence) {
            this.path = path;
            this.reason = reason == null ? "" : reason;
            this.diff = diff == null ? "" : diff;
            this.evidence = evidence;
        }

        private Map<String, Object> toMap() {
            boolean diffReady = !diff.isBlank();
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("path", path);
            card.put("reason", reason);
            card.put("diff", diff);
            card.put("evidence", evidence);
            card.put("status", diffReady ? "DIFF_READY" : "CANDIDATE_ONLY");
            card.put("statusLabel", diffReady ? "수정 예시 있음" : "후보만 확인됨");
            card.put("nextActionText", diffReady
                    ? "diff 초안을 복사해 직접 적용하거나 원문과 비교해 검토하세요."
                    : "diff 초안이 생성되지 않아 근거와 수정 계획 확인이 필요합니다.");
            return card;
        }
    }
}
