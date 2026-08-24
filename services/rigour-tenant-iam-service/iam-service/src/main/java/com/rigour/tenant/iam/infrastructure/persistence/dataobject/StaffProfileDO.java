package com.rigour.tenant.iam.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rigour.tenant.iam.infrastructure.persistence.typehandler.UuidBinaryTypeHandler;
import java.time.LocalDateTime;
import java.util.UUID;

@TableName(value = "iam_staff_profile", autoResultMap = true)
public final class StaffProfileDO {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID tenantId;
    private String staffCode;
    private String staffName;
    private String mobile;
    private String email;
    private String employmentStatus;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID primaryOrganizationId;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID primaryPositionId;
    private String recordOrigin;
    private String remark;
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
    public String getStaffCode() { return staffCode; }
    public void setStaffCode(String staffCode) { this.staffCode = staffCode; }
    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getEmploymentStatus() { return employmentStatus; }
    public void setEmploymentStatus(String employmentStatus) { this.employmentStatus = employmentStatus; }
    public UUID getPrimaryOrganizationId() { return primaryOrganizationId; }
    public void setPrimaryOrganizationId(UUID primaryOrganizationId) {
        this.primaryOrganizationId = primaryOrganizationId;
    }
    public UUID getPrimaryPositionId() { return primaryPositionId; }
    public void setPrimaryPositionId(UUID primaryPositionId) { this.primaryPositionId = primaryPositionId; }
    public String getRecordOrigin() { return recordOrigin; }
    public void setRecordOrigin(String recordOrigin) { this.recordOrigin = recordOrigin; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
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
