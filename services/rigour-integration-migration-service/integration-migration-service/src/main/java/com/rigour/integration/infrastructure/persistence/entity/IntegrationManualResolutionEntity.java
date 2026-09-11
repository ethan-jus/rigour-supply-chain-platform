package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 来源关系歧义的人工裁决实体；只保存非敏感证据和明确选择。 */
@TableName("integration_manual_resolution")
public class IntegrationManualResolutionEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceSystem;
    public String resolutionType;
    public String sourceObjectType;
    public String sourceId;
    public String selectedSourceObjectType;
    public String selectedSourceId;
    public String selectedInternalObjectType;
    public Long selectedInternalObjectId;
    public String evidenceJson;
    public String reason;
    public String status;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
}
