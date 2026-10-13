package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.hr.api.v1.model.PayrollCommissionPolicyView;
import com.rigour.hr.api.v1.model.PayrollDepartmentView;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HrPayrollReferenceMapper extends BaseMapper<HrEmployeeEntity> {
    @Select(
            """
SELECT e.employee_code employeeCode,e.employee_name employeeName,e.department_id departmentId,d.department_name departmentName,
e.city_name cityName,DATE(CONVERT_TZ(e.entry_date,'+00:00','+08:00')) entryDate,DATE(CONVERT_TZ(e.leave_date,'+00:00','+08:00')) leaveDate,e.payroll_category payrollCategory, e.revision, e.id
FROM hr_employee e LEFT JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id
${ew.customSqlSegment}
""")
    List<PayrollEmployeeView> employees(@Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            """
SELECT e.payroll_category payrollCategory,e.regularized,e.regularization_date regularizationDate,
       p.regular_salary regularSalary,p.probation_salary probationSalary,e.salary_effective_from effectiveFrom
FROM hr_employee e LEFT JOIN hr_employee_profile p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
WHERE e.tenant_id=#{tenant} AND e.employee_code=#{employee} AND e.deleted=0
""")
    com.rigour.hr.api.v1.model.PayrollSalarySourceView salarySource(
            @Param("tenant") String tenant, @Param("employee") String employee);

    @Select(
            "SELECT department_id FROM hr_employee_assignment WHERE tenant_id=#{tenant} AND"
                + " employee_code=#{employee} AND effective_from<#{until} AND (effective_to IS NULL"
                + " OR effective_to>=#{from}) ORDER BY effective_from")
    List<Long> departmentsAt(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    @Select(
            "SELECT id,parent_id parentId,department_name name,sort_order sortOrder FROM"
                + " hr_department WHERE tenant_id=#{tenant} AND deleted=0 AND status_code='ACTIVE'"
                + " ORDER BY sort_order,id")
    List<PayrollDepartmentView> departments(@Param("tenant") String tenant);

    @Select(
            "SELECT policy_code code,policy_name name,method_code methodCode,sales_rate"
                + " salesRate,manager_rate managerRate FROM hr_commission_policy ORDER BY ordinal")
    List<PayrollCommissionPolicyView> policies();
}
