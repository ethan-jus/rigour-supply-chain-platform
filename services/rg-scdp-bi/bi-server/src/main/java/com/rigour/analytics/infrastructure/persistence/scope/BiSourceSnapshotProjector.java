package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.analytics.application.port.out.BiSourceSnapshotClient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 完整批次发布本地源镜像，源版本变化、缺列、跨租户或网络失败都会回滚，不发布半份数据。 */
@Component
public class BiSourceSnapshotProjector {
    private static final Set<String> PEOPLE_DATASETS =
            Set.of("HR_EMPLOYEE", "SALES_SUBMITTED_VISIT", "CRM_CUSTOMER_AREA");
    private final JdbcTemplate jdbc;
    private final BiSourceSnapshotClient client;

    public BiSourceSnapshotProjector(JdbcTemplate jdbc, BiSourceSnapshotClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    @Transactional
    public void refresh(UUID tenantId) {
        // 人员来源只能与下游投影同时发布，局部业务刷新不得提前消耗人员版本。
        refresh(
                tenantId,
                BiSourceDatasets.ALL.stream()
                        .filter(d -> !PEOPLE_DATASETS.contains(d.code()))
                        .toList());
    }

    @Transactional
    public boolean refreshPeople(UUID tenantId) {
        return refresh(
                tenantId,
                BiSourceDatasets.ALL.stream()
                        .filter(d -> PEOPLE_DATASETS.contains(d.code()))
                        .toList());
    }

    private boolean refresh(UUID tenantId, List<BiSourceDatasets.Dataset> datasets) {
        boolean changed = false;
        String tenant = tenantId.toString();
        for (var d : datasets) {
            jdbc.update(
                    "INSERT IGNORE INTO"
                        + " bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at)"
                        + " VALUES(?,?,'UNINITIALIZED',UTC_TIMESTAMP(6))",
                    tenant,
                    d.code());
            String previous =
                    jdbc.queryForObject(
                            "SELECT source_version FROM bi_source_snapshot_checkpoint WHERE"
                                    + " tenant_id=? AND dataset=? FOR UPDATE",
                            String.class,
                            tenant,
                            d.code());
            String version = client.version(tenantId, d.source(), d.code());
            if (version.equals(previous)) continue;
            changed = true;
            String expected =
                    d.binaryTenant() ? tenant.replace("-", "").toUpperCase(Locale.ROOT) : tenant;
            jdbc.update(
                    "DELETE FROM "
                            + d.table()
                            + " WHERE tenant_id="
                            + (d.binaryTenant() ? "UUID_TO_BIN(?)" : "?"),
                    tenant);
            String insert =
                    "INSERT INTO "
                            + d.table()
                            + " ("
                            + String.join(
                                    ",",
                                    d.columns().stream()
                                            .map(BiSourceDatasets.Column::name)
                                            .toList())
                            + ") VALUES("
                            + String.join(",", Collections.nCopies(d.columns().size(), "?"))
                            + ")";
            Set<String> columns =
                    new HashSet<>(d.columns().stream().map(BiSourceDatasets.Column::name).toList());
            String cursor = "";
            int pages = 0;
            while (true) {
                if (++pages > 10000) throw new IllegalStateException("源投影超出单批容量");
                var page = client.page(tenantId, d.source(), d.code(), cursor);
                if (!version.equals(page.version()))
                    throw new IllegalStateException("源数据同步中发生变化，请重试");
                List<Object[]> batch = new ArrayList<>();
                for (var row : page.items()) {
                    if (!row.keySet().equals(columns)
                            || !expected.equalsIgnoreCase(row.get("tenant_id")))
                        throw new IllegalStateException("源投影字段或租户不匹配");
                    String next = row.get("id");
                    if (next == null
                            || !next.matches(d.binaryKey() ? "[0-9A-Fa-f]{32}" : "[0-9]{1,20}"))
                        throw new IllegalStateException("源记录标识无效");
                    if (!cursor.isEmpty()
                            && (d.binaryKey()
                                    ? next.compareToIgnoreCase(cursor) <= 0
                                    : new java.math.BigInteger(next)
                                                    .compareTo(new java.math.BigInteger(cursor))
                                            <= 0)) throw new IllegalStateException("源投影游标未前进");
                    cursor = next;
                    Object[] args = new Object[d.columns().size()];
                    for (int i = 0; i < args.length; i++) {
                        var col = d.columns().get(i);
                        String value = row.get(col.name());
                        args[i] =
                                value == null
                                        ? null
                                        : col.binary() ? HexFormat.of().parseHex(value) : value;
                    }
                    batch.add(args);
                }
                if (!batch.isEmpty()) jdbc.batchUpdate(insert, batch);
                if (page.items().size() < 1000) break;
            }
            if (!version.equals(client.version(tenantId, d.source(), d.code())))
                throw new IllegalStateException("源数据同步中发生变化，请重试");
            jdbc.update(
                    "UPDATE bi_source_snapshot_checkpoint SET"
                        + " source_version=?,projected_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND"
                        + " dataset=?",
                    version,
                    tenant,
                    d.code());
        }
        return changed;
    }
}
