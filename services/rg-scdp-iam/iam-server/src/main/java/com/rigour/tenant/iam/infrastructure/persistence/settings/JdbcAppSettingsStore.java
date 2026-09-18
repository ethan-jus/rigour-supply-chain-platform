package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.tenant.iam.application.port.out.AppSettingsStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.ManagementModels.NavigationNode;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** IAM 供应链配置存储；每次写入锁定应用版本，树、授权撤销和审计原子提交。 */
@Repository
public class JdbcAppSettingsStore implements AppSettingsStore {
    private static final Pattern CUSTOM_ROUTE_PATH =
            Pattern.compile("^/supply-chain/[A-Za-z0-9_\\-/]+$");
    private static final Pattern CUSTOM_COMPONENT_PATH =
            Pattern.compile("^supply-chain/[A-Za-z0-9_\\-/]+\\.vue$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final com.rigour.tenant.iam.application.port.out.AppEmployeeClient employees;

    public JdbcAppSettingsStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            com.rigour.tenant.iam.application.port.out.AppEmployeeClient employees) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.tx = new TransactionTemplate(manager);
    }

    public static byte[] bin(UUID id) {
        return UuidBinaryCodec.encode(id);
    }

    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        return UuidBinaryCodec.decode(rs.getBytes(name));
    }

    public UUID applicationId() {
        return UuidBinaryCodec.decode(
                jdbc.queryForObject(
                        "SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND"
                                + " status='ACTIVE' AND deleted_at IS NULL",
                        byte[].class));
    }

    public void requireIdentity(Actor actor) {
        if (!"TENANT".equals(actor.scope())
                || actor.tenantId() == null
                || count(
                                """
SELECT COUNT(*) FROM iam_user u JOIN iam_tenant t ON t.id=u.tenant_id
 WHERE u.tenant_id=? AND u.id=? AND u.status='ACTIVE' AND u.deleted_at IS NULL
   AND t.status='ACTIVE' AND t.deleted_at IS NULL
""",
                                bin(actor.tenantId()),
                                bin(actor.principalId()))
                        != 1) throw new AccessDeniedException("供应链设置需要有效租户用户");
        if (catalogRows(actor).isEmpty()) throw new AccessDeniedException("当前租户未开通供应链");
    }

    @Override
    public boolean initialized(Actor actor) {
        return "TENANT".equals(actor.scope())
                && actor.tenantId() != null
                && count(
                                "SELECT COUNT(*) FROM iam_app_settings WHERE tenant_id=? AND"
                                        + " application_id=?",
                                bin(actor.tenantId()),
                                bin(applicationId()))
                        == 1;
    }

    @Override
    public Context context(Actor actor) {
        requireIdentity(actor);
        if (!initialized(actor))
            return new Context(false, canBootstrap(actor), "PREPARING", 0, Set.of());
        return jdbc.queryForObject(
                """
SELECT authorization_mode,version FROM iam_app_settings WHERE tenant_id=? AND application_id=?
""",
                (rs, row) ->
                        new Context(
                                true, false, rs.getString(1), rs.getLong(2), permissions(actor)),
                bin(actor.tenantId()),
                bin(applicationId()));
    }

    private boolean canBootstrap(Actor actor) {
        return count(
                        """
SELECT COUNT(*) FROM iam_user_role ur JOIN iam_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
 WHERE ur.tenant_id=? AND ur.user_id=? AND ur.status='ACTIVE'
   AND ur.effective_from<=UTC_TIMESTAMP(6) AND (ur.effective_to IS NULL OR ur.effective_to>UTC_TIMESTAMP(6))
   AND r.role_type='SYSTEM' AND r.role_code='TENANT_SUPER_ADMIN'
   AND r.status='ACTIVE' AND r.deleted_at IS NULL
""",
                        bin(actor.tenantId()),
                        bin(actor.principalId()))
                > 0;
    }

    @Override
    public Context initialize(Actor actor) {
        requireIdentity(actor);
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT id FROM iam_tenant WHERE id=? FOR UPDATE",
                            byte[].class,
                            bin(actor.tenantId()));
                    if (initialized(actor)) throw new IllegalStateException("供应链设置已初始化，请刷新");
                    if (!canBootstrap(actor))
                        throw new AccessDeniedException("只有当前租户受保护管理员可执行首次初始化");
                    UUID app = applicationId();
                    jdbc.update(
                            "INSERT INTO iam_app_settings(tenant_id,application_id) VALUES(?,?)",
                            bin(actor.tenantId()),
                            bin(app));
                    List<Node> seed = seedMenus(actor);
                    AppMenuTree.validate(seed);
                    for (Node node : seed) insert(actor, node, null);
                    for (Node node : seed)
                        if (node.parentId() != null)
                            jdbc.update(
                                    "UPDATE iam_app_menu_node SET parent_id=? WHERE tenant_id=? AND"
                                            + " application_id=? AND id=?",
                                    bin(node.parentId()),
                                    bin(actor.tenantId()),
                                    bin(app),
                                    bin(node.id()));
                    UUID role = UUID.randomUUID();
                    jdbc.update(
                            """
INSERT INTO iam_app_member(tenant_id,application_id,user_id,member_kind,status)
VALUES(?,?,?,'PROTECTED','ACTIVE')
""",
                            bin(actor.tenantId()),
                            bin(app),
                            bin(actor.principalId()));
                    jdbc.update(
                            """
INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name,protected_role)
VALUES(?,?,?,'SUPPLY_BOOTSTRAP_ADMIN','供应链初始化管理员',TRUE)
""",
                            bin(actor.tenantId()),
                            bin(app),
                            bin(role));
                    jdbc.update(
                            "INSERT INTO"
                                + " iam_app_member_role(tenant_id,application_id,user_id,role_id)"
                                + " VALUES(?,?,?,?)",
                            bin(actor.tenantId()),
                            bin(app),
                            bin(actor.principalId()),
                            bin(role));
                    for (Node node : seed)
                        jdbc.update(
                                "INSERT INTO"
                                    + " iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)"
                                    + " VALUES(?,?,?,?)",
                                bin(actor.tenantId()),
                                bin(app),
                                bin(role),
                                bin(node.id()));
                    Set<String> seededActions = new HashSet<>();
                    for (Node node : seed) {
                        String object =
                                com.rigour.tenant.iam.domain.model.settings.AppScopeRules
                                        .objectType(node.permissionCode());
                        if (object != null && seededActions.add(node.permissionCode()))
                            jdbc.update(
                                    """
INSERT INTO iam_app_scope_rule(tenant_id,application_id,id,role_id,action_code,object_type,scope_mode,department_mode,region_mode,warehouse_mode,include_descendants)
VALUES(?,?,?,?,?,?,'ALL','ALL','ALL','ALL',TRUE)
""",
                                    bin(actor.tenantId()),
                                    bin(app),
                                    bin(UUID.randomUUID()),
                                    bin(role),
                                    node.permissionCode(),
                                    object);
                    }
                    for (String dimension : List.of("REGION", "WAREHOUSE"))
                        jdbc.update(
                                """
INSERT INTO iam_app_member_scope_limit(tenant_id,application_id,user_id,dimension,scope_mode)
VALUES(?,?,?,?,'ALL')
""",
                                bin(actor.tenantId()),
                                bin(app),
                                bin(actor.principalId()),
                                dimension);
                    audit(actor, "SETTINGS_INITIALIZE", app.toString(), "显式初始化供应链设置，保留原应用角色");
                    bump(actor);
                });
        return context(actor);
    }

    private List<Node> seedMenus(Actor actor) {
        List<Node> catalog = catalogRows(actor);
        Map<UUID, Node> all =
                catalog.stream()
                        .collect(
                                Collectors.toMap(
                                        Node::id,
                                        Function.identity(),
                                        (a, b) -> a,
                                        LinkedHashMap::new));
        var result = new LinkedHashMap<UUID, Node>();
        for (Node node : catalog) {
            if ("BUTTON".equals(node.type())
                    && (node.parentId() == null
                            || !all.containsKey(node.parentId())
                            || !"PAGE".equals(all.get(node.parentId()).type()))) continue;
            UUID parent = all.containsKey(node.parentId()) ? node.parentId() : null;
            result.put(
                    node.id(),
                    copy(
                            node,
                            parent,
                            node.name(),
                            node.iconKey(),
                            node.sortOrder(),
                            node.visible(),
                            node.status()));
        }
        // 保留现有租户自建目录及覆盖；它们只在初始化时作为迁移来源读取。
        jdbc.query(
                """
SELECT id,parent_id,parent_resource_id,display_name,icon_key,sort_order,visible,status
  FROM iam_tenant_menu_group WHERE tenant_id=? AND application_id=? AND status='ACTIVE'
""",
                rs -> {
                    while (rs.next()) {
                        UUID id = uuid(rs, "id");
                        UUID parent = uuid(rs, "parent_resource_id");
                        if (parent == null) parent = uuid(rs, "parent_id");
                        result.put(
                                id,
                                new Node(
                                        id,
                                        parent,
                                        "MENU",
                                        null,
                                        rs.getString("display_name"),
                                        com.rigour.tenant.iam.domain.model.settings.AppMenuIcons
                                                .legacy(rs.getString("icon_key")),
                                        rs.getInt("sort_order"),
                                        rs.getBoolean("visible"),
                                        "ACTIVE",
                                        false,
                                        0,
                                        "CUSTOM." + id,
                                        null,
                                        "tenant.menu.group." + id,
                                        null,
                                        null));
                    }
                    return null;
                },
                bin(actor.tenantId()),
                bin(applicationId()));
        jdbc.query(
                """
SELECT resource_id,display_name_override,icon_key_override,sort_order_override,visible,
       parent_group_id,parent_resource_id,parent_overridden
  FROM iam_tenant_menu_config WHERE tenant_id=?
""",
                rs -> {
                    while (rs.next()) {
                        Node old = result.get(uuid(rs, "resource_id"));
                        if (old == null) continue;
                        UUID parent = old.parentId();
                        if (rs.getBoolean("parent_overridden")
                                || rs.getBytes("parent_group_id") != null) {
                            parent = uuid(rs, "parent_resource_id");
                            if (parent == null) parent = uuid(rs, "parent_group_id");
                        }
                        String name = rs.getString("display_name_override");
                        String icon =
                                com.rigour.tenant.iam.domain.model.settings.AppMenuIcons.legacy(
                                        rs.getString("icon_key_override"));
                        Integer order = rs.getObject("sort_order_override", Integer.class);
                        result.put(
                                old.id(),
                                copy(
                                        old,
                                        parent,
                                        name == null ? old.name() : name,
                                        icon == null ? old.iconKey() : icon,
                                        order == null ? old.sortOrder() : order,
                                        old.protectedNode() || rs.getBoolean("visible"),
                                        "ACTIVE"));
                    }
                    return null;
                },
                bin(actor.tenantId()));
        for (Node node : List.copyOf(result.values())) {
            if (node.parentId() != null && !result.containsKey(node.parentId()))
                result.put(
                        node.id(),
                        copy(
                                node,
                                null,
                                node.name(),
                                node.iconKey(),
                                node.sortOrder(),
                                node.visible(),
                                node.status()));
            if (node.protectedNode()) {
                Node cursor = result.get(node.id());
                var seen = new HashSet<UUID>();
                while (cursor != null && seen.add(cursor.id())) {
                    result.put(
                            cursor.id(),
                            copy(
                                    cursor,
                                    cursor.parentId(),
                                    cursor.name(),
                                    cursor.iconKey(),
                                    cursor.sortOrder(),
                                    true,
                                    "ACTIVE"));
                    cursor = result.get(cursor.parentId());
                }
            }
        }
        return List.copyOf(result.values());
    }

    private static Node copy(
            Node n,
            UUID parent,
            String name,
            String icon,
            int sort,
            boolean visible,
            String status) {
        return new Node(
                n.id(),
                parent,
                n.type(),
                n.resourceId(),
                name,
                icon,
                sort,
                visible,
                status,
                n.protectedNode(),
                n.version(),
                n.resourceCode(),
                n.permissionCode(),
                n.routeKey(),
                n.routePath(),
                n.componentPath());
    }

    public Set<String> permissions(Actor actor) {
        if (!initialized(actor) || !eligible(actor)) return Set.of();
        Map<UUID, Node> nodes =
                rows(actor).stream().collect(Collectors.toMap(Node::id, Function.identity()));
        Set<UUID> granted = grantIds(actor);
        Set<UUID> entitled =
                catalogRows(actor).stream().map(Node::resourceId).collect(Collectors.toSet());
        return nodes.values().stream()
                .filter(n -> granted.contains(n.id()) && entitled.contains(n.resourceId()))
                .filter(n -> AppMenuTree.enabled(n, nodes))
                .map(Node::permissionCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> enabledActionNodes(Actor actor, String action) {
        Map<UUID, Node> nodes =
                rows(actor).stream().collect(Collectors.toMap(Node::id, Function.identity()));
        Set<UUID> entitled =
                catalogRows(actor).stream().map(Node::resourceId).collect(Collectors.toSet());
        return nodes.values().stream()
                .filter(
                        n ->
                                action.equals(n.permissionCode())
                                        && entitled.contains(n.resourceId())
                                        && AppMenuTree.enabled(n, nodes))
                .map(Node::id)
                .collect(Collectors.toSet());
    }

    public boolean eligible(Actor actor) {
        record Binding(String kind, String code, long access) {}
        List<Binding> bindings =
                jdbc.query(
                        """
SELECT m.member_kind,b.employee_code,b.hr_access_version FROM iam_app_member m
  LEFT JOIN iam_app_employee_binding b ON b.tenant_id=m.tenant_id AND b.application_id=m.application_id AND b.user_id=m.user_id
  JOIN iam_user u ON u.tenant_id=m.tenant_id AND u.id=m.user_id AND u.status='ACTIVE' AND u.deleted_at IS NULL
 WHERE m.tenant_id=? AND m.application_id=? AND m.user_id=? AND m.status='ACTIVE' AND m.deleted_at IS NULL
   AND EXISTS(SELECT 1 FROM iam_app_member_role mr JOIN iam_app_role r ON r.tenant_id=mr.tenant_id AND r.application_id=mr.application_id AND r.id=mr.role_id
     WHERE mr.tenant_id=m.tenant_id AND mr.application_id=m.application_id AND mr.user_id=m.user_id AND r.status='ACTIVE' AND r.deleted_at IS NULL)
""",
                        (rs, n) -> new Binding(rs.getString(1), rs.getString(2), rs.getLong(3)),
                        bin(actor.tenantId()),
                        bin(applicationId()),
                        bin(actor.principalId()));
        if (bindings.size() != 1) return false;
        Binding binding = bindings.getFirst();
        if ("PROTECTED".equals(binding.kind())) return true;
        if (binding.code() == null) return false;
        var employee = employees.employee(actor.tenantId(), binding.code());
        return employee.usable() && employee.accessVersion() == binding.access();
    }

    /** 在同一应用配置锁内写入后复核，失败会回滚整个授权变更。 */
    public void requireManagementEntry(Actor actor) {
        if (!hasManagementEntryExcludingRole(actor, null))
            throw new IllegalArgumentException("不能移除最后一个可用供应链管理入口，请先完成管理员交接");
    }

    boolean hasManagementEntryExcludingRole(Actor actor, UUID excludedRole) {
        for (UUID user :
                jdbc.query(
                        "SELECT m.user_id FROM iam_app_member m JOIN iam_user u ON"
                            + " u.tenant_id=m.tenant_id AND u.id=m.user_id WHERE m.tenant_id=? AND"
                            + " m.application_id=? AND m.status='ACTIVE' AND m.deleted_at IS NULL"
                            + " AND u.status='ACTIVE' AND u.deleted_at IS NULL ORDER BY"
                            + " (m.member_kind='PROTECTED') DESC,m.user_id",
                        (rs, n) -> UuidBinaryCodec.decode(rs.getBytes(1)),
                        bin(actor.tenantId()),
                        bin(applicationId()))) {
            Actor candidate = new Actor("TENANT", user, actor.tenantId());
            Set<String> candidatePermissions = permissionsExcludingRole(candidate, excludedRole);
            if (managementPermissions(candidatePermissions) && eligible(candidate)) return true;
        }
        return false;
    }

    Set<String> permissionsExcludingRole(Actor actor, UUID excludedRole) {
        Set<UUID> grants = protectedAdministratorRole(actor, excludedRole) != null
                ? rows(actor).stream().map(Node::id).collect(Collectors.toSet())
                : new HashSet<>(
                        jdbc.query(
                                "SELECT g.menu_node_id FROM iam_app_member_role mr JOIN"
                                    + " iam_app_role r ON r.tenant_id=mr.tenant_id AND"
                                    + " r.application_id=mr.application_id AND r.id=mr.role_id JOIN"
                                    + " iam_app_role_grant g ON g.tenant_id=r.tenant_id AND"
                                    + " g.application_id=r.application_id AND g.role_id=r.id WHERE"
                                    + " mr.tenant_id=? AND mr.application_id=? AND mr.user_id=? AND"
                                    + " r.status='ACTIVE' AND r.deleted_at IS NULL"
                                        + (excludedRole == null ? "" : " AND r.id<>?"),
                                (rs, n) -> UuidBinaryCodec.decode(rs.getBytes(1)),
                                excludedRole == null
                                        ? new Object[] {
                                            bin(actor.tenantId()),
                                            bin(applicationId()),
                                            bin(actor.principalId())
                                        }
                                        : new Object[] {
                                            bin(actor.tenantId()),
                                            bin(applicationId()),
                                            bin(actor.principalId()),
                                            bin(excludedRole)
                                        }));
        Map<UUID, Node> nodes =
                rows(actor).stream().collect(Collectors.toMap(Node::id, Function.identity()));
        Set<UUID> entitled =
                catalogRows(actor).stream().map(Node::resourceId).collect(Collectors.toSet());
        return nodes.values().stream()
                .filter(
                        n ->
                                grants.contains(n.id())
                                        && entitled.contains(n.resourceId())
                                        && AppMenuTree.enabled(n, nodes))
                .map(Node::permissionCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    static boolean managementPermissions(Set<String> permissions) {
        return permissions.containsAll(
                Set.of(
                        "supply:user:read",
                        "supply:user:assign-role",
                        "supply:role:read",
                        "supply:role:grant"));
    }

    /** 内置管理员动态拥有本企业已配置功能，不依赖初始化时的授权快照。 */
    UUID protectedAdministratorRole(Actor actor, UUID excludedRole) {
        return jdbc.query(
                """
SELECT r.id FROM iam_app_member m
  JOIN iam_user u ON u.tenant_id=m.tenant_id AND u.id=m.user_id
  JOIN iam_tenant t ON t.id=m.tenant_id
  JOIN iam_app_member_role mr ON mr.tenant_id=m.tenant_id AND mr.application_id=m.application_id AND mr.user_id=m.user_id
  JOIN iam_app_role r ON r.tenant_id=mr.tenant_id AND r.application_id=mr.application_id AND r.id=mr.role_id
 WHERE m.tenant_id=? AND m.application_id=? AND m.user_id=?
   AND m.member_kind='PROTECTED' AND m.status='ACTIVE' AND m.deleted_at IS NULL
   AND u.status='ACTIVE' AND u.deleted_at IS NULL AND t.status='ACTIVE' AND t.deleted_at IS NULL
   AND r.protected_role=1 AND r.status='ACTIVE' AND r.deleted_at IS NULL
""" + (excludedRole == null ? "" : " AND r.id<>?") + " ORDER BY r.id",
                (rs, n) -> uuid(rs, "id"),
                excludedRole == null
                        ? new Object[] {bin(actor.tenantId()), bin(applicationId()), bin(actor.principalId())}
                        : new Object[] {bin(actor.tenantId()), bin(applicationId()), bin(actor.principalId()), bin(excludedRole)})
                .stream().findFirst().orElse(null);
    }

    public boolean protectedAdministrator(Actor actor) {
        return protectedAdministratorRole(actor, null) != null;
    }

    private Set<UUID> grantIds(Actor actor) {
        if (protectedAdministrator(actor))
            return rows(actor).stream().map(Node::id).collect(Collectors.toSet());
        return new HashSet<>(
                jdbc.query(
                        """
SELECT g.menu_node_id FROM iam_app_member m
  JOIN iam_app_member_role mr ON mr.tenant_id=m.tenant_id AND mr.application_id=m.application_id AND mr.user_id=m.user_id
  JOIN iam_app_role r ON r.tenant_id=mr.tenant_id AND r.application_id=mr.application_id AND r.id=mr.role_id
  JOIN iam_app_role_grant g ON g.tenant_id=r.tenant_id AND g.application_id=r.application_id AND g.role_id=r.id
 WHERE m.tenant_id=? AND m.application_id=? AND m.user_id=?
   AND m.status='ACTIVE' AND m.deleted_at IS NULL AND r.status='ACTIVE' AND r.deleted_at IS NULL
""",
                        (rs, row) -> uuid(rs, "menu_node_id"),
                        bin(actor.tenantId()),
                        bin(applicationId()),
                        bin(actor.principalId())));
    }

    public void requirePermission(Actor actor, String permission) {
        requireIdentity(actor);
        if (!permissions(actor).contains(permission))
            throw new AccessDeniedException("缺少供应链权限：" + permission);
    }

    @Override
    public List<Node> menus(Actor actor) {
        requirePermission(actor, "supply:menu:read");
        return rows(actor);
    }

    @Override
    public List<Node> catalog(Actor actor) {
        requirePermission(actor, "supply:menu:read");
        return catalogRows(actor);
    }

    public void lock(Actor actor) {
        var row =
                jdbc.query(
                        "SELECT version FROM iam_app_settings WHERE tenant_id=? AND"
                                + " application_id=? FOR UPDATE",
                        (rs, n) -> rs.getLong(1),
                        bin(actor.tenantId()),
                        bin(applicationId()));
        if (row.size() != 1) throw new IllegalStateException("请先初始化供应链设置");
    }

    @Override
    public Node saveMenu(Actor actor, UUID id, MenuCommand c) {
        if (c == null) throw new IllegalArgumentException("菜单参数不能为空");
        UUID target = id == null ? UUID.randomUUID() : id;
        tx.executeWithoutResult(
                status -> {
                    lock(actor);
                    requirePermission(
                            actor, id == null ? "supply:menu:create" : "supply:menu:update");
                    var list = new ArrayList<>(rows(actor));
                    Node old =
                            id == null
                                    ? null
                                    : list.stream()
                                            .filter(n -> n.id().equals(id))
                                            .findFirst()
                                            .orElseThrow(
                                                    () -> new IllegalArgumentException("菜单不存在"));
                    if ((old == null ? 0 : old.version()) != c.version())
                        throw new IllegalStateException("菜单已被修改，请刷新");
                    if (old != null
                            && (!Objects.equals(old.resourceId(), c.resourceId())
                                    || !old.type().equals(c.type())))
                        throw new IllegalArgumentException("编辑不能更换菜单类型或绑定功能");
                    Node feature =
                            c.resourceId() == null
                                    ? null
                                    : catalogRows(actor).stream()
                                            .filter(n -> n.resourceId().equals(c.resourceId()))
                                            .findFirst()
                                            .orElseThrow(
                                                    () ->
                                                            new AccessDeniedException(
                                                                    "功能不在本租户供应链可用目录中"));
                    if (feature != null && !feature.type().equals(c.type()))
                        throw new IllegalArgumentException("菜单类型与功能不一致");
                    if ("BUTTON".equals(c.type())) {
                        Node parent =
                                list.stream()
                                        .filter(n -> n.id().equals(c.parentId()))
                                        .findFirst()
                                        .orElseThrow(() -> new IllegalArgumentException("请选择所属页面"));
                        if (feature == null
                                || !Objects.equals(feature.parentId(), parent.resourceId()))
                            throw new IllegalArgumentException("操作不属于所选页面");
                    }
                    boolean customPage = "PAGE".equals(c.type()) && feature == null;
                    if ("PAGE".equals(c.type())) {
                        if (feature != null
                                && (feature.routeKey() == null || feature.routePath() == null))
                            throw new IllegalArgumentException("已注册页面缺少路由配置");
                        if (customPage) validateCustomPage(c);
                    }
                    String customRouteKey = null;
                    String customRoutePath = null;
                    String customComponentPath = null;
                    String customPermissionCode = null;
                    if (customPage) {
                        customRouteKey =
                                old == null ? customRouteKey(c, target) : old.routeKey();
                        customRoutePath = c.routePath().strip();
                        customComponentPath = c.componentPath().strip();
                        customPermissionCode =
                                c.permissionCode() == null || c.permissionCode().isBlank()
                                        ? null
                                        : c.permissionCode().strip();
                    }
                    String name = c.name() == null ? null : c.name().strip();
                    String icon =
                            c.iconKey() == null || c.iconKey().isBlank()
                                    ? null
                                    : c.iconKey().strip();
                    Node node =
                            new Node(
                                    target,
                                    c.parentId(),
                                    c.type(),
                                    c.resourceId(),
                                    name,
                                    icon,
                                    c.sortOrder(),
                                    c.visible(),
                                    c.status(),
                                    old != null
                                            ? old.protectedNode()
                                            : feature != null && feature.protectedNode(),
                                    old == null ? 0 : old.version() + 1,
                                    feature == null ? "CUSTOM." + target : feature.resourceCode(),
                                    customPage
                                            ? customPermissionCode
                                            : feature == null ? null : feature.permissionCode(),
                                    customPage
                                            ? customRouteKey
                                            : feature == null
                                                    ? "tenant.menu.group." + target
                                                    : feature.routeKey(),
                                    customPage
                                            ? customRoutePath
                                            : feature == null ? null : feature.routePath(),
                                    customPage ? customComponentPath : null);
                    list.removeIf(n -> n.id().equals(target));
                    list.add(node);
                    AppMenuTree.validate(list);
                    if (old == null) insert(actor, node, node.parentId());
                    else
                        jdbc.update(
                                """
UPDATE iam_app_menu_node SET parent_id=?,display_name=?,icon_key=?,sort_order=?,visible=?,status=?,
       route_key=?,route_path=?,component_path=?,permission_code=?,version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE tenant_id=? AND application_id=? AND id=? AND version=? AND deleted_at IS NULL
""",
                                bin(node.parentId()),
                                node.name(),
                                node.iconKey(),
                                node.sortOrder(),
                                node.visible(),
                                node.status(),
                                customPage ? node.routeKey() : null,
                                customPage ? node.routePath() : null,
                                customPage ? node.componentPath() : null,
                                customPage ? node.permissionCode() : null,
                                bin(actor.tenantId()),
                                bin(applicationId()),
                                bin(target),
                                c.version());
                    audit(
                            actor,
                            old == null ? "MENU_CREATE" : "MENU_UPDATE",
                            target.toString(),
                            name);
                    bump(actor);
                });
        return rows(actor).stream().filter(n -> n.id().equals(target)).findFirst().orElseThrow();
    }

    private void insert(Actor actor, Node n, UUID parent) {
        boolean customPage = "PAGE".equals(n.type()) && n.resourceId() == null;
        jdbc.update(
                """
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
       icon_key,sort_order,visible,status,protected_node,version,
       route_key,route_path,component_path,permission_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
                bin(actor.tenantId()),
                bin(applicationId()),
                bin(n.id()),
                bin(parent),
                bin(n.resourceId()),
                n.type(),
                n.name(),
                n.iconKey(),
                n.sortOrder(),
                n.visible(),
                n.status(),
                n.protectedNode(),
                n.version(),
                customPage ? n.routeKey() : null,
                customPage ? n.routePath() : null,
                customPage ? n.componentPath() : null,
                customPage ? n.permissionCode() : null);
    }

    /** 自定义页面只允许供应链内部路由与工程内单文件组件，路由标识由服务端生成并保持稳定。 */
    private static String customRouteKey(MenuCommand c, UUID target) {
        return c.routeKey() == null || c.routeKey().isBlank()
                ? "tenant.custom.page." + target
                : c.routeKey().strip();
    }

    private static void validateCustomPage(MenuCommand c) {
        String routePath = c.routePath() == null ? null : c.routePath().strip();
        String componentPath = c.componentPath() == null ? null : c.componentPath().strip();
        if ((routePath == null || routePath.isEmpty())
                && (componentPath == null || componentPath.isEmpty()))
            throw new IllegalArgumentException(
                    "请选择已注册页面，或填写自定义页面的路由地址与组件路径");
        if (routePath == null || !CUSTOM_ROUTE_PATH.matcher(routePath).matches())
            throw new IllegalArgumentException("自定义页面路由地址必须以 /supply-chain/ 开头");
        if (componentPath == null || !CUSTOM_COMPONENT_PATH.matcher(componentPath).matches())
            throw new IllegalArgumentException(
                    "自定义页面组件路径必须以 supply-chain/ 开头并以 .vue 结尾");
    }

    @Override
    public Impact menuImpact(Actor actor, UUID id) {
        requirePermission(actor, "supply:menu:delete");
        Node node =
                rows(actor).stream()
                        .filter(n -> n.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("菜单不存在"));
        return impact(actor, node);
    }

    private Impact impact(Actor actor, Node node) {
        List<String> roles =
                jdbc.query(
                        """
SELECT r.role_name FROM iam_app_role_grant g JOIN iam_app_role r ON r.tenant_id=g.tenant_id
    AND r.application_id=g.application_id AND r.id=g.role_id
 WHERE g.tenant_id=? AND g.application_id=? AND g.menu_node_id=? AND r.deleted_at IS NULL ORDER BY r.role_name
""",
                        (rs, row) -> rs.getString(1),
                        bin(actor.tenantId()),
                        bin(applicationId()),
                        bin(node.id()));
        int users =
                count(
                        """
SELECT COUNT(DISTINCT mr.user_id) FROM iam_app_member_role mr JOIN iam_app_role_grant g
    ON g.tenant_id=mr.tenant_id AND g.application_id=mr.application_id AND g.role_id=mr.role_id
 WHERE g.tenant_id=? AND g.application_id=? AND g.menu_node_id=?
""",
                        bin(actor.tenantId()),
                        bin(applicationId()),
                        bin(node.id()));
        int children =
                count(
                        "SELECT COUNT(*) FROM iam_app_menu_node WHERE tenant_id=? AND"
                                + " application_id=? AND parent_id=? AND deleted_at IS NULL",
                        bin(actor.tenantId()),
                        bin(applicationId()),
                        bin(node.id()));
        return new Impact(node.id(), node.version(), roles, users, children);
    }

    @Override
    public void deleteMenu(Actor actor, UUID id, long version, boolean revoke) {
        tx.executeWithoutResult(
                status -> {
                    lock(actor);
                    requirePermission(actor, "supply:menu:delete");
                    Node node =
                            rows(actor).stream()
                                    .filter(n -> n.id().equals(id))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("菜单不存在"));
                    if (node.version() != version) throw new IllegalStateException("菜单已被修改，请重新预览");
                    if (node.protectedNode()) throw new IllegalArgumentException("不能删除系统管理恢复入口");
                    Impact impact = impact(actor, node);
                    if (impact.childCount() > 0) throw new IllegalArgumentException("请先移动或删除子菜单");
                    if (!impact.roleNames().isEmpty()) {
                        if (!revoke) throw new IllegalArgumentException("请先确认撤销受影响角色的授权");
                        requirePermission(actor, "supply:role:grant");
                    }
                    jdbc.update(
                            "DELETE FROM iam_app_role_grant WHERE tenant_id=? AND application_id=?"
                                    + " AND menu_node_id=?",
                            bin(actor.tenantId()),
                            bin(applicationId()),
                            bin(id));
                    jdbc.update(
                            """
UPDATE iam_app_menu_node SET deleted_at=UTC_TIMESTAMP(6),status='DISABLED',
       version=version+1
 WHERE tenant_id=? AND application_id=? AND id=?
""",
                            bin(actor.tenantId()),
                            bin(applicationId()),
                            bin(id));
                    audit(
                            actor,
                            "MENU_DELETE",
                            id.toString(),
                            "删除菜单并撤销 " + impact.roleNames().size() + " 个角色授权");
                    bump(actor);
                });
    }

    public List<Node> rows(Actor actor) {
        return jdbc.query(
                """
SELECT n.id,n.parent_id,n.resource_id,n.node_type,n.display_name,n.icon_key,n.sort_order,n.visible,
       n.status,n.protected_node,n.version,r.resource_code,
       COALESCE(n.permission_code,r.permission_code) AS permission_code,
       COALESCE(n.route_key,ui.route_key) AS route_key,
       COALESCE(n.route_path,ui.route_path) AS route_path,
       n.component_path
  FROM iam_app_menu_node n LEFT JOIN iam_resource r ON r.id=n.resource_id
  LEFT JOIN iam_resource_ui ui ON ui.resource_id=r.id
 WHERE n.tenant_id=? AND n.application_id=? AND n.deleted_at IS NULL
 ORDER BY n.sort_order,n.id
""",
                (rs, row) ->
                        new Node(
                                uuid(rs, "id"),
                                uuid(rs, "parent_id"),
                                rs.getString("node_type"),
                                uuid(rs, "resource_id"),
                                rs.getString("display_name"),
                                rs.getString("icon_key"),
                                rs.getInt("sort_order"),
                                rs.getBoolean("visible"),
                                rs.getString("status"),
                                rs.getBoolean("protected_node"),
                                rs.getLong("version"),
                                rs.getString("resource_code"),
                                rs.getString("permission_code"),
                                rs.getString("route_key") == null
                                        ? "tenant.menu.group." + uuid(rs, "id")
                                        : rs.getString("route_key"),
                                rs.getString("route_path"),
                                rs.getString("component_path")),
                bin(actor.tenantId()),
                bin(applicationId()));
    }

    private List<Node> catalogRows(Actor actor) {
        if (actor.tenantId() == null) return List.of();
        return jdbc.query(
                """
SELECT DISTINCT r.id,r.parent_id,r.resource_type,r.display_name,r.permission_code,r.resource_code,r.sort_order,
       ui.icon_key,ui.visible,ui.route_key,ui.route_path
  FROM iam_tenant_subscription s JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id
  JOIN iam_resource r ON r.id=pr.resource_id
  JOIN iam_application a ON a.id=r.application_id
  LEFT JOIN iam_resource_ui ui ON ui.resource_id=r.id
 WHERE s.tenant_id=? AND s.status IN ('ACTIVE','SCHEDULED') AND s.deleted_at IS NULL
   AND s.effective_from<=UTC_TIMESTAMP(6) AND s.effective_to>UTC_TIMESTAMP(6)
   AND a.app_code='SUPPLY_CHAIN' AND a.status='ACTIVE' AND a.deleted_at IS NULL
   AND r.status='ACTIVE' AND r.deleted_at IS NULL
   AND (r.resource_type<>'PAGE' OR (ui.route_key IS NOT NULL AND ui.route_path IS NOT NULL))
   AND (r.resource_type IN ('MENU','PAGE','BUTTON') OR (r.resource_type='API' AND r.permission_code IS NOT NULL))
 ORDER BY r.sort_order,r.id
""",
                (rs, row) ->
                        new Node(
                                uuid(rs, "id"),
                                uuid(rs, "parent_id"),
                                "API".equals(rs.getString("resource_type"))
                                        ? "BUTTON"
                                        : rs.getString("resource_type"),
                                uuid(rs, "id"),
                                rs.getString("display_name"),
                                com.rigour.tenant.iam.domain.model.settings.AppMenuIcons.legacy(
                                        rs.getString("icon_key")),
                                rs.getInt("sort_order"),
                                rs.getBoolean("visible"),
                                "ACTIVE",
                                Set.of(
                                                "SUPPLY_CHAIN.SETTINGS.MENUS",
                                                "SUPPLY_CHAIN.SETTINGS.ROLES",
                                                "SUPPLY_CHAIN.SETTINGS.USERS")
                                        .contains(rs.getString("resource_code")),
                                0,
                                rs.getString("resource_code"),
                                rs.getString("permission_code"),
                                rs.getString("route_key"),
                                rs.getString("route_path"),
                                null),
                bin(actor.tenantId()));
    }

    @Override
    public List<NavigationNode> navigation(Actor actor) {
        requireIdentity(actor);
        List<Node> all = rows(actor);
        Map<UUID, Node> index =
                all.stream().collect(Collectors.toMap(Node::id, Function.identity()));
        Set<UUID> granted = grantIds(actor);
        String mode =
                jdbc.queryForObject(
                        "SELECT authorization_mode FROM iam_app_settings WHERE tenant_id=? AND"
                                + " application_id=?",
                        String.class,
                        bin(actor.tenantId()),
                        bin(applicationId()));
        if ("PREPARING".equals(mode)) {
            Set<UUID> legacy =
                    new HashSet<>(
                            jdbc.query(
                                    """
SELECT rr.resource_id FROM iam_user_role ur
  JOIN iam_effective_tenant_role_resource rr ON rr.tenant_id=ur.tenant_id AND rr.role_id=ur.role_id
 WHERE ur.tenant_id=? AND ur.user_id=? AND ur.status='ACTIVE'
   AND ur.effective_from<=UTC_TIMESTAMP(6) AND (ur.effective_to IS NULL OR ur.effective_to>UTC_TIMESTAMP(6))
""",
                                    (rs, n) -> uuid(rs, "resource_id"),
                                    bin(actor.tenantId()),
                                    bin(actor.principalId())));
            all.stream()
                    .filter(n -> legacy.contains(n.resourceId()))
                    .map(Node::id)
                    .forEach(granted::add);
        }
        Set<UUID> entitled =
                catalogRows(actor).stream().map(Node::resourceId).collect(Collectors.toSet());
        var allowed = new HashSet<UUID>();
        for (Node node : all)
            if (granted.contains(node.id())
                    && (node.resourceId() == null || entitled.contains(node.resourceId()))
                    && AppMenuTree.enabled(node, index)) {
                Node current = node;
                while (current != null && allowed.add(current.id()))
                    current = index.get(current.parentId());
            }
        return navigationChildren(null, all, allowed, true);
    }

    private List<NavigationNode> navigationChildren(
            UUID parent, List<Node> all, Set<UUID> allowed, boolean parentVisible) {
        return all.stream()
                .filter(
                        n ->
                                Objects.equals(parent, n.parentId())
                                        && !"BUTTON".equals(n.type())
                                        && allowed.contains(n.id()))
                .sorted(
                        Comparator.comparingInt(Node::sortOrder)
                                .thenComparing(n -> n.id().toString()))
                .map(
                        n ->
                                new NavigationNode(
                                        n.id(),
                                        n.parentId(),
                                        n.resourceCode() == null
                                                ? "CUSTOM." + n.id()
                                                : n.resourceCode(),
                                        n.type(),
                                        n.name(),
                                        n.permissionCode(),
                                        n.routeKey(),
                                        n.routePath(),
                                        n.componentPath(),
                                        n.iconKey(),
                                        n.sortOrder(),
                                        parentVisible && n.visible(),
                                        false,
                                        navigationChildren(
                                                n.id(),
                                                all,
                                                allowed,
                                                parentVisible && n.visible())))
                .toList();
    }

    public void bump(Actor actor) {
        jdbc.update(
                "UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE"
                        + " tenant_id=? AND application_id=?",
                bin(actor.tenantId()),
                bin(applicationId()));
    }

    public void audit(Actor actor, String action, String target, String summary) {
        jdbc.update(
                """
INSERT INTO iam_app_audit(id,tenant_id,application_id,actor_id,action_code,target_id,result,summary)
VALUES(?,?,?,?,?,?,'SUCCESS',?)
""",
                bin(UUID.randomUUID()),
                bin(actor.tenantId()),
                bin(applicationId()),
                bin(actor.principalId()),
                action,
                target,
                summary);
    }

    @Override
    public AuditPage audits(Actor actor, String action, String keyword, int page, int size) {
        requirePermission(actor, "supply:audit:read");
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > 1000000)
            throw new IllegalArgumentException("分页范围无效");
        String actionFilter = action == null || action.isBlank() ? null : action.strip();
        String key = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (key != null && key.length() > 100) throw new IllegalArgumentException("关键词过长");
        String where =
                " WHERE a.tenant_id=? AND a.application_id=? AND (? IS NULL OR a.action_code=?) AND"
                        + " (? IS NULL OR a.target_id LIKE ? OR u.username LIKE ?)";
        Object[] args = {
            bin(actor.tenantId()),
            bin(applicationId()),
            actionFilter,
            actionFilter,
            key,
            "%" + key + "%",
            "%" + key + "%"
        };
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_app_audit a LEFT JOIN iam_user u ON"
                                + " u.tenant_id=a.tenant_id AND u.id=a.actor_id"
                                + where,
                        Long.class,
                        args);
        var queryArgs = new ArrayList<>(Arrays.asList(args));
        queryArgs.add(size);
        queryArgs.add((page - 1) * size);
        var items =
                jdbc.query(
                        """
SELECT a.*,u.username FROM iam_app_audit a LEFT JOIN iam_user u ON u.tenant_id=a.tenant_id AND u.id=a.actor_id
"""
                                + where
                                + " ORDER BY a.occurred_at DESC,a.id DESC LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new Audit(
                                        uuid(rs, "id"),
                                        rs.getString("username"),
                                        rs.getString("action_code"),
                                        rs.getString("target_id"),
                                        rs.getString("result"),
                                        rs.getString("summary"),
                                        rs.getTimestamp("occurred_at").toInstant()),
                        queryArgs.toArray());
        return new AuditPage(items, total == null ? 0 : total, page, size);
    }

    public int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
