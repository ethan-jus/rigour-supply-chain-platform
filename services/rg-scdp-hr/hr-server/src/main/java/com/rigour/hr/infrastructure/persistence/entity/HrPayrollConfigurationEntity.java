package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("hr_payroll_configuration")
public class HrPayrollConfigurationEntity {
    @TableId(type = IdType.INPUT)
    private String tenantId;

    private String unifiedFrom;
    private String reason;
    private String updatedBy;
    private java.time.LocalDateTime updatedTime;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String value) {
        tenantId = value;
    }

    public String getUnifiedFrom() {
        return unifiedFrom;
    }

    public void setUnifiedFrom(String value) {
        unifiedFrom = value;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        reason = value;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String value) {
        updatedBy = value;
    }

    public java.time.LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(java.time.LocalDateTime value) {
        updatedTime = value;
    }
}
