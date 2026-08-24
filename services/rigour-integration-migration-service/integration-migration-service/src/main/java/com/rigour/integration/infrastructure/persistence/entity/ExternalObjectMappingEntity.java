package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 外部对象到内部业务对象的统一映射实体。 */
@TableName("integration_external_object_mapping")
public class ExternalObjectMappingEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceSystem;
    public String sourceObjectType;
    public String sourceObjectId;
    public String sourceObjectNo;
    public String internalDomain;
    public String internalObjectType;
    public Long internalObjectId;
    public String internalObjectNo;
    public String mappingStatus;
    public byte[] lastSeenRunId;
    public LocalDateTime lastSeenAt;
    public LocalDateTime sourceDeletedAt;
    public String payloadChecksum;
    public String conflictReason;
    public String remark;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
    public LocalDateTime deletedAt;
    public byte[] deletedBy;
    public String deleteReason;
}
