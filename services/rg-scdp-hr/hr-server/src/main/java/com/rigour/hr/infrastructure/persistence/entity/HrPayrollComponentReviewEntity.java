package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("hr_payroll_component_review")
public class HrPayrollComponentReviewEntity {
    @TableId(type = IdType.INPUT)
    public String id;

    public String tenantId;
    public Long operationId;
    public String ruleId;
    public String ruleName;
    public String actionCode;
    public String subject;
    public String subjectId;
    public String employeeCode;
    public String reviewMonth;
    public String reason;
    public String state;
    public java.time.LocalDateTime createdTime;
    public String resolvedBy;
    public java.time.LocalDateTime resolvedTime;
    public String resolutionReason;
}
