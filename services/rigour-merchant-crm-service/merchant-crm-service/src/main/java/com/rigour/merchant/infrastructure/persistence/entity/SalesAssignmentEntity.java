package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("crm_sales_assignment")
public class SalesAssignmentEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] partyId;
    public byte[] storeId;
    public String assignmentType;
    public String assigneeType;
    public String sourceStaffId;
    public String iamStaffCode;
    public String iamStaffNameSnapshot;
    public byte[] salesProfileId;
    public byte[] salesTeamId;
    public byte[] cityId;
    public String source;
    public String sourceNameSnapshot;
    public LocalDateTime effectiveFrom;
    public LocalDateTime effectiveTo;
    public String status;
    public String reason;
    public Long revision;
    public String createdBy;
    public String updatedBy;
    public LocalDateTime createdTime;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
