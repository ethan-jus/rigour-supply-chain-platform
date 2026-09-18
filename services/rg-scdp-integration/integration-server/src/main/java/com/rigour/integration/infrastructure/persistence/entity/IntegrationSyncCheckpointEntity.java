package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 每个同步任务的成功游标实体。 */
@TableName("integration_sync_checkpoint")
public class IntegrationSyncCheckpointEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] taskId;
    public String cursorType;
    public String cursorValue;
    public LocalDateTime sourceUpdatedAt;
    public byte[] lastSuccessRunId;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
}
