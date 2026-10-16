package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("hr_payroll_scheme_month_input")
public class HrPayrollSchemeInputEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private String employeeCode;

    private String payrollMonth;

    private Integer revision;
    private String dataJson;
    private String updatedBy;
    private LocalDateTime updatedTime;

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

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String value) {
        this.employeeCode = value;
    }

    public String getPayrollMonth() {
        return payrollMonth;
    }

    public void setPayrollMonth(String value) {
        this.payrollMonth = value;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer value) {
        this.revision = value;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String value) {
        this.dataJson = value;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String value) {
        this.updatedBy = value;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime value) {
        this.updatedTime = value;
    }
}
