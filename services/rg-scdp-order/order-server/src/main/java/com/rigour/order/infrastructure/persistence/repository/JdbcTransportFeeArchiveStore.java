package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.TransportFeeArchiveStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcTransportFeeArchiveStore implements TransportFeeArchiveStore {
    private final JdbcTemplate jdbc;

    public JdbcTransportFeeArchiveStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Archive find(String tenant, String actor, String hash) {
        var rows =
                jdbc.query(
                        "SELECT id,file_key FROM order_transport_fee_import_file WHERE tenant_id=?"
                                + " AND actor_id=? AND file_hash=?",
                        (r, n) -> new Archive(r.getString(1), r.getString(2)),
                        tenant,
                        actor,
                        hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Archive insert(
            String tenant, String actor, String hash, String name, String key, long size) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO"
                    + " order_transport_fee_import_file(id,tenant_id,actor_id,file_hash,file_name,file_key,file_size)"
                    + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                id,
                tenant,
                actor,
                hash,
                name,
                key,
                size);
        return find(tenant, actor, hash);
    }
}
