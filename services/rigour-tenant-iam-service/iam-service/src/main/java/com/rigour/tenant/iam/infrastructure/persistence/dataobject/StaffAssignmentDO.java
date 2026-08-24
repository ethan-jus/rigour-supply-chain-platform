package com.rigour.tenant.iam.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rigour.tenant.iam.infrastructure.persistence.typehandler.UuidBinaryTypeHandler;
import java.time.LocalDateTime;
import java.util.UUID;

@TableName(value = "iam_staff_assignment", autoResultMap = true)
public final class StaffAssignmentDO {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID tenantId;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID staffId;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID organizationId;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID positionId;
    private String assignmentType;
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
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
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getPositionId() { return positionId; }
    public void setPositionId(UUID positionId) { this.positionId = positionId; }
    public String getAssignmentType() { return assignmentType; }
    public void setAssignmentType(String assignmentType) { this.assignmentType = assignmentType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDateTime getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDateTime effectiveTo) { this.effectiveTo = effectiveTo; }
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
