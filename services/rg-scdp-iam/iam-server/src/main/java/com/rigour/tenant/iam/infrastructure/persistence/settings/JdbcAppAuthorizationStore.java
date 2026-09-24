package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot.*;
import com.rigour.tenant.iam.application.port.out.*;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

import java.util.*;

/** 读取当前会话、员工和按动作完整角色子句；历史路径由业务单据提供。 */
@Repository
public class JdbcAppAuthorizationStore implements AppAuthorizationStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final JdbcAppRoleStore roles;
    private final JdbcAppMemberStore members;
    private final AppEmployeeClient employees;

    public JdbcAppAuthorizationStore(
            JdbcTemplate jdbc,
            JdbcAppSettingsStore settings,
            JdbcAppRoleStore roles,
            JdbcAppMemberStore members,
            AppEmployeeClient employees) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.roles = roles;
        this.members = members;
        this.employees = employees;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Override
    public com.rigour.tenant.iam.application.model.settings.CustomerAssignmentTarget
            customerAssignmentTarget(CallerIdentity caller, String employeeCode, UUID userId) {
        if ((employeeCode == null) == (userId == null)
                || (employeeCode != null && (employeeCode.isBlank() || employeeCode.length() > 50)))
            throw new RequestValidationException("必须且只能指定用户或员工");
        var effective = authorization(caller, "crm:customer:assign-owner"); // 重新核验签名原操作人的会话。
        Actor actor = new Actor("TENANT", caller.userId(), caller.tenantId());
        Set<String> permissions = effective.permissions();
        boolean assignmentAllowed = permissions.contains("crm:customer:assign-owner");
        boolean userReadAllowed =
                permissions.contains("supply:user:read")
                        && (permissions.contains("crm:customer:read") || assignmentAllowed);
        if (userId == null ? !assignmentAllowed && !userReadAllowed : !userReadAllowed)
            throw new AccessDeniedException("缺少客户主责分配或用户客户查询权限");
        record Target(
                UUID user,
                String code,
                String status,
                String kind,
                String globalStatus,
                long access) {}
        List<Target> targets =
                jdbc.query(
                        "SELECT m.user_id,b.employee_code,m.status,m.member_kind,u.status AS"
                            + " global_status,b.hr_access_version FROM iam_app_employee_binding b"
                            + " JOIN iam_app_member m ON m.tenant_id=b.tenant_id AND"
                            + " m.application_id=b.application_id AND m.user_id=b.user_id JOIN"
                            + " iam_user u ON u.tenant_id=m.tenant_id AND u.id=m.user_id WHERE"
                            + " b.tenant_id=? AND b.application_id=? AND m.deleted_at IS NULL AND"
                            + " u.deleted_at IS NULL"
                                + (userId == null ? " AND b.employee_code=?" : " AND b.user_id=?"),
                        (rs, n) ->
                                new Target(
                                        com.rigour.tenant.iam.infrastructure.persistence
                                                .UuidBinaryCodec.decode(rs.getBytes(1)),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getLong(6)),
                        bin(actor.tenantId()),
                        bin(settings.applicationId()),
                        userId == null ? employeeCode : bin(userId));
        if (targets.size() > 1) throw new StateConflictException("员工关联不唯一，无法分配客户");
        if (userId != null && targets.isEmpty())
            throw new RequestValidationException("供应链用户不存在或未关联员工");
        Target target = targets.isEmpty() ? null : targets.getFirst();
        var employee =
                employees.employee(actor.tenantId(), target == null ? employeeCode : target.code());
        String reason = employeeUnavailableReason(employee);
        if (target != null) {
            if ("PROTECTED".equals(target.kind())) reason = "受保护恢复账号不能分配业务客户";
            else if (!"ACTIVE".equals(target.status()) || !"ACTIVE".equals(target.globalStatus()))
                reason = "目标用户已禁用";
            else if (employee != null && target.access() != employee.accessVersion())
                reason = "目标用户员工关联需要重新核验";
            else if (!settings.eligible(new Actor("TENANT", target.user(), actor.tenantId())))
                reason = "目标用户没有有效供应链资格";
        }
        return new com.rigour.tenant.iam.application.model.settings.CustomerAssignmentTarget(
                actor.tenantId(),
                target == null ? null : target.user(),
                target == null ? employeeCode : target.code(),
                employee == null ? null : employee.employeeName(),
                target == null ? null : target.status(),
                reason == null,
                reason,
                settings.context(actor).version(),
                new Limit("ALL", List.of()));
    }

    private static String employeeUnavailableReason(AppEmployeeClient.Employee employee) {
        if (employee == null) return "员工不存在";
        if (employee.usable()) return null;
        return employee.unavailableReason() == null || employee.unavailableReason().isBlank()
                ? "员工不可用"
                : employee.unavailableReason();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Override
    public AppAuthorizationSnapshot authorization(CallerIdentity caller, String action) {
        if (caller == null
                || !"TENANT".equals(caller.principalScope())
                || caller.tenantId() == null) throw new AccessDeniedException("需要受信的原操作人身份");
        if (action == null || !action.matches("[a-z][a-z0-9-]*(?::[a-z0-9_-]+){1,3}"))
            throw new RequestValidationException("业务动作编码无效");
        requireSession(caller);
        Actor actor = new Actor("TENANT", caller.userId(), caller.tenantId());
        settings.requireIdentity(actor);
        if (!settings.initialized(actor)) throw new AccessDeniedException("供应链尚未初始化");
        return proposed(actor, action);
    }

    /** 与正式授权共用解析器，预览不切换模式、不伪造登录会话，也不授予目标账号权限。 */
    AppAuthorizationSnapshot proposed(Actor actor, String action) {
        var context = settings.context(actor);
        if (!settings.eligible(actor)) throw new AccessDeniedException("供应链账号或员工资格不可用");
        var app = bin(settings.applicationId());
        String code =
                jdbc
                        .queryForList(
                                "SELECT employee_code FROM iam_app_employee_binding WHERE"
                                        + " tenant_id=? AND application_id=? AND user_id=?",
                                String.class,
                                bin(actor.tenantId()),
                                app,
                                bin(actor.principalId()))
                        .stream()
                        .findFirst()
                        .orElse(null);
        var employee = code == null ? null : employees.employee(actor.tenantId(), code);
        long memberVersion =
                jdbc.queryForObject(
                        "SELECT security_version FROM iam_app_member WHERE tenant_id=? AND"
                                + " application_id=? AND user_id=?",
                        Long.class,
                        bin(actor.tenantId()),
                        app,
                        bin(actor.principalId()));
        UUID administratorRole = settings.protectedAdministratorRole(actor, null);
        Limit all = new Limit("ALL", List.of());
        List<Clause> clauses = new ArrayList<>();
        // 仍先核验动作权限、租户和账号资格；管理员的数据范围随新功能自动生效。
        if (context.permissions().contains(action) && administratorRole != null) {
            String object =
                    com.rigour.tenant.iam.domain.model.settings.AppScopeRules.objectType(action);
            if (object != null)
                clauses.add(new Clause(administratorRole, object, "ALL", all, all, all, true));
        } else if (context.permissions().contains(action))
            for (var assignment : members.assignments(actor, actor.principalId())) {
                var role = roles.role(actor, assignment.roleId());
                if (!"ACTIVE".equals(role.status())) continue;
                boolean grantsAction =
                        settings.enabledActionNodes(actor, action).stream()
                                .anyMatch(role.menuNodeIds()::contains);
                if (!grantsAction) continue;
                var scope = role.dataScope();
                String object =
                        com.rigour.tenant.iam.domain.model.settings.AppScopeRules.objectType(
                                action);
                if (scope == null || object == null) continue;
                Limit departments =
                        switch (scope.mode()) {
                            case "CUSTOM" -> new Limit("SPECIFIED", scope.departmentIds());
                            case "DEPARTMENT" ->
                                    employee == null || employee.departmentId() == null
                                            ? new Limit("NONE", List.of())
                                            : new Limit(
                                                    "SPECIFIED",
                                                    List.of(employee.departmentId().toString()));
                            default -> all;
                        };
                if ("NONE".equals(departments.mode())) continue;
                if ("SELF".equals(scope.mode()) && code == null) continue;
                clauses.add(
                        new Clause(
                                role.id(),
                                object,
                                "CUSTOM".equals(scope.mode()) ? "DEPARTMENT" : scope.mode(),
                                departments,
                                all,
                                all,
                                false));
            }
        return new AppAuthorizationSnapshot(
                context.mode(),
                actor.tenantId(),
                actor.principalId(),
                code,
                context.version(),
                memberVersion,
                employee == null ? 0 : employee.employeeRevision(),
                employee == null ? 0 : employee.organizationVersion(),
                context.permissions(),
                action,
                context.permissions().contains(action),
                clauses,
                all,
                all);
    }

    private void requireSession(CallerIdentity c) {
        int count =
                settings.count(
                        """
SELECT COUNT(*) FROM iam_auth_session s JOIN iam_user u ON u.tenant_id=s.tenant_id AND u.id=s.principal_id JOIN iam_tenant t ON t.id=s.tenant_id
WHERE s.id=? AND s.principal_scope='TENANT' AND s.tenant_id=? AND s.principal_id=? AND s.status='ACTIVE' AND s.expires_at>UTC_TIMESTAMP(6)
  AND s.version=? AND u.security_version=? AND t.policy_version=? AND u.status='ACTIVE' AND u.deleted_at IS NULL AND t.status='ACTIVE' AND t.deleted_at IS NULL
""",
                        bin(c.sessionId()),
                        bin(c.tenantId()),
                        bin(c.principalId()),
                        c.sessionVersion(),
                        c.userSecurityVersion(),
                        c.tenantPolicyVersion());
        if (count != 1) throw new AccessDeniedException("原操作人登录会话已失效");
    }
}
