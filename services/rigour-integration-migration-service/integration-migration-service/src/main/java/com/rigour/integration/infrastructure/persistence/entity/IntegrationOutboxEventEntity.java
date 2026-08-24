package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Integration 出站事件实体。 */
@TableName("integration_outbox_event")
public class IntegrationOutboxEventEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public String aggregateType;
    public byte[] aggregateId;
    public String eventType;
    public String eventKey;
    public String payloadJson;
    public String payloadChecksum;
    public String status;
    public Integer attempts;
    public LocalDateTime availableAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
