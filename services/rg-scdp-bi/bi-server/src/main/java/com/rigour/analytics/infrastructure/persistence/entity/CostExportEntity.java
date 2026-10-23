package com.rigour.analytics.infrastructure.persistence.entity;

@com.baomidou.mybatisplus.annotation.TableName("bi_cost_export")
public class CostExportEntity {
    @com.baomidou.mybatisplus.annotation.TableId(
            type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String id;

    private String tenantId;
    private String actorId;
    private String snapshotId;
    private String authorizationHash;
    private String filterJson;
    private String status;
    private String errorCode;
    private String content;
    private Long rowCount;
    private java.time.LocalDateTime createdAt;

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

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String value) {
        actorId = value;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String value) {
        snapshotId = value;
    }

    public String getAuthorizationHash() {
        return authorizationHash;
    }

    public void setAuthorizationHash(String value) {
        authorizationHash = value;
    }

    public String getFilterJson() {
        return filterJson;
    }

    public void setFilterJson(String value) {
        filterJson = value;
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

    public String getContent() {
        return content;
    }

    public void setContent(String value) {
        content = value;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long value) {
        rowCount = value;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime value) {
        createdAt = value;
    }
}
