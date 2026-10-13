package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("hr_payroll_payment")
public class HrPayrollPaymentEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private String payrollId;
    private String requestId;
    private LocalDate paidDate;
    private BigDecimal amount;
    private String referenceNo;
    private String reason;
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

    public String getPayrollId() {
        return payrollId;
    }

    public void setPayrollId(String value) {
        this.payrollId = value;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        this.requestId = value;
    }

    public LocalDate getPaidDate() {
        return paidDate;
    }

    public void setPaidDate(LocalDate value) {
        this.paidDate = value;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal value) {
        this.amount = value;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public void setReferenceNo(String value) {
        this.referenceNo = value;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        this.reason = value;
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
