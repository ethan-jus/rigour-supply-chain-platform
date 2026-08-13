package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_sync_lock")
public class CrmSyncLockEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String objectType;
    public byte[] runId;
    public String lockToken;
    public LocalDateTime acquiredAt;
    public LocalDateTime expiresAt;
}
