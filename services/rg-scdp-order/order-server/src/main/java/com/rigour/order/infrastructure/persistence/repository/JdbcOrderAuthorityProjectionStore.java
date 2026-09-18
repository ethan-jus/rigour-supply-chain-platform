package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.OrderAuthorityProjectionStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/** 从本领域表导出归属及版本；只读，不跨 Schema，也不推测缺失的历史归属。 */
@Repository
public class JdbcOrderAuthorityProjectionStore implements OrderAuthorityProjectionStore {
    private final JdbcTemplate jdbc;

    public JdbcOrderAuthorityProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String version(String tenant) {
        return jdbc.queryForObject(
                "SELECT"
                    + " CONCAT(COUNT(*),':',COALESCE(SUM(o.revision),0),':',COALESCE(SUM(s.revision),0),':',COALESCE(MAX(o.updated_time),'NONE'))"
                    + " FROM order_sales_order o LEFT JOIN order_attribution_snapshot s ON"
                    + " s.tenant_id=o.tenant_id AND s.order_id=o.id WHERE o.tenant_id=?",
                String.class,
                tenant);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page page(String tenant, long afterId, int step) {
        String version = version(tenant);
        var items =
                jdbc.queryForList(
                        """
SELECT o.id AS id,s.employee_code AS employeeCode,s.employee_name AS employeeName,s.department_id AS departmentId,
s.department_path AS departmentPath,s.region_code AS regionCode,s.region_path AS regionPath,
o.selected_warehouse_id AS warehouseId,COALESCE(s.state,'REVIEW') AS attributionState,s.source_version AS sourceVersion
FROM order_sales_order o LEFT JOIN order_attribution_snapshot s ON s.tenant_id=o.tenant_id AND s.order_id=o.id
WHERE o.tenant_id=? AND o.deleted=0 AND o.id>? ORDER BY o.id LIMIT ?
""",
                        tenant,
                        afterId,
                        step);
        return new Page(version, items, List.of());
    }
}
