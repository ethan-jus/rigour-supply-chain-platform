package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 员工个人资料中的工资投影，不包含证件、联系方式和银行资料。查询、更新须同时限定租户和员工。 */
@TableName("hr_employee_profile")
public class HrEmployeeSalaryEntity {
    @TableId(type = IdType.INPUT)
    public Long employeeId;

    public String tenantId;
    public String probationSalary;
    public String regularSalary;
}
