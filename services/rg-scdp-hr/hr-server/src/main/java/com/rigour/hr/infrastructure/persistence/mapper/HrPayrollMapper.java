package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.api.v1.model.PayrollInputCommand;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface HrPayrollMapper extends BaseMapper<HrPayrollEntity> {
    @Insert("INSERT IGNORE INTO hr_payroll_guard(tenant_id,version) VALUES(#{tenant},0)")
    void ensureGuard(@Param("tenant") String tenant);

    @Select("SELECT version FROM hr_payroll_guard WHERE tenant_id=#{tenant} FOR UPDATE")
    Long lock(@Param("tenant") String tenant);

    @Select(
            "SELECT revision,base_override baseOverride,performance_override"
                + " performanceOverride,deduction,reason,allowance_override allowanceOverride,"
                + " department_override_id departmentOverrideId FROM hr_payroll_input WHERE"
                + " tenant_id=#{tenant} AND employee_code=#{employee} AND payroll_month=#{month}")
    PayrollInputCommand input(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("month") String month);

    @Insert(
            """
INSERT INTO hr_payroll_input(tenant_id,employee_code,payroll_month,base_override,performance_override,deduction,reason,allowance_override,department_override_id,updated_by)
VALUES(#{tenant},#{employee},#{month},#{command.baseOverride},#{command.performanceOverride},#{command.deduction},#{command.reason},#{command.allowanceOverride},#{command.departmentOverrideId},#{actor})
ON DUPLICATE KEY UPDATE base_override=VALUES(base_override),performance_override=VALUES(performance_override),deduction=VALUES(deduction),
reason=VALUES(reason),allowance_override=VALUES(allowance_override),department_override_id=VALUES(department_override_id),revision=revision+1,updated_by=VALUES(updated_by),updated_time=UTC_TIMESTAMP(6)
""")
    void saveInput(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("month") String month,
            @Param("command") PayrollInputCommand command,
            @Param("actor") String actor);

    @Select(
            "SELECT CONCAT(source_line_id,'|',recipient_role_code) FROM"
                    + " hr_payroll_commission_release WHERE tenant_id=#{tenant}")
    List<String> released(@Param("tenant") String tenant);

    @Insert(
            "INSERT INTO"
                + " hr_payroll_commission_release(tenant_id,source_line_id,recipient_role_code,payroll_id,amount)"
                + " VALUES(#{tenant},#{line},#{role},#{payroll},#{amount})")
    void release(
            @Param("tenant") String tenant,
            @Param("line") String line,
            @Param("role") String role,
            @Param("payroll") String payroll,
            @Param("amount") BigDecimal amount);
}
