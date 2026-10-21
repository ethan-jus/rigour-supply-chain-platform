package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("hr_target_completion_event")
public class HrTargetCompletionEventEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private Long completionId;
    private Integer revision;
    private BigDecimal completedValue;
    private LocalDate completedThrough;
    private String reason;
    private String actor;
    private LocalDateTime occurredAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getCompletionId() {
        return completionId;
    }

    public void setCompletionId(Long completionId) {
        this.completionId = completionId;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public BigDecimal getCompletedValue() {
        return completedValue;
    }

    public void setCompletedValue(BigDecimal completedValue) {
        this.completedValue = completedValue;
    }

    public LocalDate getCompletedThrough() {
        return completedThrough;
    }

    public void setCompletedThrough(LocalDate completedThrough) {
        this.completedThrough = completedThrough;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
