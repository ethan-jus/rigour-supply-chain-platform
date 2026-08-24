package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 商品图片异步上传批次实体。 */
@TableName("integration_product_media_job")
public class IntegrationProductMediaJobEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public byte[] connectorId;
    public String status;
    public Long totalImages;
    public Long completedImages;
    public Long failedImages;
    public String errorCode;
    public String errorMessage;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public LocalDateTime updatedAt;
    public Long version;
}
