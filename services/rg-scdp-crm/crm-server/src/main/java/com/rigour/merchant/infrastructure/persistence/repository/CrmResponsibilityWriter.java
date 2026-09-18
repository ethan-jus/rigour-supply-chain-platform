package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.application.port.out.CrmEmployeeClient;
import com.rigour.shared.context.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** 与客户主表共用本地事务；记录主责/地区变更版本及来源冲突，已确认订单不回写。 */
@Component
public final class CrmResponsibilityWriter {
    private final JdbcTemplate jdbc;
    private final CrmEmployeeClient employees;

    public CrmResponsibilityWriter(JdbcTemplate jdbc, CrmEmployeeClient employees) {
        this.jdbc = jdbc;
        this.employees = employees;
    }

    public CrmEmployeeClient.Owner requireOwner(String tenant, String code) {
        if (code == null) return null;
        var owner = employees.owner(tenant, code);
        if (!owner.usable())
            throw new IllegalArgumentException("不能指定此主责员工：" + owner.unavailableReason());
        return owner;
    }

    public void requireAssignmentPermission() {
        if (AuthorizationContext.current().isPresent())
            AuthorizationContext.requirePermission("crm:customer:assign-owner");
    }

    public void changed(
            String tenant,
            long customer,
            String oldOwner,
            String oldName,
            String oldRegion,
            String newOwner,
            String newName,
            String newRegion,
            String source,
            String actor,
            String reason,
            boolean created) {
        boolean ownerChanged = !Objects.equals(oldOwner, newOwner),
                regionChanged = !Objects.equals(oldRegion, newRegion);
        if (!created && !ownerChanged && !regionChanged) return;
        jdbc.update(
                "UPDATE crm_customer SET"
                    + " owner_revision=owner_revision+?,region_revision=region_revision+? WHERE"
                    + " tenant_id=? AND id=?",
                ownerChanged ? 1 : 0,
                regionChanged ? 1 : 0,
                tenant,
                customer);
        jdbc.update(
                """
INSERT INTO crm_customer_responsibility_history(tenant_id,customer_id,old_employee_code,new_employee_code,old_employee_name,new_employee_name,old_region_code,new_region_code,change_type,reason,source_system,actor_id,customer_revision)
SELECT tenant_id,id,?,?,?,?,?,?,?,?,?,?,revision FROM crm_customer WHERE tenant_id=? AND id=?
""",
                oldOwner,
                newOwner,
                oldName,
                newName,
                oldRegion,
                newRegion,
                created
                        ? "CREATE"
                        : ownerChanged && regionChanged
                                ? "OWNER_AND_REGION"
                                : ownerChanged ? "OWNER" : "REGION",
                reason,
                source,
                actor,
                tenant,
                customer);
        change(tenant, "CUSTOMER", Long.toString(customer));
    }

    public void lockAuthority(String tenant) {
        jdbc.update(
                "INSERT INTO crm_authority_state(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE"
                    + " version=version",
                tenant);
        jdbc.queryForObject(
                "SELECT version FROM crm_authority_state WHERE tenant_id=? FOR UPDATE",
                Long.class,
                tenant);
    }

    public void change(String tenant, String type, String reference) {
        jdbc.update(
                "INSERT INTO crm_authority_state(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE"
                    + " version=version",
                tenant);
        jdbc.queryForObject(
                "SELECT version FROM crm_authority_state WHERE tenant_id=? FOR UPDATE",
                Long.class,
                tenant);
        jdbc.update("UPDATE crm_authority_state SET version=version+1 WHERE tenant_id=?", tenant);
        jdbc.update(
                "INSERT INTO crm_authority_change(tenant_id,version,object_type,object_ref) SELECT"
                    + " tenant_id,version,?,? FROM crm_authority_state WHERE tenant_id=?",
                type,
                reference,
                tenant);
    }

    public void sourceConflict(
            String tenant,
            long id,
            String source,
            String proposedOwner,
            String proposedRegion,
            String revision) {
        jdbc.update(
                """
INSERT INTO crm_responsibility_source_conflict(tenant_id,customer_id,source_system,proposed_employee_code,proposed_region_code,source_revision)
SELECT ?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM crm_responsibility_source_conflict WHERE tenant_id=? AND customer_id=? AND source_system=? AND proposed_employee_code <=> ? AND proposed_region_code <=> ? AND source_revision <=> ? )
""",
                tenant,
                id,
                source,
                proposedOwner,
                proposedRegion,
                revision,
                tenant,
                id,
                source,
                proposedOwner,
                proposedRegion,
                revision);
    }
}
