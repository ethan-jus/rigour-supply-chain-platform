package com.rigour.analytics.infrastructure.persistence.entity;

@com.baomidou.mybatisplus.annotation.TableName("bi_cost_snapshot")
public class CostSnapshotEntity {
    @com.baomidou.mybatisplus.annotation.TableId(
            type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String id;

    private String tenantId;
    private java.time.LocalDateTime generatedAt;
    private java.time.LocalDate effectiveCutoff;
    private String versionsJson;
    private String citiesJson;
    private Long rowCount;

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

    public java.time.LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(java.time.LocalDateTime value) {
        generatedAt = value;
    }

    public java.time.LocalDate getEffectiveCutoff() {
        return effectiveCutoff;
    }

    public void setEffectiveCutoff(java.time.LocalDate value) {
        effectiveCutoff = value;
    }

    public String getVersionsJson() {
        return versionsJson;
    }

    public void setVersionsJson(String value) {
        versionsJson = value;
    }

    public String getCitiesJson() {
        return citiesJson;
    }

    public void setCitiesJson(String value) {
        citiesJson = value;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long value) {
        rowCount = value;
    }
}
