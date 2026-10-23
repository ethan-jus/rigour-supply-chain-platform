package com.rigour.analytics.infrastructure.persistence.entity;

@com.baomidou.mybatisplus.annotation.TableName("bi_cost_refresh_state")
public class CostRefreshStateEntity {
    @com.baomidou.mybatisplus.annotation.TableId(
            type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String tenantId;

    private String snapshotId;
    private java.time.LocalDateTime attemptedAt;
    private java.time.LocalDateTime succeededAt;
    private String status;
    private String errorCode;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String value) {
        tenantId = value;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String value) {
        snapshotId = value;
    }

    public java.time.LocalDateTime getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(java.time.LocalDateTime value) {
        attemptedAt = value;
    }

    public java.time.LocalDateTime getSucceededAt() {
        return succeededAt;
    }

    public void setSucceededAt(java.time.LocalDateTime value) {
        succeededAt = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String value) {
        errorCode = value;
    }
}
