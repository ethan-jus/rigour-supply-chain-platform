package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import com.rigour.tenant.iam.application.port.out.*;
import com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.Role;
import com.rigour.tenant.iam.application.service.settings.AppMemberModels.*;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Repository
public class JdbcAppMemberStore implements AppMemberStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final JdbcAppRoleStore roles;
    private final AppEmployeeClient employees;
    private final PasswordHasher passwords;
    private final TransactionTemplate tx;

    public JdbcAppMemberStore(
            JdbcTemplate jdbc,
            JdbcAppSettingsStore settings,
            JdbcAppRoleStore roles,
            AppEmployeeClient employees,
            PasswordHasher passwords,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.roles = roles;
        this.employees = employees;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(manager);
    }

    private byte[] tenant(Actor a) {
        return bin(a.tenantId());
    }

    private byte[] app() {
        return bin(settings.applicationId());
    }

    private record Raw(
            UUID id,
            String username,
            String globalName,
            String globalStatus,
            String kind,
            String status,
            String remark,
            long version,
            String employeeCode,
            long accessVersion,
            java.time.Instant createdTime,
            java.time.Instant updatedTime) {}

    private static final String SELECT =
            "SELECT m.*,u.username,u.display_name,u.status AS"
                + " global_status,b.employee_code,b.hr_access_version FROM iam_app_member m JOIN"
                + " iam_user u ON u.tenant_id=m.tenant_id AND u.id=m.user_id LEFT JOIN"
                + " iam_app_employee_binding b ON b.tenant_id=m.tenant_id AND"
                + " b.application_id=m.application_id AND b.user_id=m.user_id WHERE m.tenant_id=?"
                + " AND m.application_id=? AND m.deleted_at IS NULL AND u.deleted_at IS NULL";

    private List<Raw> raw(Actor a, String suffix, Object... extra) {
        List<Object> args = new ArrayList<>(List.of(tenant(a), app()));
        args.addAll(Arrays.asList(extra));
        return jdbc.query(
                SELECT + suffix,
                (r, n) ->
                        new Raw(
                                UuidBinaryCodec.decode(r.getBytes("user_id")),
                                r.getString("username"),
                                r.getString("display_name"),
                                r.getString("global_status"),
                                r.getString("member_kind"),
                                r.getString("status"),
                                r.getString("remark"),
                                r.getLong("version"),
                                r.getString("employee_code"),
                                r.getLong("hr_access_version"),
                                r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("updated_at").toInstant()),
                args.toArray());
    }

    private Raw raw(Actor a, UUID id) {
        return raw(a, " AND m.user_id=?", bin(id)).stream()
                .findFirst()
                .orElseThrow(() -> new RequestValidationException("供应链用户不存在"));
    }

    private Member view(Actor a, Raw r, Employee employee) {
        List<Assignment> assigned = assignments(a, r.id());
        String reason =
                !"ACTIVE".equals(r.globalStatus())
                        ? "统一登录账号不可用"
                        : !"ACTIVE".equals(r.status())
                                ? "供应链账号已禁用"
                                : !"PROTECTED".equals(r.kind())
                                        ? (employee == null
                                                ? "员工关联无效"
                                                : !employee.usable()
                                                        ? employee.unavailableReason()
                                                        : employee.accessVersion()
                                                                        != r.accessVersion()
                                                                ? "员工状态曾失效，需要重新核验授权"
                                                                : assigned.isEmpty()
                                                                        ? "尚未分配角色"
                                                                        : null)
                                        : null;
        return new Member(
                r.id(),
                r.username(),
                employee == null ? r.globalName() : employee.employeeName(),
                r.kind(),
                r.status(),
                r.remark(),
                r.version(),
                r.employeeCode(),
                employee,
                assigned,
                reason == null,
                reason,
                auditActor(a, r.id(), true),
                r.createdTime(),
                auditActor(a, r.id(), false),
                r.updatedTime());
    }

    private String auditActor(Actor a, UUID id, boolean created) {
        var found =
                jdbc.query(
                        "SELECT u.display_name FROM iam_app_audit log LEFT JOIN iam_user u ON"
                            + " u.tenant_id=log.tenant_id AND u.id=log.actor_id WHERE"
                            + " log.tenant_id=? AND log.application_id=? AND log.target_id=? AND "
                                + (created
                                        ? "log.action_code='MEMBER_CREATE'"
                                        : "log.action_code IN"
                                              + " ('MEMBER_CREATE','MEMBER_UPDATE','MEMBER_STATUS','MEMBER_BATCH_ROLES','MEMBER_PASSWORD_RESET')")
                                + " ORDER BY log.occurred_at "
                                + (created ? "ASC" : "DESC")
                                + ",log.id LIMIT 1",
                        (rs, n) -> rs.getString(1),
                        tenant(a),
                        app(),
                        id.toString());
        return found.isEmpty() ? null : found.getFirst();
    }

    private Member view(Actor a, UUID id) {
        Raw r = raw(a, id);
        return view(
                a,
                r,
                r.employeeCode() == null
                        ? null
                        : employees.employee(a.tenantId(), r.employeeCode()));
    }

    @Override
    public Page members(Actor a, String keyword, int page, int size, Long departmentId) {
        settings.requirePermission(a, "supply:user:read");
        if (page < 1 || size < 1 || size > 100 || page > 100000)
            throw new RequestValidationException("分页参数无效");
        String filter = keyword == null ? "" : keyword.strip();
        if (filter.length() > 128) throw new RequestValidationException("搜索词过长");
        String suffix = " AND (u.username LIKE ? OR b.employee_code LIKE ?)";
        String pattern = "%" + filter + "%";
        if (departmentId != null) {
            if (departmentId <= 0) throw new RequestValidationException("部门 ID 无效");
            // 只读取账号和 HR 身份元数据；先按当前主部门及祖先筛选，再计数和分页。
            List<Raw> candidates =
                    raw(a, suffix + " ORDER BY m.created_at DESC,m.user_id", pattern, pattern);
            List<Raw> matched = new ArrayList<>();
            Map<String, Employee> identities = new HashMap<>();
            for (int start = 0; start < candidates.size(); start += 100) {
                var batch = candidates.subList(start, Math.min(start + 100, candidates.size()));
                var codes =
                        batch.stream()
                                .map(Raw::employeeCode)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();
                var found = employees.employees(a.tenantId(), codes);
                for (var row : batch) {
                    var employee =
                            row.employeeCode() == null ? null : found.get(row.employeeCode());
                    if (employee != null
                            && (departmentId.equals(employee.departmentId())
                                    || employee.departmentAncestorIds().contains(departmentId))) {
                        matched.add(row);
                        identities.put(row.employeeCode(), employee);
                    }
                }
            }
            return new Page(
                    matched.stream()
                            .skip((long) (page - 1) * size)
                            .limit(size)
                            .map(row -> view(a, row, identities.get(row.employeeCode())))
                            .toList(),
                    matched.size(),
                    page,
                    size);
        }
        List<Raw> rows =
                raw(
                        a,
                        suffix + " ORDER BY m.created_at DESC,m.user_id LIMIT ? OFFSET ?",
                        pattern,
                        pattern,
                        size,
                        (page - 1) * size);
        List<String> codes =
                rows.stream().map(Raw::employeeCode).filter(Objects::nonNull).distinct().toList();
        Map<String, Employee> identities = employees.employees(a.tenantId(), codes);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (" + SELECT + suffix + ") AS members",
                        Long.class,
                        tenant(a),
                        app(),
                        pattern,
                        pattern);
        return new Page(
                rows.stream()
                        .map(
                                r ->
                                        view(
                                                a,
                                                r,
                                                r.employeeCode() == null
                                                        ? null
                                                        : identities.get(r.employeeCode())))
                        .toList(),
                total,
                page,
                size);
    }

    @Override
    public List<Account> accounts(Actor a, String keyword) {
        settings.requirePermission(a, "supply:user:create");
        String pattern = "%" + (keyword == null ? "" : keyword.strip()) + "%";
        return jdbc.query(
                "SELECT u.id,u.username,u.status FROM iam_user u WHERE u.tenant_id=? AND"
                    + " u.deleted_at IS NULL AND u.status='ACTIVE' AND u.username LIKE ? AND NOT"
                    + " EXISTS(SELECT 1 FROM iam_app_member m WHERE m.tenant_id=u.tenant_id AND"
                    + " m.user_id=u.id AND m.application_id=? AND m.deleted_at IS NULL) ORDER BY"
                    + " u.username LIMIT 100",
                (r, n) ->
                        new Account(
                                UuidBinaryCodec.decode(r.getBytes(1)),
                                r.getString(2),
                                r.getString(3)),
                tenant(a),
                pattern,
                app());
    }

    @Override
    public Member save(Actor a, UUID id, Command c) {
        if (c == null) throw new RequestValidationException("用户参数不能为空");
        settings.requirePermission(a, id == null ? "supply:user:create" : "supply:user:update");
        String code = text(c.employeeCode(), 50, "关联员工");
        Employee employee = employees.employee(a.tenantId(), code);
        if (!employee.usable())
            throw new RequestValidationException("员工不可关联：" + employee.unavailableReason());
        String initialHash =
                id == null && c.existingUserId() == null
                        ? passwords.hash(password(c.initialPassword()))
                        : null;
        return tx.execute(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(
                            a, id == null ? "supply:user:create" : "supply:user:update");
                    Raw old = id == null ? null : raw(a, id);
                    if (old != null) editable(old, c.version());
                    else if (c.version() != 0) throw new RequestValidationException("新增用户版本无效");
                    if (!Set.of("ACTIVE", "DISABLED").contains(c.status()))
                        throw new RequestValidationException("用户状态无效");
                    if (c.remark() != null && c.remark().length() > 500)
                        throw new RequestValidationException("备注不能超过 500 字符");
                    boolean bindingChanged =
                            old == null
                                    || !Objects.equals(old.employeeCode(), code)
                                    || old.accessVersion() != employee.accessVersion();
                    if (old != null && bindingChanged) {
                        settings.requirePermission(a, "supply:user:rebind");
                        text(c.bindingReason(), 500, "重新核验或关联更正原因");
                    }
                    if (old != null && !old.status().equals(c.status()))
                        settings.requirePermission(a, "supply:user:disable");
                    if (old != null && "DISABLED".equals(c.status())) requireNotSelf(a, old.id());
                    List<Assignment> previous = old == null ? List.of() : assignments(a, id);
                    boolean grantChanged =
                            (old != null
                                            && !"ACTIVE".equals(old.status())
                                            && "ACTIVE".equals(c.status()))
                                    || bindingChanged
                                    || old == null
                                    || !previous.equals(c.roles());
                    if (grantChanged) settings.requirePermission(a, "supply:user:assign-role");
                    validateAssignments(
                            a, c.roles(), "ACTIVE".equals(c.status()), grantChanged, employee);

                    UUID target = id;
                    if (target == null) {
                        target =
                                c.existingUserId() == null ? UUID.randomUUID() : c.existingUserId();
                        if (c.existingUserId() == null) {
                            jdbc.queryForObject(
                                    "SELECT id FROM iam_tenant WHERE id=? FOR UPDATE",
                                    byte[].class,
                                    tenant(a));
                            requireCapacity(a);
                            String username =
                                    text(c.username(), 64, "登录账号").toLowerCase(Locale.ROOT);
                            if (!username.matches("[a-z0-9][a-z0-9._@-]{2,63}"))
                                throw new RequestValidationException("账号为 3 至 64 位字母、数字或 . _ @ -");
                            jdbc.update(
                                    "INSERT INTO"
                                        + " iam_user(id,tenant_id,username,display_name,status,created_at,created_by,updated_at,updated_by)"
                                        + " VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),?)",
                                    bin(target),
                                    tenant(a),
                                    username,
                                    employee.employeeName(),
                                    bin(a.principalId()),
                                    bin(a.principalId()));
                            jdbc.update(
                                    "INSERT INTO"
                                        + " iam_user_credential(id,tenant_id,user_id,credential_type,password_hash,algorithm,algorithm_version,password_changed_at,status,created_at,updated_at)"
                                        + " VALUES(?,?,?,'PASSWORD',?,'ARGON2ID',1,UTC_TIMESTAMP(6),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                                    bin(UUID.randomUUID()),
                                    tenant(a),
                                    bin(target),
                                    initialHash);
                        } else if (settings.count(
                                        "SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND id=?"
                                                + " AND status='ACTIVE' AND deleted_at IS NULL",
                                        tenant(a),
                                        bin(target))
                                != 1) throw new RequestValidationException("已有登录账号不可用");
                        if (settings.count(
                                        "SELECT COUNT(*) FROM iam_app_member WHERE tenant_id=? AND"
                                            + " application_id=? AND user_id=? AND deleted_at IS"
                                            + " NULL",
                                        tenant(a),
                                        app(),
                                        bin(target))
                                > 0) throw new RequestValidationException("账号已经开通供应链");
                        jdbc.update(
                                "INSERT INTO"
                                    + " iam_app_member(tenant_id,application_id,user_id,member_kind,status,remark)"
                                    + " VALUES(?,?,?,'BUSINESS',?,?) ON DUPLICATE KEY UPDATE"
                                    + " deleted_at=NULL,status=VALUES(status),remark=VALUES(remark),version=version+1,security_version=security_version+1,updated_at=UTC_TIMESTAMP(6)",
                                tenant(a),
                                app(),
                                bin(target),
                                c.status(),
                                c.remark());
                    } else
                        jdbc.update(
                                "UPDATE iam_app_member SET"
                                    + " status=?,remark=?,version=version+1,security_version=security_version+1,updated_at=UTC_TIMESTAMP(6)"
                                    + " WHERE tenant_id=? AND application_id=? AND user_id=?",
                                c.status(),
                                c.remark(),
                                tenant(a),
                                app(),
                                bin(target));
                    if (bindingChanged) {
                        if (settings.count(
                                        "SELECT COUNT(*) FROM iam_app_employee_binding WHERE"
                                                + " tenant_id=? AND application_id=? AND"
                                                + " employee_code=? AND user_id<>?",
                                        tenant(a),
                                        app(),
                                        code,
                                        bin(target))
                                > 0) throw new RequestValidationException("该员工已关联其他供应链用户");
                        jdbc.update(
                                "UPDATE iam_app_employee_binding_history SET"
                                    + " effective_to=UTC_TIMESTAMP(6) WHERE tenant_id=? AND"
                                    + " application_id=? AND user_id=? AND effective_to IS NULL",
                                tenant(a),
                                app(),
                                bin(target));
                        jdbc.update(
                                "INSERT INTO"
                                    + " iam_app_employee_binding(tenant_id,application_id,user_id,employee_code,hr_revision,hr_access_version)"
                                    + " VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                                    + " employee_code=VALUES(employee_code),hr_revision=VALUES(hr_revision),hr_access_version=VALUES(hr_access_version),version=version+1,updated_at=UTC_TIMESTAMP(6)",
                                tenant(a),
                                app(),
                                bin(target),
                                code,
                                employee.employeeRevision(),
                                employee.accessVersion());
                        jdbc.update(
                                "INSERT INTO"
                                    + " iam_app_employee_binding_history(id,tenant_id,application_id,user_id,employee_code,effective_from,reason,actor_id)"
                                    + " VALUES(?,?,?,?,?,UTC_TIMESTAMP(6),?,?)",
                                bin(UUID.randomUUID()),
                                tenant(a),
                                app(),
                                bin(target),
                                code,
                                old == null ? "首次开通供应链" : c.bindingReason(),
                                bin(a.principalId()));
                    }
                    persistAssignments(a, target, c.roles());
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                    settings.audit(
                            a,
                            old == null ? "MEMBER_CREATE" : "MEMBER_UPDATE",
                            target.toString(),
                            "关联员工 "
                                    + code
                                    + "；角色 "
                                    + c.roles().size()
                                    + " 个"
                                    + (bindingChanged ? "；已核验员工身份" : ""));
                    return view(a, raw(a, target), employee);
                });
    }

    private void requireCapacity(Actor a) {
        List<Integer> limits =
                jdbc.queryForList(
                        "SELECT user_limit FROM iam_tenant_subscription WHERE tenant_id=? AND"
                            + " status IN ('ACTIVE','SCHEDULED') AND"
                            + " effective_from<=UTC_TIMESTAMP(6) AND effective_to>UTC_TIMESTAMP(6)",
                        Integer.class,
                        tenant(a));
        if (limits.size() != 1) throw new StateConflictException("租户订阅状态无效");
        if (settings.count(
                        "SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND status IN"
                                + " ('ACTIVE','LOCKED') AND deleted_at IS NULL",
                        tenant(a))
                >= limits.getFirst()) throw new StateConflictException("登录账号数量已达到订阅上限");
    }

    private void editable(Raw r, long version) {
        if ("PROTECTED".equals(r.kind()))
            throw new RequestValidationException("受保护的恢复账号不允许在普通用户入口修改");
        if (r.version() != version) throw new StateConflictException("用户已修改，请刷新");
    }

    public List<Assignment> assignments(Actor a, UUID user) {
        return jdbc.query(
                "SELECT role_id FROM iam_app_member_role WHERE tenant_id=? AND application_id=? AND"
                        + " user_id=? ORDER BY role_id",
                (rs, n) -> {
                    UUID role = UuidBinaryCodec.decode(rs.getBytes(1));
                    return new Assignment(role, Map.of());
                },
                tenant(a),
                app(),
                bin(user));
    }

    private void validateAssignments(
            Actor a,
            List<Assignment> next,
            boolean active,
            boolean delegation,
            Employee targetEmployee) {
        if (next == null || next.size() > 50 || (active && next.isEmpty()))
            throw new RequestValidationException("启用用户至少选择一个有效角色，最多 50 个角色");
        Set<UUID> seen = new HashSet<>();
        for (Assignment assignment : next) {
            if (assignment == null
                    || !seen.add(assignment.roleId())
                    || assignment.parameters() == null)
                throw new RequestValidationException("角色分配重复或参数无效");
            Role role = roles.role(a, assignment.roleId());
            if (!"ACTIVE".equals(role.status()) || role.protectedRole())
                throw new RequestValidationException("请选择有效普通角色");
            if (delegation) {
                roles.requireDelegable(a, role.menuNodeIds(), role.rules());
                roles.requireCurrentDepartmentDelegable(a, targetEmployee, role.rules());
            }
            if (!assignment.parameters().isEmpty())
                throw new RequestValidationException("数据范围请在角色中统一设置");
        }
    }

    /** CURRENT 在每位员工身上解析不同，必须按 HR 当前任职比较，而非比较模板文本。 */
    static void requireCurrentDepartmentWithin(
            Employee actor, Employee target, boolean descendants) {
        if (actor == null
                || target == null
                || !actor.usable()
                || !target.usable()
                || actor.departmentId() == null
                || target.departmentId() == null)
            throw new AccessDeniedException("员工部门不可用，无法核验可授予边界");
        if (actor.departmentId().equals(target.departmentId())) return;
        if (descendants
                && target.departmentAncestorIds() != null
                && target.departmentAncestorIds().contains(actor.departmentId())) return;
        throw new AccessDeniedException("目标员工当前部门超出可授予边界");
    }

    private static void requireNotSelf(Actor actor, UUID target) {
        if (actor.principalId().equals(target))
            throw new RequestValidationException("不能禁用或删除当前登录的供应链账号");
    }

    private void persistAssignments(Actor a, UUID user, List<Assignment> next) {
        jdbc.update(
                "DELETE FROM iam_app_member_role_scope WHERE tenant_id=? AND application_id=? AND"
                        + " user_id=?",
                tenant(a),
                app(),
                bin(user));
        jdbc.update(
                "DELETE FROM iam_app_member_role WHERE tenant_id=? AND application_id=? AND"
                        + " user_id=?",
                tenant(a),
                app(),
                bin(user));
        for (Assignment x : next) {
            jdbc.update(
                    "INSERT INTO iam_app_member_role(tenant_id,application_id,user_id,role_id)"
                            + " VALUES(?,?,?,?)",
                    tenant(a),
                    app(),
                    bin(user),
                    bin(x.roleId()));
        }
    }

    @Override
    public void status(Actor a, UUID id, StatusCommand c) {
        if (c == null) throw new RequestValidationException("状态参数不能为空");
        tx.executeWithoutResult(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(a, "supply:user:disable");
                    Raw old = raw(a, id);
                    editable(old, c.version());
                    if ("DISABLED".equals(c.status())) requireNotSelf(a, id);
                    if (!Set.of("ACTIVE", "DISABLED").contains(c.status()))
                        throw new RequestValidationException("状态无效");
                    if ("ACTIVE".equals(c.status())) {
                        settings.requirePermission(a, "supply:user:assign-role");
                        Employee employee = employees.employee(a.tenantId(), old.employeeCode());
                        if (!employee.usable() || employee.accessVersion() != old.accessVersion())
                            throw new RequestValidationException("员工状态已变化，请编辑用户重新核验授权");
                        validateAssignments(a, assignments(a, id), true, true, employee);
                    }
                    jdbc.update(
                            "UPDATE iam_app_member SET"
                                + " status=?,version=version+1,security_version=security_version+1,updated_at=UTC_TIMESTAMP(6)"
                                + " WHERE tenant_id=? AND application_id=? AND user_id=?",
                            c.status(),
                            tenant(a),
                            app(),
                            bin(id));
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                    settings.audit(a, "MEMBER_STATUS", id.toString(), c.status());
                });
    }

    @Override
    public void delete(Actor a, UUID id, long version) {
        tx.executeWithoutResult(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(a, "supply:user:delete");
                    Raw old = raw(a, id);
                    editable(old, version);
                    requireNotSelf(a, id);
                    if (!"DISABLED".equals(old.status()))
                        throw new RequestValidationException("请先禁用供应链用户");
                    persistAssignments(a, id, List.of());
                    jdbc.update(
                            "DELETE FROM iam_app_employee_binding WHERE tenant_id=? AND"
                                    + " application_id=? AND user_id=?",
                            tenant(a),
                            app(),
                            bin(id));
                    jdbc.update(
                            "UPDATE iam_app_employee_binding_history SET"
                                    + " effective_to=UTC_TIMESTAMP(6) WHERE tenant_id=? AND"
                                    + " application_id=? AND user_id=? AND effective_to IS NULL",
                            tenant(a),
                            app(),
                            bin(id));
                    jdbc.update(
                            "UPDATE iam_app_member SET"
                                + " deleted_at=UTC_TIMESTAMP(6),version=version+1,security_version=security_version+1"
                                + " WHERE tenant_id=? AND application_id=? AND user_id=?",
                            tenant(a),
                            app(),
                            bin(id));
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                    settings.audit(a, "MEMBER_DELETE", id.toString(), "删除供应链资格，保留历史记录");
                });
    }

    @Override
    public BatchPreview preview(Actor a, BatchCommand c) {
        return tx.execute(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(a, "supply:user:assign-role");
                    validateBatch(a, c, false);
                    long version =
                            jdbc.queryForObject(
                                    "SELECT version FROM iam_app_settings WHERE tenant_id=? AND"
                                            + " application_id=?",
                                    Long.class,
                                    tenant(a),
                                    app());
                    return new BatchPreview(version, c.members(), c.mode(), c.roles().size());
                });
    }

    private void validateBatch(Actor a, BatchCommand c, boolean checkVersion) {
        if (c == null
                || c.members() == null
                || c.members().isEmpty()
                || c.members().size() > 100
                || c.roles() == null
                || !Set.of("APPEND", "REMOVE", "REPLACE").contains(c.mode()))
            throw new RequestValidationException("批量分配最多 100 人，模式必须为追加、移除或替换");
        if (c.members().stream().map(VersionedMember::id).distinct().count() != c.members().size())
            throw new RequestValidationException("用户不能重复");
        if (checkVersion
                && jdbc.queryForObject(
                                "SELECT version FROM iam_app_settings WHERE tenant_id=? AND"
                                        + " application_id=?",
                                Long.class,
                                tenant(a),
                                app())
                        != c.applicationVersion())
            throw new StateConflictException("授权配置已变化，请重新预览");
        for (var member : c.members()) {
            Raw r = raw(a, member.id());
            editable(r, member.version());
            List<Assignment> next = merge(assignments(a, r.id()), c.roles(), c.mode());
            validateAssignments(
                    a,
                    next,
                    "ACTIVE".equals(r.status()),
                    true,
                    employees.employee(a.tenantId(), r.employeeCode()));
        }
    }

    private static List<Assignment> merge(
            List<Assignment> old, List<Assignment> requested, String mode) {
        Map<UUID, Assignment> next = new LinkedHashMap<>();
        if (!"REPLACE".equals(mode)) for (var x : old) next.put(x.roleId(), x);
        for (var x : requested)
            if ("REMOVE".equals(mode)) next.remove(x.roleId());
            else next.put(x.roleId(), x);
        return List.copyOf(next.values());
    }

    @Override
    public void assignBatch(Actor a, BatchCommand c) {
        tx.executeWithoutResult(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(a, "supply:user:assign-role");
                    validateBatch(a, c, true);
                    for (var member :
                            c.members().stream()
                                    .sorted(Comparator.comparing(x -> x.id().toString()))
                                    .toList()) {
                        persistAssignments(
                                a,
                                member.id(),
                                merge(assignments(a, member.id()), c.roles(), c.mode()));
                        jdbc.update(
                                "UPDATE iam_app_member SET"
                                    + " version=version+1,security_version=security_version+1,updated_at=UTC_TIMESTAMP(6)"
                                    + " WHERE tenant_id=? AND application_id=? AND user_id=?",
                                tenant(a),
                                app(),
                                bin(member.id()));
                        settings.audit(
                                a,
                                "MEMBER_BATCH_ROLES",
                                member.id().toString(),
                                c.mode() + "；角色 " + c.roles().size() + " 个");
                    }
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                });
    }

    @Override
    public void resetPassword(Actor a, UUID id, PasswordCommand c) {
        if (c == null) throw new RequestValidationException("密码参数不能为空");
        settings.requirePermission(a, "supply:user:reset-password");
        String hash = passwords.hash(password(c.password()));
        tx.executeWithoutResult(
                status -> {
                    settings.lock(a);
                    settings.requirePermission(a, "supply:user:reset-password");
                    editable(raw(a, id), c.version());
                    int changed =
                            jdbc.update(
                                    "UPDATE iam_user_credential SET"
                                        + " password_hash=?,algorithm='ARGON2ID',algorithm_version=1,failed_attempts=0,last_failed_at=NULL,locked_until=NULL,password_changed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)"
                                        + " WHERE tenant_id=? AND user_id=? AND"
                                        + " credential_type='PASSWORD' AND status='ACTIVE'",
                                    hash,
                                    tenant(a),
                                    bin(id));
                    if (changed != 1) throw new StateConflictException("该登录账号没有可重置的密码凭证");
                    jdbc.update(
                            "UPDATE iam_user SET"
                                + " security_version=security_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                                + " WHERE tenant_id=? AND id=?",
                            tenant(a),
                            bin(id));
                    jdbc.update(
                            "UPDATE iam_app_member SET"
                                    + " version=version+1,security_version=security_version+1 WHERE"
                                    + " tenant_id=? AND application_id=? AND user_id=?",
                            tenant(a),
                            app(),
                            bin(id));
                    settings.requireManagementEntry(a);
                    settings.bump(a);
                    settings.audit(a, "MEMBER_PASSWORD_RESET", id.toString(), "重置统一登录凭证并使旧会话失效");
                });
    }

    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.strip().length() > max)
            throw new RequestValidationException(label + "不能为空且不能超过 " + max + " 字符");
        return value.strip();
    }

    private static String password(String value) {
        return com.rigour.tenant.iam.domain.model.settings.MemberPasswordPolicy.validate(value);
    }
}
