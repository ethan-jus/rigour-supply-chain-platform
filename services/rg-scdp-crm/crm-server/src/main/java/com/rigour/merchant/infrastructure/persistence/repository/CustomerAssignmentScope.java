package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.application.port.out.CustomerAssignmentTargetClient;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** IAM 提供用户上限，CRM 负责把自己的地区树解析为可选客户集合。 */
@Component
public final class CustomerAssignmentScope {
    private final CustomerAssignmentTargetClient targets;
    private final JdbcTemplate jdbc;

    public CustomerAssignmentScope(CustomerAssignmentTargetClient targets, JdbcTemplate jdbc) {
        this.targets = targets;
        this.jdbc = jdbc;
    }

    public CustomerAssignmentTargetView member(String tenant, UUID userId, boolean write) {
        var target = targets.byUser(tenant, userId);
        if (target == null
                || !tenant.equals(target.tenantId().toString())
                || !userId.equals(target.userId())
                || target.employeeCode() == null) throw invalid("目标供应链用户关联员工无效");
        if (write) usable(target);
        return target;
    }

    public void requireEmployeeRegion(String tenant, String employee, String region) {
        if (employee == null) return;
        var target = targets.byEmployee(tenant, employee);
        if (target == null
                || !tenant.equals(target.tenantId().toString())
                || !employee.equals(target.employeeCode())) throw invalid("目标员工地区权限暂无法核验");
        requireRegion(tenant, target, region);
    }

    public CrmDataScope.Predicate predicate(
            String tenant, CustomerAssignmentTargetView target, String field) {
        var args = new ArrayList<Object>();
        String ceiling =
                CrmDataScope.region(
                        UUID.fromString(tenant), target.regionLimit(), true, field, args);
        args.add(tenant);
        args.add(tenant);
        String valid =
                field
                        + " IN (WITH RECURSIVE assignable_regions AS (SELECT area_code FROM"
                        + " crm_customer_area WHERE tenant_id=UUID_TO_BIN(?) AND deleted=0 AND"
                        + " status='ACTIVE' AND parent_area_code IS NULL UNION DISTINCT SELECT"
                        + " child.area_code FROM crm_customer_area child JOIN assignable_regions"
                        + " parent ON child.parent_area_code=parent.area_code WHERE"
                        + " child.tenant_id=UUID_TO_BIN(?) AND child.deleted=0 AND"
                        + " child.status='ACTIVE') SELECT area_code FROM assignable_regions)";
        return new CrmDataScope.Predicate("(" + ceiling + " AND " + valid + ")", args);
    }

    public void requireRegion(String tenant, CustomerAssignmentTargetView target, String region) {
        usable(target);
        var p = predicate(tenant, target, "candidate.region_code");
        var args = new ArrayList<Object>();
        args.add(region);
        args.addAll(p.args());
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT ? AS region_code) candidate WHERE " + p.sql(),
                        Integer.class,
                        args.toArray())
                != 1) throw new AuthorizationDeniedException("客户不在目标用户允许的归属地区内");
    }

    public static String fingerprint(CustomerAssignmentTargetView target) {
        var refs = new ArrayList<>(target.regionLimit().references());
        Collections.sort(refs);
        String input =
                target.tenantId()
                        + "|"
                        + target.userId()
                        + "|"
                        + target.employeeCode()
                        + "|"
                        + target.memberStatus()
                        + "|"
                        + target.usable()
                        + "|"
                        + target.authorizationVersion()
                        + "|"
                        + target.regionLimit().mode()
                        + "|"
                        + refs;
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void usable(CustomerAssignmentTargetView target) {
        if (!target.usable())
            throw invalid("目标用户或员工不可用：" + Objects.toString(target.unavailableReason(), "请重新核验关联"));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
