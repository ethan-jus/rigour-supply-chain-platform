package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollRuleEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HrPayrollRuleMapper extends BaseMapper<HrPayrollRuleEntity> {
    @Delete("DELETE FROM hr_payroll_rule_department WHERE tenant_id=#{tenant} AND rule_id=#{rule}")
    void clearDepartments(@Param("tenant") String tenant, @Param("rule") String rule);

    @Insert(
            "INSERT INTO hr_payroll_rule_department(tenant_id,rule_id,department_id)"
                    + " VALUES(#{tenant},#{rule},#{department})")
    void addDepartment(
            @Param("tenant") String tenant,
            @Param("rule") String rule,
            @Param("department") Long department);
}
