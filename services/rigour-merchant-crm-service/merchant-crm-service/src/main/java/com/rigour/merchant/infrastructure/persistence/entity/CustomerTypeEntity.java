package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_customer_type")
public class CustomerTypeEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public String typeCode;
    public String typeName;
    public String status;
    public String ownershipState;
    public String recordOrigin;
    public Long revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
