package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订货宝连接配置实体，对应 integration_dhb_connector。 */
@TableName("integration_dhb_connector")
public class DhbConnectorEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public String connectorCode;
    public String connectorName;
    public String baseUrl;
    public String apiVersion;
    public String authSecretRef;
    public String credentialStatus;
    public LocalDateTime lastCheckedAt;
    public String lastErrorCode;
    public String lastErrorMessage;
    public String status;
    public Long version;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
    public LocalDateTime deletedAt;
    public byte[] deletedBy;
    public String deleteReason;
}
