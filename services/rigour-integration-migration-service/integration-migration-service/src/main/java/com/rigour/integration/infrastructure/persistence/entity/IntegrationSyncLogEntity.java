package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 同步运行日志实体。 */
@TableName("integration_sync_log")
public class IntegrationSyncLogEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] taskId;
    public byte[] runId;
    public String logLevel;
    public String message;
    public String errorCode;
    public LocalDateTime occurredAt;
}
