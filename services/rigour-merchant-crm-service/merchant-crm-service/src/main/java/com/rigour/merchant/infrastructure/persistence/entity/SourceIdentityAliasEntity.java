package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_source_identity_alias")
public class SourceIdentityAliasEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] bindingId;
    public byte[] connectorId;
    public String sourceSystem;
    public String sourceObjectType;
    public String aliasType;
    public String aliasValue;
    public Boolean isPrimary;
    public LocalDateTime firstSeenAt;
    public LocalDateTime lastSeenAt;
    public Integer revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
