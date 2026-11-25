package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.shared.context.CallerIdentity;
import java.util.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只有唯一覆盖成交时刻的主部门任职才能作为历史部门证据。 */
@Component
public class OrderRegisterDepartmentWriter {
    private final JdbcTemplate jdbc;
    private final HrEmployeeDisplayClient hr;
    public OrderRegisterDepartmentWriter(JdbcTemplate jdbc,HrEmployeeDisplayClient hr) {
        this.jdbc=jdbc; this.hr=hr;
    }

    public void capture(String tenant, long id) {
        var rows=jdbc.queryForList("SELECT o.owner_employee_code,o.order_date FROM order_sales_order o"
                + " WHERE o.tenant_id=? AND o.id=? AND o.deleted=0 AND o.owner_employee_code IS NOT NULL"
                + " AND o.order_date IS NOT NULL AND (o.register_assignment_id IS NULL"
                + " OR NOT(o.register_employee_code COLLATE utf8mb4_general_ci <=> o.owner_employee_code AND o.register_order_date <=> o.order_date))"
                + " AND NOT EXISTS(SELECT 1 FROM order_attribution_snapshot s WHERE s.tenant_id=o.tenant_id"
                + " AND s.order_id=o.id AND s.state='FROZEN')",tenant,id);
        if (rows.isEmpty()) return;
        var row=rows.getFirst();
        String code=(String)row.get("owner_employee_code");
        Object date=row.get("order_date");
        Instant at=date instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC)
                : ((java.sql.Timestamp)date).toLocalDateTime().toInstant(ZoneOffset.UTC);
        var caller=new CallerIdentity("SERVICE",UUID.nameUUIDFromBytes(
                "service:rigour-order-center-service".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.fromString(tenant),null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("hr:employee:read"));
        var assignment=hr.assignmentAt(caller,code,at);
        if (assignment!=null) jdbc.update("UPDATE order_sales_order SET register_assignment_id=?,register_department_id=?,"
                + "register_department_name=?,register_employee_code=?,register_order_date=?,updated_time=updated_time"
                + " WHERE tenant_id=? AND id=? AND owner_employee_code=? AND order_date=?",
                assignment.id(),assignment.departmentId(),assignment.departmentName(),code,date,tenant,id,code,date);
    }
}
