package com.rigour.hr.infrastructure.persistence.repository;

import static com.rigour.hr.infrastructure.persistence.repository.HrPayrollPersistenceSupport.error;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.hr.api.v1.model.PayrollBatchProfileCommand;
import com.rigour.hr.api.v1.model.PayrollProfilePatchCommand;
import com.rigour.hr.application.port.out.HrAuditActorNameResolver;
import com.rigour.hr.application.port.out.HrEmployeePayrollProfileStore;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeSalaryEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeSalaryMapper;
import com.rigour.shared.core.api.ErrorCode;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 只维护 HR 明确填写的薪酬字段，不读取个人敏感资料；空字段保持原值。 */
@Repository
public class MybatisPlusHrEmployeePayrollProfileRepository
        implements HrEmployeePayrollProfileStore {
    private final HrEmployeeMapper mapper;
    private final HrEmployeeSalaryMapper salaries;
    private final HrDataScope scope;
    private final HrPayrollPersistenceSupport audit;
    private final HrAuditActorNameResolver actorNames;

    public MybatisPlusHrEmployeePayrollProfileRepository(
            HrEmployeeMapper mapper,
            HrEmployeeSalaryMapper salaries,
            HrDataScope scope,
            HrPayrollPersistenceSupport audit,
            HrAuditActorNameResolver actorNames) {
        this.mapper = mapper;
        this.salaries = salaries;
        this.scope = scope;
        this.audit = audit;
        this.actorNames = actorNames;
    }

    @Override
    @Transactional
    public void update(String tenant, PayrollBatchProfileCommand command, String actor) {
        String actorName = actorNames.resolve(tenant, actor);
        for (var c : command.employees()) {
            var old =
                    mapper.selectOne(
                            scope.apply(
                                    new QueryWrapper<HrEmployeeEntity>()
                                            .select(
                                                    "id",
                                                    "employee_code",
                                                    "employee_name",
                                                    "revision",
                                                    "payroll_category",
                                                    "regularized",
                                                    "regularization_date",
                                                    "salary_effective_from",
                                                    "entry_date")
                                            .eq("tenant_id", tenant)
                                            .eq("employee_code", c.employeeCode())
                                            .eq("deleted", 0),
                                    "hr:employee:update"));
            if (old == null) throw error(ErrorCode.NOT_FOUND, "员工不存在或无维护权限：" + c.employeeCode());
            if (c.regularizationDate() != null
                    && old.entryDate != null
                    && c.regularizationDate().isBefore(old.entryDate.plusHours(8).toLocalDate()))
                throw error(ErrorCode.BAD_REQUEST, old.employeeName + "：转正日期早于入职日期");
            boolean salaryChanged = c.probationSalary() != null || c.regularSalary() != null;
            String category =
                    c.payrollCategory() == null ? old.payrollCategory : c.payrollCategory();
            if (salaryChanged && !"SALES".equals(category))
                throw error(ErrorCode.BAD_REQUEST, old.employeeName + "：批量工资维护仅适用于销售");
            if (salaryChanged && c.salaryEffectiveFrom() == null && old.salaryEffectiveFrom == null)
                throw error(ErrorCode.BAD_REQUEST, old.employeeName + "：请填写薪资生效日期");
            var oldSalary =
                    salaries.selectOne(
                            new QueryWrapper<HrEmployeeSalaryEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("employee_id", old.id));
            String beforeProbation = oldSalary == null ? null : oldSalary.probationSalary;
            String beforeRegular = oldSalary == null ? null : oldSalary.regularSalary;
            var q =
                    new UpdateWrapper<HrEmployeeEntity>()
                            .eq("tenant_id", tenant)
                            .eq("id", old.id)
                            .eq("revision", c.revision())
                            .eq("deleted", 0)
                            .set(
                                    c.payrollCategory() != null,
                                    "payroll_category",
                                    c.payrollCategory())
                            .set(c.regularized() != null, "regularized", c.regularized())
                            .set(
                                    c.regularized() != null,
                                    "regularization_date",
                                    Boolean.TRUE.equals(c.regularized())
                                            ? c.regularizationDate()
                                            : null)
                            .set(
                                    c.salaryEffectiveFrom() != null,
                                    "salary_effective_from",
                                    c.salaryEffectiveFrom())
                            .set("updated_by", actor)
                            .set("updated_by_name", actorName)
                            .set("updated_time", LocalDateTime.now(ZoneOffset.UTC))
                            .setSql("revision=revision+1");
            if (mapper.update(null, q) != 1)
                throw error(ErrorCode.CONFLICT, old.employeeName + "档案已变更，请刷新后重新选择");
            if (salaryChanged) {
                if (oldSalary == null) {
                    var added = new HrEmployeeSalaryEntity();
                    added.tenantId = tenant;
                    added.employeeId = old.id;
                    added.probationSalary = c.probationSalary();
                    added.regularSalary = c.regularSalary();
                    salaries.insert(added);
                } else {
                    salaries.update(
                            null,
                            new UpdateWrapper<HrEmployeeSalaryEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("employee_id", old.id)
                                    .set(
                                            c.probationSalary() != null,
                                            "probation_salary",
                                            c.probationSalary())
                                    .set(
                                            c.regularSalary() != null,
                                            "regular_salary",
                                            c.regularSalary()));
                }
            }
            audit.audit(
                    tenant,
                    "EMPLOYEE:" + old.employeeCode,
                    "PROFILE_BEFORE",
                    actor,
                    command.reason(),
                    new PayrollProfilePatchCommand(
                            old.employeeCode,
                            old.revision,
                            old.payrollCategory,
                            old.regularized,
                            old.regularizationDate,
                            old.salaryEffectiveFrom,
                            beforeProbation,
                            beforeRegular));
            audit.audit(
                    tenant,
                    "EMPLOYEE:" + old.employeeCode,
                    "PROFILE_BATCH",
                    actor,
                    command.reason(),
                    new PayrollProfilePatchCommand(
                            old.employeeCode,
                            c.revision() + 1,
                            c.payrollCategory() == null ? old.payrollCategory : c.payrollCategory(),
                            c.regularized() == null ? old.regularized : c.regularized(),
                            c.regularized() == null
                                    ? old.regularizationDate
                                    : Boolean.TRUE.equals(c.regularized())
                                            ? c.regularizationDate()
                                            : null,
                            c.salaryEffectiveFrom() == null
                                    ? old.salaryEffectiveFrom
                                    : c.salaryEffectiveFrom(),
                            c.probationSalary() == null ? beforeProbation : c.probationSalary(),
                            c.regularSalary() == null ? beforeRegular : c.regularSalary()));
        }
    }
}
