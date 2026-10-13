package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.math.BigDecimal;

@TableName("hr_payroll_commission_release")
public class HrPayrollCommissionReleaseEntity {
    private String tenantId;
    private String sourceLineId;
    private String recipientRoleCode;
    private String payrollId;
    private BigDecimal amount;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String value) {
        this.tenantId = value;
    }

    public String getSourceLineId() {
        return sourceLineId;
    }

    public void setSourceLineId(String value) {
        this.sourceLineId = value;
    }

    public String getRecipientRoleCode() {
        return recipientRoleCode;
    }

    public void setRecipientRoleCode(String value) {
        this.recipientRoleCode = value;
    }

    public String getPayrollId() {
        return payrollId;
    }

    public void setPayrollId(String value) {
        this.payrollId = value;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal value) {
        this.amount = value;
    }
}
