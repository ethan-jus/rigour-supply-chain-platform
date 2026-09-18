package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.CustomerResponsibilityApi.*;
import com.rigour.merchant.application.port.out.CustomerResponsibilityStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 人工移交和来源差异确认与 CRM 当前主责、历史和版本在同一事务完成。 */
@Repository
public class JdbcCustomerResponsibilityStore implements CustomerResponsibilityStore {
    private final JdbcTemplate jdbc;
    private final CrmDataScope scopes;
    private final CrmResponsibilityWriter writer;
    private final CustomerAssignmentScope targets;
    private final CustomerIdentityGuard identities;

    public JdbcCustomerResponsibilityStore(
            JdbcTemplate jdbc,
            CrmDataScope scopes,
            CrmResponsibilityWriter writer,
            CustomerAssignmentScope targets, CustomerIdentityGuard identities) {
        this.identities = identities;
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.writer = writer;
        this.targets = targets;
    }

    private static final String ACTION = "crm:customer:assign-owner";

    private Map<String, Object> customer(String tenant, long id, boolean lock) {
        scopes.requireCustomer(tenant, id, ACTION);
        if (lock) identities.lock(tenant);
        return jdbc
                .queryForList(
                        "SELECT"
                            + " id,customer_name,login_account,city_name,owner_employee_code,owner_employee_name_snapshot,region_code,revision,owner_management_mode,region_management_mode"
                            + " FROM crm_customer WHERE tenant_id=? AND id=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        tenant,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> missing("客户不存在"));
    }

    public Overview overview(String tenant, long id) {
        var c = customer(tenant, id, false);
        var history =
                jdbc.query(
                        "SELECT * FROM crm_customer_responsibility_history WHERE tenant_id=? AND"
                                + " customer_id=? ORDER BY effective_at DESC,id DESC LIMIT 100",
                        (r, n) ->
                                new History(
                                        r.getLong("id"),
                                        r.getString("old_employee_code"),
                                        r.getString("old_employee_name"),
                                        r.getString("new_employee_code"),
                                        r.getString("new_employee_name"),
                                        r.getString("old_region_code"),
                                        r.getString("new_region_code"),
                                        r.getString("reason"),
                                        r.getString("actor_id"),
                                        r.getTimestamp("effective_at").toInstant()),
                        tenant,
                        id);
        var conflicts =
                jdbc.query(
                        "SELECT * FROM crm_responsibility_source_conflict WHERE tenant_id=? AND"
                            + " customer_id=? AND status='PENDING' ORDER BY created_at DESC,id DESC"
                            + " LIMIT 100",
                        (r, n) ->
                                new Conflict(
                                        r.getLong("id"),
                                        r.getString("source_system"),
                                        r.getString("proposed_employee_code"),
                                        r.getString("proposed_region_code"),
                                        r.getString("source_revision"),
                                        r.getTimestamp("created_at").toInstant()),
                        tenant,
                        id);
        return new Overview(
                id,
                text(c, "customer_name"),
                text(c, "owner_employee_code"),
                text(c, "owner_employee_name_snapshot"),
                text(c, "region_code"),
                number(c, "revision"),
                history,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crm_customer_responsibility_history WHERE tenant_id=?"
                                + " AND customer_id=?",
                        Long.class,
                        tenant,
                        id),
                conflicts,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crm_responsibility_source_conflict WHERE tenant_id=?"
                                + " AND customer_id=? AND status='PENDING'",
                        Long.class,
                        tenant,
                        id));
    }

    @Transactional
    public Overview transfer(String tenant, long id, Change c, String actor) {
        if (c == null) throw invalid("归属变更不能为空");
        var current = customer(tenant, id, true);
        apply(tenant, id, c, actor, current);
        return overview(tenant, id);
    }

    @Transactional
    public Overview resolve(String tenant, long id, long conflict, Resolution c, String actor) {
        if (c == null || !Set.of("KEEP_LOCAL", "USE_SOURCE").contains(c.decision()))
            throw invalid("请选择保留当前归属或采用本次来源归属");
        reason(c.reason());
        var current = customer(tenant, id, true);
        revision(current, c.customerRevision());
        var source =
                jdbc
                        .queryForList(
                                "SELECT * FROM crm_responsibility_source_conflict WHERE tenant_id=?"
                                    + " AND customer_id=? AND id=? AND status='PENDING' FOR UPDATE",
                                tenant,
                                id,
                                conflict)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> conflict("来源差异不存在或已处理，请刷新"));
        if ("USE_SOURCE".equals(c.decision()))
            apply(
                    tenant,
                    id,
                    new Change(
                            text(source, "proposed_employee_code"),
                            text(source, "proposed_region_code"),
                            c.customerRevision(),
                            c.reason()),
                    actor,
                    current);
        jdbc.update(
                "UPDATE crm_responsibility_source_conflict SET"
                    + " status=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(6),resolution_reason=?,customer_revision=?"
                    + " WHERE tenant_id=? AND customer_id=? AND id=? AND status='PENDING'",
                c.decision(),
                actor,
                c.reason().trim(),
                number(customer(tenant, id, false), "revision"),
                tenant,
                id,
                conflict);
        return overview(tenant, id);
    }

    private void apply(String tenant, long id, Change c, String actor, Map<String, Object> before) {
        apply(tenant, id, c, actor, before, null, false);
    }

    /** 仅批量事务调用：同一目标员工已统一在线核验，提交前再次核验成员授权。 */
    void applyInBatch(
            String tenant,
            long id,
            Change c,
            String actor,
            Map<String, Object> before,
            com.rigour.merchant.application.port.out.CrmEmployeeClient.Owner owner) {
        apply(tenant, id, c, actor, before, owner, true);
    }

    private void apply(
            String tenant,
            long id,
            Change c,
            String actor,
            Map<String, Object> before,
            com.rigour.merchant.application.port.out.CrmEmployeeClient.Owner verifiedOwner,
            boolean batch) {
        reason(c.reason());
        revision(before, c.revision());
        String owner = clean(c.employeeCode()), region = clean(c.regionCode());
        if (owner != null && owner.length() > 50) throw invalid("员工编码过长");
        if (region == null || region.length() > 128) throw invalid("请选择客户归属地区");
        var identity = batch ? verifiedOwner : writer.requireOwner(tenant, owner);
        if (owner != null
                && (identity == null || !owner.equals(identity.code()) || !identity.usable()))
            throw invalid("目标员工身份不一致");
        String name = identity == null ? null : identity.name();
        // 有效地区的完整父链必须可用，不能挂在已停用的父级下。
        Set<String> seen = new HashSet<>();
        String next = region;
        while (next != null) {
            if (!seen.add(next) || seen.size() > 100) throw invalid("客户地区层级无效");
            var rows =
                    jdbc.queryForList(
                            "SELECT parent_area_code FROM crm_customer_area WHERE"
                                + " tenant_id=UUID_TO_BIN(?) AND area_code=? AND status='ACTIVE'"
                                + " AND deleted=0",
                            tenant,
                            next);
            if (rows.size() != 1) throw invalid("请选择当前有效的客户归属地区");
            next = (String) rows.getFirst().get("parent_area_code");
        }
        scopes.requireProposed(tenant, owner, region, ACTION);
        if (!batch) targets.requireEmployeeRegion(tenant, owner, region);
        String identityConflict = identities.conflict(tenant,id,text(before,"customer_name"),text(before,"login_account"),text(before,"city_name"),region);
        if (identityConflict != null) throw conflict(identityConflict);
        int updated =
                jdbc.update(
                        "UPDATE crm_customer SET"
                            + " owner_employee_code=?,owner_employee_name_snapshot=?,owner_sales_user_id=NULL,owner_sales_name=NULL,region_code=?,owner_management_mode='LOCAL',region_management_mode='LOCAL',revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6)"
                            + " WHERE tenant_id=? AND id=? AND revision=? AND deleted=0",
                        owner,
                        name,
                        region,
                        actor,
                        tenant,
                        id,
                        c.revision());
        if (updated != 1) throw conflict("客户已被修改，请刷新");
        boolean same =
                Objects.equals(owner, text(before, "owner_employee_code"))
                        && Objects.equals(region, text(before, "region_code"));
        if (same) {
            jdbc.update(
                    """
INSERT INTO crm_customer_responsibility_history(tenant_id,customer_id,old_employee_code,new_employee_code,old_employee_name,new_employee_name,old_region_code,new_region_code,change_type,reason,source_system,actor_id,customer_revision)
VALUES(?,?,?,?,?,?,?,?,'LOCAL_CONTROL',?,'LOCAL',?,?)
""",
                    tenant,
                    id,
                    owner,
                    owner,
                    text(before, "owner_employee_name_snapshot"),
                    name,
                    region,
                    region,
                    c.reason().trim(),
                    actor,
                    c.revision() + 1);
            writer.change(tenant, "CUSTOMER", Long.toString(id));
        } else
            writer.changed(
                    tenant,
                    id,
                    text(before, "owner_employee_code"),
                    text(before, "owner_employee_name_snapshot"),
                    text(before, "region_code"),
                    owner,
                    name,
                    region,
                    "LOCAL",
                    actor,
                    c.reason().trim(),
                    false);
    }

    private static void revision(Map<String, Object> c, long expected) {
        if (number(c, "revision") != expected) throw conflict("客户已被修改，请刷新后重新确认");
    }

    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 500)
            throw invalid("请填写不超过500字的变更原因");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static BusinessException invalid(String m) {
        return new BusinessException(ErrorCode.BAD_REQUEST, m, List.of());
    }

    private static BusinessException missing(String m) {
        return new BusinessException(ErrorCode.NOT_FOUND, m, List.of());
    }

    private static BusinessException conflict(String m) {
        return new BusinessException(ErrorCode.CONFLICT, m, List.of());
    }
}
