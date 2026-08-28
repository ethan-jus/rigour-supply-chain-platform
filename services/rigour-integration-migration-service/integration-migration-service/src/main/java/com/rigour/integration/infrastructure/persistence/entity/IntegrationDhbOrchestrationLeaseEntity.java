package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订货宝统一编排租约实体。 */
@TableName("integration_dhb_orchestration_lease")
public class IntegrationDhbOrchestrationLeaseEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String leaseToken;
    public String ownerId;
    public LocalDateTime acquiredAt;
    public LocalDateTime heartbeatAt;
    public LocalDateTime expiresAt;
}
