package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_sync_checkpoint")
public class CrmSyncCheckpointEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceSystem;
    public String objectType;
    public String cursorType;
    public String cursorValue;
    public LocalDateTime sourceUpdatedAt;
    public byte[] lastSuccessRunId;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
