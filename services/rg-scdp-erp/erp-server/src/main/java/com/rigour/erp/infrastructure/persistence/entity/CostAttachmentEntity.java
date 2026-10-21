package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("erp_cost_entry_attachment")
public class CostAttachmentEntity {
    @TableId(type = IdType.INPUT)
    private String id;

    private String tenantId;
    private String entryId;
    private String fileKey;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String createdBy;
    private LocalDateTime createdTime;

    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String value) {
        tenantId = value;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String value) {
        entryId = value;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String value) {
        fileKey = value;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String value) {
        fileName = value;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String value) {
        contentType = value;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long value) {
        fileSize = value;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        createdBy = value;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime value) {
        createdTime = value;
    }
}
