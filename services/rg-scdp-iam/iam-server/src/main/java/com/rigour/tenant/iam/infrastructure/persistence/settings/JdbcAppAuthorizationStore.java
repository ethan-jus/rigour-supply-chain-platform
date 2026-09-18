package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.shared.context.CallerIdentity;
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
            throw new IllegalArgumentException("必须且只能指定用户或员工");
        var effective = authorization(caller, "crm:customer:assign-owner"); // 重新核验签名原操作人的会话。
        Actor actor = new Actor("TENANT", caller.userId(), caller.tenantId());
        Set<String> permissions =
                "ACTIVE".equals(effective.mode())
                        ? effective.permissions()
                        : legacyAccess
                                .readCurrentUser(
                                        new com.rigour.tenant.iam.application.service.identity
                                                .IdentityAccessQuery(
                                                "TENANT", actor.principalId(), actor.tenantId()))
                                .permissions();
        boolean legacyWildcard =
                !"ACTIVE".equals(effective.mode()) && permissions.contains("*:*:*");
        boolean assignmentAllowed =
                legacyWildcard || permissions.contains("crm:customer:assign-owner");
        boolean userReadAllowed =
                legacyWildcard
                        || permissions.contains("supply:user:read")
                                && (permissions.contains("crm:customer:read") || assignmentAllowed);
        if (userId == null ? !assignmentAllowed && !userReadAllowed : !userReadAllowed)
            throw new AccessDeniedException("缺少客户主责分配或用户客户查询权限");
        // 旧 CRM 入口在切换前只遵循旧授权与 HR 事实；准备中的成员状态/上限不能提前接管业务。
        // 用户配置页显式按 userId 查询仍需核验待配置的新模型。
        if (userId == null && !"ACTIVE".equals(effective.mode())) {
            var employee = employees.employee(actor.tenantId(), employeeCode);
            String reason = employeeUnavailableReason(employee);
            return new com.rigour.tenant.iam.application.model.settings.CustomerAssignmentTarget(
                    actor.tenantId(),
                    null,
                    employeeCode,
                    employee == null ? null : employee.employeeName(),
                    null,
                    reason == null,
                    reason,
                    effective.applicationVersion(),
                    new Limit("ALL", List.of()));
        }
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
        if (targets.size() > 1) throw new IllegalStateException("员工关联不唯一，无法分配客户");
        if (userId != null && targets.isEmpty())
            throw new IllegalArgumentException("供应链用户不存在或未关联员工");
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
                target == null
                        ? new Limit("ALL", List.of())
                        : limit(members.limit(actor, target.user(), "REGION")));
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
            throw new IllegalArgumentException("业务动作编码无效");
        requireSession(caller);
        Actor actor = new Actor("TENANT", caller.userId(), caller.tenantId());
        settings.requireIdentity(actor);
        if (!settings.initialized(actor)) return legacy(actor, action, 0);
        var context = settings.context(actor);
        if (!"ACTIVE".equals(context.mode())
                && !action.startsWith("supply:parameter:")
                && !action.equals("supply:audit:read"))
            return legacy(actor, action, context.version());
        return proposed(actor, action);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public AppAuthorizationSnapshot candidate(CallerIdentity caller, String action) {
        // 正式入口完成会话、身份和编码校验，再读取候选策略；不改变应用模式。
        var effective = authorization(caller, action);
        if (!"PREPARING".equals(effective.mode()) || effective.applicationVersion() == 0)
            throw new IllegalStateException("只有准备阶段可比较候选数据授权");
        var actor = new Actor("TENANT", caller.userId(), caller.tenantId());
        try {
            return proposed(actor, action);
        } catch (AccessDeniedException e) {
            return new AppAuthorizationSnapshot(
                    "PREPARING",
                    actor.tenantId(),
                    actor.principalId(),
                    null,
                    effective.applicationVersion(),
                    0,
                    0,
                    0,
                    Set.of(),
                    action,
                    false,
                    List.of(),
                    new Limit("NONE", List.of()),
                    new Limit("NONE", List.of()));
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void observeData(
            CallerIdentity caller,
            com.rigour.tenant.iam.application.model.settings.AppDataObservation r) {
        if (r == null
                || r.domain() == null
                || !Set.of("ORDER", "CRM", "ERP", "HR", "BI").contains(r.domain())
                || r.recordKey() == null
                || r.recordKey().isBlank()
                || r.recordKey().length() > 160) throw new IllegalArgumentException("数据对比对象无效");
        String prefix =
                switch (r.domain()) {
                    case "ORDER" -> "order:";
                    case "CRM" -> "crm:";
                    case "ERP" -> "erp:";
                    case "HR" -> "hr:";
                    default -> "analytics:";
                };
        if (r.action() == null
                || (!r.action().startsWith(prefix)
                        && !("BI".equals(r.domain()) && r.action().startsWith("bi:"))))
            throw new IllegalArgumentException("操作与数据领域不匹配");
        var next = candidate(caller, r.action());
        if (next.applicationVersion() != r.applicationVersion()
                || next.memberVersion() != r.memberVersion()
                || next.employeeRevision() != r.employeeRevision()
                || next.organizationVersion() != r.organizationVersion())
            throw new IllegalStateException("候选授权已变化，请重新采样");
        if (r.proposedAllowed() && !next.functionAllowed())
            throw new IllegalArgumentException("数据决定不能越过功能授权");
        String json =
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(next);
        jdbc.update(
                "INSERT INTO"
                    + " iam_app_data_observation(tenant_id,application_id,user_id,application_version,action_code,domain_code,record_key,legacy_allowed,proposed_allowed,policy_json)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " legacy_allowed=VALUES(legacy_allowed),proposed_allowed=VALUES(proposed_allowed),policy_json=VALUES(policy_json),sample_count=sample_count+1,observed_at=UTC_TIMESTAMP(6)",
                bin(caller.tenantId()),
                bin(settings.applicationId()),
                bin(caller.userId()),
                next.applicationVersion(),
                r.action(),
                r.domain(),
                r.recordKey(),
                r.legacyAllowed(),
                r.proposedAllowed(),
                json);
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
            String object = com.rigour.tenant.iam.domain.model.settings.AppScopeRules.objectType(action);
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
                for (var rule : role.rules())
                    if (action.equals(rule.actionCode()) && !"NONE".equals(rule.scopeMode())) {
                        Map<String, List<String>> values =
                                assignment.parameters().getOrDefault(rule.id(), Map.of());
                        Limit departments =
                                resolve(
                                        rule.departmentMode(),
                                        rule.references().getOrDefault("DEPARTMENT", List.of()),
                                        values.getOrDefault("DEPARTMENT", List.of()),
                                        employee == null ? null : employee.departmentId());
                        Limit regions =
                                resolve(
                                        rule.regionMode(),
                                        rule.references().getOrDefault("REGION", List.of()),
                                        values.getOrDefault("REGION", List.of()),
                                        null);
                        Limit warehouses =
                                resolve(
                                        rule.warehouseMode(),
                                        rule.references().getOrDefault("WAREHOUSE", List.of()),
                                        values.getOrDefault("WAREHOUSE", List.of()),
                                        null);
                        if (requiredMissing(rule.departmentMode(), departments)
                                || requiredMissing(rule.regionMode(), regions)
                                || requiredMissing(rule.warehouseMode(), warehouses)) continue;
                        clauses.add(
                                new Clause(
                                        role.id(),
                                        rule.objectType(),
                                        rule.scopeMode(),
                                        departments,
                                        regions,
                                        warehouses,
                                        rule.includeDescendants()));
                    }
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
                administratorRole != null ? all : limit(members.limit(actor, actor.principalId(), "REGION")),
                administratorRole != null ? all : limit(members.limit(actor, actor.principalId(), "WAREHOUSE")));
    }

    @org.springframework.beans.factory.annotation.Autowired private IdentityAccessReader legacyAccess;

    /** 使用当前会话和数据库旧授权核对功能差异，JSON保留完整候选范围和旧角色证据。 */
    @org.springframework.transaction.annotation.Transactional
    public void observe(CallerIdentity caller, String action, String legacyAction) {
        if (caller == null || !"TENANT".equals(caller.principalScope()))
            throw new AccessDeniedException("需要原操作人");
        requireSession(caller);
        var actor = new Actor("TENANT", caller.userId(), caller.tenantId());
        settings.requireIdentity(actor);
        if (!settings.initialized(actor) || !"PREPARING".equals(settings.context(actor).mode()))
            return;
        for (String code : Arrays.asList(action, legacyAction))
            if (code == null
                    || !code.matches("[a-z][a-z0-9-]*(?::[a-z0-9_-]+){1,3}")
                    || settings.count(
                                    "SELECT COUNT(*) FROM iam_resource WHERE application_id=? AND"
                                            + " permission_code=? AND deleted_at IS NULL",
                                    bin(settings.applicationId()),
                                    code)
                            == 0) throw new IllegalArgumentException("对比操作未在供应链注册");
        var previous =
                legacyAccess.readCurrentUser(
                        new com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery(
                                "TENANT", actor.principalId(), actor.tenantId()));
        boolean oldAllowed =
                previous.permissions().contains(legacyAction)
                        || previous.permissions().contains("*:*:*");
        AppAuthorizationSnapshot next = null;
        String failure = null;
        try {
            next = proposed(actor, action);
        } catch (AccessDeniedException e) {
            failure = "MEMBER_UNAVAILABLE";
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("legacyRoles", previous.roles());
        evidence.put("policy", next);
        evidence.put("reason", failure);
        String json =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsString(evidence);
        jdbc.update(
                "INSERT INTO"
                    + " iam_app_authorization_observation(tenant_id,application_id,user_id,application_version,action_code,legacy_action_code,legacy_allowed,proposed_allowed,policy_json)"
                    + " VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " legacy_allowed=VALUES(legacy_allowed),proposed_allowed=VALUES(proposed_allowed),policy_json=VALUES(policy_json),sample_count=sample_count+1,observed_at=UTC_TIMESTAMP(6)",
                bin(actor.tenantId()),
                bin(settings.applicationId()),
                bin(actor.principalId()),
                settings.context(actor).version(),
                action,
                legacyAction,
                oldAllowed,
                next != null && next.functionAllowed(),
                json);
    }

    private static Limit limit(
            com.rigour.tenant.iam.application.service.settings.AppMemberModels.Limit x) {
        return new Limit(x.mode(), x.references());
    }

    private static boolean requiredMissing(String mode, Limit value) {
        return !"NONE".equals(mode) && "NONE".equals(value.mode());
    }

    private static Limit resolve(
            String mode, List<String> fixed, List<String> assigned, Long current) {
        return switch (mode) {
            case "ALL" -> new Limit("ALL", List.of());
            case "SPECIFIED" -> new Limit(fixed.isEmpty() ? "NONE" : "SPECIFIED", fixed);
            case "MEMBER", "MANAGED" ->
                    new Limit(assigned.isEmpty() ? "NONE" : "SPECIFIED", assigned);
            case "CURRENT" ->
                    new Limit(
                            current == null ? "NONE" : "SPECIFIED",
                            current == null ? List.of() : List.of(current.toString()));
            default -> new Limit("NONE", List.of());
        };
    }

    private static AppAuthorizationSnapshot legacy(Actor actor, String action, long version) {
        return new AppAuthorizationSnapshot(
                "PREPARING",
                actor.tenantId(),
                actor.principalId(),
                null,
                version,
                0,
                0,
                0,
                Set.of(),
                action,
                false,
                List.of(),
                new Limit("NONE", List.of()),
                new Limit("NONE", List.of()));
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
