package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_external_staff")
public class ExternalStaffEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceSystem;
    public String sourceStaffId;
    public String sourceAccountId;
    public String accountName;
    public String staffType;
    public String staffName;
    public String title;
    public String branchName;
    public String accountMobile;
    public String mobile;
    public String email;
    public String qq;
    public String roleName;
    public String inviteCode;
    public String remark;
    public String sourceStatus;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime sourceUpdatedAt;
    public byte[] salesProfileId;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
