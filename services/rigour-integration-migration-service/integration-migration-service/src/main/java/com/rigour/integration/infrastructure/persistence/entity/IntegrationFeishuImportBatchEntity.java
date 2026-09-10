package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书导入批次实体。 */
@TableName("integration_feishu_import_batch")
public class IntegrationFeishuImportBatchEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public String sourceSystem;
    public String sourceUrl;
    public String originalFileName;
    public Long fileSizeBytes;
    public String fileSha256;
    public String status;
    public Integer totalSheets;
    public Long totalRows;
    public Long duplicateRows;
    public Long attachmentReferenceCount;
    public LocalDateTime createdAt;
    public byte[] createdBy;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
    public Long version;
}
