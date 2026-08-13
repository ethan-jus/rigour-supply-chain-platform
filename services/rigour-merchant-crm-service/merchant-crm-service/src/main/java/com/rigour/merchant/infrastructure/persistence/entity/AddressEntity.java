package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_address")
public class AddressEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] partyId;
    public byte[] storeId;
    public byte[] contactId;
    public String addressType;
    public String consignee;
    public String regionText;
    public String areaName;
    public String addressDetail;
    public String fullAddress;
    public Boolean isDefault;
    public String status;
    public String ownershipState;
    public String recordOrigin;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime deletedAt;
}
