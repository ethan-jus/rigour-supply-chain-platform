package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 外部事实与内部事实的差异核对单实体。 */
@TableName("integration_reconciliation_case")
public class IntegrationReconciliationCaseEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] runId;
    public String sourceSystem;
    public String sourceObjectType;
    public String businessKey;
    public String checkType;
    public String expectedValueJson;
    public String actualValueJson;
    public String status;
    public String severity;
    public String message;
    public LocalDateTime resolvedAt;
    public byte[] resolvedBy;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
}
