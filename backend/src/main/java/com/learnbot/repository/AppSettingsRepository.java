package com.learnbot.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AppSettingsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AppSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findValue(String key) {
        List<String> values = jdbc.query("""
                SELECT setting_value
                FROM app_settings
                WHERE setting_key = :key
                """, new MapSqlParameterSource().addValue("key", key), (rs, rowNum) -> rs.getString("setting_value"));
        return values.stream().findFirst();
    }

    public void upsertValue(String key, String value, UUID updatedBy) {
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, updated_by, updated_at)
                VALUES (:key, :value, :updatedBy, now())
                ON CONFLICT (setting_key) DO UPDATE
                SET setting_value = EXCLUDED.setting_value,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("value", value)
                .addValue("updatedBy", updatedBy));
    }
}
