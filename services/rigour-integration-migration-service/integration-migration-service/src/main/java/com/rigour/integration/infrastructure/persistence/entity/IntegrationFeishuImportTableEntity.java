package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书导入工作表预检实体。 */
@TableName("integration_feishu_import_table")
public class IntegrationFeishuImportTableEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] batchId;
    public byte[] tenantId;
    public String sheetName;
    public String tableCode;
    public String domainCode;
    public String objectType;
    public String mappingStatus;
    public Integer headerRowNumber;
    public Long rowCount;
    public Long duplicateRows;
    public Integer columnCount;
    public Long attachmentReferenceCount;
    public String headersJson;
    public String attachmentFieldsJson;
    public LocalDateTime createdAt;
}
