package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Integration 同步批次实体。 */
@TableName("integration_sync_run")
public class IntegrationSyncRunEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] taskId;
    public String triggerType;
    public String status;
    public String cursorBefore;
    public String cursorAfter;
    public LocalDateTime windowFrom;
    public LocalDateTime windowTo;
    public Long fetchedCount;
    public Long acceptedCount;
    public Long duplicateCount;
    public Long rejectedCount;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public String errorCode;
    public String errorMessage;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
}
