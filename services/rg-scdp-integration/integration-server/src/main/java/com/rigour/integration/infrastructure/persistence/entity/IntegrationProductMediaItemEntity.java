package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 商品图片异步上传明细实体。 */
@TableName("integration_product_media_item")
public class IntegrationProductMediaItemEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] jobId;
    public byte[] tenantId;
    public byte[] connectorId;
    public String sourceProductId;
    public String sourceResourceId;
    public String sourceGoodsId;
    public String sourceUrl;
    public String originalName;
    public String sourceFileName;
    public Integer sortOrder;
    public String status;
    public String objectKey;
    public String contentType;
    public Integer attempts;
    public LocalDateTime nextRetryAt;
    public String errorCode;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public Long version;
}
