package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrOrganizationStore;
import com.rigour.hr.domain.code.HrBusinessCodeRules;
import com.rigour.hr.domain.organization.DepartmentTree;
import com.rigour.shared.core.code.BusinessCodeGenerator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** HR 单库事务维护部门、员工主任职和历史；组织锁串行化树移动与任职变更。 */
@Repository
public class JdbcHrOrganizationStore implements HrOrganizationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HrDataScope scopes;
    private final com.rigour.hr.application.port.out.HrAuditActorNameResolver actorNames;
    private final BusinessCodeGenerator codes = new BusinessCodeGenerator();

    public JdbcHrOrganizationStore(
            JdbcTemplate jdbc, PlatformTransactionManager manager, HrDataScope scopes,
            com.rigour.hr.application.port.out.HrAuditActorNameResolver actorNames) {
        this.actorNames = actorNames;
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    private void lock(String tenant) {
        jdbc.update(
                "INSERT INTO hr_organization_state(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE"
                    + " tenant_id=tenant_id",
                tenant);
        jdbc.queryForObject(
                "SELECT version FROM hr_organization_state WHERE tenant_id=? FOR UPDATE",
                Long.class,
                tenant);
    }

    /** 来源同步复用同一租户组织锁，不能与本地任职变更并发覆盖。调用者已开启 HR 事务。 */
    void lockForSource(String tenant) {
        lock(tenant);
    }

    void sourceEmployeeChanged(
            String tenant,
            String employee,
            String previousStatus,
            String currentStatus,
            String actor) {
        if (!Objects.equals(previousStatus, currentStatus)) {
            jdbc.update(
                    "UPDATE hr_employee_assignment SET"
                        + " effective_to=UTC_TIMESTAMP(6),revision=revision+1 WHERE tenant_id=? AND"
                        + " employee_code=? AND effective_to IS NULL",
                    tenant,
                    employee);
            if ("ACTIVE".equals(currentStatus))
                jdbc.update(
                        """
INSERT INTO hr_employee_assignment(tenant_id,employee_code,department_id,department_name_snapshot,position_code,position_name_snapshot,effective_from,actor_id)
SELECT e.tenant_id,e.employee_code,e.department_id,d.department_name,e.primary_position_code,p.position_name,UTC_TIMESTAMP(6),?
FROM hr_employee e JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id AND d.deleted=0 AND d.status_code='ACTIVE'
JOIN hr_position p ON p.tenant_id=e.tenant_id AND p.position_code=e.primary_position_code AND p.deleted=0 AND p.status_code='ACTIVE'
WHERE e.tenant_id=? AND e.employee_code=? AND e.deleted=0
""",
                        actor,
                        tenant,
                        employee);
        }
        change(
                tenant,
                previousStatus == null ? "EMPLOYEE_SOURCE_CREATE" : "EMPLOYEE_SOURCE_UPDATE",
                employee,
                actor);
    }

    void change(String tenant, String type, String ref, String actor) {
        jdbc.update("UPDATE hr_organization_state SET version=version+1 WHERE tenant_id=?", tenant);
        jdbc.update(
                """
                INSERT INTO hr_organization_change(tenant_id,version,event_type,object_ref,actor_id)
                SELECT tenant_id,version,?,?,? FROM hr_organization_state WHERE tenant_id=?
                """,
                type,
                ref,
                actor,
                tenant);
    }

    @Override
    public List<HrDepartmentView> departments(String tenant) {
        return jdbc.query(
                """
                SELECT d.*, e.employee_name AS current_leader_name
                  FROM hr_department d LEFT JOIN hr_employee e ON e.tenant_id=d.tenant_id
                   AND e.employee_code=d.leader_employee_code AND e.deleted=0
                  WHERE d.tenant_id=? AND d.deleted=0 ORDER BY d.sort_order,d.id
                """,
                (rs, n) -> department(rs),
                tenant);
    }

    private static HrDepartmentView department(ResultSet rs) throws SQLException {
        return new HrDepartmentView(
                rs.getLong("id"),
                rs.getObject("parent_id", Long.class),
                rs.getString("department_code"),
                rs.getString("department_name"),
                rs.getInt("sort_order"),
                rs.getString("status_code"),
                rs.getInt("revision"),
                rs.getString("current_leader_name"), rs.getString("contact_phone"),
                rs.getObject("established_date", java.time.LocalDate.class),
                rs.getTimestamp("created_time").toInstant(), rs.getString("created_by"), rs.getString("created_by_name"),
                rs.getTimestamp("updated_time").toInstant(), rs.getString("updated_by"), rs.getString("updated_by_name"), rs.getString("leader_employee_code"));
    }

    @Override
    public HrDepartmentView saveDepartment(
            String tenant, Long id, HrDepartmentCommand c, String actor) {
        String actorName = actorNames.resolve(tenant, actor);
        return tx.execute(
                status -> {
                    lock(tenant);
                    var all = new ArrayList<>(departments(tenant));
                    HrDepartmentView old =
                            id == null
                                    ? null
                                    : all.stream()
                                            .filter(d -> d.id().equals(id))
                                            .findFirst()
                                            .orElseThrow(
                                                    () -> new IllegalArgumentException("部门不存在"));
                    if ((old == null ? 0 : old.revision()) != c.revision())
                        throw new IllegalStateException("部门已修改，请刷新");
                    if (c.parentId() != null
                            && all.stream()
                                    .noneMatch(
                                            d ->
                                                    d.id().equals(c.parentId())
                                                            && "ACTIVE".equals(d.statusCode())))
                        throw new IllegalArgumentException("请选择当前租户有效的上级部门");
                    if (id != null && "INACTIVE".equals(c.statusCode())) {
                        if (count(
                                                "SELECT COUNT(*) FROM hr_department WHERE"
                                                    + " tenant_id=? AND parent_id=? AND"
                                                    + " status_code='ACTIVE' AND deleted=0",
                                                tenant,
                                                id)
                                        > 0
                                || count(
                                                """
SELECT COUNT(*) FROM hr_employee e JOIN hr_department_closure c
  ON c.tenant_id=e.tenant_id AND c.descendant_id=e.department_id
 WHERE e.tenant_id=? AND c.ancestor_id=? AND e.employment_status='ACTIVE' AND e.deleted=0
""",
                                                tenant,
                                                id)
                                        > 0)
                            throw new IllegalArgumentException("请先处理有效下级部门和在职员工，再停用部门");
                    }
                    String leaderName = null;
                    if (c.leaderEmployeeCode() != null) {
                        var leaders = jdbc.query("SELECT id,employee_name FROM hr_employee WHERE tenant_id=? AND employee_code=? AND deleted=0 AND employment_status='ACTIVE'",
                                (r,n) -> Map.entry(r.getLong("id"), r.getString("employee_name")), tenant, c.leaderEmployeeCode());
                        if (leaders.size()!=1) throw new IllegalArgumentException("请选择当前企业的在职员工担任部门负责人");
                        scopes.requireEmployee(tenant, leaders.getFirst().getKey(), "hr:employee:read");
                        leaderName = leaders.getFirst().getValue();
                    }
                    Long target = id;
                    if (target == null) {
                        String code =
                                "DEP"
                                        + UUID.randomUUID()
                                                .toString()
                                                .replace("-", "")
                                                .substring(0, 20)
                                                .toUpperCase(Locale.ROOT);
                        jdbc.update(
                                """
INSERT INTO hr_department(tenant_id,department_code,parent_id,department_name,sort_order,status_code,
 leader_name,leader_employee_code,contact_phone,established_date,created_by,created_by_name,updated_by,updated_by_name,created_time,updated_time)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
""",
                                tenant,
                                code,
                                c.parentId(),
                                c.departmentName(),
                                c.sortOrder(),
                                c.statusCode(), leaderName, c.leaderEmployeeCode(), c.contactPhone(), c.establishedDate(),
                                actor, actorName, actor, actorName);
                        target =
                                jdbc.queryForObject(
                                        "SELECT id FROM hr_department WHERE tenant_id=? AND"
                                            + " department_code=?",
                                        Long.class,
                                        tenant,
                                        code);
                    } else {
                        jdbc.update(
                                """
UPDATE hr_department SET parent_id=?,department_name=?,sort_order=?,status_code=?,
       leader_name=?,leader_employee_code=?,contact_phone=?,established_date=?,updated_by=?,updated_by_name=?,
       revision=revision+1,updated_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND revision=?
""",
                                c.parentId(),
                                c.departmentName(),
                                c.sortOrder(),
                                c.statusCode(), leaderName, c.leaderEmployeeCode(), c.contactPhone(), c.establishedDate(), actor, actorName,
                                tenant,
                                target,
                                c.revision());
                        // 当前展示跟随更名；任职历史和已确认业务快照不更新。
                        jdbc.update(
                                """
UPDATE hr_employee SET department_name_snapshot=?,revision=revision+1,updated_time=UTC_TIMESTAMP(6),updated_by=?,updated_by_name=?
 WHERE tenant_id=? AND department_id=? AND deleted=0 AND NOT(department_name_snapshot <=> ?)
""",
                                c.departmentName(), actor, actorName,
                                tenant,
                                target,
                                c.departmentName());
                    }
                    rebuild(tenant);
                    change(
                            tenant,
                            id == null ? "DEPARTMENT_CREATE" : "DEPARTMENT_UPDATE",
                            String.valueOf(target),
                            actor);
                    long resultId = target;
                    return departments(tenant).stream()
                            .filter(d -> d.id() == resultId)
                            .findFirst()
                            .orElseThrow();
                });
    }

    private void rebuild(String tenant) {
        List<DepartmentTree.Path> paths =
                DepartmentTree.closure(
                        departments(tenant).stream()
                                .map(d -> new DepartmentTree.Node(d.id(), d.parentId()))
                                .toList());
        jdbc.update("DELETE FROM hr_department_closure WHERE tenant_id=?", tenant);
        jdbc.batchUpdate(
                "INSERT INTO hr_department_closure(tenant_id,ancestor_id,descendant_id,depth)"
                    + " VALUES(?,?,?,?)",
                paths,
                200,
                (ps, p) -> {
                    ps.setString(1, tenant);
                    ps.setLong(2, p.ancestorId());
                    ps.setLong(3, p.descendantId());
                    ps.setInt(4, p.depth());
                });
    }

    @Override
    public void deleteDepartment(String tenant, long id, int revision, String actor) {
        String actorName = actorNames.resolve(tenant, actor);
        tx.executeWithoutResult(
                status -> {
                    lock(tenant);
                    HrDepartmentView department =
                            departments(tenant).stream()
                                    .filter(d -> d.id() == id)
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("部门不存在"));
                    if (department.revision() != revision)
                        throw new IllegalStateException("部门已修改，请刷新");
                    if (!"INACTIVE".equals(department.statusCode()))
                        throw new IllegalArgumentException("请先停用部门");
                    if (count(
                                            "SELECT COUNT(*) FROM hr_department WHERE tenant_id=?"
                                                + " AND parent_id=? AND deleted=0",
                                            tenant,
                                            id)
                                    > 0
                            || count(
                                            "SELECT COUNT(*) FROM hr_employee WHERE tenant_id=? AND"
                                                + " department_id=?",
                                            tenant,
                                            id)
                                    > 0
                            || count(
                                            "SELECT COUNT(*) FROM hr_employee_assignment WHERE"
                                                + " tenant_id=? AND department_id=?",
                                            tenant,
                                            id)
                                    > 0) throw new IllegalArgumentException("部门仍有下级或任职引用，请保留停用状态");
                    jdbc.update(
                            "DELETE FROM hr_department_closure WHERE tenant_id=? AND (ancestor_id=?"
                                + " OR descendant_id=?)",
                            tenant,
                            id,
                            id);
                    jdbc.update(
                            "UPDATE hr_department SET"
                                + " deleted=1,revision=revision+1,updated_time=UTC_TIMESTAMP(6),updated_by=?,updated_by_name=?"
                                + " WHERE tenant_id=? AND id=?",
                            actor, actorName, tenant,
                            id);
                    change(tenant, "DEPARTMENT_DELETE", String.valueOf(id), actor);
                });
    }

    @Override
    public long saveEmployee(String tenant, Long id, HrEmployeeCommand c, String actor) {
        String actorName = actorNames.resolve(tenant, actor);
        return Objects.requireNonNull(
                tx.execute(
                        status -> {
                            lock(tenant);
                            HrEmployeeIdentityView old = null;
                            if (id != null) {
                                List<String> code =
                                        jdbc.query(
                                                "SELECT employee_code FROM hr_employee WHERE"
                                                    + " tenant_id=? AND id=? AND deleted=0",
                                                (rs, n) -> rs.getString(1),
                                                tenant,
                                                id);
                                if (code.size() != 1) throw new IllegalArgumentException("员工不存在");
                                scopes.requireEmployee(tenant, id, "hr:employee:update");
                                old = rawIdentity(tenant, code.getFirst()).orElseThrow();
                            }
                            if ((old == null ? 0 : old.employeeRevision()) != c.revision())
                                throw new IllegalStateException("员工已修改，请刷新");
                            if (old == null
                                    || !Objects.equals(old.departmentId(), c.departmentId())
                                    || !Objects.equals(old.positionCode(), c.positionCode()))
                                scopes.requireDepartment(
                                        tenant,
                                        c.departmentId(),
                                        id == null ? "hr:employee:create" : "hr:employee:update");
                            if (old != null && !old.employmentStatus().equals(c.employmentStatus()))
                                scopes.requireEmployee(tenant, id, "hr:employee:status");
                            HrDepartmentView department =
                                    departments(tenant).stream()
                                            .filter(d -> d.id().equals(c.departmentId()))
                                            .findFirst()
                                            .orElseThrow(
                                                    () -> new IllegalArgumentException("请选择有效部门"));
                            if (!"ACTIVE".equals(department.statusCode()))
                                throw new IllegalArgumentException("不能选择停用部门");
                            List<String> positionNames =
                                    jdbc.query(
                                            """
SELECT position_name FROM hr_position WHERE tenant_id=? AND position_code=?
  AND status_code='ACTIVE' AND deleted=0
""",
                                            (rs, n) -> rs.getString(1),
                                            tenant,
                                            c.positionCode());
                            if (positionNames.size() != 1)
                                throw new IllegalArgumentException("请选择有效岗位职位");
                            String code =
                                    old == null
                                            ? codes.generateUnique(
                                                    HrBusinessCodeRules.EMPLOYEE,
                                                    candidate ->
                                                            count(
                                                                            "SELECT COUNT(*) FROM"
                                                                                + " hr_employee"
                                                                                + " WHERE"
                                                                                + " tenant_id=? AND"
                                                                                + " employee_code=?",
                                                                            tenant,
                                                                            candidate)
                                                                    == 0)
                                            : old.employeeCode();
                            Long target = id;
                            if (target == null) {
                                jdbc.update(
                                        """
INSERT INTO hr_employee(tenant_id,employee_code,employee_name,mobile,email,employment_status,
    primary_position_code,primary_position_name_snapshot,department_id,department_name_snapshot,
    entry_date,leave_date,source_system,remark,created_by,updated_by)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL',?,?,?)
""",
                                        tenant,
                                        code,
                                        c.employeeName(),
                                        c.mobile(),
                                        c.email(),
                                        c.employmentStatus(),
                                        c.positionCode(),
                                        positionNames.getFirst(),
                                        department.id(),
                                        department.departmentName(),
                                        timestamp(c.entryDate()),
                                        timestamp(c.leaveDate()),
                                        c.remark(),
                                        actor,
                                        actor);
                                target =
                                        jdbc.queryForObject(
                                                "SELECT id FROM hr_employee WHERE tenant_id=? AND"
                                                    + " employee_code=?",
                                                Long.class,
                                                tenant,
                                                code);
                            } else {
                                int changed =
                                        jdbc.update(
                                                """
UPDATE hr_employee SET access_version=access_version+CASE WHEN employment_status='ACTIVE' AND ?<>'ACTIVE' THEN 1 ELSE 0 END,
    employee_name=?,mobile=?,email=?,employment_status=?,primary_position_code=?,
    primary_position_name_snapshot=?,department_id=?,department_name_snapshot=?,entry_date=?,leave_date=?,
    remark=?,revision=revision+1,updated_by=?,updated_time=UTC_TIMESTAMP(6)
 WHERE tenant_id=? AND id=? AND revision=? AND deleted=0
""",
                                                c.employmentStatus(),
                                                c.employeeName(),
                                                c.mobile(),
                                                c.email(),
                                                c.employmentStatus(),
                                                c.positionCode(),
                                                positionNames.getFirst(),
                                                department.id(),
                                                department.departmentName(),
                                                timestamp(c.entryDate()),
                                                timestamp(c.leaveDate()),
                                                c.remark(),
                                                actor,
                                                tenant,
                                                target,
                                                c.revision());
                                if (changed != 1) throw new IllegalStateException("员工已修改，请刷新");
                            }
                            String jobGrade = c.jobGrade();
                            if ((jobGrade == null || jobGrade.isBlank()) && "业务员".equals(positionNames.getFirst())) jobGrade = "S1";
                            jdbc.update("UPDATE hr_employee SET job_grade=?, updated_by_name=?, created_by_name=CASE WHEN ? THEN ? ELSE created_by_name END WHERE tenant_id=? AND id=?",
                                    jobGrade, actorName, id == null, actorName, tenant, target);
                            if (c.profile() != null) {
                                var p = c.profile();
                                jdbc.update("""
INSERT INTO hr_employee_profile(tenant_id,employee_id,id_number,contract_end_date,education,registered_address,
 household_type,residential_address,bank_account,bank_name,social_insurance,emergency_contact,emergency_phone,
 graduation_school,major,regular_salary,probation_salary,probation_period)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id_number=VALUES(id_number),contract_end_date=VALUES(contract_end_date),
 education=VALUES(education),registered_address=VALUES(registered_address),household_type=VALUES(household_type),
 residential_address=VALUES(residential_address),bank_account=VALUES(bank_account),bank_name=VALUES(bank_name),
 social_insurance=VALUES(social_insurance),emergency_contact=VALUES(emergency_contact),emergency_phone=VALUES(emergency_phone),
 graduation_school=VALUES(graduation_school),major=VALUES(major),regular_salary=VALUES(regular_salary),
 probation_salary=VALUES(probation_salary),probation_period=VALUES(probation_period)
""", tenant,target,p.idNumber(),p.contractEndDate(),p.education(),p.registeredAddress(),p.householdType(),
                                    p.residentialAddress(),p.bankAccount(),p.bankName(),p.socialInsurance(),p.emergencyContact(),p.emergencyPhone(),
                                    p.graduationSchool(),p.major(),p.regularSalary(),p.probationSalary(),p.probationPeriod());
                            }
                            boolean changedAssignment =
                                    old == null
                                            || !Objects.equals(old.departmentId(), department.id())
                                            || !Objects.equals(old.positionCode(), c.positionCode())
                                            || !old.employmentStatus().equals(c.employmentStatus())
                                            || count(
                                                            "SELECT COUNT(*) FROM"
                                                                + " hr_employee_assignment WHERE"
                                                                + " tenant_id=? AND employee_code=?"
                                                                + " AND effective_to IS NULL",
                                                            tenant,
                                                            code)
                                                    == 0;
                            if (changedAssignment) {
                                jdbc.update(
                                        """
UPDATE hr_employee_assignment SET effective_to=UTC_TIMESTAMP(6),revision=revision+1
 WHERE tenant_id=? AND employee_code=? AND effective_to IS NULL
""",
                                        tenant,
                                        code);
                                if ("ACTIVE".equals(c.employmentStatus()))
                                    jdbc.update(
                                            """
INSERT INTO hr_employee_assignment(tenant_id,employee_code,department_id,department_name_snapshot,
    position_code,position_name_snapshot,effective_from,actor_id) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),?)
""",
                                            tenant,
                                            code,
                                            department.id(),
                                            department.departmentName(),
                                            c.positionCode(),
                                            positionNames.getFirst(),
                                            actor);
                            }
                            change(
                                    tenant,
                                    id == null ? "EMPLOYEE_CREATE" : "EMPLOYEE_UPDATE",
                                    code,
                                    actor);
                            return target;
                        }));
    }

    @Override
    public Optional<HrEmployeeIdentityView> identity(String tenant, String code) {
        if (!scopes.canReadCode(tenant, code)) return Optional.empty();
        return rawIdentity(tenant, code);
    }

    private Optional<HrEmployeeIdentityView> rawIdentity(String tenant, String code) {
        var rows =
                jdbc.query(
                        """
SELECT e.id,e.employee_code,e.employee_name,e.employment_status,e.department_id,e.primary_position_code,
       e.revision,e.access_version,e.entry_date,e.leave_date,d.department_name,d.status_code department_status,d.deleted department_deleted,
       p.position_name,p.status_code position_status,p.deleted position_deleted,
       COALESCE(s.version,0) organization_version
  FROM hr_employee e LEFT JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id
  LEFT JOIN hr_position p ON p.tenant_id=e.tenant_id AND p.position_code=e.primary_position_code
  LEFT JOIN hr_organization_state s ON s.tenant_id=e.tenant_id
 WHERE e.tenant_id=? AND e.employee_code=? AND e.deleted=0
""",
                        (rs, n) -> {
                            Long departmentId = rs.getObject("department_id", Long.class);
                            List<Long> ancestors =
                                    departmentId == null
                                            ? List.of()
                                            : jdbc.query(
                                                    """
SELECT ancestor_id FROM hr_department_closure WHERE tenant_id=? AND descendant_id=? ORDER BY depth DESC
""",
                                                    (r, i) -> r.getLong(1),
                                                    tenant,
                                                    departmentId);
                            String reason = null;
                            Instant now = Instant.now();
                            if (!"ACTIVE".equals(rs.getString("employment_status")))
                                reason = "员工不在有效在职状态";
                            else if (rs.getTimestamp("entry_date") != null
                                    && rs.getTimestamp("entry_date").toInstant().isAfter(now))
                                reason = "员工尚未到入职时间";
                            else if (rs.getTimestamp("leave_date") != null
                                    && !rs.getTimestamp("leave_date").toInstant().isAfter(now))
                                reason = "员工在职状态与离职日期需要核对";
                            else if (departmentId == null
                                    || !"ACTIVE".equals(rs.getString("department_status"))
                                    || rs.getInt("department_deleted") != 0
                                    || ancestors.isEmpty()) reason = "员工尚未关联有效部门";
                            else if (!"ACTIVE".equals(rs.getString("position_status"))
                                    || rs.getInt("position_deleted") != 0) reason = "员工尚未关联有效岗位";
                            return new HrEmployeeIdentityView(
                                    rs.getLong("id"),
                                    rs.getString("employee_code"),
                                    rs.getString("employee_name"),
                                    rs.getString("employment_status"),
                                    departmentId,
                                    rs.getString("department_name"),
                                    rs.getString("primary_position_code"),
                                    rs.getString("position_name"),
                                    ancestors,
                                    rs.getInt("revision"),
                                    rs.getLong("organization_version"),
                                    rs.getLong("access_version"),
                                    reason == null,
                                    reason);
                        },
                        tenant,
                        code);
        return rows.stream().findFirst();
    }

    @Override
    public HrPageView<HrEmployeeIdentityView> identities(
            String tenant, String keyword, int begin, int step) {
        String key = keyword == null ? null : keyword.strip();
        var scope = scopes.employeePredicate("hr:employee:read", "");
        String where =
                " WHERE tenant_id=? AND deleted=0 AND (? IS NULL OR employee_code LIKE ? OR"
                    + " employee_name LIKE ?) AND "
                        + scope.sql();
        List<Object> argsList = new ArrayList<>();
        argsList.add(tenant);
        argsList.add(key);
        argsList.add("%" + key + "%");
        argsList.add("%" + key + "%");
        argsList.addAll(scope.args());
        Object[] args = argsList.toArray();
        Long total =
                jdbc.queryForObject("SELECT COUNT(*) FROM hr_employee" + where, Long.class, args);
        var parameters = new ArrayList<>(Arrays.asList(args));
        parameters.add(step);
        parameters.add(begin);
        List<String> codes =
                jdbc.query(
                        "SELECT employee_code FROM hr_employee"
                                + where
                                + " ORDER BY employee_code LIMIT ? OFFSET ?",
                        (rs, n) -> rs.getString(1),
                        parameters.toArray());
        return new HrPageView<>(
                total == null ? 0 : total,
                begin,
                step,
                codes.stream().map(c -> identity(tenant, c).orElseThrow()).toList());
    }

    @Override
    public List<HrAssignmentView> assignments(String tenant, long employeeId) {
        scopes.requireEmployee(tenant, employeeId, "hr:employee:read");
        return jdbc.query(
                """
SELECT a.* FROM hr_employee_assignment a JOIN hr_employee e
  ON e.tenant_id=a.tenant_id AND e.employee_code=a.employee_code
 WHERE e.tenant_id=? AND e.id=? AND e.deleted=0 ORDER BY a.effective_from DESC,a.id DESC
""",
                (rs, n) ->
                        new HrAssignmentView(
                                rs.getLong("id"),
                                rs.getLong("department_id"),
                                rs.getString("department_name_snapshot"),
                                rs.getString("position_code"),
                                rs.getString("position_name_snapshot"),
                                rs.getTimestamp("effective_from").toInstant(),
                                rs.getTimestamp("effective_to") == null
                                        ? null
                                        : rs.getTimestamp("effective_to").toInstant()),
                tenant,
                employeeId);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private int count(String sql, Object... args) {
        return Optional.ofNullable(jdbc.queryForObject(sql, Integer.class, args)).orElse(0);
    }
}
