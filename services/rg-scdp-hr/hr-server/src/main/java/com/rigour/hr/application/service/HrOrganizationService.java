package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrEmployeeStore;
import com.rigour.hr.application.port.out.HrOrganizationStore;
import com.rigour.hr.application.service.support.HrServiceValidation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.util.List;

/** 人员组织维护用例；账号角色始终由 IAM 管理。 */
@Service
public final class HrOrganizationService {
    private final HrOrganizationStore store;
    private final HrEmployeeStore employees;

    public HrOrganizationService(HrOrganizationStore store, HrEmployeeStore employees) {
        this.store = store;
        this.employees = employees;
    }

    public List<HrDepartmentView> departments() {
        return store.departments(actor("hr:department:read").tenantId().toString());
    }

    public HrDepartmentView saveDepartment(Long id, HrDepartmentCommand c) {
        CallerIdentity actor = actor(id == null ? "hr:department:create" : "hr:department:update");
        if (c == null) throw bad("部门参数不能为空");
        if (c.sortOrder() < 0 || c.sortOrder() > 99999) throw bad("排序值应在 0 到 99999 之间");
        if (c.statusCode() == null || !List.of("ACTIVE", "INACTIVE").contains(c.statusCode()))
            throw bad("部门状态无效");
        if (c.revision() < 0 || (id != null && c.revision() < 1)) throw bad("部门版本无效");
        if ((id != null && id <= 0) || (c.parentId() != null && c.parentId() <= 0))
            throw bad("部门 ID 无效");
        HrDepartmentCommand normalized =
                new HrDepartmentCommand(
                        c.parentId(),
                        required(c.departmentName(), 128, "部门名称"),
                        c.sortOrder(),
                        c.statusCode(),
                        c.revision(),
                        text(c.leaderEmployeeCode(), 50),
                        text(c.contactPhone(), 32),
                        c.establishedDate());
        return store.saveDepartment(
                actor.tenantId().toString(), id, normalized, actor.principalId().toString());
    }

    public void deleteDepartment(Long id, int revision) {
        CallerIdentity actor = actor("hr:department:delete");
        if (id == null || id <= 0 || revision < 1) throw bad("部门 ID 或版本无效");
        store.deleteDepartment(
                actor.tenantId().toString(), id, revision, actor.principalId().toString());
    }

    public HrEmployeeView saveEmployee(Long id, HrEmployeeCommand c) {
        CallerIdentity actor = actor(id == null ? "hr:employee:create" : "hr:employee:update");
        if (c == null) throw bad("员工参数不能为空");
        if ((id != null && id <= 0) || c.departmentId() == null || c.departmentId() <= 0)
            throw bad("请选择有效部门");
        if (c.employmentStatus() == null
                || !List.of("ACTIVE", "LEFT", "INACTIVE", "PENDING").contains(c.employmentStatus()))
            throw bad("在职状态无效");
        if (c.revision() < 0 || (id != null && c.revision() < 1)) throw bad("员工版本无效");
        if ("LEFT".equals(c.employmentStatus()) && c.leaveDate() == null) throw bad("离职员工必须填写离职日期");
        if (!"LEFT".equals(c.employmentStatus()) && c.leaveDate() != null) throw bad("仅离职员工可填写离职日期");
        if (c.entryDate() != null && c.leaveDate() != null && c.leaveDate().isBefore(c.entryDate()))
            throw bad("离职时间不能早于入职时间");
        if (id != null) {
            var old =
                    employees
                            .employee(actor.tenantId().toString(), id)
                            .orElseThrow(() -> bad("员工不存在"));
            if (!old.employmentStatus().equals(c.employmentStatus()))
                AuthorizationContext.requirePermission("hr:employee:status");
        }
        HrEmployeeCommand normalized =
                new HrEmployeeCommand(
                        required(c.employeeName(), 128, "员工姓名"),
                        c.departmentId(),
                        required(c.positionCode(), 50, "岗位"),
                        c.employmentStatus(),
                        text(c.mobile(), 32),
                        text(c.email(), 128),
                        c.entryDate(),
                        c.leaveDate(),
                        text(c.remark(), 500),
                        c.revision(), normalizeProfile(c.profile()), text(c.jobGrade(), 32));
        long result =
                store.saveEmployee(
                        actor.tenantId().toString(),
                        id,
                        normalized,
                        actor.principalId().toString());
        return employees.employee(actor.tenantId().toString(), result).orElseThrow();
    }

    public List<HrAssignmentView> assignments(Long id) {
        CallerIdentity actor = actor("hr:employee:read");
        if (id == null || id <= 0) throw bad("员工 ID 无效");
        return store.assignments(actor.tenantId().toString(), id);
    }

    public HrPageView<HrEmployeeIdentityView> identities(String keyword, int begin, int step) {
        CallerIdentity actor = identityActor();
        return store.identities(
                actor.tenantId().toString(),
                text(keyword, 128),
                HrServiceValidation.pageBegin(begin),
                HrServiceValidation.pageStep(step));
    }

    public HrEmployeeIdentityView identity(String code) {
        CallerIdentity actor = identityActor();
        return store.identity(actor.tenantId().toString(), required(code, 50, "员工编码"))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "员工不存在", List.of()));
    }

    public List<HrEmployeeIdentityView> resolveIdentities(List<String> codes) {
        CallerIdentity actor = identityActor();
        if (codes == null || codes.size() > 100) throw bad("单次核验最多 100 个员工");
        return codes.stream()
                .distinct()
                .map(
                        code ->
                                store.identity(
                                        actor.tenantId().toString(), required(code, 50, "员工编码")))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static HrEmployeeProfile normalizeProfile(HrEmployeeProfile p) {
        if (p == null) return null;
        String id = text(p.idNumber(), 18);
        if (id != null) id = com.rigour.hr.domain.organization.EmployeeIdentityNumber.normalize(id);
        String social = text(p.socialInsurance(), 32);
        if (social != null && !List.of("五险一金", "五险", "公积金", "未参保").contains(social)) throw bad("参保状态选项无效");
        return new HrEmployeeProfile(id, p.contractEndDate(), text(p.education(),32),
                text(p.registeredAddress(),300), text(p.householdType(),32), text(p.residentialAddress(),300),
                text(p.bankAccount(),32), text(p.bankName(),128), social,
                text(p.emergencyContact(),128), text(p.emergencyPhone(),32),
                text(p.graduationSchool(),128), text(p.major(),128), text(p.regularSalary(),64),
                text(p.probationSalary(),64), text(p.probationPeriod(),32));
    }

    private static CallerIdentity identityActor() {
        CallerIdentity actor = AuthorizationContext.requireCurrent();
        return actor(
                "SERVICE".equals(actor.principalScope())
                        ? "hr:employee:identity-read"
                        : "hr:employee:read");
    }

    private static CallerIdentity actor(String permission) {
        com.rigour.tenant.iam.client.SupplyAuthorizationContext.observe(permission, permission);
        CallerIdentity actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return actor;
    }

    private static String required(String value, int max, String label) {
        return HrServiceValidation.required(value, label + "不能为空", max);
    }

    private static String text(String value, int max) {
        return HrServiceValidation.text(value, max, "输入");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
