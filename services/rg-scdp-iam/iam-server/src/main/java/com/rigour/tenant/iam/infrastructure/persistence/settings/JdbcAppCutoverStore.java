package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.tenant.iam.application.port.out.*;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppCutoverModels.*;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.Context;
import com.rigour.tenant.iam.domain.model.settings.*;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 应用版本锁、实时域检查、资格核验、模式变更与审计构成一次启用事务。 */
@Repository
public class JdbcAppCutoverStore implements AppCutoverStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final JdbcAppRoleStore roles;
    private final JdbcAppMemberStore members;
    private final AppReferenceValidator refs;
    private final AppReadinessClient domains;

    public JdbcAppCutoverStore(
            JdbcTemplate jdbc,
            JdbcAppSettingsStore settings,
            JdbcAppRoleStore roles,
            JdbcAppMemberStore members,
            AppReferenceValidator refs,
            AppReadinessClient domains) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.roles = roles;
        this.members = members;
        this.refs = refs;
        this.domains = domains;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcAppAuthorizationStore authorization;

    @org.springframework.beans.factory.annotation.Autowired private IdentityAccessReader legacyAccess;

    @Transactional(readOnly = true)
    public PermissionPreview preview(Actor reviewer, UUID user, String action) {
        require(reviewer);
        var target = new Actor("TENANT", user, reviewer.tenantId());
        settings.requireIdentity(target);
        var current = settings.context(target);
        var catalog =
                settings.catalog(reviewer).stream()
                        .map(AppMenuTree.Node::permissionCode)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        var old =
                legacyAccess.readCurrentUser(
                        new com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery(
                                "TENANT", user, reviewer.tenantId()));
        var previous =
                catalog.stream()
                        .filter(
                                code ->
                                        old.permissions().contains(code)
                                                || old.permissions().contains("*:*:*"))
                        .sorted()
                        .toList();
        boolean eligible = settings.eligible(target);
        var next = eligible ? current.permissions().stream().sorted().toList() : List.<String>of();
        String selected =
                action == null || action.isBlank()
                        ? next.stream()
                                .filter(code -> AppScopeRules.objectType(code) != null)
                                .findFirst()
                                .orElse(null)
                        : action;
        if (selected != null && !catalog.contains(selected))
            throw new IllegalArgumentException("仅可预览供应链已注册操作");
        var policy = eligible && selected != null ? authorization.proposed(target, selected) : null;
        return new PermissionPreview(
                user,
                old.username(),
                current.version(),
                previous,
                next,
                next.stream().filter(code -> !previous.contains(code)).toList(),
                previous.stream().filter(code -> !next.contains(code)).toList(),
                selected,
                policy,
                eligible ? null : "供应链成员或员工当前不可用；启用后无业务访问权");
    }

    @Transactional(readOnly = true)
    public ObservationPage observations(Actor a, int page, int size) {
        require(a);
        if (page < 1 || page > 100000 || size < 1 || size > 100)
            throw new IllegalArgumentException("分页范围无效");
        var context = settings.context(a);
        long version = context.version();
        if ("ACTIVE".equals(context.mode()))
            version =
                    jdbc.queryForObject(
                            "SELECT COALESCE(MAX(application_version),0) FROM"
                                    + " iam_app_authorization_observation WHERE tenant_id=? AND"
                                    + " application_id=?",
                            Long.class,
                            bin(a.tenantId()),
                            bin(settings.applicationId()));
        Object[] args = {bin(a.tenantId()), bin(settings.applicationId()), version};
        long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_app_authorization_observation WHERE tenant_id=?"
                                + " AND application_id=? AND application_version=?",
                        Long.class,
                        args);
        var rows =
                jdbc.query(
                        "SELECT o.*,u.username FROM iam_app_authorization_observation o JOIN"
                            + " iam_user u ON u.tenant_id=o.tenant_id AND u.id=o.user_id WHERE"
                            + " o.tenant_id=? AND o.application_id=? AND o.application_version=?"
                            + " ORDER BY o.observed_at"
                            + " DESC,o.user_id,o.action_code,o.legacy_action_code LIMIT ? OFFSET ?",
                        (r, n) ->
                                new Observation(
                                        UuidBinaryCodec.decode(r.getBytes("user_id")).toString(),
                                        r.getString("username"),
                                        r.getLong("application_version"),
                                        r.getString("action_code"),
                                        r.getString("legacy_action_code"),
                                        r.getBoolean("legacy_allowed"),
                                        r.getBoolean("proposed_allowed"),
                                        r.getString("policy_json"),
                                        r.getLong("sample_count"),
                                        r.getTimestamp("observed_at").toInstant()),
                        args[0],
                        args[1],
                        args[2],
                        size,
                        (long) (page - 1) * size);
        return new ObservationPage(rows, count, page, size);
    }

    @Transactional(readOnly = true)
    public DataObservationPage dataObservations(Actor a, int page, int size) {
        require(a);
        if (page < 1 || page > 100000 || size < 1 || size > 100)
            throw new IllegalArgumentException("分页范围无效");
        var context = settings.context(a);
        long version =
                "ACTIVE".equals(context.mode())
                        ? jdbc.queryForObject(
                                "SELECT COALESCE(MAX(application_version),0) FROM"
                                    + " iam_app_data_observation WHERE tenant_id=? AND"
                                    + " application_id=?",
                                Long.class,
                                bin(a.tenantId()),
                                bin(settings.applicationId()))
                        : context.version();
        long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_app_data_observation WHERE tenant_id=? AND"
                            + " application_id=? AND application_version=?",
                        Long.class,
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        version);
        var items =
                jdbc.query(
                        "SELECT o.*,u.username FROM iam_app_data_observation o JOIN iam_user u ON"
                            + " u.tenant_id=o.tenant_id AND u.id=o.user_id WHERE o.tenant_id=? AND"
                            + " o.application_id=? AND o.application_version=? ORDER BY"
                            + " o.observed_at"
                            + " DESC,o.user_id,o.action_code,o.domain_code,o.record_key LIMIT ?"
                            + " OFFSET ?",
                        (r, n) ->
                                new DataObservation(
                                        UuidBinaryCodec.decode(r.getBytes("user_id")).toString(),
                                        r.getString("username"),
                                        r.getLong("application_version"),
                                        r.getString("action_code"),
                                        r.getString("domain_code"),
                                        r.getString("record_key"),
                                        r.getBoolean("legacy_allowed"),
                                        r.getBoolean("proposed_allowed"),
                                        r.getString("policy_json"),
                                        r.getLong("sample_count"),
                                        r.getTimestamp("observed_at").toInstant()),
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        version,
                        size,
                        (long) (page - 1) * size);
        return new DataObservationPage(items, count, page, size);
    }

    private void require(Actor a) {
        settings.requirePermission(a, "supply:role:grant");
        if (!roles.protectedAdministrator(a)) throw new AccessDeniedException("仅供应链受保护管理员可切换授权体系");
    }

    @Transactional(readOnly = true)
    public Report inspect(Actor a) {
        require(a);
        return check(a);
    }

    private Report check(Actor a) {
        var context = settings.context(a);
        var issues = new ArrayList<Issue>();
        var nodes = settings.rows(a);
        try {
            AppMenuTree.validate(nodes);
        } catch (RuntimeException e) {
            issues.add(new Issue("MENU_TREE", "BLOCKING", 1, "菜单树无效：" + e.getMessage()));
        }
        var catalog = settings.catalog(a);
        var allowed =
                catalog.stream()
                        .map(AppMenuTree.Node::resourceId)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        long stale =
                nodes.stream()
                        .filter(
                                n ->
                                        n.resourceId() != null
                                                && !allowed.contains(n.resourceId())
                                                && "ACTIVE".equals(n.status()))
                        .count();
        if (stale > 0)
            issues.add(new Issue("MENU_CATALOG", "BLOCKING", stale, "菜单绑定的能力已失效，请在菜单管理中停用或更正"));
        var allRoles = roles.all(a);
        for (var role : allRoles) {
            if (!"ACTIVE".equals(role.status())) continue;
            for (var node : nodes) {
                if (!role.menuNodeIds().contains(node.id())
                        || !"ACTIVE".equals(node.status())
                        || AppScopeRules.objectType(node.permissionCode()) == null) continue;
                if (role.rules().stream()
                        .noneMatch(r -> Objects.equals(r.actionCode(), node.permissionCode())))
                    issues.add(
                            new Issue(
                                    "ROLE_RULE:" + role.id() + ":" + node.permissionCode(),
                                    "BLOCKING",
                                    1,
                                    role.name() + " 的 " + node.name() + " 缺少明确数据范围（可配置为无数据）"));
            }
            for (var r : role.rules()) {
                try {
                    AppScopeRules.validate(
                            r.actionCode(),
                            r.objectType(),
                            r.scopeMode(),
                            r.departmentMode(),
                            r.regionMode(),
                            r.warehouseMode());
                    for (var e : r.references().entrySet())
                        refs.requireActive(a.tenantId(), e.getKey(), e.getValue());
                } catch (RuntimeException e) {
                    issues.add(
                            new Issue(
                                    "ROLE_REFERENCE:" + role.id() + ":" + r.id(),
                                    "BLOCKING",
                                    1,
                                    role.name() + " 的范围无效：" + e.getMessage()));
                }
            }
        }
        var actorIds =
                jdbc.query(
                        "SELECT user_id FROM iam_app_member WHERE tenant_id=? AND application_id=?"
                                + " AND status='ACTIVE' AND deleted_at IS NULL ORDER BY user_id",
                        (rs, n) -> UuidBinaryCodec.decode(rs.getBytes(1)),
                        bin(a.tenantId()),
                        bin(settings.applicationId()));
        for (var id : actorIds) {
            var member = new Actor("TENANT", id, a.tenantId());
            try {
                if (!settings.eligible(member))
                    throw new IllegalArgumentException("账号、HR 员工资格或角色不可用");
                for (String dimension : List.of("REGION", "WAREHOUSE")) {
                    var limit = members.limit(a, id, dimension);
                    refs.requireActive(a.tenantId(), dimension, limit.references());
                }
                for (var assignment : members.assignments(a, id)) {
                    var role =
                            allRoles.stream()
                                    .filter(
                                            r ->
                                                    r.id().equals(assignment.roleId())
                                                            && "ACTIVE".equals(r.status()))
                                    .findFirst();
                    if (role.isEmpty()) continue;
                    for (var rule : role.get().rules()) {
                        if ("NONE".equals(rule.scopeMode())) continue;
                        var parameters = assignment.parameters().getOrDefault(rule.id(), Map.of());
                        Map<String, String> modes =
                                Map.of(
                                        "DEPARTMENT",
                                        rule.departmentMode(),
                                        "REGION",
                                        rule.regionMode(),
                                        "WAREHOUSE",
                                        rule.warehouseMode());
                        for (var e : modes.entrySet()) {
                            var values = parameters.getOrDefault(e.getKey(), List.of());
                            if (Set.of("MANAGED", "MEMBER").contains(e.getValue())
                                    && values.isEmpty())
                                throw new IllegalArgumentException(
                                        "角色 " + role.get().name() + " 尚未分配 " + e.getKey() + " 范围");
                            refs.requireActive(a.tenantId(), e.getKey(), values);
                        }
                    }
                }
            } catch (RuntimeException e) {
                issues.add(
                        new Issue(
                                "MEMBER:" + id, "BLOCKING", 1, "用户 " + id + "：" + e.getMessage()));
            }
        }
        var oldUsers =
                jdbc.query(
                        "SELECT DISTINCT u.id,u.username FROM iam_user u JOIN iam_user_role ur ON"
                            + " ur.tenant_id=u.tenant_id AND ur.user_id=u.id JOIN iam_role r ON"
                            + " r.tenant_id=ur.tenant_id AND r.id=ur.role_id JOIN"
                            + " iam_effective_tenant_role_resource rr ON rr.tenant_id=r.tenant_id"
                            + " AND rr.role_id=r.id JOIN iam_resource res ON res.id=rr.resource_id"
                            + " JOIN iam_application app ON app.id=res.application_id JOIN"
                            + " iam_tenant_subscription sub ON sub.tenant_id=u.tenant_id AND"
                            + " sub.status IN ('ACTIVE','SCHEDULED') AND"
                            + " sub.effective_from<=UTC_TIMESTAMP(6) AND"
                            + " sub.effective_to>UTC_TIMESTAMP(6) JOIN iam_package_resource pr ON"
                            + " pr.package_version_id=sub.package_version_id AND"
                            + " pr.resource_id=res.id WHERE u.tenant_id=? AND res.application_id=?"
                            + " AND u.status='ACTIVE' AND u.deleted_at IS NULL AND"
                            + " ur.status='ACTIVE' AND ur.effective_from<=UTC_TIMESTAMP(6) AND"
                            + " (ur.effective_to IS NULL OR ur.effective_to>UTC_TIMESTAMP(6)) AND"
                            + " r.status='ACTIVE' AND r.deleted_at IS NULL AND rr.status='ACTIVE'"
                            + " AND res.status='ACTIVE' AND res.deleted_at IS NULL AND"
                            + " app.status='ACTIVE' AND app.deleted_at IS NULL AND NOT"
                            + " EXISTS(SELECT 1 FROM iam_app_member m WHERE m.tenant_id=u.tenant_id"
                            + " AND m.application_id=res.application_id AND m.user_id=u.id) ORDER"
                            + " BY u.username,u.id",
                        (rs, n) ->
                                new String[] {
                                    UuidBinaryCodec.decode(rs.getBytes("id")).toString(),
                                    rs.getString("username")
                                },
                        bin(a.tenantId()),
                        bin(settings.applicationId()));
        for (var user : oldUsers)
            issues.add(
                    new Issue(
                            "LEGACY_MEMBER:" + user[0],
                            "BLOCKING",
                            1,
                            "旧供应链用户「" + user[1] + "」尚未处理；请在用户管理中开通并关联员工，或明确保存为禁用资格"));
        var readiness = domains.inspect(a.tenantId());
        if (!readiness.stream()
                .map(Domain::domain)
                .collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("hr", "crm", "erp", "order", "bi", "settings")))
            issues.add(new Issue("DOMAIN_CONTRACT", "BLOCKING", 1, "领域检查结果不完整"));
        for (var domain : readiness)
            issues.addAll(domain.issues().stream().filter(i -> i.count() > 0).toList());
        var observations =
                jdbc.queryForList(
                        "SELECT"
                            + " CONCAT(HEX(user_id),':',action_code,':',legacy_action_code,':',legacy_allowed,':',proposed_allowed,':',MD5(CAST(policy_json"
                            + " AS CHAR))) FROM iam_app_authorization_observation WHERE tenant_id=?"
                            + " AND application_id=? AND application_version=? ORDER BY"
                            + " user_id,action_code,legacy_action_code",
                        String.class,
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        context.version());
        long changes =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_app_authorization_observation WHERE tenant_id=?"
                                + " AND application_id=? AND application_version=? AND"
                                + " legacy_allowed<>proposed_allowed",
                        Long.class,
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        context.version());
        if (observations.isEmpty())
            issues.add(
                    new Issue(
                            "SHADOW_NOT_SAMPLED",
                            "WARNING",
                            1,
                            "当前配置尚无实际请求功能对比记录；请完成代表账号业务验证，并核对权限预览与数据范围"));
        if (changes > 0)
            issues.add(
                    new Issue(
                            "SHADOW_FUNCTION_DIFF",
                            "WARNING",
                            changes,
                            "实际请求存在新旧功能授权差异，请在功能授权对比中核对；记录不代表业务数据范围已验收"));
        var dataObservations =
                jdbc.queryForList(
                        "SELECT"
                            + " CONCAT(HEX(user_id),':',action_code,':',domain_code,':',record_key,':',legacy_allowed,':',proposed_allowed,':',MD5(CAST(policy_json"
                            + " AS CHAR))) FROM iam_app_data_observation WHERE tenant_id=? AND"
                            + " application_id=? AND application_version=? ORDER BY"
                            + " user_id,action_code,domain_code,record_key",
                        String.class,
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        context.version());
        var sampledDomains =
                jdbc.queryForList(
                        "SELECT DISTINCT domain_code FROM iam_app_data_observation WHERE"
                            + " tenant_id=? AND application_id=? AND application_version=?",
                        String.class,
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        context.version());
        var missingDomains = new java.util.TreeSet<>(Set.of("ORDER", "CRM", "ERP", "HR", "BI"));
        missingDomains.removeAll(sampledDomains);
        if (!missingDomains.isEmpty())
            issues.add(
                    new Issue(
                            "SHADOW_DATA_COVERAGE",
                            "WARNING",
                            missingDomains.size(),
                            "以下领域尚无当前配置的数据决定样本：" + missingDomains + "。已有样本也不能替代完整业务验收"));
        long dataChanges =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_app_data_observation WHERE tenant_id=? AND"
                            + " application_id=? AND application_version=? AND"
                            + " legacy_allowed<>proposed_allowed",
                        Long.class,
                        bin(a.tenantId()),
                        bin(settings.applicationId()),
                        context.version());
        if (dataChanges > 0)
            issues.add(
                    new Issue(
                            "SHADOW_DATA_DIFF",
                            "WARNING",
                            dataChanges,
                            "实际业务记录存在新旧数据权限差异，请逐项核对并说明原因"));
        String source =
                context.version()
                        + "|"
                        + readiness.stream()
                                .sorted(Comparator.comparing(Domain::domain))
                                .map(d -> d.domain() + ":" + d.version())
                                .toList()
                        + "|"
                        + issues;
        return new Report(
                context.mode(),
                context.version(),
                hash(source + "|" + observations + "|" + dataObservations),
                issues.stream().noneMatch(i -> "BLOCKING".equals(i.severity())),
                List.copyOf(issues));
    }

    @Transactional
    public Context activate(Actor a, Command c) {
        require(a);
        settings.lock(a);
        var report = check(a);
        if (c == null
                || c.version() != report.version()
                || !Objects.equals(c.fingerprint(), report.fingerprint()))
            throw new IllegalStateException("检查后配置或业务数据已变化，请重新检查");
        if (!"PREPARING".equals(report.mode())) throw new IllegalStateException("当前供应链已启用新授权");
        if (!report.ready()) throw new IllegalStateException("仍有阻塞问题，不能启用");
        if (c.reason() == null || c.reason().strip().length() < 4 || c.reason().length() > 500)
            throw new IllegalArgumentException("请填写至少 4 字、最多 500 字的启用说明");
        if (!report.issues().isEmpty() && !c.acknowledgeWarnings())
            throw new IllegalArgumentException("请先核对提示项，并确认历史待核对数据按限制保留");
        jdbc.update(
                "UPDATE iam_app_settings SET authorization_mode='ACTIVE' WHERE tenant_id=? AND"
                        + " application_id=?",
                bin(a.tenantId()),
                bin(settings.applicationId()));
        settings.bump(a);
        settings.audit(
                a,
                "AUTHORIZATION_ACTIVATE",
                settings.applicationId().toString(),
                "启用新授权；检查指纹 " + report.fingerprint() + "；" + c.reason().strip());
        return settings.context(a);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
