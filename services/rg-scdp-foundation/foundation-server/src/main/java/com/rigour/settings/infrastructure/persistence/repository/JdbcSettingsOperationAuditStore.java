package com.rigour.settings.infrastructure.persistence.repository;

import com.rigour.settings.api.v1.SettingsOperationAuditApi.*;
import com.rigour.settings.application.port.out.SettingsOperationAuditStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class JdbcSettingsOperationAuditStore implements SettingsOperationAuditStore {
    private final JdbcTemplate jdbc;

    public JdbcSettingsOperationAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page audits(String tenant, String action, String keyword, int page, int size) {
        List<Object> args = new ArrayList<>(List.of(tenant));
        String where = "tenant_id=?";
        if (action != null && !action.isBlank()) {
            where += " AND action_code=?";
            args.add(action);
        }
        if (keyword != null && !keyword.isBlank()) {
            where += " AND (actor_id LIKE ? OR object_code LIKE ?)";
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settings_operation_audit WHERE " + where,
                        Long.class,
                        args.toArray());
        args.add(size);
        args.add((long) (page - 1) * size);
        var rows =
                jdbc.query(
                        "SELECT"
                            + " id,actor_id,action_code,object_code,CONCAT(action_code,'：',object_code)"
                            + " AS summary,created_at FROM settings_operation_audit WHERE "
                                + where
                                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                        (r, n) ->
                                new Audit(
                                        r.getString("id"),
                                        r.getString("actor_id"),
                                        r.getString("action_code"),
                                        r.getString("object_code"),
                                        "SUCCESS",
                                        r.getString("summary"),
                                        r.getTimestamp("created_at").toInstant()),
                        args.toArray());
        return new Page(rows, total, page, size);
    }
}
