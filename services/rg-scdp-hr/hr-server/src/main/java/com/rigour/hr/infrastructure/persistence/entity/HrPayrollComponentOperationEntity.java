package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("hr_payroll_component_operation")
public class HrPayrollComponentOperationEntity {
    @TableId(type = IdType.AUTO)
    public Long id;

    public String tenantId;
    public String requestId;
    public String ruleId;
    public String versionId;
    public String actionCode;
    public String effectiveMonth;
    public Integer stateRevision;
    public String responseJson;
    public String reason;
    public String actorId;
    public java.time.LocalDateTime createdTime;
}
