package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface HrAttendanceEmployeeMapper extends BaseMapper<HrEmployeeEntity> {
    record EmployeeFact(
            String code,
            String name,
            Long departmentId,
            String departmentName,
            LocalDate entryDate,
            LocalDate leaveDate) {}

    @Select(
            "SELECT id,parent_id parentId,department_name departmentName,sort_order sortOrder FROM"
                + " hr_department WHERE tenant_id=#{tenant} AND deleted=0 ORDER BY sort_order,id")
    List<com.rigour.hr.api.v1.model.HrAttendanceModels.DepartmentOption> departments(
            @Param("tenant") String tenant);

    @Select("SELECT COUNT(*) FROM hr_employee e ${ew.customSqlSegment}")
    long countEmployees(@Param(Constants.WRAPPER) Wrapper<?> query);

    @Select(
            """
SELECT e.employee_code code,e.employee_name name,e.department_id departmentId,
       d.department_name departmentName,DATE(e.entry_date) entryDate,DATE(e.leave_date) leaveDate
FROM hr_employee e LEFT JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id
${ew.customSqlSegment}
""")
    List<EmployeeFact> employees(@Param(Constants.WRAPPER) Wrapper<?> query);
}
