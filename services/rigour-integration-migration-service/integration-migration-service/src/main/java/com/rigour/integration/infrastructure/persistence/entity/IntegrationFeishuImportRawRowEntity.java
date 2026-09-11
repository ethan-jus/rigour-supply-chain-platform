package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书导入原始行实体。 */
@TableName("integration_feishu_import_raw_row")
public class IntegrationFeishuImportRawRowEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] batchId;
    public byte[] tableId;
    public byte[] tenantId;
    public String sheetName;
    public String tableCode;
    public String domainCode;
    public String objectType;
    @TableField("source_row_number")
    public Integer rowNumber;
    public String sourceDocumentNo;
    public LocalDateTime sourceCreatedAt;
    public String rawRowHash;
    public String deduplicationKey;
    public String duplicateScope;
    public byte[] duplicateOfRawRowId;
    public String rowJson;
    public String attachmentRefsJson;
    public String importStatus;
    public String projectionStatus;
    public String targetDomain;
    public String targetObjectType;
    public String targetId;
    public String errorCode;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public byte[] updatedBy;
    public Long version;
}
