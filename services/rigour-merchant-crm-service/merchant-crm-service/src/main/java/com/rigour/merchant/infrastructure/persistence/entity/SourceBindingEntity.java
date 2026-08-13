package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_source_binding")
public class SourceBindingEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceSystem;
    public String sourceObjectType;
    public String sourceObjectId;
    public String sourceCode;
    public String sourceName;
    public String sourceStatus;
    public String targetType;
    public byte[] targetId;
    public String bindingStatus;
    public String resolutionErrorCode;
    public String resolutionErrorMessage;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime sourceUpdatedAt;
    public String sourceFieldsJson;
    public String sourcePayloadHash;
    public String sourcePresence;
    public Integer absentConfirmCount;
    public LocalDateTime sourceAbsentAt;
    public byte[] lastSeenRunId;
    public byte[] lastSyncRunId;
    public LocalDateTime syncedAt;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
