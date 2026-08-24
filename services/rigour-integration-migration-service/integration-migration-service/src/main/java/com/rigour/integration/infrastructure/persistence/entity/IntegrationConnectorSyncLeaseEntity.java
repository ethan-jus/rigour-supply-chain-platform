package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订货宝连接器同步租约实体。 */
@TableName("integration_connector_sync_lease")
public class IntegrationConnectorSyncLeaseEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String leaseToken;
    public String ownerId;
    public LocalDateTime acquiredAt;
    public LocalDateTime heartbeatAt;
    public LocalDateTime expiresAt;
}
