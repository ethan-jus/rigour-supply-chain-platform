package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("hr_payroll_component_version")
public class HrPayrollComponentEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private String requestId;
    private String ruleId;
    private String ruleCode;

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String value) {
        ruleCode = value;
    }

    private Integer version;
    private String kind;
    private String dataJson;
    private String reason;
    private String createdBy;
    private java.time.LocalDateTime createdTime;

    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String value) {
        tenantId = value;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        requestId = value;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String value) {
        ruleId = value;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        version = value;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String value) {
        kind = value;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String value) {
        dataJson = value;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        reason = value;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        createdBy = value;
    }

    public java.time.LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(java.time.LocalDateTime value) {
        createdTime = value;
    }
}
