package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.application.port.out.CustomerAuthorityProjectionStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/** 从本领域表导出归属及版本；只读，不跨 Schema，也不推测缺失的历史归属。 */
@Repository
public class JdbcCustomerAuthorityProjectionStore implements CustomerAuthorityProjectionStore {
    private final JdbcTemplate jdbc;

    public JdbcCustomerAuthorityProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String version(String tenant) {
        Long current =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version),0) FROM crm_authority_state WHERE"
                            + " tenant_id=?",
                        Long.class,
                        tenant);
        String areas =
                jdbc.queryForObject(
                        "SELECT"
                            + " CONCAT(COUNT(*),':',COALESCE(SUM(revision),0),':',COALESCE(MAX(updated_time),'NONE'))"
                            + " FROM crm_customer_area WHERE tenant_id=UUID_TO_BIN(?)",
                        String.class,
                        tenant);
        String customers =
                jdbc.queryForObject(
                        "SELECT"
                            + " CONCAT(COUNT(*),':',COALESCE(SUM(revision),0),':',COALESCE(MAX(updated_time),'NONE'))"
                            + " FROM crm_customer WHERE tenant_id=?",
                        String.class,
                        tenant);
        return current + ":" + areas + ":" + customers;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page page(String tenant, long afterId, int step) {
        String version = version(tenant);
        var areas =
                jdbc.queryForList(
                        "SELECT area_code id,parent_area_code parentId,area_code code FROM"
                            + " crm_customer_area WHERE tenant_id=UUID_TO_BIN(?) AND deleted=0 AND"
                            + " status='ACTIVE'",
                        tenant);
        Map<String, Map<String, Object>> byId = new HashMap<>();
        Map<String, List<String>> paths = new HashMap<>();
        for (var a : areas) byId.put(a.get("id").toString(), a);
        List<Map<String, Object>> regions = new ArrayList<>();
        for (var a : areas) {
            List<String> path = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            var at = a;
            boolean valid = true;
            while (at != null) {
                String id = at.get("id").toString();
                if (!seen.add(id) || seen.size() > 32)
                    throw new IllegalStateException("客户地区树循环或层级异常");
                path.add(at.get("code").toString());
                Object parent = at.get("parentId");
                if (parent == null || parent.toString().isBlank()) break;
                at = byId.get(parent.toString());
                if (at == null) valid = false;
            }
            if (valid) {
                Collections.reverse(path);
                paths.put(a.get("code").toString(), List.copyOf(path));
                regions.add(Map.of("code", a.get("code"), "path", List.copyOf(path)));
            }
        }
        var items =
                jdbc.queryForList(
                        "SELECT id,owner_employee_code AS employeeCode,region_code AS"
                            + " regionCode,revision FROM crm_customer WHERE tenant_id=? AND"
                            + " deleted=0 AND id>? ORDER BY id LIMIT ?",
                        tenant,
                        afterId,
                        step);
        for (var c : items) c.put("regionPath", paths.getOrDefault(c.get("regionCode"), List.of()));
        return new Page(version, items, afterId == 0 ? regions : List.of());
    }
}
