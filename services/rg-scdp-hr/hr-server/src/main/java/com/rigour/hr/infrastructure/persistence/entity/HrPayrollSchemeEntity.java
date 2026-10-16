package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("hr_payroll_scheme_version")
public class HrPayrollSchemeEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private String requestId;
    private String schemeId;
    private Integer version;
    private Integer revision;
    private String status;
    private String dataJson;
    private String reason;
    private String createdBy;
    private String publishedBy;
    private LocalDateTime updatedTime;
    private LocalDateTime publishedTime;
    private java.time.LocalDate stopFrom;

    public java.time.LocalDate getStopFrom() {
        return stopFrom;
    }

    public void setStopFrom(java.time.LocalDate value) {
        this.stopFrom = value;
    }

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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        this.requestId = value;
    }

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String value) {
        this.schemeId = value;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        this.version = value;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer value) {
        this.revision = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String value) {
        this.dataJson = value;
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

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String value) {
        this.publishedBy = value;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime value) {
        this.updatedTime = value;
    }

    public LocalDateTime getPublishedTime() {
        return publishedTime;
    }

    public void setPublishedTime(LocalDateTime value) {
        this.publishedTime = value;
    }
}
