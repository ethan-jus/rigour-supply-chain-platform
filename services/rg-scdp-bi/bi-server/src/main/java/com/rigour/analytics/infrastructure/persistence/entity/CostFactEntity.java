package com.rigour.analytics.infrastructure.persistence.entity;

@com.baomidou.mybatisplus.annotation.TableName("bi_source_cost_record")
public class CostFactEntity {
    @com.baomidou.mybatisplus.annotation.TableId(
            type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String id;

    private String tenantId;
    private String snapshotId;
    private String sourceService;
    private String sourceId;
    private java.time.LocalDate costDate;
    private String category;
    private String recordJson;

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

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String value) {
        snapshotId = value;
    }

    public String getSourceService() {
        return sourceService;
    }

    public void setSourceService(String value) {
        sourceService = value;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String value) {
        sourceId = value;
    }

    public java.time.LocalDate getCostDate() {
        return costDate;
    }

    public void setCostDate(java.time.LocalDate value) {
        costDate = value;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String value) {
        category = value;
    }

    public String getRecordJson() {
        return recordJson;
    }

    public void setRecordJson(String value) {
        recordJson = value;
    }
}
