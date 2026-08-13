package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_customer_profile")
public class CustomerProfileEntity {
    @TableId(type = IdType.INPUT) public byte[] partyId;
    public byte[] tenantId;
    public byte[] customerTypeId;
    public byte[] customerAreaId;
    public String loginAccount;
    public String customerTypeNameSnapshot;
    public String customerAreaNameSnapshot;
    public String cityText;
    public String inviterName;
    public String remark;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
