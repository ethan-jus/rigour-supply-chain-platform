package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.application.port.out.DhbIncrementalWorkStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Repository
public class JdbcDhbIncrementalWorkStore implements DhbIncrementalWorkStore {
    private final JdbcTemplate jdbc;

    public JdbcDhbIncrementalWorkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Set<String> stage(
            UUID tenant, UUID connector, String type, List<Item> items, Instant now) {
        if (items.isEmpty()) return Set.of();
        // 一页一次批量写、一次批量读，避免每张订单额外往返数据库。
        var args = new ArrayList<Object>();
        for (var item : items) {
            Collections.addAll(
                    args,
                    tenant.toString(),
                    connector.toString(),
                    type,
                    item.id(),
                    item.fingerprint(),
                    item.payload(),
                    utc(item.sourceUpdatedAt()),
                    utc(now));
        }
        jdbc.update(
                "INSERT INTO"
                    + " integration_dhb_incremental_work(tenant_id,connector_id,object_type,source_id,fingerprint,payload,source_updated_at,retry_at)"
                    + " VALUES "
                        + String.join(",", Collections.nCopies(items.size(), "(?,?,?,?,?,?,?,?)"))
                        + " ON DUPLICATE KEY UPDATE"
                        + " applied=IF("
                        + acceptsVersion()
                        + ",IF(fingerprint=VALUES(fingerprint),applied,0),applied),"
                        + " retry_at=IF("
                        + acceptsVersion()
                        + " AND fingerprint<>VALUES(fingerprint),VALUES(retry_at),retry_at),"
                        + " payload=IF("
                        + acceptsVersion()
                        + ",VALUES(payload),payload),"
                        + " fingerprint=IF("
                        + acceptsVersion()
                        + ",VALUES(fingerprint),fingerprint),"
                        + " source_updated_at=IF("
                        + acceptsVersion()
                        + ",VALUES(source_updated_at),source_updated_at)",
                args.toArray());
        var query = new ArrayList<Object>(List.of(tenant.toString(), connector.toString(), type));
        items.forEach(i -> query.add(i.id()));
        // 短重叠窗口快速跳过；一天后仍复核，不永久信任来源版本字段。
        var incoming = new HashMap<String, Item>();
        items.forEach(i -> incoming.put(i.id(), i));
        var skip = new HashSet<String>();
        jdbc.query(
                "SELECT source_id,fingerprint,source_updated_at,applied,checked_at FROM"
                    + " integration_dhb_incremental_work WHERE tenant_id=? AND connector_id=? AND"
                    + " object_type=? AND source_id IN ("
                        + String.join(",", Collections.nCopies(items.size(), "?"))
                        + ")",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        r -> {
                            var item = incoming.get(r.getString(1));
                            var updated = instant(r.getObject(3, LocalDateTime.class));
                            var checked = instant(r.getObject(5, LocalDateTime.class));
                            boolean stale =
                                    updated != null
                                            && item.sourceUpdatedAt() != null
                                            && item.sourceUpdatedAt().isBefore(updated);
                            boolean unchanged =
                                    item.fingerprint().equals(r.getString(2))
                                            && r.getBoolean(4)
                                            && checked != null
                                            && !checked.isBefore(now.minus(Duration.ofDays(1)))
                                            && (!"SALES_ORDER".equals(type) || updated != null);
                            if (stale || unchanged) skip.add(item.id());
                        },
                query.toArray());
        return skip;
    }

    private static String acceptsVersion() {
        return "(source_updated_at IS NULL OR VALUES(source_updated_at) IS NULL OR"
                   + " source_updated_at<=VALUES(source_updated_at))";
    }

    public void complete(
            UUID tenant, UUID connector, String type, Item item, boolean applied, Instant now) {
        jdbc.update(
                "UPDATE integration_dhb_incremental_work SET applied=?,checked_at=?,retry_at=?"
                    + " WHERE tenant_id=? AND connector_id=? AND object_type=? AND source_id=? AND"
                    + " fingerprint=?",
                applied ? 1 : 0,
                utc(now),
                utc(now.plusSeconds(300)),
                tenant.toString(),
                connector.toString(),
                type,
                item.id(),
                item.fingerprint());
    }

    public List<Item> pending(UUID tenant, UUID connector, String type, int limit, Instant now) {
        return jdbc.query(
                "SELECT source_id,fingerprint,payload,source_updated_at FROM"
                    + " integration_dhb_incremental_work WHERE tenant_id=? AND connector_id=? AND"
                    + " object_type=? AND applied=0 AND retry_at<=? ORDER BY retry_at,source_id"
                    + " LIMIT ?",
                (r, i) ->
                        new Item(
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                instant(r.getObject(4, LocalDateTime.class))),
                tenant.toString(),
                connector.toString(),
                type,
                utc(now),
                limit);
    }

    public long pendingCount(UUID tenant, UUID connector, String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration_dhb_incremental_work"
                        + " WHERE tenant_id=? AND connector_id=? AND object_type=? AND applied=0",
                Long.class,
                tenant.toString(),
                connector.toString(),
                type);
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
