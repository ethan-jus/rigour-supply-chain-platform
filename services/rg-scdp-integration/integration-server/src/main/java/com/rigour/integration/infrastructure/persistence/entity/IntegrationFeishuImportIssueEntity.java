package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书导入预检问题实体。 */
@TableName("integration_feishu_import_issue")
public class IntegrationFeishuImportIssueEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] batchId;
    public byte[] tenantId;
    public String severity;
    public String issueType;
    public String tableName;
    @TableField("source_row_number")
    public Integer rowNumber;
    public String fieldName;
    public String message;
    public LocalDateTime createdAt;
}
