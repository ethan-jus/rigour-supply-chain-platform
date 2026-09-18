package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书导入模板实体。 */
@TableName("integration_feishu_import_template")
public class IntegrationFeishuImportTemplateEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public String sourceSystem;
    public String templateCode;
    public String templateName;
    public String domainCode;
    public String objectType;
    public String aliasesJson;
    public String requiredHeadersJson;
    public String sourceDocumentFieldsJson;
    public String sourceCreatedFieldsJson;
    public String deduplicationStrategy;
    public String deduplicationFieldsJson;
    public Boolean readyByDefaultFlag;
    public Boolean enabledFlag;
    public String remark;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public Long version;
}
