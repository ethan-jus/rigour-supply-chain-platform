package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 同步死信实体，用于保存可重放失败。 */
@TableName("integration_dead_letter")
public class IntegrationDeadLetterEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] runId;
    public byte[] rawLandingId;
    public String sourceSystem;
    public String sourceObjectType;
    public String sourceId;
    public String status;
    public Integer attempts;
    public LocalDateTime nextRetryAt;
    public String lastErrorCode;
    public String lastErrorMessage;
    public LocalDateTime resolvedAt;
    public byte[] resolvedBy;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
}
