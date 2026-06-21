package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.TrashItemSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TrashService {
    private final NamedParameterJdbcTemplate jdbc;
    private final LearnBotProperties properties;
    private final AuditService auditService;

    public TrashService(NamedParameterJdbcTemplate jdbc, LearnBotProperties properties, AuditService auditService) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.auditService = auditService;
    }

    public List<TrashItemSummary> list(AppUser actor, String type, UUID spaceId) {
        String normalizedType = normalizeType(type);
        OffsetDateTime cutoff = cutoff();
        List<TrashItemSummary> items = new ArrayList<>();
        if (normalizedType == null || "DOCUMENT_SOURCE".equals(normalizedType)) {
            items.addAll(documentSources(cutoff, spaceId));
        }
        if (normalizedType == null || "CODE_REPOSITORY".equals(normalizedType)) {
            items.addAll(codeRepositories(cutoff, spaceId));
        }
        if (normalizedType == null || "SAVED_ANSWER".equals(normalizedType)) {
            items.addAll(savedAnswers(cutoff, spaceId));
        }
        if (normalizedType == null || "USER".equals(normalizedType)) {
            items.addAll(users(cutoff));
        }
        if (normalizedType == null || "SPACE".equals(normalizedType)) {
            items.addAll(spaces(cutoff));
        }
        return items.stream()
                .sorted(Comparator.comparing(TrashItemSummary::deletedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public void restore(AppUser actor, String type, UUID id) {
        String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            throw new IllegalArgumentException("Trash item type is required.");
        }
        OffsetDateTime cutoff = cutoff();
        int restored = switch (normalizedType) {
            case "DOCUMENT_SOURCE" -> restoreDocumentSource(id, cutoff);
            case "CODE_REPOSITORY" -> restoreCodeRepository(id, cutoff);
            case "SAVED_ANSWER" -> restoreSavedAnswer(id, cutoff);
            case "USER" -> restoreUser(id, cutoff);
            case "SPACE" -> restoreSpace(id, cutoff);
            default -> throw new IllegalArgumentException("Unsupported trash item type: " + type);
        };
        if (restored == 0) {
            throw new IllegalArgumentException("Trash item was not found or is past the restore window.");
        }
        auditService.log(actor, normalizedType + "_RESTORED", normalizedType, id.toString(), null,
                "Trash item was restored.", java.util.Map.of("type", normalizedType));
    }

    private List<TrashItemSummary> documentSources(OffsetDateTime cutoff, UUID spaceId) {
        return jdbc.query("""
                SELECT s.id, s.space_id, s.name, s.type, s.status, s.location,
                       s.deleted_at,
                       COUNT(DISTINCT d.id) AS document_count,
                       COUNT(DISTINCT o.id) AS object_count
                FROM data_sources s
                LEFT JOIN documents d ON d.source_id = s.id
                LEFT JOIN source_objects o ON o.source_id = s.id
                WHERE s.deleted_at IS NOT NULL
                  AND s.deleted_at >= :cutoff
                  AND (CAST(:spaceId AS uuid) IS NULL OR s.space_id = CAST(:spaceId AS uuid))
                GROUP BY s.id
                """, params(cutoff, spaceId), (rs, rowNum) -> {
            int documents = rs.getInt("document_count");
            int objects = rs.getInt("object_count");
            String message = documents > 0 || objects > 0
                    ? "복구하면 기존 문서/원본을 다시 사용할 수 있습니다."
                    : "복구 후 재등록 또는 재인덱싱이 필요할 수 있습니다.";
            return new TrashItemSummary(
                    "DOCUMENT_SOURCE",
                    rs.getObject("id", UUID.class),
                    rs.getObject("space_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("type") + " · " + rs.getString("location"),
                    rs.getString("status"),
                    rs.getObject("deleted_at", OffsetDateTime.class),
                    expiresAt(rs.getObject("deleted_at", OffsetDateTime.class)),
                    true,
                    message
            );
        });
    }

    private List<TrashItemSummary> codeRepositories(OffsetDateTime cutoff, UUID spaceId) {
        return jdbc.query("""
                SELECT id, space_id, name, source_type, source_label, status, deleted_at
                FROM code_repositories
                WHERE deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                  AND (CAST(:spaceId AS uuid) IS NULL OR space_id = CAST(:spaceId AS uuid))
                """, params(cutoff, spaceId), (rs, rowNum) -> new TrashItemSummary(
                "CODE_REPOSITORY",
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("name"),
                rs.getString("source_type") + " · " + nullToDash(rs.getString("source_label")),
                rs.getString("status"),
                rs.getObject("deleted_at", OffsetDateTime.class),
                expiresAt(rs.getObject("deleted_at", OffsetDateTime.class)),
                true,
                "복구하면 기존 코드 청크가 다시 노출됩니다. 로컬 원본이 없으면 재인덱싱이 필요할 수 있습니다."
        ));
    }

    private List<TrashItemSummary> savedAnswers(OffsetDateTime cutoff, UUID spaceId) {
        return jdbc.query("""
                SELECT id, space_id, title, answer_type, question, deleted_at
                FROM saved_rag_answers
                WHERE deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                  AND (CAST(:spaceId AS uuid) IS NULL OR space_id = CAST(:spaceId AS uuid))
                """, params(cutoff, spaceId), (rs, rowNum) -> new TrashItemSummary(
                "SAVED_ANSWER",
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("title"),
                rs.getString("answer_type") + " · " + rs.getString("question"),
                "DELETED",
                rs.getObject("deleted_at", OffsetDateTime.class),
                expiresAt(rs.getObject("deleted_at", OffsetDateTime.class)),
                true,
                "복구하면 저장됨 목록에 다시 표시됩니다."
        ));
    }

    private List<TrashItemSummary> users(OffsetDateTime cutoff) {
        return jdbc.query("""
                SELECT id, email, display_name, role, status, deleted_at
                FROM app_users
                WHERE status = 'DELETED'
                  AND deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, new MapSqlParameterSource().addValue("cutoff", cutoff), (rs, rowNum) -> new TrashItemSummary(
                "USER",
                rs.getObject("id", UUID.class),
                null,
                rs.getString("display_name"),
                rs.getString("email") + " · " + rs.getString("role"),
                rs.getString("status"),
                rs.getObject("deleted_at", OffsetDateTime.class),
                expiresAt(rs.getObject("deleted_at", OffsetDateTime.class)),
                true,
                "복구 후 사용자는 다시 로그인할 수 있습니다."
        ));
    }

    private List<TrashItemSummary> spaces(OffsetDateTime cutoff) {
        return jdbc.query("""
                SELECT id, name, description, deleted_at
                FROM spaces
                WHERE deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, new MapSqlParameterSource().addValue("cutoff", cutoff), (rs, rowNum) -> new TrashItemSummary(
                "SPACE",
                rs.getObject("id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                nullToDash(rs.getString("description")),
                "DELETED",
                rs.getObject("deleted_at", OffsetDateTime.class),
                expiresAt(rs.getObject("deleted_at", OffsetDateTime.class)),
                true,
                "복구하면 해당 공간과 연결된 데이터에 다시 접근할 수 있습니다."
        ));
    }

    private int restoreDocumentSource(UUID id, OffsetDateTime cutoff) {
        return jdbc.update("""
                UPDATE data_sources
                SET deleted_at = NULL,
                    deleted_by = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, restoreParams(id, cutoff));
    }

    private int restoreCodeRepository(UUID id, OffsetDateTime cutoff) {
        return jdbc.update("""
                UPDATE code_repositories
                SET deleted_at = NULL,
                    deleted_by = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, restoreParams(id, cutoff));
    }

    private int restoreSavedAnswer(UUID id, OffsetDateTime cutoff) {
        return jdbc.update("""
                UPDATE saved_rag_answers
                SET deleted_at = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, restoreParams(id, cutoff));
    }

    private int restoreUser(UUID id, OffsetDateTime cutoff) {
        return jdbc.update("""
                UPDATE app_users
                SET status = 'ACTIVE',
                    deleted_at = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND status = 'DELETED'
                  AND deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, restoreParams(id, cutoff));
    }

    private int restoreSpace(UUID id, OffsetDateTime cutoff) {
        return jdbc.update("""
                UPDATE spaces
                SET deleted_at = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND deleted_at IS NOT NULL
                  AND deleted_at >= :cutoff
                """, restoreParams(id, cutoff));
    }

    private MapSqlParameterSource params(OffsetDateTime cutoff, UUID spaceId) {
        return new MapSqlParameterSource()
                .addValue("cutoff", cutoff)
                .addValue("spaceId", spaceId);
    }

    private MapSqlParameterSource restoreParams(UUID id, OffsetDateTime cutoff) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("cutoff", cutoff);
    }

    private OffsetDateTime cutoff() {
        return OffsetDateTime.now().minusDays(properties.getRetention().getOrphanGraceDays());
    }

    private OffsetDateTime expiresAt(OffsetDateTime deletedAt) {
        return deletedAt == null ? null : deletedAt.plusDays(properties.getRetention().getOrphanGraceDays());
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) {
            return null;
        }
        return type.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
