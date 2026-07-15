package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.CodeNavigationHandle;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NavigationEvidenceExtractor implements EvidenceExtractor {
    private static final Pattern QUALIFIED_CALL = Pattern.compile(
            "\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern CONSTRUCTED_TYPE = Pattern.compile(
            "\\bnew\\s+([A-Z][A-Za-z0-9_$]{1,})\\b");

    @Override
    public String id() {
        return "navigation";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_SEED, EvidenceExtractionStage.POST_OPERATION);
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        if (context == null) return false;
        return context.evidence().stream().anyMatch(result -> {
            String content = result.content() == null ? "" : result.content();
            return QUALIFIED_CALL.matcher(content).find() || CONSTRUCTED_TYPE.matcher(content).find()
                    || EvidenceExtractionSupport.metadataBoolean(result, "graphExpanded");
        });
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        int limit = context.maxItemsPerExtractor();
        Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
        Map<String, CodeNavigationHandle> handles = new LinkedHashMap<>();
        List<CodeEvidenceConstraint> constraints = new ArrayList<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();
        Set<String> signaled = new java.util.LinkedHashSet<>();

        for (CodeSearchResult result : context.evidence()) {
            if (handles.size() >= limit) break;
            String content = result.content() == null ? "" : result.content();
            Matcher calls = QUALIFIED_CALL.matcher(content);
            while (calls.find() && handles.size() < limit) {
                addHandle(result, CodeNavigationHandle.Kind.CALL, calls.group(1) + "." + calls.group(2),
                        calls.start(), items, handles, constraints, signals, signaled);
            }
            Matcher types = CONSTRUCTED_TYPE.matcher(content);
            while (types.find() && handles.size() < limit) {
                addHandle(result, CodeNavigationHandle.Kind.TYPE, types.group(1), types.start(),
                        items, handles, constraints, signals, signaled);
            }
            if (handles.size() < limit && EvidenceExtractionSupport.metadataBoolean(result, "graphExpanded")) {
                addHandle(result, CodeNavigationHandle.Kind.DEFINITION,
                        EvidenceExtractionSupport.subject(result), 0,
                        items, handles, constraints, signals, signaled);
            }
        }
        return new CodeEvidenceIr(List.copyOf(items.values()), List.of(), constraints, signals,
                List.copyOf(handles.values()), List.of());
    }

    private void addHandle(
            CodeSearchResult result,
            CodeNavigationHandle.Kind kind,
            String symbol,
            int offset,
            Map<String, CodeEvidenceItem> items,
            Map<String, CodeNavigationHandle> handles,
            List<CodeEvidenceConstraint> constraints,
            List<CodeEvidenceSignal> signals,
            Set<String> signaled
    ) {
        String evidenceId = CodeEvidenceItem.evidenceId(result);
        CodeNavigationHandle handle = CodeNavigationHandle.of(kind, result.filePath(), symbol,
                result.chunkId(), EvidenceExtractionSupport.lineAtOffset(result, offset),
                EvidenceExtractionSupport.lineAtOffset(result, offset), evidenceId);
        if (handles.putIfAbsent(handle.handleId(), handle) != null) return;
        items.putIfAbsent(evidenceId, new CodeEvidenceItem(evidenceId, result,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION),
                EvidenceExtractionSupport.directSyntaxAuthority(result)));
        constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.NAVIGATION_ONLY,
                handle.handleId(), "A navigation handle locates evidence but does not prove behavior by itself."));
        if (signaled.add(evidenceId)) {
            signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.OBSERVED_NAVIGATION,
                    evidenceId, 0.7, "A bounded navigation operand was observed directly in source."));
        }
    }
}
