package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_sync_run")
public class CrmSyncRunEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceSystem;
    public String objectType;
    public String triggerType;
    public byte[] sourceTaskId;
    public String syncMode;
    public String status;
    public Integer pageSize;
    public Integer maxPages;
    public Long fetchedCount;
    public Long createdCount;
    public Long changedCount;
    public Long repairedCount;
    public Long duplicateCount;
    public Long absentCount;
    public Long rejectedCount;
    public String errorCode;
    public String errorMessage;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public Integer revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
