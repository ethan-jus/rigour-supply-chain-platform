package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.application.port.out.DhbObjectCheckpointStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.*;
import java.util.*;

/** 本域对象游标在连接器租约内写入；只接受成功完整窗口，不能由手动回填跳跃推进。 */
@Repository
public class JdbcDhbObjectCheckpointStore implements DhbObjectCheckpointStore {
    private final JdbcTemplate jdbc;

    public JdbcDhbObjectCheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant successfulTo(UUID tenant, UUID connector, String scope) {
        var rows =
                jdbc.query(
                        "SELECT successful_to FROM integration_dhb_object_checkpoint WHERE"
                            + " tenant_id=? AND connector_id=? AND object_type=?",
                        (rs, i) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        tenant.toString(),
                        connector.toString(),
                        scope);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void completed(UUID tenant, UUID connector, String scope, Instant to, UUID runId) {
        jdbc.update(
                "INSERT INTO"
                    + " integration_dhb_object_checkpoint(tenant_id,connector_id,object_type,successful_to,last_run_id,updated_at)"
                    + " VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " successful_to=GREATEST(successful_to,VALUES(successful_to)),last_run_id=VALUES(last_run_id),updated_at=VALUES(updated_at)",
                tenant.toString(),
                connector.toString(),
                scope,
                LocalDateTime.ofInstant(to, ZoneOffset.UTC),
                runId.toString(),
                LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }
}
