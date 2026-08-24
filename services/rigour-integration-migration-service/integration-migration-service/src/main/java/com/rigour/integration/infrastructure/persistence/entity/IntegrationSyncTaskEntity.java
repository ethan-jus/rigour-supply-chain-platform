package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Integration 同步任务实体。 */
@TableName("integration_sync_task")
public class IntegrationSyncTaskEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String taskCode;
    public String objectType;
    public String taskStatus;
    public LocalDateTime lastRunAt;
    public LocalDateTime nextRunAt;
    public Integer batchSize;
    public Integer retryLimit;
    public Integer overlapSeconds;
    public Integer enabled;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
    public LocalDateTime deletedAt;
    public byte[] deletedBy;
    public String deleteReason;
}
