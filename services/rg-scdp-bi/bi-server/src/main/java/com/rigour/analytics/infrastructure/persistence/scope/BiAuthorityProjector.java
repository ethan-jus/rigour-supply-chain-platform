package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.analytics.application.port.out.BiAuthoritySource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** 按源版本同步最小归属，整批成功后发布；源变化或中断回滚旧投影并拒绝当前请求。 */
@Component
public class BiAuthorityProjector {
    private final JdbcTemplate jdbc;
    private final BiAuthoritySource source;
    private final JsonMapper json = JsonMapper.builder().build();

    public BiAuthorityProjector(JdbcTemplate jdbc, BiAuthoritySource source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    @Transactional
    public void refresh(UUID tenantId) {
        String tenant = tenantId.toString();
        for (String kind : List.of("CRM", "ORDER")) {
            jdbc.update(
                    "INSERT IGNORE INTO"
                        + " bi_authority_checkpoint(tenant_id,source_code,source_version,projected_at)"
                        + " VALUES(?,?,'UNINITIALIZED',UTC_TIMESTAMP(6))",
                    tenant,
                    kind);
            String previous =
                    jdbc.queryForObject(
                            "SELECT source_version FROM bi_authority_checkpoint WHERE tenant_id=?"
                                + " AND source_code=? FOR UPDATE",
                            String.class,
                            tenant,
                            kind);
            String version = source.version(tenantId, kind);
            if (version.equals(previous)) continue;
            String table = "ORDER".equals(kind) ? "bi_order_authority" : "bi_customer_authority";
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
            if ("CRM".equals(kind))
                jdbc.update("DELETE FROM bi_region_authority WHERE tenant_id=?", tenant);
            long last = 0;
            int pages = 0;
            while (true) {
                if (++pages > 1000) throw new IllegalStateException("归属投影超过单次同步容量");
                var page = source.page(tenantId, kind, last);
                if (!version.equals(page.version()))
                    throw new IllegalStateException("归属来源同步中发生变化，请重试");
                if ("CRM".equals(kind) && last == 0)
                    for (var r : page.regions())
                        jdbc.update(
                                "INSERT INTO bi_region_authority(tenant_id,region_code,region_path)"
                                    + " VALUES(?,?,?)",
                                tenant,
                                text(r, "code"),
                                json(r.get("path")));
                for (var r : page.items()) {
                    long id = number(r, "id");
                    if (id <= last) throw new IllegalStateException("归属来源游标未前进");
                    last = id;
                    if ("ORDER".equals(kind))
                        jdbc.update(
                                "INSERT INTO"
                                    + " bi_order_authority(tenant_id,order_id,employee_code,employee_name,department_id,department_path,region_code,region_path,warehouse_id,attribution_state,source_version)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                tenant,
                                id,
                                r.get("employeeCode"),
                                r.get("employeeName"),
                                r.get("departmentId"),
                                json(r.get("departmentPath")),
                                r.get("regionCode"),
                                json(r.get("regionPath")),
                                r.get("warehouseId"),
                                r.get("attributionState"),
                                r.get("sourceVersion"));
                    else
                        jdbc.update(
                                "INSERT INTO"
                                    + " bi_customer_authority(tenant_id,customer_id,employee_code,region_code,region_path,source_revision)"
                                    + " VALUES(?,?,?,?,?,?)",
                                tenant,
                                id,
                                r.get("employeeCode"),
                                r.get("regionCode"),
                                json(r.get("regionPath")),
                                number(r, "revision"));
                }
                if (page.items().size() < 1000) break;
            }
            if (!version.equals(source.version(tenantId, kind)))
                throw new IllegalStateException("归属来源同步中发生变化，请重试");
            if ("CRM".equals(kind))
                jdbc.update(
                        "UPDATE bi_customer_dim f JOIN bi_customer_authority a ON"
                            + " a.tenant_id=f.tenant_id AND a.customer_id=f.customer_id SET"
                            + " f.owner_staff_name=IF(f.owner_staff_code <=>"
                            + " a.employee_code,f.owner_staff_name,NULL),f.region_name=IF(f.region_code"
                            + " <=> a.region_code,f.region_name,a.region_code),f.owner_staff_code=a.employee_code,f.region_code=a.region_code"
                            + " WHERE f.tenant_id=?",
                        tenant);
            jdbc.update(
                    "UPDATE bi_authority_checkpoint SET"
                        + " source_version=?,projected_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND"
                        + " source_code=?",
                    version,
                    tenant,
                    kind);
        }
    }

    private String json(Object value) {
        if (value == null) return "[]";
        if (value instanceof String s) {
            var node = json.readTree(s);
            if (!node.isArray()) throw new IllegalStateException("归属路径必须是数组");
            return node.toString();
        }
        return json.writeValueAsString(value);
    }

    private static String text(Map<String, Object> r, String key) {
        var v = r.get(key);
        if (v == null) throw new IllegalStateException("归属字段缺失");
        return v.toString();
    }

    private static long number(Map<String, Object> r, String key) {
        return Long.parseLong(text(r, key));
    }
}
