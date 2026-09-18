package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.merchant.application.port.out.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 在线读取所属服务，带版本摘要；调用方在提交前重读核验，不使用旧缓存兜底。 */
@Repository
public class JdbcCustomerOrderAttributionStore implements CustomerOrderAttributionStore {
    private final JdbcTemplate jdbc;
    private final CrmEmployeeClient employees;

    public JdbcCustomerOrderAttributionStore(JdbcTemplate jdbc, CrmEmployeeClient employees) {
        this.jdbc = jdbc;
        this.employees = employees;
    }

    @Override
    public CustomerOrderAttributionView read(String tenant, long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " customer_code,customer_name,owner_employee_code,region_code,revision,status_code,owner_revision,region_revision"
                            + " FROM crm_customer WHERE tenant_id=? AND id=? AND deleted=0",
                        tenant,
                        id);
        if (rows.size() != 1) throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在", List.of());
        var row = rows.getFirst();
        String employee = (String) row.get("owner_employee_code"),
                region = (String) row.get("region_code");
        var owner = employee == null ? null : employees.owner(tenant, employee);
        List<String> path = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        String next = region, reason = null;
        while (next != null) {
            if (path.contains(next) || path.size() >= 100) {
                reason = "客户归属地区层级无效";
                break;
            }
            var areas =
                    jdbc.queryForList(
                            "SELECT area_code,parent_area_code,status,revision FROM"
                                + " crm_customer_area WHERE tenant_id=UUID_TO_BIN(?) AND"
                                + " area_code=? AND deleted=0",
                            tenant,
                            next);
            if (areas.size() != 1 || !"ACTIVE".equals(areas.getFirst().get("status"))) {
                reason = "客户归属地区未启用或不存在";
                break;
            }
            var area = areas.getFirst();
            path.add(next);
            versions.add(next + ":" + area.get("revision"));
            next = (String) area.get("parent_area_code");
        }
        if (!"ACTIVE".equals(row.get("status_code"))) reason = "客户未启用";
        else if (region == null) reason = "客户尚未分配经营归属地区";
        else if (owner == null) reason = "客户尚未分配主责员工";
        else if (!owner.usable()) reason = owner.unavailableReason();
        else if (owner.departmentId() == null || owner.departmentAncestorIds().isEmpty())
            reason = "主责员工尚未建立有效部门归属";
        long revision = ((Number) row.get("revision")).longValue();
        String version =
                hash(
                        tenant
                                + "|"
                                + id
                                + "|"
                                + revision
                                + "|"
                                + row.get("owner_revision")
                                + "|"
                                + row.get("region_revision")
                                + "|"
                                + versions
                                + "|"
                                + owner);
        // HR 调用期间 CRM 已变化时拒绝交付混合版本。
        Long current =
                jdbc.queryForObject(
                        "SELECT revision FROM crm_customer WHERE tenant_id=? AND id=? AND"
                            + " deleted=0",
                        Long.class,
                        tenant,
                        id);
        if (current == null || current != revision)
            throw new BusinessException(ErrorCode.CONFLICT, "客户已变更，请重新获取归属", List.of());
        return new CustomerOrderAttributionView(
                tenant,
                id,
                (String) row.get("customer_code"),
                (String) row.get("customer_name"),
                employee,
                owner == null ? null : owner.name(),
                owner == null ? null : owner.departmentId(),
                owner == null ? null : owner.departmentName(),
                owner == null ? List.of() : owner.departmentAncestorIds(),
                region,
                path,
                version,
                revision,
                owner == null ? 0 : owner.revision(),
                owner == null ? 0 : owner.organizationVersion(),
                Instant.now(),
                reason == null,
                reason);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
