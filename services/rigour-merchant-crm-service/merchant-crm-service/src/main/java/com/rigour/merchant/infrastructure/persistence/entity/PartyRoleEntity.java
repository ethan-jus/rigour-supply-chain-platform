package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_party_role")
public class PartyRoleEntity {
    public byte[] tenantId;
    public byte[] partyId;
    public String roleCode;
    public String status;
    public LocalDateTime effectiveFrom;
    public LocalDateTime effectiveTo;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
