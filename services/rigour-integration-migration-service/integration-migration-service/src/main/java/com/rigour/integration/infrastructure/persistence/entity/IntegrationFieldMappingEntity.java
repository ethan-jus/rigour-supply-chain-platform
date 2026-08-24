package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订货宝字段映射配置实体。 */
@TableName("integration_field_mapping")
public class IntegrationFieldMappingEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceField;
    public String targetField;
    public String transformType;
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
