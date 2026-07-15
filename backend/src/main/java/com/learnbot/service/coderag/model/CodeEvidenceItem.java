package com.learnbot.service.coderag.model;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CodeEvidenceItem(
        String evidenceId,
        CodeSearchResult source,
        Set<Kind> kinds,
        CodeIntelligenceAuthority authority
) {
    public enum Kind {
        DIRECT_SOURCE,
        GRAPH_RELATION,
        ENDPOINT,
        ASSIGNMENT,
        TRANSACTION,
        NAVIGATION,
        PERSISTENCE
    }

    public CodeEvidenceItem {
        source = Objects.requireNonNull(source, "source");
        evidenceId = evidenceId == null || evidenceId.isBlank() ? evidenceId(source) : evidenceId.trim();
        if (evidenceId.isBlank()) {
            throw new IllegalArgumentException("evidenceId must not be blank");
        }
        LinkedHashSet<Kind> safeKinds = new LinkedHashSet<>();
        if (kinds != null) {
            kinds.stream().filter(Objects::nonNull).forEach(safeKinds::add);
        }
        if (safeKinds.isEmpty()) {
            safeKinds.add(Kind.DIRECT_SOURCE);
        }
        kinds = Collections.unmodifiableSet(safeKinds);
        authority = authority == null ? CodeIntelligenceAuthority.UNKNOWN : authority;
    }

    public static CodeEvidenceItem from(CodeSearchResult source, Kind... kinds) {
        LinkedHashSet<Kind> values = new LinkedHashSet<>();
        if (kinds != null) {
            for (Kind kind : kinds) {
                if (kind != null) values.add(kind);
            }
        }
        return new CodeEvidenceItem(evidenceId(source), source, values, authority(source));
    }

    public CodeEvidenceItem merge(CodeEvidenceItem other) {
        if (other == null) return this;
        if (!evidenceId.equals(other.evidenceId)) {
            throw new IllegalArgumentException("Cannot merge different evidence identities");
        }
        LinkedHashSet<Kind> mergedKinds = new LinkedHashSet<>(kinds);
        mergedKinds.addAll(other.kinds);
        CodeEvidenceItem preferred = other.authority.rank() > authority.rank() ? other : this;
        CodeIntelligenceAuthority mergedAuthority = other.authority.rank() > authority.rank()
                ? other.authority : authority;
        return new CodeEvidenceItem(evidenceId, preferred.source, mergedKinds, mergedAuthority);
    }

    public static String evidenceId(CodeSearchResult source) {
        if (source == null) return "";
        String indexVersion = metadata(source.metadata(), "indexVersion");
        String chunkId = source.chunkId() == null ? "unknown-chunk" : source.chunkId().toString();
        return (indexVersion.isBlank() ? "unknown-index" : indexVersion)
                + ":" + chunkId
                + ":" + Math.max(0, source.lineStart())
                + "-" + Math.max(0, source.lineEnd());
    }

    public static CodeIntelligenceAuthority authority(CodeSearchResult source) {
        return source == null ? CodeIntelligenceAuthority.UNKNOWN
                : CodeIntelligenceAuthority.from(metadata(source.metadata(), "codeIntelligenceAuthority"));
    }

    private static String metadata(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
