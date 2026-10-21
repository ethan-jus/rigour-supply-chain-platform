package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.analytics.application.port.out.BiSourceSnapshotClient;
import com.rigour.analytics.infrastructure.persistence.mapper.BiSourceSnapshotMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 完整批次发布本地源镜像，源版本变化、缺列、跨租户或网络失败都会回滚，不发布半份数据。 */
@Component
public class BiSourceSnapshotProjector {
    private static final Set<String> PEOPLE_DATASETS =
            Set.of("HR_DEPARTMENT", "HR_EMPLOYEE", "SALES_SUBMITTED_VISIT", "CRM_CUSTOMER_AREA");
    private static final Logger log = LoggerFactory.getLogger(BiSourceSnapshotProjector.class);
    private final BiSourceSnapshotMapper mapper;
    private final BiSourceSnapshotClient client;

    public BiSourceSnapshotProjector(BiSourceSnapshotMapper mapper, BiSourceSnapshotClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Transactional
    public void refresh(UUID tenantId) {
        // 销售组织与员工部门也是财务维度；人员刷新使用独立检查点，避免业务同步消耗人员版本。
        refresh(
                tenantId,
                BiSourceDatasets.ALL.stream()
                        .filter(
                                d ->
                                        !PEOPLE_DATASETS.contains(d.code())
                                                || Set.of(
                                                                "CRM_CUSTOMER_AREA",
                                                                "HR_DEPARTMENT",
                                                                "HR_EMPLOYEE")
                                                        .contains(d.code()))
                        .toList());
    }

    @Transactional
    public boolean refreshOrganization(UUID tenantId) {
        // 部门校验只依赖当前组织和地区；独立检查点确保业务同步不会消耗维度重建信号。
        return refresh(
                tenantId,
                BiSourceDatasets.ALL.stream()
                        .filter(
                                d ->
                                        Set.of("HR_DEPARTMENT", "HR_EMPLOYEE", "CRM_CUSTOMER_AREA")
                                                .contains(d.code()))
                        .toList(),
                "ORGANIZATION_");
    }

    @Transactional
    public boolean refreshPeople(UUID tenantId) {
        return refresh(
                tenantId,
                BiSourceDatasets.ALL.stream()
                        .filter(d -> PEOPLE_DATASETS.contains(d.code()))
                        .toList(),
                "PEOPLE_");
    }

    private boolean refresh(UUID tenantId, List<BiSourceDatasets.Dataset> datasets) {
        return refresh(tenantId, datasets, "");
    }

    private boolean refresh(
            UUID tenantId, List<BiSourceDatasets.Dataset> datasets, String checkpointPrefix) {
        boolean changed = false;
        String tenant = tenantId.toString();
        for (var d : datasets) {
            String checkpoint = checkpointPrefix + d.code();
            mapper.initialize(tenant, checkpoint);
            String previous = mapper.lockVersion(tenant, checkpoint);
            String version = client.version(tenantId, d.source(), d.code());
            if (version.equals(previous)) continue;
            changed = true;
            String expected =
                    d.binaryTenant() ? tenant.replace("-", "").toUpperCase(Locale.ROOT) : tenant;
            mapper.clearDataset(tenant, d.code());
            Set<String> columns =
                    new HashSet<>(d.columns().stream().map(BiSourceDatasets.Column::name).toList());
            String cursor = "";
            int pages = 0;
            int rowCount = 0;
            while (true) {
                if (++pages > 10000) throw new IllegalStateException("源投影超出单批容量");
                var page = client.page(tenantId, d.source(), d.code(), cursor);
                if (!version.equals(page.version()))
                    throw new IllegalStateException("源数据同步中发生变化，请重试");
                List<Map<String, Object>> batch = new ArrayList<>();
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
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (var col : d.columns()) {
                        String value = row.get(col.name());
                        values.put(
                                col.name(),
                                value == null
                                        ? null
                                        : col.binary() ? HexFormat.of().parseHex(value) : value);
                    }
                    batch.add(values);
                }
                if (!batch.isEmpty()) mapper.insertRows(d.code(), batch);
                rowCount += batch.size();
                if (page.items().size() < 1000) break;
            }
            if (!version.equals(client.version(tenantId, d.source(), d.code())))
                throw new IllegalStateException("源数据同步中发生变化，请重试");
            mapper.publish(tenant, checkpoint, version);
            log.info(
                    "BI 源快照已写入当前事务: tenant={}, dataset={}, rows={}, pages={}",
                    tenant,
                    d.code(),
                    rowCount,
                    pages);
        }
        return changed;
    }
}
