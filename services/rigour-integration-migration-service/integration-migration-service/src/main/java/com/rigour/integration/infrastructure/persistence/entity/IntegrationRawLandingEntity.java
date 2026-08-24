package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 第三方原始数据落库实体。 */
@TableName("integration_raw_landing")
public class IntegrationRawLandingEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public byte[] runId;
    public String sourceSystem;
    public String sourceObjectType;
    public String sourceId;
    public String sourceVersion;
    public LocalDateTime sourceUpdatedAt;
    public String payloadJson;
    public String payloadChecksum;
    public LocalDateTime receivedAt;
    public String landingStatus;
    public LocalDateTime processedAt;
    public String errorCode;
    public String errorMessage;
    public Integer attempts;
    public LocalDateTime lastAttemptAt;
    public Long version;
}
