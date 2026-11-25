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

    /** 仅补空归属；同名有效地区须唯一，地区父链完整，已有地区不跟随员工调岗。 */
    public void fillMissingRegionFromOwnerDepartment(String tenant, byte[] partyId) {
        var customers = jdbc.queryForList("SELECT id,owner_employee_code,owner_employee_name_snapshot,region_code,revision"
                + " FROM crm_customer WHERE tenant_id=? AND party_id=? AND deleted=0"
                + " AND (region_code IS NULL OR TRIM(region_code)='') FOR UPDATE", tenant, partyId);
        for (var customer : customers) {
            String code = (String)customer.get("owner_employee_code");
            if (code == null || code.isBlank()) continue;
            var owner = employees.owner(tenant, code);
            if (owner == null || !owner.usable() || !code.equals(owner.code()) || owner.departmentId() == null
                    || owner.departmentName() == null || owner.departmentName().isBlank()) continue;
            String department = owner.departmentName().strip();
            String cityAlias = department.endsWith("市") ? department.substring(0, department.length() - 1) : department + "市";
            var areas = jdbc.queryForList("SELECT area_code,area_name FROM crm_customer_area WHERE tenant_id=UUID_TO_BIN(?)"
                    + " AND area_name IN (?,?) AND status='ACTIVE' AND deleted=0", tenant, department, cityAlias);
            if (areas.size() != 1) continue;
            String region = (String)areas.getFirst().get("area_code"), next = region;
            var seen = new HashSet<String>();
            boolean valid = true;
            while (next != null) {
                if (!seen.add(next) || seen.size() > 100) { valid = false; break; }
                var parents = jdbc.queryForList("SELECT parent_area_code FROM crm_customer_area WHERE tenant_id=UUID_TO_BIN(?)"
                        + " AND area_code=? AND status='ACTIVE' AND deleted=0", tenant, next);
                if (parents.size() != 1) { valid = false; break; }
                next = (String)parents.getFirst().get("parent_area_code");
            }
            if (!valid) continue;
            long id = ((Number)customer.get("id")).longValue();
            int changed = jdbc.update("UPDATE crm_customer SET region_code=?,region_name=?,region_management_mode='LOCAL',"
                    + "revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?"
                    + " AND revision=? AND (region_code IS NULL OR TRIM(region_code)='')", region,
                    areas.getFirst().get("area_name"), tenant, id, customer.get("revision"));
            if (changed == 1) changed(tenant, id, code, (String)customer.get("owner_employee_name_snapshot"),
                    (String)customer.get("region_code"), code, (String)customer.get("owner_employee_name_snapshot"),
                    region, "SYSTEM", "SYSTEM", "归属地区为空，按业务员所属部门默认：" + department, false);
        }
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
        if ("LOCAL".equals(source)) {
            var owner = newOwner == null ? null : employees.owner(tenant,newOwner);
            jdbc.update("UPDATE crm_customer_responsibility_history SET effective_kind='CONFIRMED',new_department_id=?,new_department_name=? WHERE tenant_id=? AND customer_id=? AND customer_revision=(SELECT revision FROM crm_customer WHERE tenant_id=? AND id=?) AND source_system='LOCAL'",
                owner == null ? null : owner.departmentId(), owner == null ? null : owner.departmentName(),tenant,customer,tenant,customer);
        }
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
