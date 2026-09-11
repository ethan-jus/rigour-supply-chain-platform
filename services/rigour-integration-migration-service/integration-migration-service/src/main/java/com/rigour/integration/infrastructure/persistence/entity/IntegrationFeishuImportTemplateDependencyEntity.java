package com.rigour.integration.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 飞书导入模板依赖实体。 */
@TableName("integration_feishu_import_template_dependency")
public class IntegrationFeishuImportTemplateDependencyEntity {
    @TableId(type = IdType.INPUT) public byte[] id;
    public byte[] tenantId;
    public String sourceSystem;
    public String templateCode;
    public String dependsOnTemplateCode;
    public String relationKind;
    public String sourceReferenceFieldsJson;
    public String targetReferenceFieldsJson;
    public Boolean requiredFlag;
    public Boolean enabledFlag;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public Long version;
}
