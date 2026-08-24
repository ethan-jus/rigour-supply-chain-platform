package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订货宝订单轻量镜像实体，仅用于 Integration 审计兼容。 */
@TableName("integration_order_mirror")
public class IntegrationOrderMirrorEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceOrderId;
    public String orderNo;
    public String sourceStatus;
    public BigDecimal amount;
    public LocalDateTime orderTime;
    public byte[] rawLandingId;
    public String mirrorStatus;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
}
