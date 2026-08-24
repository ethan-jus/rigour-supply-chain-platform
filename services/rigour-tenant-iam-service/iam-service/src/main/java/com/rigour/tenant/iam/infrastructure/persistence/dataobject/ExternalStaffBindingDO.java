package com.rigour.tenant.iam.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rigour.tenant.iam.infrastructure.persistence.typehandler.UuidBinaryTypeHandler;
import java.time.LocalDateTime;
import java.util.UUID;

@TableName(value = "iam_external_staff_binding", autoResultMap = true)
public final class ExternalStaffBindingDO {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID tenantId;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID staffId;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID connectorId;
    private String sourceSystem;
    private String sourceTenantKey;
    private String sourceStaffId;
    private String sourceStaffType;
    private String sourceAccountName;
    private String sourceStaffName;
    private String sourceTitle;
    private String sourceBranchName;
    private String sourceAccountsMobile;
    private String sourceAbout;
    private String sourceRole;
    private String sourceInviteCode;
    private String sourceMobile;
    private String sourceEmail;
    private String sourceQq;
    private String sourceStatus;
    private String sourcePayloadHash;
    private String sourcePayloadJson;
    private LocalDateTime sourceCreatedAt;
    private LocalDateTime sourceUpdatedAt;
    private String sourcePresence;
    private LocalDateTime lastSeenAt;
    private long version;
    private LocalDateTime createdAt;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID createdBy;
    private LocalDateTime updatedAt;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID updatedBy;
    private LocalDateTime deletedAt;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID deletedBy;
    private String deleteReason;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }
    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceTenantKey() { return sourceTenantKey; }
    public void setSourceTenantKey(String sourceTenantKey) { this.sourceTenantKey = sourceTenantKey; }
    public String getSourceStaffId() { return sourceStaffId; }
    public void setSourceStaffId(String sourceStaffId) { this.sourceStaffId = sourceStaffId; }
    public String getSourceStaffType() { return sourceStaffType; }
    public void setSourceStaffType(String sourceStaffType) { this.sourceStaffType = sourceStaffType; }
    public String getSourceAccountName() { return sourceAccountName; }
    public void setSourceAccountName(String sourceAccountName) { this.sourceAccountName = sourceAccountName; }
    public String getSourceStaffName() { return sourceStaffName; }
    public void setSourceStaffName(String sourceStaffName) { this.sourceStaffName = sourceStaffName; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
    public String getSourceBranchName() { return sourceBranchName; }
    public void setSourceBranchName(String sourceBranchName) { this.sourceBranchName = sourceBranchName; }
    public String getSourceAccountsMobile() { return sourceAccountsMobile; }
    public void setSourceAccountsMobile(String sourceAccountsMobile) { this.sourceAccountsMobile = sourceAccountsMobile; }
    public String getSourceAbout() { return sourceAbout; }
    public void setSourceAbout(String sourceAbout) { this.sourceAbout = sourceAbout; }
    public String getSourceRole() { return sourceRole; }
    public void setSourceRole(String sourceRole) { this.sourceRole = sourceRole; }
    public String getSourceInviteCode() { return sourceInviteCode; }
    public void setSourceInviteCode(String sourceInviteCode) { this.sourceInviteCode = sourceInviteCode; }
    public String getSourceMobile() { return sourceMobile; }
    public void setSourceMobile(String sourceMobile) { this.sourceMobile = sourceMobile; }
    public String getSourceEmail() { return sourceEmail; }
    public void setSourceEmail(String sourceEmail) { this.sourceEmail = sourceEmail; }
    public String getSourceQq() { return sourceQq; }
    public void setSourceQq(String sourceQq) { this.sourceQq = sourceQq; }
    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }
    public String getSourcePayloadHash() { return sourcePayloadHash; }
    public void setSourcePayloadHash(String sourcePayloadHash) { this.sourcePayloadHash = sourcePayloadHash; }
    public String getSourcePayloadJson() { return sourcePayloadJson; }
    public void setSourcePayloadJson(String sourcePayloadJson) { this.sourcePayloadJson = sourcePayloadJson; }
    public LocalDateTime getSourceCreatedAt() { return sourceCreatedAt; }
    public void setSourceCreatedAt(LocalDateTime sourceCreatedAt) { this.sourceCreatedAt = sourceCreatedAt; }
    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(LocalDateTime sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }
    public String getSourcePresence() { return sourcePresence; }
    public void setSourcePresence(String sourcePresence) { this.sourcePresence = sourcePresence; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
    public void setDeletedBy(UUID deletedBy) { this.deletedBy = deletedBy; }
    public String getDeleteReason() { return deleteReason; }
    public void setDeleteReason(String deleteReason) { this.deleteReason = deleteReason; }
}
