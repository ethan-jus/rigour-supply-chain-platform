package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.tenant.iam.application.port.out.AppRoleStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;
import com.rigour.tenant.iam.domain.model.settings.AppScopeRules;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 角色及授权写入始终与菜单版本和审计处于同一个应用事务。 */
@Repository
public class JdbcAppRoleStore implements AppRoleStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final TransactionTemplate tx;
    private final AppReferenceValidator references;
    private final com.rigour.tenant.iam.application.port.out.AppEmployeeClient employees;

    public JdbcAppRoleStore(
            JdbcTemplate jdbc,
            JdbcAppSettingsStore settings,
            PlatformTransactionManager manager,
            AppReferenceValidator references,
            com.rigour.tenant.iam.application.port.out.AppEmployeeClient employees) {
        this.employees = employees;
        this.references = references;
        this.jdbc = jdbc;
        this.settings = settings;
        this.tx = new TransactionTemplate(manager);
    }

    private byte[] tenant(Actor a) {
        return bin(a.tenantId());
    }

    private byte[] app() {
        return bin(settings.applicationId());
    }

    @Override
    public List<Role> roles(Actor a) {
        settings.requirePermission(a, "supply:role:read");
        return all(a);
    }

    @Override
    public List<Role> memberRoleCatalog(Actor a) {
        settings.requireIdentity(a);
        var p = settings.permissions(a);
        if (!p.contains("supply:user:read") && !p.contains("supply:user:assign-role"))
            throw new AccessDeniedException("无权读取用户角色目录");
        return all(a).stream().filter(r -> !r.protectedRole()).toList();
    }

    public List<Role> all(Actor a) {
        return jdbc.query(
                "SELECT * FROM iam_app_role WHERE tenant_id=? AND application_id=? AND deleted_at"
                        + " IS NULL ORDER BY protected_role DESC,role_name,id",
                (r, n) -> {
                    UUID id = UuidBinaryCodec.decode(r.getBytes("id"));
                    return new Role(
                            id,
                            r.getString("role_code"),
                            r.getString("role_name"),
                            r.getString("description"),
                            r.getString("status"),
                            r.getBoolean("protected_role"),
                            r.getLong("version"),
                            settings.count(
                                    "SELECT COUNT(*) FROM iam_app_member_role WHERE tenant_id=? AND"
                                            + " application_id=? AND role_id=?",
                                    tenant(a),
                                    app(),
                                    bin(id)),
                            grants(a, id),
                            rules(a, id));
                },
                tenant(a),
                app());
    }

    public Role role(Actor a, UUID id) {
        return all(a).stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    }

    private Set<UUID> grants(Actor a, UUID id) {
        return new HashSet<>(
                jdbc.query(
                        "SELECT menu_node_id FROM iam_app_role_grant WHERE tenant_id=? AND"
                                + " application_id=? AND role_id=?",
                        (r, n) -> UuidBinaryCodec.decode(r.getBytes(1)),
                        tenant(a),
                        app(),
                        bin(id)));
    }

    private List<ScopeRule> rules(Actor a, UUID role) {
        return jdbc.query(
                "SELECT * FROM iam_app_scope_rule WHERE tenant_id=? AND application_id=? AND"
                        + " role_id=? ORDER BY action_code",
                (r, n) -> {
                    UUID id = UuidBinaryCodec.decode(r.getBytes("id"));
                    Map<String, List<String>> refs = new TreeMap<>();
                    jdbc.query(
                            "SELECT dimension,reference_key FROM iam_app_scope_reference WHERE"
                                + " tenant_id=? AND application_id=? AND scope_rule_id=? ORDER BY"
                                + " reference_key",
                            rs -> {
                                refs.computeIfAbsent(rs.getString(1), key -> new ArrayList<>())
                                        .add(rs.getString(2));
                            },
                            tenant(a),
                            app(),
                            bin(id));
                    return new ScopeRule(
                            id,
                            r.getString("action_code"),
                            r.getString("object_type"),
                            r.getString("scope_mode"),
                            r.getString("department_mode"),
                            r.getString("region_mode"),
                            r.getString("warehouse_mode"),
                            r.getBoolean("include_descendants"),
                            Map.copyOf(refs));
                },
                tenant(a),
                app(),
                bin(role));
    }

    @Override
    public List<Node> grantableMenus(Actor a) {
        settings.requirePermission(a, "supply:role:grant");
        return settings.rows(a);
    }

    @Override
    public Role saveRole(Actor a, UUID id, RoleCommand c) {
        if (c == null) throw new IllegalArgumentException("角色参数不能为空");
        return tx.execute(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(
                            a, id == null ? "supply:role:create" : "supply:role:update");
                    Role old = id == null ? null : role(a, id);
                    if (old != null && old.protectedRole())
                        throw new IllegalArgumentException("受保护的恢复角色不支持普通编辑");
                    if (c.version() != (old == null ? 0 : old.version()))
                        throw new IllegalStateException("角色已修改，请刷新");
                    String code = text(c.code(), 64, "角色编码"), name = text(c.name(), 128, "角色名称");
                    if (!code.matches("[A-Z][A-Z0-9_]{1,63}"))
                        throw new IllegalArgumentException("角色编码使用大写字母、数字和下划线，且以字母开头");
                    if (old != null && !old.code().equals(code))
                        throw new IllegalArgumentException("角色编码不能修改");
                    if (!Set.of("ACTIVE", "DISABLED").contains(c.status()))
                        throw new IllegalArgumentException("角色状态无效");
                    if (c.description() != null && c.description().length() > 500)
                        throw new IllegalArgumentException("角色说明过长");
                    if (c.menuNodeIds() == null || c.rules() == null)
                        throw new IllegalArgumentException("请提交完整菜单授权和数据规则");
                    boolean grantsChanged =
                            old == null
                                    ? !c.menuNodeIds().isEmpty() || !c.rules().isEmpty()
                                    : !old.menuNodeIds().equals(c.menuNodeIds())
                                            || !old.rules().equals(c.rules());
                    if (grantsChanged) {
                        settings.requirePermission(a, "supply:role:grant");
                        requireDelegable(a, c.menuNodeIds(), c.rules());
                    }
                    if (old != null
                            && (grantsChanged
                                    || (!"ACTIVE".equals(old.status())
                                            && "ACTIVE".equals(c.status())))) {
                        requireDelegable(a, c.menuNodeIds(), c.rules());
                        if (!protectedAdministrator(a)
                                && c.rules().stream()
                                        .anyMatch(r -> "CURRENT".equals(r.departmentMode())))
                            for (String employeeCode :
                                    jdbc.queryForList(
                                            "SELECT b.employee_code FROM iam_app_member_role mr"
                                                + " JOIN iam_app_employee_binding b ON"
                                                + " b.tenant_id=mr.tenant_id AND"
                                                + " b.application_id=mr.application_id AND"
                                                + " b.user_id=mr.user_id WHERE mr.tenant_id=? AND"
                                                + " mr.application_id=? AND mr.role_id=?",
                                            String.class,
                                            tenant(a),
                                            app(),
                                            bin(id)))
                                requireCurrentDepartmentDelegable(
                                        a,
                                        employees.employee(a.tenantId(), employeeCode),
                                        c.rules());
                    }
                    Map<UUID, Node> nodes =
                            settings.rows(a).stream()
                                    .collect(Collectors.toMap(Node::id, Function.identity()));
                    for (UUID node : c.menuNodeIds())
                        if (!nodes.containsKey(node)
                                || !AppMenuTree.enabled(nodes.get(node), nodes))
                            throw new IllegalArgumentException("授权菜单不存在或已停用");
                    Set<String> actions =
                            c.menuNodeIds().stream()
                                    .map(nodes::get)
                                    .map(Node::permissionCode)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toSet());
                    Set<String> ruleActions = new HashSet<>();
                    for (ScopeRule rule : c.rules()) {
                        if (!ruleActions.add(rule.actionCode())
                                || !actions.contains(rule.actionCode()))
                            throw new IllegalArgumentException("规则动作重复或未授予对应功能");
                        AppScopeRules.validate(
                                rule.actionCode(),
                                rule.objectType(),
                                rule.scopeMode(),
                                rule.departmentMode(),
                                rule.regionMode(),
                                rule.warehouseMode());
                        validateReferences(rule);
                        for (var dimension : rule.references().entrySet())
                            references.requireActive(
                                    a.tenantId(), dimension.getKey(), dimension.getValue());
                    }
                    if (old != null
                            && "ACTIVE".equals(old.status())
                            && "DISABLED".equals(c.status())) {
                        RoleImpact impact = roleImpact(a, old);
                        if (!impact.lastRoleUsernames().isEmpty())
                            throw new IllegalArgumentException(
                                    "不能停用启用用户的最后一个有效角色，请先分配替代角色："
                                            + String.join("、", impact.lastRoleUsernames()));
                        if (!impact.managementEntryUsernames().isEmpty())
                            throw new IllegalArgumentException("请先完成管理员交接，不能移除最后一个可用管理入口");
                    }
                    UUID target = id == null ? UUID.randomUUID() : id;
                    if (old == null)
                        jdbc.update(
                                "INSERT INTO"
                                    + " iam_app_role(tenant_id,application_id,id,role_code,role_name,description,status)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                tenant(a),
                                app(),
                                bin(target),
                                code,
                                name,
                                c.description(),
                                c.status());
                    else
                        jdbc.update(
                                "UPDATE iam_app_role SET"
                                    + " role_name=?,description=?,status=?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                                    + " WHERE tenant_id=? AND application_id=? AND id=?",
                                name,
                                c.description(),
                                c.status(),
                                tenant(a),
                                app(),
                                bin(target));
                    jdbc.update(
                            "DELETE FROM iam_app_role_grant WHERE tenant_id=? AND application_id=?"
                                    + " AND role_id=?",
                            tenant(a),
                            app(),
                            bin(target));
                    for (UUID node : c.menuNodeIds())
                        jdbc.update(
                                "INSERT INTO"
                                    + " iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)"
                                    + " VALUES(?,?,?,?)",
                                tenant(a),
                                app(),
                                bin(target),
                                bin(node));
                    saveRules(a, target, old == null ? List.of() : old.rules(), c.rules());
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                    settings.audit(
                            a,
                            old == null ? "ROLE_CREATE" : "ROLE_UPDATE",
                            target.toString(),
                            name
                                    + "；功能 "
                                    + c.menuNodeIds().size()
                                    + " 项；数据规则 "
                                    + c.rules().size()
                                    + " 项");
                    return role(a, target);
                });
    }

    private void saveRules(Actor a, UUID role, List<ScopeRule> old, List<ScopeRule> next) {
        Map<String, ScopeRule> existing =
                old.stream().collect(Collectors.toMap(ScopeRule::actionCode, Function.identity()));
        Set<String> retained = next.stream().map(ScopeRule::actionCode).collect(Collectors.toSet());
        for (ScopeRule removed : old)
            if (!retained.contains(removed.actionCode())) deleteRule(a, removed.id());
        for (ScopeRule rule : next) {
            ScopeRule previous = existing.get(rule.actionCode());
            UUID id = previous == null ? UUID.randomUUID() : previous.id();
            if (rule.id() != null && (previous == null || !rule.id().equals(id)))
                throw new IllegalArgumentException("规则标识已变化，请刷新");
            if (previous == null)
                jdbc.update(
                        "INSERT INTO"
                            + " iam_app_scope_rule(tenant_id,application_id,id,role_id,action_code,object_type,scope_mode,department_mode,region_mode,warehouse_mode,include_descendants)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        tenant(a),
                        app(),
                        bin(id),
                        bin(role),
                        rule.actionCode(),
                        rule.objectType(),
                        rule.scopeMode(),
                        rule.departmentMode(),
                        rule.regionMode(),
                        rule.warehouseMode(),
                        rule.includeDescendants());
            else
                jdbc.update(
                        "UPDATE iam_app_scope_rule SET"
                            + " object_type=?,scope_mode=?,department_mode=?,region_mode=?,warehouse_mode=?,include_descendants=?,version=version+1"
                            + " WHERE tenant_id=? AND application_id=? AND id=?",
                        rule.objectType(),
                        rule.scopeMode(),
                        rule.departmentMode(),
                        rule.regionMode(),
                        rule.warehouseMode(),
                        rule.includeDescendants(),
                        tenant(a),
                        app(),
                        bin(id));
            jdbc.update(
                    "DELETE FROM iam_app_scope_reference WHERE tenant_id=? AND application_id=? AND"
                            + " scope_rule_id=?",
                    tenant(a),
                    app(),
                    bin(id));
            for (var dimension : rule.references().entrySet())
                for (String ref : dimension.getValue())
                    jdbc.update(
                            "INSERT INTO"
                                + " iam_app_scope_reference(tenant_id,application_id,scope_rule_id,dimension,reference_key)"
                                + " VALUES(?,?,?,?,?)",
                            tenant(a),
                            app(),
                            bin(id),
                            dimension.getKey(),
                            ref);
        }
    }

    private void deleteRule(Actor a, UUID id) {
        jdbc.update(
                "DELETE FROM iam_app_member_role_scope WHERE tenant_id=? AND application_id=? AND"
                        + " scope_rule_id=?",
                tenant(a),
                app(),
                bin(id));
        jdbc.update(
                "DELETE FROM iam_app_scope_reference WHERE tenant_id=? AND application_id=? AND"
                        + " scope_rule_id=?",
                tenant(a),
                app(),
                bin(id));
        jdbc.update(
                "DELETE FROM iam_app_scope_rule WHERE tenant_id=? AND application_id=? AND id=?",
                tenant(a),
                app(),
                bin(id));
    }

    private static void validateReferences(ScopeRule rule) {
        if (rule.references() == null) throw new IllegalArgumentException("规则引用不能为空");
        for (var refs : rule.references().entrySet()) {
            if (!AppScopeRules.DIMENSIONS.contains(refs.getKey())
                    || refs.getValue() == null
                    || refs.getValue().size() > 1000
                    || refs.getValue().size() != new HashSet<>(refs.getValue()).size())
                throw new IllegalArgumentException("规则引用维度或数量无效");
            for (String ref : refs.getValue()) text(ref, 128, "授权范围标识");
        }
        for (String dimension : AppScopeRules.DIMENSIONS) {
            String mode =
                    switch (dimension) {
                        case "DEPARTMENT" -> rule.departmentMode();
                        case "REGION" -> rule.regionMode();
                        default -> rule.warehouseMode();
                    };
            boolean specified = "SPECIFIED".equals(mode),
                    has = !rule.references().getOrDefault(dimension, List.of()).isEmpty();
            if (specified != has)
                throw new IllegalArgumentException(specified ? "指定范围至少选择一项" : "只有指定范围可以保存固定引用");
        }
    }

    public boolean protectedAdministrator(Actor a) {
        return settings.protectedAdministrator(a);
    }

    /** 普通授权管理员只能再授予自身持有的完整条件；不能拼接两个角色的维度扩权。 */
    public void requireDelegable(Actor a, Set<UUID> nodes, List<ScopeRule> rules) {
        if (protectedAdministrator(a)) return;
        Set<String> owned = settings.permissions(a);
        Map<UUID, Node> menus =
                settings.rows(a).stream().collect(Collectors.toMap(Node::id, Function.identity()));
        for (UUID id : nodes) {
            Node n = menus.get(id);
            if (n == null || (n.permissionCode() != null && !owned.contains(n.permissionCode())))
                throw new AccessDeniedException("只能授予管理边界内的功能");
        }
        Set<UUID> roleIds =
                new HashSet<>(
                        jdbc.query(
                                "SELECT role_id FROM iam_app_member_role WHERE tenant_id=? AND"
                                        + " application_id=? AND user_id=?",
                                (r, n) -> UuidBinaryCodec.decode(r.getBytes(1)),
                                tenant(a),
                                app(),
                                bin(a.principalId())));
        List<ScopeRule> ownedRules =
                all(a).stream()
                        .filter(r -> roleIds.contains(r.id()) && "ACTIVE".equals(r.status()))
                        .flatMap(r -> r.rules().stream())
                        .toList();
        for (ScopeRule r : rules)
            if (ownedRules.stream().noneMatch(o -> sameCondition(o, r)))
                throw new AccessDeniedException("数据规则超出当前可授予边界");
    }

    public void requireCurrentDepartmentDelegable(
            Actor actor,
            com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee target,
            List<ScopeRule> rules) {
        if (protectedAdministrator(actor)
                || rules.stream().noneMatch(r -> "CURRENT".equals(r.departmentMode()))) return;
        record Binding(String code, long access) {}
        Binding binding =
                jdbc
                        .query(
                                "SELECT employee_code,hr_access_version FROM"
                                    + " iam_app_employee_binding WHERE tenant_id=? AND"
                                    + " application_id=? AND user_id=?",
                                (rs, n) -> new Binding(rs.getString(1), rs.getLong(2)),
                                tenant(actor),
                                app(),
                                bin(actor.principalId()))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new AccessDeniedException("操作者员工关联不可用"));
        var employee = employees.employee(actor.tenantId(), binding.code());
        if (employee == null || !employee.usable() || employee.accessVersion() != binding.access())
            throw new AccessDeniedException("操作者员工关联已失效，请重新核验授权");
        for (var rule : rules)
            if ("CURRENT".equals(rule.departmentMode()))
                JdbcAppMemberStore.requireCurrentDepartmentWithin(
                        employee, target, rule.includeDescendants());
    }

    private static boolean sameCondition(ScopeRule a, ScopeRule b) {
        return Objects.equals(a.actionCode(), b.actionCode())
                && Objects.equals(a.objectType(), b.objectType())
                && Objects.equals(a.scopeMode(), b.scopeMode())
                && Objects.equals(a.departmentMode(), b.departmentMode())
                && Objects.equals(a.regionMode(), b.regionMode())
                && Objects.equals(a.warehouseMode(), b.warehouseMode())
                && a.includeDescendants() == b.includeDescendants()
                && Objects.equals(a.references(), b.references());
    }

    @Override
    public RoleImpact impact(Actor a, UUID id) {
        settings.requireIdentity(a);
        Set<String> permissions = settings.permissions(a);
        if (!permissions.contains("supply:role:update")
                && !permissions.contains("supply:role:delete"))
            throw new AccessDeniedException("无权预览角色变更影响");
        return roleImpact(a, role(a, id));
    }

    private RoleImpact roleImpact(Actor a, Role role) {
        String members =
                " FROM iam_app_member_role mr JOIN iam_user u ON u.tenant_id=mr.tenant_id AND"
                    + " u.id=mr.user_id JOIN iam_app_member m ON m.tenant_id=mr.tenant_id AND"
                    + " m.application_id=mr.application_id AND m.user_id=mr.user_id WHERE"
                    + " mr.tenant_id=? AND mr.application_id=? AND mr.role_id=? AND m.deleted_at IS"
                    + " NULL";
        List<String> usernames =
                jdbc.query(
                        "SELECT u.username" + members + " ORDER BY u.username LIMIT 100",
                        (rs, n) -> rs.getString(1),
                        tenant(a),
                        app(),
                        bin(role.id()));
        int activeUsers =
                settings.count(
                        "SELECT COUNT(*)" + members + " AND m.status='ACTIVE'",
                        tenant(a),
                        app(),
                        bin(role.id()));
        List<String> lastRoles =
                jdbc.query(
                        "SELECT u.username"
                                + members
                                + " AND m.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM"
                                + " iam_app_member_role other_mr JOIN iam_app_role other_r ON"
                                + " other_r.tenant_id=other_mr.tenant_id AND"
                                + " other_r.application_id=other_mr.application_id AND"
                                + " other_r.id=other_mr.role_id WHERE"
                                + " other_mr.tenant_id=mr.tenant_id AND"
                                + " other_mr.application_id=mr.application_id AND"
                                + " other_mr.user_id=mr.user_id AND other_r.id<>mr.role_id AND"
                                + " other_r.status='ACTIVE' AND other_r.deleted_at IS NULL) ORDER"
                                + " BY u.username LIMIT 100",
                        (rs, n) -> rs.getString(1),
                        tenant(a),
                        app(),
                        bin(role.id()));
        List<String> managers =
                settings.hasManagementEntryExcludingRole(a, role.id()) ? List.of() : usernames;
        return new RoleImpact(
                role.id(),
                role.version(),
                role.userCount(),
                usernames,
                role.status(),
                activeUsers,
                lastRoles,
                managers,
                !role.protectedRole()
                        && "ACTIVE".equals(role.status())
                        && lastRoles.isEmpty()
                        && managers.isEmpty(),
                !role.protectedRole() && "DISABLED".equals(role.status()) && role.userCount() == 0);
    }

    @Override
    public void deleteRole(Actor a, UUID id, long version, boolean revoke) {
        tx.executeWithoutResult(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(a, "supply:role:delete");
                    Role role = role(a, id);
                    if (role.protectedRole()) throw new IllegalArgumentException("不能删除受保护的恢复角色");
                    if (role.version() != version) throw new IllegalStateException("角色已修改，请刷新");
                    if (!"DISABLED".equals(role.status()))
                        throw new IllegalArgumentException("请先禁用角色，再解除用户关联后删除");
                    if (role.userCount() > 0)
                        throw new IllegalArgumentException("角色仍分配给用户，请先完成交接并解除关联");
                    for (ScopeRule rule : role.rules()) deleteRule(a, rule.id());
                    jdbc.update(
                            "DELETE FROM iam_app_role_grant WHERE tenant_id=? AND application_id=?"
                                    + " AND role_id=?",
                            tenant(a),
                            app(),
                            bin(id));
                    jdbc.update(
                            "DELETE FROM iam_app_member_role WHERE tenant_id=? AND application_id=?"
                                    + " AND role_id=?",
                            tenant(a),
                            app(),
                            bin(id));
                    jdbc.update(
                            "UPDATE iam_app_role SET"
                                + " deleted_at=UTC_TIMESTAMP(6),status='DISABLED',version=version+1"
                                + " WHERE tenant_id=? AND application_id=? AND id=?",
                            tenant(a),
                            app(),
                            bin(id));
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                    settings.audit(
                            a,
                            "ROLE_DELETE",
                            id.toString(),
                            role.name() + "；撤销成员 " + role.userCount() + " 人");
                });
    }

    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.strip().length() > max)
            throw new IllegalArgumentException(label + "不能为空且不能超过 " + max + " 字符");
        return value.strip();
    }
}
