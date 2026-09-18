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
    public Integer revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
