package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_contact")
public class ContactEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] partyId;
    public byte[] storeId;
    public String contactType;
    public String contactName;
    public String phone;
    public String email;
    public Boolean isPrimary;
    public String status;
    public String ownershipState;
    public String recordOrigin;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime deletedAt;
}
