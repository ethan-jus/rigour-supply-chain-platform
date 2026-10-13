package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("hr_payroll_correction")
public class HrPayrollCorrectionEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private String sourcePayrollId;
    private String sourceMonth;
    private String employeeCode;
    private String targetMonth;
    private String requestId;
    private BigDecimal amount;
    private String reason;
    private String statusCode;
    private Integer revision;
    private String createdBy;
    private LocalDateTime createdTime;

    public String getId() {
        return id;
    }

    public void setId(String value) {
        this.id = value;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String value) {
        this.tenantId = value;
    }

    public String getSourcePayrollId() {
        return sourcePayrollId;
    }

    public void setSourcePayrollId(String value) {
        this.sourcePayrollId = value;
    }

    public String getSourceMonth() {
        return sourceMonth;
    }

    public void setSourceMonth(String value) {
        this.sourceMonth = value;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String value) {
        this.employeeCode = value;
    }

    public String getTargetMonth() {
        return targetMonth;
    }

    public void setTargetMonth(String value) {
        this.targetMonth = value;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        this.requestId = value;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal value) {
        this.amount = value;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        this.reason = value;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String value) {
        this.statusCode = value;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer value) {
        this.revision = value;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        this.createdBy = value;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime value) {
        this.createdTime = value;
    }
}
